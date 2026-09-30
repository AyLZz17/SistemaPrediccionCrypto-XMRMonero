"""R-03: las caracteristicas tecnicas son causales (sin fuga de futuro).

Dos comprobaciones complementarias:

1. **Estatico**: escaneo AST que prohibe ``shift`` con argumento negativo y
   ``center=True`` en ``app/ml/data``.
2. **Dinamico**: mutar el futuro no cambia ninguna feature pasada.
"""

from __future__ import annotations

import ast
from pathlib import Path

import numpy as np
import pandas as pd
import pytest

from app.ml.data.features import (
    FEATURE_COLUMNS,
    build_features,
    drop_warmup,
    feature_columns,
)

PACKAGE_ROOT = Path(__file__).resolve().parents[1]


def _feature_sources() -> list[Path]:
    """Ficheros Python del subpaquete ``data`` responsible de features."""
    return sorted((PACKAGE_ROOT / "app" / "ml" / "data").rglob("*.py"))


@pytest.mark.parametrize("path", _feature_sources(), ids=lambda p: p.name)
def test_no_negative_shift_in_source(path: Path) -> None:
    """Ninguna llamada usa ``shift(-n)``: prohibido por R-03."""
    tree = ast.parse(path.read_text(encoding="utf-8"), filename=str(path))
    offenders: list[str] = []

    for node in ast.walk(tree):
        if not isinstance(node, ast.Call):
            continue
        func = node.func
        name = func.attr if isinstance(func, ast.Attribute) else getattr(func, "id", "")
        if name != "shift":
            continue
        for arg in node.args:
            # shift(-1), shift(periods=-1), etc.
            if isinstance(arg, ast.UnaryOp) and isinstance(arg.op, ast.USub):
                offenders.append(f"shift con constante negativa en linea {node.lineno}")
            if isinstance(arg, ast.Constant) and isinstance(arg.value, int) and arg.value < 0:
                offenders.append(f"shift con constante negativa en linea {node.lineno}")
            if isinstance(arg, ast.BinOp) and isinstance(arg.op, ast.USub):
                offenders.append(f"shift con expresion negativa en linea {node.lineno}")
        for keyword in node.keywords:
            value = keyword.value
            if (
                isinstance(value, ast.Constant)
                and isinstance(value.value, int)
                and value.value < 0
            ):
                offenders.append(f"shift con argumento negativo en linea {node.lineno}")

    assert not offenders, f"{path.name} usa shift negativo (R-03): {offenders}"


@pytest.mark.parametrize("path", _feature_sources(), ids=lambda p: p.name)
def test_no_centered_windows_in_source(path: Path) -> None:
    """Ninguna llamada usa ``center=True`` en ventanas moviles (R-03)."""
    tree = ast.parse(path.read_text(encoding="utf-8"), filename=str(path))
    offenders = [
        f"center=True en linea {node.lineno}"
        for node in ast.walk(tree)
        if isinstance(node, ast.Call)
        for keyword in node.keywords
        if keyword.arg == "center"
        and isinstance(keyword.value, ast.Constant)
        and keyword.value.value is True
    ]
    assert not offenders, f"{path.name} usa ventanas centradas (R-03): {offenders}"


def test_expected_feature_columns_present(feature_frame: pd.DataFrame) -> None:
    """Se construyen todas las features exigidas por la especificacion."""
    present = feature_columns(feature_frame)
    assert present == FEATURE_COLUMNS
    assert set(FEATURE_COLUMNS).issubset(feature_frame.columns)


def test_mutating_future_does_not_change_past_features(synthetic_ohlcv: pd.DataFrame) -> None:
    """Alterar el futuro no cambia ninguna feature pasada (R-03).

    Es la prueba de fuga mas fuerte: se calcula el feature set dos veces,
    perturbando solo las filas posteriores a un corte, y se comparan las filas
    anteriores al corte.
    """
    original = build_features(synthetic_ohlcv)
    cutoff = len(synthetic_ohlcv) // 2

    perturbed_input = synthetic_ohlcv.copy()
    perturbed_input.iloc[cutoff:, perturbed_input.columns.get_loc("close")] *= 3.0
    perturbed_input.iloc[cutoff:, perturbed_input.columns.get_loc("volume")] *= 5.0
    perturbed = build_features(perturbed_input)

    columns = list(FEATURE_COLUMNS)
    past_original = original.iloc[:cutoff][columns]
    past_perturbed = perturbed.iloc[:cutoff][columns]
    both_valid = past_original.notna() & past_perturbed.notna()

    assert both_valid.to_numpy().any(), "no hay filas comparables"
    left = past_original.to_numpy(dtype=np.float64)[both_valid.to_numpy()]
    right = past_perturbed.to_numpy(dtype=np.float64)[both_valid.to_numpy()]
    assert np.isfinite(left).all() and np.isfinite(right).all(), "quedan NaN tras el recorte"
    assert np.allclose(left, right), (
        "las features del pasado cambiaron al modificar el futuro (fuga, R-03)"
    )


def test_past_features_unchanged_after_warmup(feature_frame: pd.DataFrame) -> None:
    """Tras el calentamiento, las features ya son definitivas (R-03)."""
    cutoff = len(feature_frame) // 2
    tail = feature_frame.iloc[:cutoff][list(FEATURE_COLUMNS)]
    assert not tail.isna().to_numpy().any(), "el calentamiento deberia eliminar todos los NaN"


def test_returns_use_only_previous_day(synthetic_ohlcv: pd.DataFrame) -> None:
    """``returns_1d`` de ``t`` solo depende de ``t`` y ``t-1``."""
    frame = build_features(synthetic_ohlcv)
    index = 100
    close = synthetic_ohlcv["close"].to_numpy(dtype=np.float64)
    expected = close[index] / close[index - 1] - 1.0
    assert np.isclose(frame["returns_1d"].iloc[index], expected)


def test_sma_is_trailing_average(synthetic_ohlcv: pd.DataFrame) -> None:
    """``sma_7`` es la media de los 7 ultimos cierres, incluidos hoy."""
    frame = build_features(synthetic_ohlcv)
    close = synthetic_ohlcv["close"].to_numpy(dtype=np.float64)
    index = 50
    expected = close[index - 6 : index + 1].mean()
    assert np.isclose(frame["sma_7"].iloc[index], expected)


def test_ema_is_recursive_and_causal(synthetic_ohlcv: pd.DataFrame) -> None:
    """``ema_12`` sigue la recursion ``ema_t = a*x_t + (1-a)*ema_{t-1}``."""
    frame = build_features(synthetic_ohlcv)
    close = synthetic_ohlcv["close"].to_numpy(dtype=np.float64)
    alpha = 2.0 / (12.0 + 1.0)

    ema = close[0]
    for step in range(1, 60):
        ema = alpha * close[step] + (1.0 - alpha) * ema
        assert np.isclose(frame["ema_12"].iloc[step], ema)


def test_rsi_is_bounded_between_0_and_100(feature_frame: pd.DataFrame) -> None:
    """El RSI se mantiene en el rango teorico."""
    rsi = feature_frame["rsi_14"].dropna()
    assert len(rsi) > 0
    assert rsi.min() >= -1e-9
    assert rsi.max() <= 100.0 + 1e-9


def test_macd_components_are_consistent(feature_frame: pd.DataFrame) -> None:
    """``macd_hist = macd - macd_signal`` (coherencia interna)."""
    expected = feature_frame["macd"] - feature_frame["macd_signal"]
    assert np.allclose(feature_frame["macd_hist"].to_numpy(), expected.to_numpy())


def test_macd_is_difference_of_emas(synthetic_ohlcv: pd.DataFrame) -> None:
    """``macd = ema_12 - ema_26``."""
    frame = build_features(synthetic_ohlcv)
    expected = frame["ema_12"] - frame["ema_26"]
    assert np.allclose(frame["macd"].to_numpy(), expected.to_numpy())


def test_volatility_is_rolling_std(synthetic_ohlcv: pd.DataFrame) -> None:
    """``volatility`` es la desviacion movil de ``returns_1d``."""
    frame = build_features(synthetic_ohlcv)
    returns = frame["returns_1d"].to_numpy(dtype=np.float64)
    index = 60
    expected = np.std(returns[index - 13 : index + 1], ddof=0)
    assert np.isclose(frame["volatility"].iloc[index], expected)


def test_build_features_requires_datetime_index(synthetic_ohlcv: pd.DataFrame) -> None:
    """Un indice que no sea DatetimeIndex se rechaza."""
    reset = synthetic_ohlcv.reset_index(drop=True)
    with pytest.raises(ValueError, match="DatetimeIndex"):
        build_features(reset)


def test_build_features_requires_monotonic_dates(synthetic_ohlcv: pd.DataFrame) -> None:
    """Un indice desordenado se rechaza (evita fugas silenciosas)."""
    with pytest.raises(ValueError, match="monotonas"):
        build_features(synthetic_ohlcv.iloc[::-1])


def test_drop_warmup_removes_leading_nans(synthetic_ohlcv: pd.DataFrame) -> None:
    """``drop_warmup`` elimina exactamente las filas con NaN inicial."""
    featured = build_features(synthetic_ohlcv)
    columns = list(FEATURE_COLUMNS)
    warm = drop_warmup(featured, columns)
    assert len(warm) < len(featured)
    assert not warm[columns].isna().to_numpy().any()
