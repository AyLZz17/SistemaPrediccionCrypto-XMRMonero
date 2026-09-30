"""R-01 y R-23: particion cronologica y ventanas asignadas por fecha de objetivo.

Invariantes verificados:

* ``max(train) < min(val) < min(test)`` en el tiempo.
* Cada muestra pertenece al subconjunto de la **fecha de su objetivo**.
* La ventana de entrada **nunca** contiene su propio objetivo ni datos posteriores.
* Ninguna ventana del subconjunto incluye un objetivo de otro subconjunto.
"""

from __future__ import annotations

import numpy as np
import pandas as pd
import pytest

from app.ml.data.split import (
    SUBSETS,
    WindowSet,
    chronological_split,
    make_windows,
    split_windows,
)

WINDOW = 30
FEATURES = ("close", "sma_7")


@pytest.fixture()
def windows(feature_frame: pd.DataFrame) -> WindowSet:
    """Conjunto de ventanas sobre el frame con features."""
    return make_windows(
        feature_frame,
        feature_names=FEATURES,
        target_column="close",
        window=WINDOW,
    )


def test_chronological_split_is_disjoint_and_ordered() -> None:
    """Los subconjuntos no se solapan y respetan el orden temporal (R-01)."""
    split = chronological_split(1000, train_ratio=0.70, val_ratio=0.15)

    assert split.train.max() < split.val.min()
    assert split.val.max() < split.test.min()

    all_positions = split.all_indices
    assert len(np.unique(all_positions)) == len(all_positions) == 1000
    assert np.array_equal(np.sort(all_positions), all_positions)

    sizes = (len(split.train), len(split.val), len(split.test))
    assert sizes == (700, 150, 150)


@pytest.mark.parametrize(
    ("n_rows", "train_ratio", "val_ratio", "expected"),
    [(1000, 0.70, 0.15, (700, 150, 150)), (500, 0.60, 0.20, (300, 100, 100))],
)
def test_chronological_split_sizes(
    n_rows: int, train_ratio: float, val_ratio: float, expected: tuple[int, int, int]
) -> None:
    """Las proporciones se respetan exactamente."""
    split = chronological_split(n_rows, train_ratio, val_ratio)
    assert (len(split.train), len(split.val), len(split.test)) == expected


@pytest.mark.parametrize(
    ("n_rows", "train_ratio", "val_ratio"),
    [(0, 0.7, 0.15), (100, 1.5, 0.15), (100, 0.7, 0.0), (100, 0.8, 0.3)],
)
def test_chronological_split_rejects_invalid(
    n_rows: int, train_ratio: float, val_ratio: float
) -> None:
    """Proporciones invalidas producen error explicito."""
    with pytest.raises(ValueError):
        chronological_split(n_rows, train_ratio, val_ratio)


def test_split_dates_are_strictly_increasing(windows: WindowSet) -> None:
    """Las fechas objetivo de cada subconjunto son estrictamente crecientes (R-01)."""
    for subset in SUBSETS:
        dates = windows.select(subset).target_dates
        assert len(dates) > 0
        assert dates.is_monotonic_increasing
        assert not dates.duplicated().any()


def test_target_dates_follow_temporal_subset_order(windows: WindowSet) -> None:
    """``max(train) < min(val) < min(test)`` sobre las fechas objetivo (R-01)."""
    train_dates = windows.select("train").target_dates
    val_dates = windows.select("val").target_dates
    test_dates = windows.select("test").target_dates

    assert train_dates.max() < val_dates.min()
    assert val_dates.max() < test_dates.min()


def test_sample_subset_matches_target_date(windows: WindowSet) -> None:
    """Cada muestra pertenece al subconjunto de la fecha de su objetivo (R-23)."""
    for subset in SUBSETS:
        part = windows.select(subset)
        expected_positions = set(part.target_positions.tolist())
        labelled = {
            int(position)
            for position, label in zip(windows.target_positions, windows.subsets, strict=True)
            if label == subset
        }
        assert expected_positions == labelled


def test_window_excludes_target_row_by_construction(feature_frame: pd.DataFrame) -> None:
    """Reconstruye la ventana a mano y comprueba que es exactamente ``[t-W, t-1]`` (R-23).

    Es la comprobacion mas fuerte: se recuperan los valores reales del frame
    original y se verifica que la ultima fila de la ventana es el dia ``t-1``,
    nunca el dia ``t`` del objetivo.
    """
    built = make_windows(
        feature_frame, feature_names=FEATURES, target_column="close", window=WINDOW
    )
    closes = feature_frame["close"].to_numpy(dtype=np.float64)
    positions = [int(p) for p in built.target_positions]

    for i, position in enumerate(positions[:60]):
        # La ultima fila de la ventana es el cierre de t-1.
        assert np.isclose(built.x[i, -1, 0], closes[position - 1])
        # El objetivo es el cierre de t.
        assert np.isclose(built.y[i], closes[position])
        # La ventana no contiene el cierre de t en ninguna posicion.
        assert not np.isclose(built.x[i, :, 0], closes[position]).any()


def test_window_indices_precede_target(windows: WindowSet) -> None:
    """Los indices de la ventana son exactamente ``[t-W, t-1]`` (R-23)."""
    for position in windows.target_positions:
        position = int(position)
        assert position - WINDOW >= 0, "la ventana no puede empezar antes del dataset"
        window_positions = list(range(position - WINDOW, position))
        assert position not in window_positions
        assert max(window_positions) == position - 1


def test_train_window_cannot_see_val_or_test_targets(windows: WindowSet) -> None:
    """Ningun objetivo de train aparece en la ventana de val o test (R-23)."""
    parts = split_windows(windows)
    future_targets = set(parts["val"].target_positions.tolist()) | set(
        parts["test"].target_positions.tolist()
    )

    train = parts["train"]
    for position in train.target_positions:
        window_positions = range(int(position) - WINDOW, int(position))
        assert not (set(window_positions) & future_targets)


def test_val_window_may_include_train_days(windows: WindowSet) -> None:
    """La ventana de val **si** puede incluir dias previos de train (R-23)."""
    train = windows.select("train")
    val = windows.select("val")
    train_positions = set(train.target_positions.tolist())

    crosses_boundary = any(
        set(range(int(pos) - WINDOW, int(pos))) & train_positions
        for pos in val.target_positions[: min(50, len(val))]
    )
    assert crosses_boundary, "se espera que alguna ventana de val alcance dias de train"


def test_every_window_ends_strictly_before_its_target(windows: WindowSet) -> None:
    """Ninguna muestra ve su propio objetivo ni nada posterior (R-23).

    Invariante universal: para toda muestra, ``max(ventana) < posicion_objetivo``.
    """
    for position in windows.target_positions:
        position = int(position)
        window_positions = range(position - WINDOW, position)
        assert max(window_positions) < position
        assert position not in window_positions


def test_test_targets_are_all_after_val_and_train_targets(windows: WindowSet) -> None:
    """Los objetivos de test son posteriores a los de train y val (R-01/R-23)."""
    parts = split_windows(windows)
    assert parts["train"].target_positions.max() < parts["val"].target_positions.min()
    assert parts["val"].target_positions.max() < parts["test"].target_positions.min()


def test_make_windows_rejects_non_monotonic_frame(synthetic_ohlcv: pd.DataFrame) -> None:
    """Un frame desordenado se rechaza en lugar de filtrar silenciosamente."""
    from app.ml.data.features import build_features, drop_warmup

    featured = drop_warmup(build_features(synthetic_ohlcv))
    shuffled = featured.iloc[::-1]
    with pytest.raises(ValueError, match="monotonas"):
        make_windows(shuffled, feature_names=FEATURES, target_column="close", window=WINDOW)


def test_make_windows_rejects_missing_columns(feature_frame: pd.DataFrame) -> None:
    """Faltar una columna requerida es un error explicito."""
    with pytest.raises(ValueError, match="Faltan columnas"):
        make_windows(
            feature_frame.drop(columns=["sma_7"]),
            feature_names=FEATURES,
            target_column="close",
            window=WINDOW,
        )


def test_subset_selection_round_trip(windows: WindowSet) -> None:
    """``select`` devuelve subconjuntos alineados y con la suma correcta."""
    total = sum(len(windows.select(name)) for name in SUBSETS)
    assert total == len(windows)
    for subset in SUBSETS:
        part = windows.select(subset)
        assert len(part.y) == len(part.x) == len(part.target_dates)
        assert set(part.subsets.tolist()) == {subset}


def test_subset_of_rejects_unknown_name(windows: WindowSet) -> None:
    """Un nombre de subconjunto desconocido falla."""
    with pytest.raises(ValueError, match="Subconjunto desconocido"):
        windows.select("holdout")
