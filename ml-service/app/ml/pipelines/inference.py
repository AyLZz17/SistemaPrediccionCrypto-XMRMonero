"""Pipeline de inferencia: carga de artefactos y prediccion trazable.

Solo depende de numpy/pandas/joblib y del paquete ``app.ml`` (R-13). La
trazabilidad incluye ``dataset_version``, ``artifact_sha256``, ``config_sha256``
y la semilla, tal y como exige el contrato de ``POST /v1/predict``.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from datetime import UTC, datetime
from pathlib import Path
from typing import Any

import joblib
import numpy as np
import pandas as pd

from app.ml.data.features import build_features, drop_warmup, feature_columns
from app.ml.data.split import WindowSet
from app.ml.models.base import Direction

__all__ = [
    "ArtifactBundle",
    "InferenceError",
    "PredictionRecord",
    "WindowMismatchError",
    "build_window_from_history",
    "load_bundle",
    "predict_with_bundle",
    "save_bundle",
    "write_checksum",
]


class InferenceError(RuntimeError):
    """Error al cargar un artefacto o ejecutar la inferencia."""


class WindowMismatchError(InferenceError):
    """La ventana solicitada no coincide con la del modelo entrenado.

    Es un error de **peticion** del cliente (400), no de integridad del
    artefacto: el modelo existe pero se le pide otra geometria de entrada.
    """


@dataclass(frozen=True)
class ArtifactBundle:
    """Artefacto completo: modelo, escalador, features y procedencia.

    Attributes:
        model: Instancia de modelo ya entrenada (deserializada con joblib).
        scaler: ``FittedScaler`` ajustado solo con train (R-02).
        feature_names: Orden canonico de las features.
        provenance: Bloque de trazabilidad serializable.
        window: Longitud de la ventana de entrada.
    """

    model: Any
    scaler: Any
    feature_names: tuple[str, ...]
    provenance: dict[str, Any] = field(default_factory=dict)
    window: int = 30

    @property
    def model_key(self) -> str:
        """Clave del modelo empaquetado."""
        return str(self.provenance.get("model_key", "unknown"))

    @property
    def version(self) -> str:
        """Version del modelo empaquetado."""
        return str(self.provenance.get("version", "v1"))


@dataclass(frozen=True)
class PredictionRecord:
    """Prediccion individual con su trazabilidad completa."""

    model_key: str
    version: str
    target_date: str
    predicted_close: float
    predicted_direction: str
    confidence: float
    actual_close: float | None = None
    trace: dict[str, Any] = field(default_factory=dict)

    def to_dict(self) -> dict[str, Any]:
        """Representacion serializable para la API."""
        return {
            "model_key": self.model_key,
            "version": self.version,
            "target_date": self.target_date,
            "predicted_close": self.predicted_close,
            "predicted_direction": self.predicted_direction,
            "actual_close": self.actual_close,
            "confidence": self.confidence,
            "trace": self.trace,
        }


def save_bundle(bundle: ArtifactBundle, path: str | Path) -> Path:
    """Serializa un bundle a disco con ``joblib`` y devuelve la ruta."""
    out = Path(path)
    out.parent.mkdir(parents=True, exist_ok=True)
    joblib.dump(bundle, out)
    return out


def load_bundle(path: str | Path) -> ArtifactBundle:
    """Carga un bundle serializado.

    Solo se aceptan artefactos cuyo SHA-256 coincida con el esperado si se
    proporciona ``expected_sha256`` (gate de integridad, R-28).

    Raises:
        InferenceError: si el artefacto no existe o el checksum no coincide.
    """
    from app.ml.data.ingest import file_sha256

    artifact = Path(path)
    if not artifact.is_file():
        raise InferenceError(f"No existe el artefacto: {artifact}")

    expected = artifact.with_suffix(".sha256")
    if expected.is_file():
        recorded = expected.read_text(encoding="utf-8").strip().split()[0]
        actual = file_sha256(artifact)
        if recorded != actual:
            raise InferenceError(
                "Fallo de integridad: SHA-256 del artefacto no coincide con el registrado "
                f"(esperado {recorded}, real {actual})"
            )

    try:
        bundle = joblib.load(artifact)
    except Exception as exc:  # pragma: no cover - depende del artefacto
        raise InferenceError(f"No se pudo deserializar el artefacto: {exc}") from exc

    if not isinstance(bundle, ArtifactBundle):
        raise InferenceError("El artefacto no contiene un ArtifactBundle valido")
    return bundle


def write_checksum(artifact_path: str | Path) -> Path:
    """Escribe el fichero ``.sha256`` acompanante del artefacto (R-28)."""
    from app.ml.data.ingest import file_sha256

    path = Path(artifact_path)
    digest = file_sha256(path)
    path.with_suffix(".sha256").write_text(f"{digest}  {path.name}\n", encoding="utf-8")
    return path.with_suffix(".sha256")


def build_window_from_history(
    history: pd.DataFrame, window: int, feature_names: tuple[str, ...] | None = None
) -> np.ndarray:
    """Construye la ventana de entrada mas reciente a partir del historico OHLCV.

    Calcula las features con el mismo codigo del entrenamiento, de modo que la
    inferencia usa exactamente la misma transformacion (R-03, R-28).

    Args:
        history: Serie OHLCV con indice ``DatetimeIndex`` ascendente.
        window: Numero de pasos de la ventana.
        feature_names: Columnas exigidas por el artefacto, **en ese orden**. Si
            es ``None`` se usan las derivadas mas ``close`` (orden canonico).

    Returns:
        Array ``(1, window, n_features)`` con la ultima ventana.

    Raises:
        InferenceError: si el historico no tiene filas suficientes o si el
            artefacto espera columnas que el historico no produce.
    """
    if len(history) < window:
        raise InferenceError(
            f"Se necesitan al menos {window} filas de historico; hay {len(history)}"
        )
    featured = drop_warmup(build_features(history))
    if len(featured) < 1:
        raise InferenceError("El historico no produce filas utilizables tras el calentamiento")

    if feature_names is None:
        # Orden canonico: el precio primero, despues las derivadas.
        available = feature_columns(featured)
        columns = ("close", *available) if "close" in featured.columns else available
    else:
        columns = tuple(feature_names)
        missing = [name for name in columns if name not in featured.columns]
        if missing:
            raise InferenceError(
                f"El historico no produce las columnas exigidas por el artefacto: {missing}"
            )

    tail = featured.loc[:, list(columns)].tail(window)
    return tail.to_numpy(dtype=np.float64).reshape(1, window, len(columns))


def _direction_from_prediction(predicted: float, last_close: float, flat_threshold: float) -> str:
    delta = predicted - last_close
    if delta > flat_threshold:
        return Direction.UP
    if delta < -flat_threshold:
        return Direction.DOWN
    return Direction.FLAT


def _confidence(predicted: float, last_close: float, in_sample_mae: float | None) -> float:
    """Confianza heuristica en ``[0.5, 0.99]`` segun el movimiento relativo.

    No es una probabilidad calibrada: es una heuristica transparente que
    crece con el cambio relativo previsto y se modera con el error tipico del
    modelo. Se documenta como tal para no sugerir certeza (R-11, R-12).
    """
    if last_close <= 0:
        return 0.5
    relative_move = abs(predicted - last_close) / last_close
    confidence = 0.5 + min(0.49, relative_move * 10.0)
    if in_sample_mae and last_close > 0:
        noise = min(0.2, float(in_sample_mae) / float(last_close))
        confidence -= noise
    return float(max(0.5, min(0.99, confidence)))


def predict_with_bundle(
    bundle: ArtifactBundle,
    history: pd.DataFrame,
    target_date: str,
    window: int | None = None,
    actual_close: float | None = None,
    flat_threshold: float = 0.0,
) -> PredictionRecord:
    """Predice el cierre siguiente usando un bundle entrenado.

    Aplica el escalador con ``transform`` (nunca ``fit``, R-02), predice en
    escala escalada, desescala a USD y registra la trazabilidad.

    Args:
        bundle: Artefacto cargado.
        history: Historico OHLCV que termina en el dia anterior al objetivo.
        target_date: Fecha objetivo en formato ISO.
        window: Longitud de ventana; si es ``None`` se usa la del bundle.
        actual_close: Cierre real, si ya se conoce (backfill).
        flat_threshold: Umbral para considerar el movimiento plano.

    Returns:
        PredictionRecord con prediccion, direccion, confianza y traza.

    Raises:
        InferenceError: si el historico es insuficiente o el escalador falla.
    """
    win = int(window or bundle.window)
    # Se exigen exactamente las features del artefacto, en su orden.
    features = build_window_from_history(history, win, tuple(bundle.feature_names))
    if features.shape[2] != len(bundle.feature_names):
        raise InferenceError(
            "El numero de features del historico "
            f"({features.shape[2]}) no coincide con el artefacto "
            f"({len(bundle.feature_names)}); el modelo fue entrenado con otra configuracion"
        )

    try:
        scaled_window = bundle.scaler.transform(
            WindowSet(
                x=features,
                y=np.zeros(1, dtype=np.float64),
                feature_names=bundle.feature_names,
                target_positions=np.asarray([0], dtype=np.int64),
                target_dates=pd.DatetimeIndex([pd.Timestamp.now(tz="UTC")]),
                subsets=np.asarray(["train"], dtype="<U5"),
                window=win,
            )
        ).x
    except Exception as exc:
        raise InferenceError(f"El escalador del artefacto no pudo aplicarse: {exc}") from exc

    predicted_scaled = np.asarray(bundle.model.predict(scaled_window), dtype=np.float64).ravel()
    if predicted_scaled.size != 1:
        raise InferenceError(
            f"El modelo devolvio {predicted_scaled.size} predicciones; se esperaba 1"
        )

    predicted_usd = float(np.asarray(bundle.scaler.inverse_target(predicted_scaled)).ravel()[0])
    last_close = float(np.asarray(history["close"], dtype=np.float64).ravel()[-1])

    metrics = bundle.provenance.get("metrics") or {}
    in_sample_mae = metrics.get("mae") if isinstance(metrics, dict) else None

    trace = dict(bundle.provenance.get("trace", {}))
    trace.setdefault("dataset_version", bundle.provenance.get("dataset_version", ""))
    trace.setdefault("artifact_sha256", bundle.provenance.get("artifact_sha256", ""))
    trace.setdefault("config_sha256", bundle.provenance.get("config_sha256", ""))
    trace.setdefault("seed", bundle.provenance.get("seed", 0))
    trace["generated_at"] = datetime.now(UTC).isoformat()

    return PredictionRecord(
        model_key=bundle.model_key,
        version=bundle.version,
        target_date=target_date,
        predicted_close=predicted_usd,
        predicted_direction=_direction_from_prediction(predicted_usd, last_close, flat_threshold),
        confidence=_confidence(predicted_usd, last_close, in_sample_mae),
        actual_close=actual_close,
        trace=trace,
    )
