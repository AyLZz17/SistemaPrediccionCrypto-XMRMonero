"""R-02: el ``MinMaxScaler`` se ajusta **solo con train**.

Invariantes verificados:

* ``data_min_``/``data_max_`` del escalador coinciden con los min/max de train,
  **no** con los del dataset completo.
* ``transform`` de val/test no modifica el escalador.
* Las predicciones se desescalan a USD antes de calcular metricas.
* El escalador nunca se ajusta sobre un subconjunto vacio.
"""

from __future__ import annotations

import numpy as np
import pandas as pd
import pytest

from app.ml.data.split import (
    FittedScaler,
    WindowSet,
    fit_scaler,
    make_windows,
    split_windows,
)

WINDOW = 30
FEATURES = ("close", "sma_7")


@pytest.fixture()
def parts(feature_frame: pd.DataFrame) -> dict[str, WindowSet]:
    """Subconjuntos de ventanas sin escalar."""
    return split_windows(
        make_windows(feature_frame, feature_names=FEATURES, target_column="close", window=WINDOW)
    )


@pytest.fixture()
def scaler(parts: dict[str, WindowSet]) -> FittedScaler:
    """Escalador ajustado con train unicamente."""
    return fit_scaler(parts["train"], target_name="close")


def test_scaler_min_max_match_train_not_full(
    scaler: FittedScaler, parts: dict[str, WindowSet]
) -> None:
    """``data_min_``/``data_max_`` reflejan train, no el dataset completo (R-02)."""
    train = parts["train"]
    n_samples, window, n_features = train.x.shape
    train_flat = train.x.reshape(n_samples * window, n_features)

    for index in range(n_features):
        assert np.isclose(scaler.scaler.data_min_[index], train_flat[:, index].min())
        assert np.isclose(scaler.scaler.data_max_[index], train_flat[:, index].max())

    # El escalador solo ha visto las muestras de train (R-02).
    assert int(scaler.scaler.n_samples_seen_) == n_samples * window
    assert int(scaler.scaler.n_features_in_) == n_features

    # Ajustar sobre el dataset completo daria otros limites; el escalador de
    # train no coincide con el de "todo el dataset" salvo en features planas.
    from sklearn.preprocessing import MinMaxScaler

    full = np.concatenate(
        [parts[name].x.reshape(-1, len(FEATURES)) for name in ("train", "val", "test")]
    )
    full_scaler = MinMaxScaler().fit(full)
    assert not np.array_equal(full_scaler.data_min_, scaler.scaler.data_min_) or not np.array_equal(
        full_scaler.data_max_, scaler.scaler.data_max_
    ), "el escalador parece haberse ajustado sobre el dataset completo"


def test_scaler_maps_train_into_unit_interval(
    scaler: FittedScaler, parts: dict[str, WindowSet]
) -> None:
    """Tras ``fit`` + ``transform``, train queda en ``[0, 1]`` (R-02)."""
    train_scaled = scaler.transform(parts["train"])
    assert train_scaled.x.min() >= -1e-12
    assert train_scaled.x.max() <= 1.0 + 1e-12
    assert np.isclose(train_scaled.x.min(), 0.0)
    assert np.isclose(train_scaled.x.max(), 1.0)


def test_transform_does_not_refit_scaler(scaler: FittedScaler, parts: dict[str, WindowSet]) -> None:
    """``transform`` de val/test no altera los limites del escalador (R-02)."""
    before_min = scaler.scaler.data_min_.copy()
    before_max = scaler.scaler.data_max_.copy()
    before_n = int(scaler.scaler.n_samples_seen_)

    scaler.transform(parts["val"])
    scaler.transform(parts["test"])

    assert np.array_equal(scaler.scaler.data_min_, before_min)
    assert np.array_equal(scaler.scaler.data_max_, before_max)
    assert int(scaler.scaler.n_samples_seen_) == before_n


def test_out_of_range_values_are_not_clipped(
    scaler: FittedScaler, parts: dict[str, WindowSet]
) -> None:
    """Si test sale del rango de train, la transformacion **no** recorta (S-05)."""
    test_scaled = scaler.transform(parts["test"])
    assert test_scaled.x.max() > 1.0 or test_scaled.x.min() < 0.0, (
        "el dataset sintetico deberia tener precios fuera del rango de train"
    )


def test_round_trip_target_scaling_is_exact(
    scaler: FittedScaler, parts: dict[str, WindowSet]
) -> None:
    """``inverse_target(scale_target(y)) == y``: las metricas se calculan en USD (R-02)."""
    y = np.asarray(parts["train"].y, dtype=np.float64)
    recovered = scaler.inverse_target(scaler.scale_target(y))
    assert np.allclose(recovered, y)


def test_round_trip_close_feature_matches_target_column(
    scaler: FittedScaler, parts: dict[str, WindowSet]
) -> None:
    """Objetivo y columna ``close`` comparten transformador (R-02) pero shifted (R-23).

    El ultimo valor de la ventana es el cierre de ``t-1``; el objetivo es el de
    ``t``. Que ambos coincidan con el mismo escalador demuestra R-02, y que
    esten desplazados demuestra R-23.
    """
    train = parts["train"]
    scaled_close = scaler.transform(train).x[:, -1, 0]
    scaled_target = scaler.scale_target(np.asarray(train.y, dtype=np.float64))

    assert len(scaled_close) == len(scaled_target)
    # El cierre de la ventana de la muestra i coincide con el objetivo de i-1.
    assert np.allclose(scaled_close[1:], scaled_target[:-1])
    # Y no con su propio objetivo.
    assert not np.allclose(scaled_close, scaled_target)


def test_inverse_target_uses_target_column_only(scaler: FittedScaler) -> None:
    """``inverse_target`` devuelve una serie, no una matriz de features (R-02)."""
    scaled = np.asarray([0.25, 0.5, 0.75], dtype=np.float64)
    usd = scaler.inverse_target(scaled)
    assert usd.shape == (3,)
    assert np.all(np.diff(usd) > 0)


def test_fit_scaler_rejects_empty_train() -> None:
    """Ajustar con train vacio es un error explicito (R-02)."""
    empty = WindowSet(
        x=np.zeros((0, 3, 2)),
        y=np.zeros(0),
        feature_names=("close", "sma_7"),
        target_positions=np.zeros(0, dtype=np.int64),
        target_dates=pd.DatetimeIndex([]),
        subsets=np.zeros(0, dtype="<U5"),
        window=3,
    )
    with pytest.raises(ValueError, match="train vacio"):
        fit_scaler(empty, target_name="close")


def test_fit_scaler_requires_target_in_features(parts: dict[str, WindowSet]) -> None:
    """El objetivo debe estar entre las features para compartir escalador."""
    train = WindowSet(
        x=parts["train"].x[:, :, 1:],  # quitamos 'close'
        y=parts["train"].y,
        feature_names=("sma_7",),
        target_positions=parts["train"].target_positions,
        target_dates=parts["train"].target_dates,
        subsets=parts["train"].subsets,
        window=WINDOW,
    )
    with pytest.raises(ValueError, match="debe estar entre las features"):
        fit_scaler(train, target_name="close")


def test_scaler_transform_preserves_shape_and_metadata(
    scaler: FittedScaler, parts: dict[str, WindowSet]
) -> None:
    """``transform`` no altera la forma ni los metadatos de las ventanas (R-02)."""
    original = parts["test"]
    scaled = scaler.transform(original)
    assert scaled.x.shape == original.x.shape
    assert scaled.target_dates.equals(original.target_dates)
    assert np.array_equal(scaled.target_positions, original.target_positions)
    assert np.array_equal(scaled.subsets, original.subsets)
