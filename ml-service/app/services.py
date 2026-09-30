"""Servicio de aplicacion: acceso al registro y a los artefactos ML.

Este modulo es la **unica** pieza que conecta la capa web con el paquete puro
``app.ml`` (R-13). No contiene logica de negocio ni SQL.
"""

from __future__ import annotations

import logging
from dataclasses import dataclass
from functools import lru_cache
from pathlib import Path
from typing import Any

import pandas as pd

from app.config import Settings
from app.ml.data.ingest import read_snapshot
from app.ml.models.registry import ModelArtifact, ModelRegistry
from app.ml.pipelines.inference import (
    ArtifactBundle,
    load_bundle,
    predict_with_bundle,
)

__all__ = [
    "InvalidRequestError",
    "MarketDataError",
    "ModelNotFoundError",
    "ModelService",
    "get_model_service",
]

logger = logging.getLogger(__name__)


class ModelNotFoundError(LookupError):
    """No existe la version de modelo solicitada."""


class MarketDataError(RuntimeError):
    """No hay datos de mercado disponibles para la inferencia."""


class InvalidRequestError(ValueError):
    """La peticion es valida en esquema pero incompatible con el modelo (400)."""


@dataclass
class ModelService:
    """Punto unico de acceso a modelos registrados y datos de mercado.

    Args:
        settings: Configuracion del servicio.
    """

    settings: Settings

    def __post_init__(self) -> None:
        """Carga el registro desde disco al construir el servicio."""
        self._registry = ModelRegistry(self.settings.registry_path)
        self._registry.load()

    @property
    def registry(self) -> ModelRegistry:
        """Registro de modelos cargado."""
        return self._registry

    def refresh(self) -> None:
        """Recarga el registro desde disco (por si otro proceso entreno)."""
        self._registry.load()

    def list_models(self) -> list[ModelArtifact]:
        """Artefactos registrados, ordenados de forma estable."""
        self.refresh()
        return self._registry.all()

    def resolve(self, model_key: str, version: str | None) -> ModelArtifact:
        """Resuelve un artefacto por clave y version.

        Args:
            model_key: Clave logica del modelo.
            version: Version concreta; si es ``None``, la mas reciente.

        Raises:
            ModelNotFoundError: si no hay ninguna version registrada.
        """
        self.refresh()
        artifact = (
            self._registry.get(model_key, version) if version else self._registry.latest(model_key)
        )
        if artifact is None:
            known = sorted({item.model_key for item in self._registry.all()})
            raise ModelNotFoundError(
                f"Modelo no encontrado: {model_key}"
                + (f"/{version}" if version else "")
                + (f". Registrados: {known}" if known else ". El registro esta vacio")
            )
        return artifact

    def load_artifact(self, artifact: ModelArtifact) -> ArtifactBundle:
        """Carga el bundle serializado de un artefacto.

        Raises:
            InferenceError: si el artefacto no se puede cargar o falla integridad.
        """
        return load_bundle(artifact.artifact_path)

    def load_market_data(self, symbol: str) -> pd.DataFrame:
        """Carga el snapshot OHLCV configurado.

        Raises:
            MarketDataError: si no hay snapshot configurado o no es valido.
        """
        snapshot = self.settings.data_snapshot
        if not snapshot:
            raise MarketDataError(
                "No hay snapshot de mercado configurado (ML_DATA_SNAPSHOT); "
                "la inferencia en linea requiere datos de entrada"
            )
        try:
            result = read_snapshot(snapshot)
        except Exception as exc:
            raise MarketDataError(f"Snapshot no utilizable ({symbol}): {exc}") from exc
        return result.frame

    def predict(
        self,
        model_key: str,
        version: str | None,
        lookback_days: int,
        symbol: str,
        actual_close: float | None = None,
    ) -> Any:
        """Ejecuta la inferencia y devuelve un ``PredictionRecord``.

        Raises:
            ModelNotFoundError: si el modelo no existe.
            InferenceError: si el artefacto falla la carga o la inferencia.
            MarketDataError: si no hay datos de mercado.
        """
        artifact = self.resolve(model_key, version)
        bundle = self.load_artifact(artifact)
        history = self.load_market_data(symbol)

        expected_window = int(bundle.window)
        if lookback_days != expected_window:
            raise InvalidRequestError(
                f"lookback_days={lookback_days} no coincide con la ventana del modelo "
                f"{model_key}/{bundle.version} ({expected_window}); "
                "usa la version entrenada con esa ventana"
            )
        if len(history) < lookback_days:
            raise MarketDataError(
                f"El snapshot tiene {len(history)} filas y se piden {lookback_days}"
            )

        target_date = (pd.Timestamp(history.index.max()) + pd.Timedelta(days=1)).date().isoformat()
        return predict_with_bundle(
            bundle,
            history=history,
            target_date=target_date,
            window=lookback_days,
            actual_close=actual_close,
        )

    def compare(self, experiment_id: str | None) -> dict[str, Any]:
        """Construye la comparacion entre modelos registrados.

        Args:
            experiment_id: Filtro opcional por experimento.

        Raises:
            ModelNotFoundError: si no hay modelos con metricas de test.
        """
        self.refresh()
        artifacts = [
            a for a in self._registry.all() if a.metrics is not None and a.metrics.split == "test"
        ]
        if experiment_id:
            filtered = [
                a
                for a in artifacts
                if experiment_id in (a.notes or "") or a.model_key == experiment_id
            ]
            if filtered:
                artifacts = filtered
        if not artifacts:
            raise ModelNotFoundError("No hay modelos con metricas de test registradas")

        best = max(a.metrics.n_samples for a in artifacts if a.metrics is not None)
        rows: list[dict[str, Any]] = []
        for artifact in artifacts:
            if artifact.metrics is None or artifact.metrics.n_samples != best:
                continue
            rows.append(
                {
                    "model_key": artifact.model_key,
                    "family": artifact.family,
                    "version": artifact.version,
                    "n_seeds": artifact.metrics.n_seeds,
                    "mae": artifact.metrics.mae,
                    "rmse": artifact.metrics.rmse,
                    "mape": artifact.metrics.mape,
                    "direction_accuracy": artifact.metrics.direction_accuracy,
                    "stddev": artifact.metrics.stddev,
                }
            )
        rows.sort(key=lambda r: float(r["mae"]))
        return {
            "models": rows,
            "test_dates_shared": len({a.metrics.n_samples for a in artifacts if a.metrics}) == 1,
            "n_test_samples": best,
            "experiment_id": experiment_id,
        }


@lru_cache(maxsize=1)
def get_model_service() -> ModelService:
    """Devuelve el servicio de modelos cacheado del proceso."""
    from app.config import get_settings

    return ModelService(get_settings())


def default_artifacts_dir(settings: Settings) -> Path:
    """Directorio de artefactos configurado."""
    return Path(settings.artifacts_dir)
