"""Metricas obligatorias (R-07): MAE, RMSE, MAPE y acierto de direccion.

Todas las funciones esperan valores **en USD**, es decir, ya desescalados (R-02).
Se calculan con NumPy puro para que los valores hand-computed de los tests
sean reproducibles sin dependencias adicionales.
"""

from __future__ import annotations

from dataclasses import dataclass

import numpy as np

__all__ = [
    "ConfusionCounts",
    "EvaluationMetrics",
    "compute_metrics",
    "confusion_matrix",
    "direction_accuracy",
    "f1_score",
    "mae",
    "mape",
    "rmse",
]


def _as_arrays(y_true: np.ndarray, y_pred: np.ndarray) -> tuple[np.ndarray, np.ndarray]:
    true = np.asarray(y_true, dtype=np.float64).ravel()
    pred = np.asarray(y_pred, dtype=np.float64).ravel()
    if true.shape != pred.shape:
        raise ValueError(f"y_true {true.shape} e y_pred {pred.shape} deben tener la misma forma")
    return true, pred


def mae(y_true: np.ndarray, y_pred: np.ndarray) -> float:
    """Error absoluto medio: ``(1/n) * sum |y - y_hat|``."""
    true, pred = _as_arrays(y_true, y_pred)
    if true.size == 0:
        raise ValueError("mae requiere al menos una muestra")
    return float(np.mean(np.abs(true - pred)))


def rmse(y_true: np.ndarray, y_pred: np.ndarray) -> float:
    """Raiz del error cuadratico medio."""
    true, pred = _as_arrays(y_true, y_pred)
    if true.size == 0:
        raise ValueError("rmse requiere al menos una muestra")
    return float(np.sqrt(np.mean(np.square(true - pred))))


def mape(y_true: np.ndarray, y_pred: np.ndarray, epsilon: float = 1e-9) -> float:
    """Error porcentual absoluto medio (en %).

    Los valores reales nulos o muy cercanos a cero se acotan con ``epsilon`` para
    no dividir por cero; en series de precios esto no ocurre en la practica.
    """
    true, pred = _as_arrays(y_true, y_pred)
    if true.size == 0:
        raise ValueError("mape requiere al menos una muestra")
    denominator = np.where(np.abs(true) < epsilon, epsilon, np.abs(true))
    return float(np.mean(np.abs((true - pred) / denominator)) * 100.0)


def direction_accuracy(y_true: np.ndarray, y_pred: np.ndarray, last_close: np.ndarray) -> float:
    """Proporcion de aciertos de direccion (R-07).

    La direccion real es ``sign(y_true - last_close)`` y la predicha
    ``sign(y_pred - last_close)``, es decir el movimiento del ultimo cierre
    conocido al cierre siguiente.

    Args:
        y_true: Cierres reales.
        y_pred: Cierres predichos.
        last_close: Ultimo cierre observado antes de cada objetivo.

    Returns:
        Proporcion en ``[0, 1]``.

    Raises:
        ValueError: si alguna serie esta vacia o las formas no coinciden.
    """
    true, pred = _as_arrays(y_true, y_pred)
    prev = np.asarray(last_close, dtype=np.float64).ravel()
    if true.size == 0:
        raise ValueError("direction_accuracy requiere al menos una muestra")
    if prev.shape != true.shape:
        raise ValueError(f"last_close {prev.shape} debe coincidir con y_true {true.shape}")
    true_dir = np.sign(true - prev)
    pred_dir = np.sign(pred - prev)
    return float(np.mean(true_dir == pred_dir))


@dataclass(frozen=True)
class ConfusionCounts:
    """Matriz de confusion binaria para la tarea de direccion."""

    true_positive: int
    true_negative: int
    false_positive: int
    false_negative: int

    @property
    def total(self) -> int:
        """Numero total de observaciones."""
        return self.true_positive + self.true_negative + self.false_positive + self.false_negative

    def to_dict(self) -> dict[str, int]:
        """Representacion serializable."""
        return {
            "true_positive": self.true_positive,
            "true_negative": self.true_negative,
            "false_positive": self.false_positive,
            "false_negative": self.false_negative,
        }


def confusion_matrix(
    y_true: np.ndarray, y_pred: np.ndarray, last_close: np.ndarray
) -> ConfusionCounts:
    """Matriz de confusion de la tarea binaria subir/bajar.

    Clase positiva = ``UP`` (el precio sube). Los movimientos planos se cuentan
    como ``true_negative`` si la prediccion tambien es plana.
    """
    true, pred = _as_arrays(y_true, y_pred)
    prev = np.asarray(last_close, dtype=np.float64).ravel()
    if true.shape != prev.shape:
        raise ValueError("last_close debe tener la misma forma que y_true")
    if true.size == 0:
        raise ValueError("confusion_matrix requiere al menos una muestra")

    true_dir = np.sign(true - prev)
    pred_dir = np.sign(pred - prev)

    return ConfusionCounts(
        true_positive=int(np.sum((true_dir > 0) & (pred_dir > 0))),
        true_negative=int(np.sum((true_dir <= 0) & (pred_dir <= 0))),
        false_positive=int(np.sum((true_dir <= 0) & (pred_dir > 0))),
        false_negative=int(np.sum((true_dir > 0) & (pred_dir <= 0))),
    )


def f1_score(counts: ConfusionCounts) -> float:
    """F1 de la clase positiva (subir) a partir de la matriz de confusion."""
    tp, fp, fn = counts.true_positive, counts.false_positive, counts.false_negative
    denominator = 2 * tp + fp + fn
    if denominator == 0:
        return 0.0
    return float(2 * tp / denominator)


@dataclass(frozen=True)
class EvaluationMetrics:
    """Paquete completo de metricas de una corrida (R-07)."""

    mae: float
    rmse: float
    mape: float
    direction_accuracy: float
    n_samples: int
    f1: float = 0.0
    confusion: dict[str, int] | None = None

    def to_dict(self) -> dict[str, object]:
        """Representacion serializable."""
        payload: dict[str, object] = {
            "mae": self.mae,
            "rmse": self.rmse,
            "mape": self.mape,
            "direction_accuracy": self.direction_accuracy,
            "n_samples": self.n_samples,
            "f1": self.f1,
        }
        if self.confusion is not None:
            payload["confusion"] = self.confusion
        return payload


def compute_metrics(
    y_true: np.ndarray, y_pred: np.ndarray, last_close: np.ndarray
) -> EvaluationMetrics:
    """Calcula MAE, RMSE, MAPE, acierto de direccion, matriz de confusion y F1.

    Args:
        y_true: Cierres reales en USD.
        y_pred: Cierres predichos en USD (ya desescalados, R-02).
        last_close: Ultimo cierre observado antes de cada objetivo.
    """
    true = np.asarray(y_true, dtype=np.float64).ravel()
    pred = np.asarray(y_pred, dtype=np.float64).ravel()
    counts = confusion_matrix(true, pred, last_close)
    return EvaluationMetrics(
        mae=mae(true, pred),
        rmse=rmse(true, pred),
        mape=mape(true, pred),
        direction_accuracy=direction_accuracy(true, pred, last_close),
        n_samples=int(true.size),
        f1=f1_score(counts),
        confusion=counts.to_dict(),
    )
