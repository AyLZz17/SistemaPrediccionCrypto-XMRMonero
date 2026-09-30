"""Registro versionado de artefactos ML con procedencia verificable (R-08, R-28).

Cada entrada apunta a un artefacto y registra su SHA-256, el ``dataset_version``
(checksum del snapshot), la configuracion, la semilla y las metricas. La
promocion de un modelo como *campeon* exige metricas de **validacion** (R-24).
"""

from __future__ import annotations

import hashlib
import json
from dataclasses import dataclass, field
from datetime import UTC, datetime
from pathlib import Path
from typing import Any

__all__ = [
    "MetricsSummary",
    "ModelArtifact",
    "ModelRegistry",
    "RegistryError",
    "sha256_bytes",
]


def sha256_bytes(payload: bytes) -> str:
    """SHA-256 de un bloque de bytes."""
    return hashlib.sha256(payload).hexdigest()


class RegistryError(ValueError):
    """Error de registro (duplicado, artefacto ausente, integridad fallida)."""


@dataclass(frozen=True)
class MetricsSummary:
    """Metricas de evaluacion de una version de modelo (R-07)."""

    mae: float
    rmse: float
    mape: float
    direction_accuracy: float
    n_samples: int
    n_seeds: int = 1
    stddev: dict[str, float] = field(default_factory=dict)
    split: str = "test"

    def to_dict(self) -> dict[str, Any]:
        """Representacion serializable."""
        return {
            "mae": self.mae,
            "rmse": self.rmse,
            "mape": self.mape,
            "direction_accuracy": self.direction_accuracy,
            "n_samples": self.n_samples,
            "n_seeds": self.n_seeds,
            "stddev": dict(self.stddev),
            "split": self.split,
        }

    @classmethod
    def from_dict(cls, payload: dict[str, Any]) -> MetricsSummary:
        """Reconstruye desde JSON."""
        return cls(
            mae=float(payload["mae"]),
            rmse=float(payload["rmse"]),
            mape=float(payload["mape"]),
            direction_accuracy=float(payload["direction_accuracy"]),
            n_samples=int(payload["n_samples"]),
            n_seeds=int(payload.get("n_seeds", 1)),
            stddev=dict(payload.get("stddev", {})),
            split=str(payload.get("split", "test")),
        )


@dataclass(frozen=True)
class ModelArtifact:
    """Una version de modelo registrada y su procedencia."""

    model_key: str
    family: str
    version: str
    artifact_path: Path
    artifact_sha256: str
    dataset_version: str
    trained_at: str
    seed: int
    seeds: tuple[int, ...]
    config: dict[str, Any]
    config_sha256: str
    metrics: MetricsSummary | None = None
    feature_names: tuple[str, ...] = ()
    scaler_sha256: str | None = None
    notes: str = ""

    def to_dict(self) -> dict[str, Any]:
        """Representacion serializable (paths como cadenas)."""
        return {
            "model_key": self.model_key,
            "family": self.family,
            "version": self.version,
            "artifact_path": str(self.artifact_path),
            "artifact_sha256": self.artifact_sha256,
            "dataset_version": self.dataset_version,
            "trained_at": self.trained_at,
            "seed": self.seed,
            "seeds": list(self.seeds),
            "config": self.config,
            "config_sha256": self.config_sha256,
            "metrics": self.metrics.to_dict() if self.metrics else None,
            "feature_names": list(self.feature_names),
            "scaler_sha256": self.scaler_sha256,
            "notes": self.notes,
        }

    @classmethod
    def from_dict(cls, payload: dict[str, Any]) -> ModelArtifact:
        """Reconstruye desde JSON."""
        metrics = payload.get("metrics")
        return cls(
            model_key=str(payload["model_key"]),
            family=str(payload["family"]),
            version=str(payload["version"]),
            artifact_path=Path(str(payload["artifact_path"])),
            artifact_sha256=str(payload["artifact_sha256"]),
            dataset_version=str(payload["dataset_version"]),
            trained_at=str(payload["trained_at"]),
            seed=int(payload["seed"]),
            seeds=tuple(int(s) for s in payload.get("seeds", [])),
            config=dict(payload.get("config", {})),
            config_sha256=str(payload.get("config_sha256", "")),
            metrics=MetricsSummary.from_dict(metrics) if metrics else None,
            feature_names=tuple(str(f) for f in payload.get("feature_names", [])),
            scaler_sha256=payload.get("scaler_sha256"),
            notes=str(payload.get("notes", "")),
        )

    def trace(self) -> dict[str, Any]:
        """Bloque de trazabilidad devuelto por ``POST /v1/predict``."""
        return {
            "dataset_version": self.dataset_version,
            "artifact_sha256": self.artifact_sha256,
            "config_sha256": self.config_sha256,
            "seed": self.seed,
            "generated_at": datetime.now(UTC).isoformat(),
        }


class ModelRegistry:
    """Registro en memoria persistible en JSON.

    Args:
        index_path: Fichero JSON donde se persiste el registro. Si es ``None``
            el registro es puramente efimero (util en tests).
    """

    def __init__(self, index_path: str | Path | None = None) -> None:
        """Crea el registro; sin ``index_path`` es efimero (util en tests)."""
        self.index_path = Path(index_path) if index_path is not None else None
        self._items: dict[tuple[str, str], ModelArtifact] = {}

    @staticmethod
    def _key(model_key: str, version: str) -> tuple[str, str]:
        return (model_key, version)

    def register(
        self,
        model_key: str,
        family: str,
        version: str,
        artifact_path: str | Path,
        dataset_version: str,
        seed: int,
        seeds: tuple[int, ...],
        config: dict[str, Any],
        feature_names: tuple[str, ...],
        metrics: MetricsSummary | None = None,
        scaler_path: str | Path | None = None,
        trained_at: str | None = None,
        notes: str = "",
        overwrite: bool = False,
    ) -> ModelArtifact:
        """Registra una version de modelo y devuelve su artefacto.

        Calcula los SHA-256 del artefacto, del escalador y de la configuracion
        serializada, de modo que la procedencia sea verificable (R-28).

        Raises:
            RegistryError: si el artefacto no existe o la version ya esta
                registrada y ``overwrite`` es ``False``.
        """
        path = Path(artifact_path)
        if not path.is_file():
            raise RegistryError(f"No existe el artefacto registrado: {path}")

        key = self._key(model_key, version)
        if key in self._items and not overwrite:
            raise RegistryError(f"Version ya registrada: {model_key}/{version}")

        artifact_sha = sha256_bytes(path.read_bytes())
        scaler_sha = None
        if scaler_path is not None:
            scaler_file = Path(scaler_path)
            if scaler_file.is_file():
                scaler_sha = sha256_bytes(scaler_file.read_bytes())

        config_bytes = json.dumps(config, sort_keys=True, separators=(",", ":")).encode()
        artifact = ModelArtifact(
            model_key=model_key,
            family=family,
            version=version,
            artifact_path=path,
            artifact_sha256=artifact_sha,
            dataset_version=dataset_version,
            trained_at=trained_at or datetime.now(UTC).isoformat(),
            seed=seed,
            seeds=tuple(seeds),
            config=dict(config),
            config_sha256=sha256_bytes(config_bytes),
            metrics=metrics,
            feature_names=tuple(feature_names),
            scaler_sha256=scaler_sha,
            notes=notes,
        )
        self._items[key] = artifact
        self.save()
        return artifact

    def get(self, model_key: str, version: str) -> ModelArtifact | None:
        """Devuelve un artefacto registrado o ``None``."""
        return self._items.get(self._key(model_key, version))

    def latest(self, model_key: str) -> ModelArtifact | None:
        """Devuelve la version mas reciente (orden de entrenamiento) del modelo."""
        candidates = [a for (k, _), a in self._items.items() if k == model_key]
        if not candidates:
            return None
        return max(candidates, key=lambda a: a.trained_at)

    def all(self) -> list[ModelArtifact]:
        """Todos los artefactos registrados, ordenados de forma estable.

        Se llama ``all`` y no ``list`` para no sombrear el tipo builtin
        dentro de este modulo, donde mypy interpretaria el nombre como anotacion.
        """
        return sorted(self._items.values(), key=lambda a: (a.model_key, a.version, a.trained_at))

    def verify(self, artifact: ModelArtifact) -> bool:
        """Recalcula el SHA-256 del artefacto y lo compara con el registrado."""
        path = artifact.artifact_path
        if not path.is_file():
            return False
        return sha256_bytes(path.read_bytes()) == artifact.artifact_sha256

    def champion_by_validation(
        self, model_key: str, versions: list[str] | None = None
    ) -> ModelArtifact | None:
        """Elige el campeon por metricas de **validacion**, nunca de test (R-24).

        Args:
            model_key: Clave del modelo a comparar.
            versions: Versiones candidatas; si es ``None``, todas las registradas.

        Returns:
            El artefacto con menor MAE de validacion, o ``None`` si no hay
            candidatos con metricas de validacion.
        """
        candidates = [
            a
            for a in self.all()
            if a.model_key == model_key
            and (versions is None or a.version in versions)
            and a.metrics is not None
            and a.metrics.split == "val"
        ]
        if not candidates:
            return None
        return min(candidates, key=lambda a: float(a.metrics.mae))  # type: ignore[union-attr]

    def save(self) -> None:
        """Persiste el registro en disco si hay ``index_path``."""
        if self.index_path is None:
            return
        payload = {"items": [a.to_dict() for a in self.all()]}
        self.index_path.parent.mkdir(parents=True, exist_ok=True)
        self.index_path.write_text(json.dumps(payload, indent=2, sort_keys=True), encoding="utf-8")

    def load(self) -> None:
        """Carga el registro desde disco si existe el fichero."""
        if self.index_path is None or not self.index_path.is_file():
            return
        payload = json.loads(self.index_path.read_text(encoding="utf-8"))
        self._items = {
            self._key(str(item["model_key"]), str(item["version"])): ModelArtifact.from_dict(item)
            for item in payload.get("items", [])
        }
