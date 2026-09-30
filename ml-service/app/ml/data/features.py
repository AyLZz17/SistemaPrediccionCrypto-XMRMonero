"""Ingenieria de caracteristicas tecnicas **causales** (R-03).

Todas las operaciones usan informacion ``<= t``: ``rolling(window)`` (sin
``center=True``) y ``ewm(adjust=False)``. Prohibido ``shift(-n)`` (verificado
por ``tests/test_features_no_leakage.py``).
"""

from __future__ import annotations

import numpy as np
import pandas as pd

__all__ = [
    "FEATURE_COLUMNS",
    "add_exponential_moving_averages",
    "add_macd",
    "add_moving_averages",
    "add_returns",
    "add_rsi",
    "add_volatility",
    "build_features",
    "drop_warmup",
    "feature_columns",
]

#: Nombres de las caracteristicas derivadas, en orden canonico.
FEATURE_COLUMNS: tuple[str, ...] = (
    "returns_1d",
    "sma_7",
    "sma_14",
    "sma_30",
    "ema_12",
    "ema_26",
    "rsi_14",
    "macd",
    "macd_signal",
    "macd_hist",
    "volatility",
)

_SMA_WINDOWS = (7, 14, 30)
_EMA_SPANS = (12, 26)
_RSI_PERIOD = 14
_MACD_SIGNAL_SPAN = 9
_VOLATILITY_WINDOW = 14


def add_returns(frame: pd.DataFrame, column: str = "close") -> pd.DataFrame:
    """Anade ``returns_1d`` = variación porcentual diaria (solo usa ``t`` y ``t-1``)."""
    out = frame.copy()
    out["returns_1d"] = out[column].pct_change(fill_method=None)
    return out


def add_moving_averages(frame: pd.DataFrame, column: str = "close") -> pd.DataFrame:
    """Anade ``sma_7``/``sma_14``/``sma_30`` con ventana hacia atras."""
    out = frame.copy()
    for window in _SMA_WINDOWS:
        out[f"sma_{window}"] = out[column].rolling(window=window, min_periods=window).mean()
    return out


def add_exponential_moving_averages(frame: pd.DataFrame, column: str = "close") -> pd.DataFrame:
    """Anade ``ema_12``/``ema_26`` con ``adjust=False`` (recursiva y causal)."""
    out = frame.copy()
    for span in _EMA_SPANS:
        out[f"ema_{span}"] = out[column].ewm(span=span, adjust=False).mean()
    return out


def add_rsi(frame: pd.DataFrame, period: int = _RSI_PERIOD, column: str = "close") -> pd.DataFrame:
    """Anade ``rsi_14`` (Wilder). Solo usa diferencias hasta ``t``.

    Se usan ``ewm(alpha=1/period, adjust=False)`` para ganar y perdida, que son
    medias exponenciales causalmente informadas.
    """
    out = frame.copy()
    delta = out[column].diff()
    gain = delta.clip(lower=0.0)
    loss = (-delta).clip(lower=0.0)
    alpha = 1.0 / period
    avg_gain = gain.ewm(alpha=alpha, adjust=False, min_periods=period).mean()
    avg_loss = loss.ewm(alpha=alpha, adjust=False, min_periods=period).mean()
    rs = avg_gain / avg_loss.replace(0.0, np.nan)
    rsi = 100.0 - (100.0 / (1.0 + rs))
    # Sin perdidas (loss == 0) el RSI satura a 100.
    rsi = rsi.where(~((avg_loss == 0) & (avg_gain > 0)), 100.0)
    rsi = rsi.where(~((avg_loss == 0) & (avg_gain == 0)), 50.0)
    out[f"rsi_{period}"] = rsi
    return out


def add_macd(
    frame: pd.DataFrame, fast: int = 12, slow: int = 26, signal: int = _MACD_SIGNAL_SPAN
) -> pd.DataFrame:
    """Anade ``macd``, ``macd_signal`` y ``macd_hist`` (todos causales)."""
    out = frame.copy()
    ema_fast = out["close"].ewm(span=fast, adjust=False).mean()
    ema_slow = out["close"].ewm(span=slow, adjust=False).mean()
    out["macd"] = ema_fast - ema_slow
    out["macd_signal"] = out["macd"].ewm(span=signal, adjust=False).mean()
    out["macd_hist"] = out["macd"] - out["macd_signal"]
    return out


def add_volatility(frame: pd.DataFrame, window: int = _VOLATILITY_WINDOW) -> pd.DataFrame:
    """Anade ``volatility`` = desviacion tipica movil de ``returns_1d``."""
    out = frame.copy()
    out["volatility"] = out["returns_1d"].rolling(window=window, min_periods=window).std(ddof=0)
    return out


def feature_columns(frame: pd.DataFrame) -> tuple[str, ...]:
    """Devuelve las caracteristicas disponibles en el frame, en orden canonico."""
    return tuple(col for col in FEATURE_COLUMNS if col in frame.columns)


def build_features(frame: pd.DataFrame, price_column: str = "close") -> pd.DataFrame:
    """Construye el ``feature set`` completo de forma determinista y causal.

    Args:
        frame: Serie OHLCV con indice ``DatetimeIndex`` monotono creciente.
        price_column: Columna de precio usada como referencia.

    Returns:
        Copia del frame con las columnas de :data:`FEATURE_COLUMNS` anadidas.
    """
    if not isinstance(frame.index, pd.DatetimeIndex):
        raise ValueError("build_features requiere un indice DatetimeIndex")
    if not frame.index.is_monotonic_increasing:
        raise ValueError("build_features requiere fechas monotonas crecientes")

    out = add_returns(frame, price_column)
    out = add_moving_averages(out, price_column)
    out = add_exponential_moving_averages(out, price_column)
    out = add_rsi(out, _RSI_PERIOD, price_column)
    out = add_macd(out)
    out = add_volatility(out)
    return out


def drop_warmup(frame: pd.DataFrame, columns: tuple[str, ...] | None = None) -> pd.DataFrame:
    """Elimina las filas iniciales con ``NaN`` por calentamiento de indicadores."""
    cols = list(columns) if columns is not None else list(feature_columns(frame))
    return frame.dropna(subset=cols)
