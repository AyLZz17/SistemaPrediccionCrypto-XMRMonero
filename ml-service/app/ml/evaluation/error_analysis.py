"""Analisis de fallos obligatorio (R-10, SKILLS.md S-07).

Cubre los dos ejes exigidos:

1. **Cambios bruscos del mercado**: segmentacion por regimen de volatilidad
   (percentil 75 de la desviacion movil de retornos) y error por regimen.
2. **Suavizado de picos**: correlacion entre cambios reales y predichos, y
   amplitud relativa de la prediccion frente a la real.

No afirma causalidad con eventos de mercado; son contexto, no explicacion.
"""

from __future__ import annotations

from dataclasses import dataclass, field

import numpy as np
import pandas as pd

__all__ = [
    "ErrorAnalysisReport",
    "PeakSmoothingReport",
    "RegimeReport",
    "analyze_errors",
    "peak_smoothing_analysis",
    "regime_analysis",
    "worst_days",
]

#: Percentil que define el regimen de alta volatilidad (SKILLS.md S-07).
HIGH_VOLATILITY_PERCENTILE = 75.0


@dataclass(frozen=True)
class RegimeReport:
    """Error medio separado por regimen de volatilidad."""

    threshold: float
    n_high: int
    n_low: int
    mae_high_volatility: float
    mae_low_volatility: float
    rmse_high_volatility: float
    rmse_low_volatility: float

    def to_dict(self) -> dict[str, object]:
        """Representacion serializable."""
        return {
            "threshold": self.threshold,
            "n_high_volatility": self.n_high,
            "n_low_volatility": self.n_low,
            "mae_high_volatility": self.mae_high_volatility,
            "mae_low_volatility": self.mae_low_volatility,
            "rmse_high_volatility": self.rmse_high_volatility,
            "rmse_low_volatility": self.rmse_low_volatility,
        }


@dataclass(frozen=True)
class PeakSmoothingReport:
    """Medida del suavizado de picos de la prediccion."""

    change_correlation: float
    predicted_amplitude: float
    actual_amplitude: float
    relative_amplitude: float
    n_samples: int

    def to_dict(self) -> dict[str, object]:
        """Representacion serializable."""
        return {
            "change_correlation": self.change_correlation,
            "predicted_amplitude": self.predicted_amplitude,
            "actual_amplitude": self.actual_amplitude,
            "relative_amplitude": self.relative_amplitude,
            "n_samples": self.n_samples,
        }


@dataclass(frozen=True)
class ErrorAnalysisReport:
    """Informe completo de analisis de fallos de un modelo."""

    model_key: str
    n_samples: int
    worst_days: list[dict[str, object]] = field(default_factory=list)
    regime: RegimeReport | None = None
    peak_smoothing: PeakSmoothingReport | None = None
    limitations: list[str] = field(default_factory=list)

    def to_dict(self) -> dict[str, object]:
        """Representacion serializable."""
        return {
            "model_key": self.model_key,
            "n_samples": self.n_samples,
            "worst_days": self.worst_days,
            "regime": self.regime.to_dict() if self.regime else None,
            "peak_smoothing": (self.peak_smoothing.to_dict() if self.peak_smoothing else None),
            "limitations": list(self.limitations),
        }


def _abs_error(y_true: np.ndarray, y_pred: np.ndarray) -> np.ndarray:
    return np.abs(np.asarray(y_true, dtype=np.float64) - np.asarray(y_pred, dtype=np.float64))


def worst_days(
    dates: pd.DatetimeIndex, y_true: np.ndarray, y_pred: np.ndarray, k: int = 10
) -> list[dict[str, object]]:
    """Lista los ``k`` dias con mayor error absoluto (SKILLS.md S-07, punto 1)."""
    errors = _abs_error(y_true, y_pred)
    order = np.argsort(errors)[::-1][: min(k, len(errors))]
    return [
        {
            "date": pd.DatetimeIndex(dates)[int(i)].date().isoformat(),
            "actual_close": float(np.asarray(y_true)[int(i)]),
            "predicted_close": float(np.asarray(y_pred)[int(i)]),
            "abs_error": float(errors[int(i)]),
        }
        for i in order
    ]


def regime_analysis(
    y_true: np.ndarray, y_pred: np.ndarray, last_close: np.ndarray, percentile: float = 75.0
) -> RegimeReport:
    """Segmenta el error por regimen de volatilidad (R-10).

    La volatilidad de cada paso es la desviacion tipica movil de los
    ``returns_1d`` calculada con la ventana ``HIGH_VOLATILITY_PERCENTILE``. El
    umbral de corte es el percentil indicado (75 por defecto, S-07).

    Args:
        y_true: Cierres reales en USD.
        y_pred: Cierres predichos en USD.
        last_close: Ultimo cierre observado antes de cada objetivo.
        percentile: Percentil que define alta volatilidad.

    Returns:
        RegimeReport con MAE/RMSE por regimen.

    Raises:
        ValueError: si hay menos de 2 muestras.
    """
    true = np.asarray(y_true, dtype=np.float64).ravel()
    pred = np.asarray(y_pred, dtype=np.float64).ravel()
    prev = np.asarray(last_close, dtype=np.float64).ravel()
    if true.size < 2:
        raise ValueError("regime_analysis requiere al menos 2 muestras")

    returns = np.concatenate(([np.nan], np.diff(prev) / prev[:-1]))
    window = max(2, int(np.ceil(true.size * percentile / 100.0)))
    volatility = pd.Series(returns).rolling(window=window, min_periods=2).std(ddof=0)
    vol_values = volatility.to_numpy(dtype=np.float64)

    finite = np.isfinite(vol_values)
    threshold = float(np.nanpercentile(vol_values[finite], percentile))

    high = finite & (vol_values > threshold)
    low = finite & (vol_values <= threshold)
    errors = _abs_error(true, pred)

    def _mean(values: np.ndarray, mask: np.ndarray) -> float:
        selected = values[mask]
        return float(selected.mean()) if selected.size else float("nan")

    def _rmse(mask: np.ndarray) -> float:
        if not mask.any():
            return float("nan")
        return float(np.sqrt(np.mean(np.square(errors[mask]))))

    return RegimeReport(
        threshold=threshold,
        n_high=int(high.sum()),
        n_low=int(low.sum()),
        mae_high_volatility=_mean(errors, high),
        mae_low_volatility=_mean(errors, low),
        rmse_high_volatility=_rmse(high),
        rmse_low_volatility=_rmse(low),
    )


def peak_smoothing_analysis(
    y_true: np.ndarray, y_pred: np.ndarray, last_close: np.ndarray
) -> PeakSmoothingReport:
    """Mide si la prediccion suaviza los picos (R-10, S-07 punto 3).

    Compara las **variaciones** diarias reales y predichas: una correlacion
    baja indica que el modelo no reproduce los giros, y una amplitud relativa
    menor que 1 indica que infravalora los movimientos.

    Args:
        y_true: Cierres reales en USD.
        y_pred: Cierres predichos en USD.
        last_close: Ultimo cierre observado antes de cada objetivo. No interviene
            en el calculo (la correlacion usa variaciones entre objetivos
            consecutivos), pero se acepta para mantener la firma uniforme de la
            API de analisis de fallos.

    Returns:
        PeakSmoothingReport con correlacion y amplitudes.

    Raises:
        ValueError: si hay menos de 2 muestras.
    """
    true = np.asarray(y_true, dtype=np.float64).ravel()
    pred = np.asarray(y_pred, dtype=np.float64).ravel()
    if true.size < 2:
        raise ValueError("peak_smoothing_analysis requiere al menos 2 muestras")

    actual_changes = np.diff(true)
    predicted_changes = np.diff(pred)
    if actual_changes.std() == 0 or predicted_changes.std() == 0:
        correlation = 0.0
    else:
        correlation = float(np.corrcoef(actual_changes, predicted_changes)[0, 1])

    predicted_amplitude = float(np.mean(np.abs(predicted_changes)))
    actual_amplitude = float(np.mean(np.abs(actual_changes)))
    relative = predicted_amplitude / actual_amplitude if actual_amplitude > 0 else 0.0

    return PeakSmoothingReport(
        change_correlation=correlation,
        predicted_amplitude=predicted_amplitude,
        actual_amplitude=actual_amplitude,
        relative_amplitude=float(relative),
        n_samples=int(true.size),
    )


#: Limitaciones que se registran siempre (S-07 punto 5).
DEFAULT_LIMITATIONS: tuple[str, ...] = (
    "El analisis describe comportamiento del modelo, no causalidad de mercado.",
    "Un resultado negativo es valido y se reporta sin ajustes sobre el test (R-09).",
    "El conjunto de test se uso una sola vez para la evaluacion final (R-04).",
    "Factores externos (noticias, liquidaciones, regulacion) no estan en el modelo.",
)


def analyze_errors(
    model_key: str,
    dates: pd.DatetimeIndex,
    y_true: np.ndarray,
    y_pred: np.ndarray,
    last_close: np.ndarray,
    k_worst: int = 10,
) -> ErrorAnalysisReport:
    """Ejecuta el analisis de fallos completo (R-10).

    Args:
        model_key: Clave del modelo analizado.
        dates: Fechas objetivo del periodo evaluado.
        y_true: Cierres reales en USD.
        y_pred: Cierres predichos en USD.
        last_close: Ultimo cierre observado antes de cada objetivo.
        k_worst: Cuantos dias de mayor error listar.

    Returns:
        ErrorAnalysisReport con peores dias, regimenes y suavizado de picos.
    """
    true = np.asarray(y_true, dtype=np.float64).ravel()
    pred = np.asarray(y_pred, dtype=np.float64).ravel()
    prev = np.asarray(last_close, dtype=np.float64).ravel()
    return ErrorAnalysisReport(
        model_key=model_key,
        n_samples=int(true.size),
        worst_days=worst_days(dates, true, pred, k_worst),
        regime=regime_analysis(true, pred, prev),
        peak_smoothing=peak_smoothing_analysis(true, pred, prev),
        limitations=list(DEFAULT_LIMITATIONS),
    )
