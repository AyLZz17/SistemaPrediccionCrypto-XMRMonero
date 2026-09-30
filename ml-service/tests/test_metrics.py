"""R-07: MAE, RMSE, MAPE, acierto de direccion y matriz de confusion.

Todos los valores esperados estan calculados **a mano** para series pequenas,
de modo que un error en las formulas no pase desapercibido.
"""

from __future__ import annotations

import numpy as np
import pytest

from app.ml.evaluation.metrics import (
    compute_metrics,
    confusion_matrix,
    direction_accuracy,
    f1_score,
    mae,
    mape,
    rmse,
)

# Serie de ejemplo:
#   real  = [100, 110, 105, 120]
#   pred  = [102, 108, 103, 130]
#   prev  = [ 99, 100, 110, 105]
Y_TRUE = np.array([100.0, 110.0, 105.0, 120.0])
Y_PRED = np.array([102.0, 108.0, 103.0, 130.0])
LAST_CLOSE = np.array([99.0, 100.0, 110.0, 105.0])


def test_mae_hand_computed() -> None:
    """MAE = (2 + 2 + 2 + 10) / 4 = 4.0."""
    assert mae(Y_TRUE, Y_PRED) == pytest.approx(4.0)


def test_rmse_hand_computed() -> None:
    """RMSE = sqrt((4 + 4 + 4 + 100) / 4) = sqrt(28) ~ 5.2915026."""
    assert rmse(Y_TRUE, Y_PRED) == pytest.approx(np.sqrt(28.0))


def test_mape_hand_computed() -> None:
    """MAPE = 100 * ((2/100 + 2/110 + 2/105 + 10/120) / 4) ~ 1.9893."""
    expected = 100.0 * np.mean([2.0 / 100.0, 2.0 / 110.0, 2.0 / 105.0, 10.0 / 120.0])
    assert mape(Y_TRUE, Y_PRED) == pytest.approx(expected)


def test_mape_perfect_prediction_is_zero() -> None:
    """Una prediccion perfecta da MAPE 0."""
    assert mape(Y_TRUE, Y_TRUE) == pytest.approx(0.0)


def test_mae_and_rmse_relationship() -> None:
    """RMSE >= MAE siempre (propiedad matematica basica)."""
    assert rmse(Y_TRUE, Y_PRED) >= mae(Y_TRUE, Y_PRED)


def test_direction_accuracy_hand_computed() -> None:
    """Direcciones reales: UP, UP, DOWN, UP. Predichas: UP, UP, DOWN, UP -> 4/4."""
    # real  vs prev: [1, 10, -5, 15]  -> UP, UP, DOWN, UP
    # pred  vs prev: [3, 8, -7, 25]   -> UP, UP, DOWN, UP
    assert direction_accuracy(Y_TRUE, Y_PRED, LAST_CLOSE) == pytest.approx(1.0)


def test_direction_accuracy_counts_only_correct_ones() -> None:
    """Cuatro de cinco acierto -> 0.8."""
    true = np.array([100.0, 110.0, 105.0, 120.0, 90.0])
    pred = np.array([102.0, 108.0, 103.0, 130.0, 80.0])  # ultimo: real UP, pred DOWN
    prev = np.array([99.0, 100.0, 110.0, 105.0, 85.0])
    assert direction_accuracy(true, pred, prev) == pytest.approx(4.0 / 5.0)


def test_confusion_matrix_hand_computed() -> None:
    """TP=3, TN=1, FP=0, FN=0: la clase positiva es ``UP``.

    Direcciones reales: [UP, UP, DOWN, UP]; predichas: [UP, UP, DOWN, UP].
    Los tres dias ``UP`` correctos son verdaderos positivos; el dia ``DOWN``
    correcto es un verdadero negativo (clase negativa).
    """
    counts = confusion_matrix(Y_TRUE, Y_PRED, LAST_CLOSE)
    assert counts.true_positive == 3
    assert counts.true_negative == 1
    assert counts.false_positive == 0
    assert counts.false_negative == 0
    assert counts.total == 4
    assert f1_score(counts) == pytest.approx(1.0)
    # El acierto de direccion es 4/4: cuenta ambas clases.
    assert direction_accuracy(Y_TRUE, Y_PRED, LAST_CLOSE) == pytest.approx(1.0)


def test_confusion_matrix_with_errors() -> None:
    """Un error por clase: TP=2, FP=1, FN=1, TN=1 y F1 = 4/6.

    Direcciones reales: [UP, UP, DOWN, UP]; predichas: [UP, UP, UP, DOWN].
    """
    true = np.array([100.0, 110.0, 105.0, 120.0])
    pred = np.array([102.0, 108.0, 115.0, 103.0])
    prev = np.array([99.0, 100.0, 110.0, 118.0])
    counts = confusion_matrix(true, pred, prev)
    assert counts.true_positive == 2
    assert counts.false_positive == 1
    assert counts.false_negative == 1
    # No hay ningun DOWN predicho como DOWN, luego no hay verdaderos negativos.
    assert counts.true_negative == 0
    assert f1_score(counts) == pytest.approx(4.0 / 6.0)
    # Dos de cuatro direcciones correctas.
    assert direction_accuracy(true, pred, prev) == pytest.approx(2.0 / 4.0)


def test_f1_with_no_positives_is_zero() -> None:
    """Sin verdaderos positivos, F1 = 0."""
    counts = confusion_matrix(
        np.array([100.0, 105.0]), np.array([99.0, 104.0]), np.array([101.0, 106.0])
    )
    assert counts.true_positive == 0
    assert f1_score(counts) == 0.0


def test_compute_metrics_bundle() -> None:
    """``compute_metrics`` agrega las cuatro metricas obligatorias (R-07)."""
    metrics = compute_metrics(Y_TRUE, Y_PRED, LAST_CLOSE)
    assert metrics.mae == pytest.approx(4.0)
    assert metrics.rmse == pytest.approx(np.sqrt(28.0))
    assert metrics.mape > 0.0
    assert metrics.direction_accuracy == pytest.approx(1.0)
    assert metrics.n_samples == 4
    assert metrics.confusion is not None
    assert metrics.confusion["true_positive"] == 3
    assert metrics.confusion["true_negative"] == 1

    payload = metrics.to_dict()
    assert set(payload) >= {"mae", "rmse", "mape", "direction_accuracy", "n_samples"}


@pytest.mark.parametrize("metric", [mae, rmse, mape], ids=["mae", "rmse", "mape"])
def test_metrics_reject_empty_input(metric) -> None:
    """Las metricas de error requieren al menos una muestra."""
    with pytest.raises(ValueError, match="al menos una muestra"):
        metric(np.array([]), np.array([]))


def test_metrics_reject_shape_mismatch() -> None:
    """``y_true`` e ``y_pred`` deben tener la misma forma."""
    with pytest.raises(ValueError, match="misma forma"):
        mae(np.array([1.0, 2.0]), np.array([1.0]))


def test_direction_accuracy_rejects_shape_mismatch() -> None:
    """``last_close`` debe tener la misma forma que ``y_true``."""
    with pytest.raises(ValueError, match="last_close"):
        direction_accuracy(Y_TRUE, Y_PRED, np.array([100.0]))


def test_flat_movement_counts_as_non_positive() -> None:
    """Un movimiento plano (real == previo) no cuenta como ``UP``."""
    true = np.array([100.0, 100.0])
    pred = np.array([101.0, 99.0])
    prev = np.array([100.0, 100.0])
    # real_dir = [0, 0]; pred_dir = [1, -1] -> ambos fallan como clase no positiva
    counts = confusion_matrix(true, pred, prev)
    assert counts.true_positive == 0
    assert counts.false_positive == 1
    assert counts.false_negative == 0
    assert counts.true_negative == 1


def test_metrics_on_realistic_random_series(synthetic_ohlcv) -> None:
    """Sanity check sobre una serie sintetica: RMSE >= MAE y metricas finitas."""
    close = synthetic_ohlcv["close"].to_numpy(dtype=np.float64)
    prev = close[:-1]
    truth = close[1:]
    prediction = truth * 1.01

    metrics = compute_metrics(truth, prediction, prev)
    assert np.isfinite(metrics.mae) and metrics.mae > 0.0
    assert metrics.rmse >= metrics.mae
    assert 0.0 <= metrics.direction_accuracy <= 1.0
    assert 0.0 <= metrics.f1 <= 1.0
    assert metrics.mape > 0.0
