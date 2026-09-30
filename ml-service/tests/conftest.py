"""Fixtures compartidas. Datos **sinteticos**, sin red (R-18)."""

from __future__ import annotations

from collections.abc import Iterator
from pathlib import Path

import numpy as np
import pandas as pd
import pytest

#: Semilla fija: todos los datos de prueba son reproducibles (R-08).
SYNTHETIC_SEED = 20260930
N_DAYS = 720


@pytest.fixture(scope="session")
def synthetic_seed() -> int:
    """Semilla usada para generar el dataset sintetico."""
    return SYNTHETIC_SEED


def make_ohlcv(n_days: int = N_DAYS, seed: int = SYNTHETIC_SEED) -> pd.DataFrame:
    """Genera una serie OHLCV diaria sintetica tipo paseo geometrico.

    No usa red ni ficheros externos: es la unica fuente de datos de la suite
    (R-18). El precio parte de 150 USD y evoluciona con volatilidad Mild.

    Args:
        n_days: Numero de dias a generar.
        seed: Semilla de NumPy.

    Returns:
        DataFrame con indice ``DatetimeIndex`` UTC y columnas OHLCV.
    """
    rng = np.random.default_rng(seed)
    dates = pd.date_range("2023-01-01", periods=n_days, freq="D", tz="UTC")
    log_returns = rng.normal(loc=0.0002, scale=0.012, size=n_days)
    close = 150.0 * np.exp(np.cumsum(log_returns))
    open_ = close * (1.0 + rng.normal(0.0, 0.002, size=n_days))
    high = np.maximum(open_, close) * (1.0 + np.abs(rng.normal(0.0, 0.004, size=n_days)))
    low = np.minimum(open_, close) * (1.0 - np.abs(rng.normal(0.0, 0.004, size=n_days)))
    volume = rng.integers(2_000, 50_000, size=n_days).astype(float)
    return pd.DataFrame(
        {"open": open_, "high": high, "low": low, "close": close, "volume": volume},
        index=dates,
    )


@pytest.fixture(scope="session")
def synthetic_ohlcv() -> pd.DataFrame:
    """Dataset OHLCV sintetico de sesion."""
    return make_ohlcv()


@pytest.fixture(scope="session")
def synthetic_snapshot(
    tmp_path_factory: pytest.TempPathFactory, synthetic_ohlcv: pd.DataFrame
) -> Path:
    """Snapshot CSV sintetico en disco, con SHA-256 verificable."""
    path = tmp_path_factory.mktemp("data") / "xmr_usd_synthetic.csv"
    frame = synthetic_ohlcv.copy()
    frame.index.name = "date"
    frame.to_csv(path)
    return path


@pytest.fixture(scope="session")
def clean_price_frame(synthetic_ohlcv: pd.DataFrame) -> pd.DataFrame:
    """Frame sintetico ya validado, listo para ``build_features``."""
    return synthetic_ohlcv.copy()


@pytest.fixture(scope="session")
def feature_frame(synthetic_ohlcv: pd.DataFrame) -> pd.DataFrame:
    """Frame con el feature set completo y sin NaN de calentamiento."""
    from app.ml.data.features import build_features, drop_warmup

    return drop_warmup(build_features(synthetic_ohlcv))


@pytest.fixture()
def temp_registry_dir(tmp_path: Path) -> Iterator[Path]:
    """Directorio temporal para artefactos de test."""
    target = tmp_path / "artifacts"
    target.mkdir(parents=True, exist_ok=True)
    yield target
