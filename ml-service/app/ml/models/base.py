"""Contrato base de los modelos de XMR-Forecast.

Todos los modelos exponen la misma interfaz para que la comparacion sea justa
(R-05): mismos datos de test, mismos indices, mismas fechas.
"""

from __future__ import annotations

import abc
from dataclasses import dataclass, field
from typing import Any

import numpy as np

__all__ = ["BaseModel", "Direction", "ModelConfig", "TrainedModel", "direction_from_delta"]


class Direction:
    """Direccion de un movimiento de precio."""

    UP = "UP"
    DOWN = "DOWN"
    FLAT = "FLAT"

    ALL: tuple[str, ...] = (UP, DOWN, FLAT)


@dataclass(frozen=True)
class ModelConfig:
    """Hiperparametros de un modelo. Serializable a YAML/JSON (R-08)."""

    model_key: str
    family: str
    window: int = 30
    seed: int = 42
    params: dict[str, Any] = field(default_factory=dict)

    def to_dict(self) -> dict[str, Any]:
        """Representacion plana y determinista."""
        return {
            "model_key": self.model_key,
            "family": self.family,
            "window": self.window,
            "seed": self.seed,
            "params": dict(self.params),
        }


@dataclass
class TrainedModel:
    """Resultado del entrenamiento de cualquier modelo."""

    model: Any
    config: ModelConfig
    feature_names: tuple[str, ...]
    n_train: int
    n_features: int
    metrics: dict[str, float] = field(default_factory=dict)
    extra: dict[str, Any] = field(default_factory=dict)

    @property
    def model_key(self) -> str:
        """Clave logica del modelo."""
        return self.config.model_key

    @property
    def family(self) -> str:
        """Familia del modelo (LSTM, GRU, ARIMA, ...)."""
        return self.config.family


def direction_from_delta(delta: np.ndarray, flat_threshold: float = 0.0) -> np.ndarray:
    """Convierte variaciones en etiquetas de direccion.

    Args:
        delta: Diferencias ``predicho - ultimo_real``.
        flat_threshold: Umbral absoluto para considerar el movimiento plano.

    Returns:
        Array de cadenas ``"UP"``/``"DOWN"``/``"FLAT"``.
    """
    values = np.asarray(delta, dtype=np.float64)
    out = np.full(values.shape, Direction.FLAT, dtype="<U5")
    out[values > flat_threshold] = Direction.UP
    out[values < -flat_threshold] = Direction.DOWN
    return out


class BaseModel(abc.ABC):
    """Interfaz comun de entrenamiento y prediccion.

    Contrato:

    * ``fit(x_scaled, y_scaled)`` recibe datos **ya escalados** (R-02) y
      devuelve el propio modelo para encadenar.
    * ``predict(x_scaled)`` devuelve el objetivo en la **escala escalada**.
      La desescala a USD la realiza el pipeline (R-02).
    * ``predict_direction(x_scaled, last_close)`` devuelve ``"UP"``/``"DOWN"``.
    """

    config: ModelConfig

    @abc.abstractmethod
    def fit(self, x_scaled: np.ndarray, y_scaled: np.ndarray) -> BaseModel:
        """Entrena el modelo con ventanas y objetivos escalados."""

    @abc.abstractmethod
    def predict(self, x_scaled: np.ndarray) -> np.ndarray:
        """Predice el cierre siguiente en la escala escalada."""

    @abc.abstractmethod
    def predict_direction(self, x_scaled: np.ndarray, last_close: np.ndarray) -> np.ndarray:
        """Predice la direccion del movimiento (arriba/abajo)."""

    def set_seed(self, seed: int) -> None:
        """Fija la semilla del modelo (R-08)."""
        self.config = ModelConfig(
            model_key=self.config.model_key,
            family=self.config.family,
            window=self.config.window,
            seed=seed,
            params=dict(self.config.params),
        )

    def provenance(self) -> dict[str, Any]:
        """Datos de procedencia del modelo (R-08, R-28)."""
        return {
            "model_key": self.config.model_key,
            "family": self.config.family,
            "seed": self.config.seed,
            "window": self.config.window,
            "params": dict(self.config.params),
        }
