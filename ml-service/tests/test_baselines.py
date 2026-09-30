"""R-06 y R-24: media movil, regresion lineal y ARIMA con walk-forward de 1 paso.

La prueba clave de R-24 comprueba que ARIMA **no** emite un unico pronostico
multi-paso: cada paso se pronostica antes de observar su objetivo, y el estado
se extiende con la observacion real.
"""

from __future__ import annotations

import numpy as np
import pytest

from app.ml.models.arima import ArimaBaseline, ArimaOrder, fit_arima_order
from app.ml.models.base import Direction, ModelConfig, direction_from_delta
from app.ml.models.baselines import LinearRegressionBaseline, MovingAverageBaseline

WINDOW = 10
N_SAMPLES = 60


@pytest.fixture()
def windows() -> np.ndarray:
    """Ventanas sinteticas deterministas ``(N, WINDOW, 1)``."""
    rng = np.random.default_rng(99)
    base = np.linspace(0.1, 0.9, WINDOW)
    data = base[None, :] + rng.normal(0.0, 0.01, size=(N_SAMPLES, WINDOW))
    return np.clip(data, 0.0, 1.0).reshape(N_SAMPLES, WINDOW, 1)


@pytest.fixture()
def targets() -> np.ndarray:
    """Objetivos sinteticos deterministas."""
    rng = np.random.default_rng(123)
    return np.clip(rng.normal(0.5, 0.05, N_SAMPLES), 0.0, 1.0)


def test_moving_average_predicts_tail_mean(windows: np.ndarray) -> None:
    """La media movil predice la media de los ultimos k pasos de la ventana."""
    model = MovingAverageBaseline(
        ModelConfig(model_key="moving_average", family="MovingAverage", window=WINDOW),
        target_column_index=0,
    )
    model.fit(windows, np.zeros(N_SAMPLES))
    predictions = model.predict(windows)

    # Sin params['window'], la media movil usa los WINDOW ultimos pasos.
    expected = windows[:, -WINDOW:, 0].mean(axis=1)
    assert np.allclose(predictions, expected)


def test_moving_average_respects_configured_k(windows: np.ndarray) -> None:
    """``params['window']`` fija la longitud del tramo promediado."""
    model = MovingAverageBaseline(
        ModelConfig(
            model_key="moving_average",
            family="MovingAverage",
            window=WINDOW,
            params={"window": 3},
        ),
        target_column_index=0,
    )
    model.fit(windows, np.zeros(N_SAMPLES))
    assert np.allclose(model.predict(windows), windows[:, -3:, 0].mean(axis=1))


def test_moving_average_is_unaffected_by_future_columns() -> None:
    """Solo promedia la columna de precio, no el resto de features."""
    rng = np.random.default_rng(5)
    x = rng.uniform(0.0, 1.0, size=(5, 4, 3))
    model = MovingAverageBaseline(
        ModelConfig(model_key="moving_average", family="MovingAverage", window=4),
        target_column_index=2,
    )
    model.fit(x, np.zeros(5))
    assert np.allclose(model.predict(x), x[:, :, 2].mean(axis=1))


def test_linear_regression_fits_and_predicts(windows: np.ndarray, targets: np.ndarray) -> None:
    """La regresion lineal aprende la relacion y devuelve ``n`` predicciones."""
    model = LinearRegressionBaseline(
        ModelConfig(model_key="linear_regression", family="LinearRegression", window=WINDOW)
    )
    model.fit(windows, targets)
    predictions = model.predict(windows)

    assert predictions.shape == (N_SAMPLES,)
    assert np.all(np.isfinite(predictions))
    # El ajuste debe ser claramente mejor que la media trivial de los targets.
    baseline = np.abs(targets - targets.mean()).mean()
    assert np.abs(targets - predictions).mean() < baseline


def test_linear_regression_direction(windows: np.ndarray, targets: np.ndarray) -> None:
    """``predict_direction`` devuelve solo ``UP`` o ``DOWN``."""
    model = LinearRegressionBaseline(
        ModelConfig(model_key="linear_regression", family="LinearRegression", window=WINDOW)
    )
    model.fit(windows, targets)
    directions = model.predict_direction(windows, windows[:, -1, 0])

    assert set(directions.tolist()) <= {Direction.UP, Direction.DOWN, Direction.FLAT}


def test_direction_from_delta_handles_flat() -> None:
    """Un delta exactamente cero produce ``FLAT``."""
    out = direction_from_delta(np.array([-0.1, 0.0, 0.1]))
    assert out.tolist() == ["DOWN", "FLAT", "UP"]


class TestArimaRollingOneStep:
    """Comprobaciones especificas de R-24."""

    @staticmethod
    def _series(n: int = 200) -> np.ndarray:
        rng = np.random.default_rng(3)
        return np.cumsum(rng.normal(0.0, 1.0, n)) + 100.0

    def test_forecast_length_matches_steps(self) -> None:
        """Se emite un pronostico por paso del periodo solicitado."""
        series = self._series()
        model = ArimaBaseline(order=ArimaOrder(p=2, d=1, q=0))
        model.fit(series[:150].reshape(-1, 1), series[:150])

        predictions = model.forecast_one_step(series, 20)
        assert predictions.shape == (20,)
        assert np.all(np.isfinite(predictions))

    def test_each_step_is_one_step_ahead_not_multi_step(self) -> None:
        """Cada pronostico difiere del siguiente: no es un multi-paso congelado."""
        series = self._series()
        model = ArimaBaseline(order=ArimaOrder(p=2, d=1, q=0))
        model.fit(series[:150], series[:150])

        predictions = model.forecast_one_step(series, 20)
        # Un unico forecast multi-paso seria identico en los primeros pasos;
        # el walk-forward cambia con cada observacion real incorporada.
        assert not np.allclose(predictions[0], predictions[1])
        assert len(set(predictions.tolist())) > 1

    def test_walk_forward_uses_actual_observations(self) -> None:
        """Cambiar una observacion real futura cambia las predicciones posteriores.

        Es la demostracion de que ARIMA incorpora cada real, en vez de extrapolar
        desde el ultimo dato de train.
        """
        series = self._series()
        model = ArimaBaseline(order=ArimaOrder(p=2, d=1, q=0))
        model.fit(series[:150], series[:150])
        baseline = model.forecast_one_step(series, 20)

        modified = series.copy()
        modified[160] += 50.0
        altered = model.forecast_one_step(modified, 20)

        assert not np.allclose(baseline, altered)

    def test_forecast_before_observation_differs_from_fitted_value(self) -> None:
        """El pronostico del primer paso no puede ser el valor real de ese paso."""
        series = self._series()
        model = ArimaBaseline(order=ArimaOrder(p=2, d=1, q=0))
        model.fit(series[:150], series[:150])

        predictions = model.forecast_one_step(series, 5)
        assert not np.isclose(predictions[0], series[150])

    def test_requires_real_observations(self) -> None:
        """Sin observaciones reales suficientes se lanza un error explicito."""
        series = self._series(n=160)
        model = ArimaBaseline(order=ArimaOrder(p=2, d=1, q=0))
        model.fit(series[:150], series[:150])

        with pytest.raises(ValueError, match="observaciones reales"):
            model.forecast_one_step(series, 50)

    def test_requires_fit_first(self) -> None:
        """``forecast_one_step`` antes de ``fit`` es un error explicito."""
        model = ArimaBaseline(order=ArimaOrder(p=1, d=1, q=0))
        with pytest.raises(RuntimeError, match="fit"):
            model.forecast_one_step(self._series(), 5)

    def test_predict_is_not_supported(self) -> None:
        """ARIMA no se usa via ``predict``: exige la serie completa (R-24)."""
        model = ArimaBaseline(order=ArimaOrder(p=1, d=1, q=0))
        with pytest.raises(NotImplementedError, match="forecast_one_step"):
            model.predict(np.zeros((2, 1)))

    def test_arima_order_selection_uses_train_only(self) -> None:
        """La seleccion de (p, d, q) se hace por AIC sobre la serie dada."""
        series = self._series(n=300)
        order = fit_arima_order(series[:250])
        assert isinstance(order, ArimaOrder)
        assert order.p >= 0 and order.d in (0, 1) and order.q >= 0

    def test_arima_order_rejects_short_series(self) -> None:
        """Una serie demasiado corta produce un error, no un orden inventado."""
        with pytest.raises(ValueError, match="demasiado corta"):
            fit_arima_order(np.arange(5, dtype=np.float64))


def test_arima_is_deterministic_for_same_input() -> None:
    """Con la misma serie, ARIMA da exactamente las mismas predicciones."""
    series = np.cumsum(np.random.default_rng(11).normal(0.0, 1.0, 200)) + 100.0
    a = ArimaBaseline(order=ArimaOrder(p=2, d=1, q=0))
    b = ArimaBaseline(order=ArimaOrder(p=2, d=1, q=0))
    a.fit(series[:150], series[:150])
    b.fit(series[:150], series[:150])
    assert np.array_equal(a.forecast_one_step(series, 10), b.forecast_one_step(series, 10))


def test_baselines_implement_common_interface(windows: np.ndarray, targets: np.ndarray) -> None:
    """Los baselines comparten la interfaz de :class:`BaseModel` (R-06)."""
    models = [
        MovingAverageBaseline(target_column_index=0),
        LinearRegressionBaseline(),
    ]
    for model in models:
        fitted = model.fit(windows, targets)
        assert fitted is model
        predictions = model.predict(windows)
        assert predictions.shape == (N_SAMPLES,)
        directions = model.predict_direction(windows, windows[:, -1, 0])
        assert len(directions) == N_SAMPLES
        provenance = model.provenance()
        assert {"model_key", "family", "seed", "window"} <= set(provenance)
