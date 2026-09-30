"""Comparacion de modelos sobre **identicas** fechas de test (R-05).

La comparacion solo es valida si todos los modelos se evaluan sobre las mismas
muestras y las mismas fechas. Este modulo lo **verifica** y falla si no es asi,
en lugar de producir una tabla enganosa.
"""

from __future__ import annotations

from dataclasses import dataclass

import numpy as np
import pandas as pd

from app.ml.evaluation.metrics import EvaluationMetrics, compute_metrics

__all__ = ["ComparisonReport", "ComparisonRow", "ModelPrediction", "compare_models"]


@dataclass(frozen=True)
class ModelPrediction:
    """Predicciones de un modelo sobre un conjunto de evaluacion."""

    model_key: str
    family: str
    target_dates: pd.DatetimeIndex
    y_pred: np.ndarray
    y_true: np.ndarray
    last_close: np.ndarray

    def __post_init__(self) -> None:
        n = len(self.y_pred)
        if not (n == len(self.y_true) == len(self.last_close) == len(self.target_dates)):
            raise ValueError(
                "Prediccion desalineada: "
                f"pred={len(self.y_pred)}, true={len(self.y_true)}, "
                f"prev={len(self.last_close)}, dates={len(self.target_dates)}"
            )

    def metrics(self) -> EvaluationMetrics:
        """Calcula las metricas (R-07) sobre USD."""
        return compute_metrics(self.y_true, self.y_pred, self.last_close)


@dataclass(frozen=True)
class ComparisonRow:
    """Fila de la tabla comparativa."""

    model_key: str
    family: str
    metrics: dict[str, float]
    n_seeds: int = 1
    n_samples: int = 0
    stddev: dict[str, float] | None = None

    def to_dict(self) -> dict[str, object]:
        """Representacion serializable para la API."""
        payload: dict[str, object] = {
            "model_key": self.model_key,
            "family": self.family,
            "n_seeds": self.n_seeds,
            "n_samples": self.n_samples,
            **self.metrics,
        }
        if self.stddev:
            payload["stddev"] = self.stddev
        return payload


@dataclass(frozen=True)
class ComparisonReport:
    """Resultado de comparar varios modelos."""

    rows: list[ComparisonRow]
    test_dates_shared: bool
    n_test_samples: int
    test_dates_start: str
    test_dates_end: str

    def to_dict(self) -> dict[str, object]:
        """Representacion serializable para ``GET /v1/metrics/compare``."""
        return {
            "models": [row.to_dict() for row in self.rows],
            "test_dates_shared": self.test_dates_shared,
            "n_test_samples": self.n_test_samples,
            "test_dates_start": self.test_dates_start,
            "test_dates_end": self.test_dates_end,
        }


def numeric_metrics(metrics: EvaluationMetrics) -> dict[str, float]:
    """Extrae solo las metricas numericas agregables (sin ``confusion``)."""
    return {
        "mae": metrics.mae,
        "rmse": metrics.rmse,
        "mape": metrics.mape,
        "direction_accuracy": metrics.direction_accuracy,
        "f1": metrics.f1,
    }


def compare_models(predictions: list[ModelPrediction]) -> ComparisonReport:
    """Compara varios modelos exigiendo fechas de test identicas (R-05).

    Args:
        predictions: Una entrada por modelo, todas sobre las mismas fechas.

    Returns:
        ComparisonReport ordenado por MAE ascendente.

    Raises:
        ValueError: si no hay predicciones o las fechas objetivo no coinciden.
    """
    if not predictions:
        raise ValueError("compare_models requiere al menos una prediccion")

    reference = predictions[0]
    reference_dates = pd.DatetimeIndex(reference.target_dates)
    for other in predictions[1:]:
        other_dates = pd.DatetimeIndex(other.target_dates)
        if not other_dates.equals(reference_dates):
            raise ValueError(
                "R-05: todos los modelos deben evaluarse sobre las mismas fechas; "
                f"{other.model_key} difiere de {reference.model_key}"
            )

    rows = [
        ComparisonRow(
            model_key=p.model_key,
            family=p.family,
            metrics=numeric_metrics(p.metrics()),
            n_samples=p.metrics().n_samples,
        )
        for p in predictions
    ]
    rows.sort(key=lambda r: float(r.metrics["mae"]))

    return ComparisonReport(
        rows=rows,
        test_dates_shared=True,
        n_test_samples=len(reference_dates),
        test_dates_start=(reference_dates.min().date().isoformat() if len(reference_dates) else ""),
        test_dates_end=(reference_dates.max().date().isoformat() if len(reference_dates) else ""),
    )


def aggregate_over_seeds(
    metric_lists: list[dict[str, float]],
) -> tuple[dict[str, float], dict[str, float]]:
    """Agrega metricas de N semillas: media y desviacion tipica (R-08).

    Args:
        metric_lists: Lista de diccionarios de metricas, uno por semilla.

    Returns:
        Tupla ``(media, desviacion)`` con la misma clave que la entrada.

    Raises:
        ValueError: si la lista esta vacia o las claves no coinciden.
    """
    if not metric_lists:
        raise ValueError("aggregate_over_seeds requiere al menos una corrida")
    keys = set(metric_lists[0])
    for entry in metric_lists:
        if set(entry) != keys:
            raise ValueError("Todas las semillas deben reportar las mismas metricas")

    mean: dict[str, float] = {}
    stddev: dict[str, float] = {}
    for key in sorted(keys):
        values = np.asarray([float(entry[key]) for entry in metric_lists], dtype=np.float64)
        mean[key] = float(values.mean())
        stddev[key] = float(values.std(ddof=0))
    return mean, stddev
