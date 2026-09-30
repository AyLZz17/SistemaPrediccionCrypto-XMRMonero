"""Baselines obligatorios (R-06): media movil y regresion lineal.

Ambos son deterministas salvo por la semilla (que no afecta al resultado),
lo que hace trivial verificar la reproducibilidad (R-08).
"""

from __future__ import annotations

import numpy as np
from sklearn.linear_model import LinearRegression

from app.ml.models.base import BaseModel, ModelConfig, direction_from_delta

__all__ = ["LinearRegressionBaseline", "MovingAverageBaseline"]


class MovingAverageBaseline(BaseModel):
    """Media movil: ``y_hat_{t+1} = media(ultimos k cierres)``.

    Es la persistencia suavizada; no necesita estado de entrenamiento mas alla
    del numero de periodos (R-06, S-04). El indice de la columna de precio
    dentro de la ventana se fija en ``target_column_index`` para que la media se
    calcule sobre el cierre y no sobre el resto de features.

    Args:
        config: Configuracion del modelo; ``params["window"]`` fija ``k``.
        target_column_index: Posicion de ``close`` dentro de cada ventana.
    """

    def __init__(self, config: ModelConfig | None = None, target_column_index: int = 0) -> None:
        self.config = config or ModelConfig(
            model_key="moving_average", family="MovingAverage", window=30
        )
        self.target_column_index = int(target_column_index)

    def fit(self, x_scaled: np.ndarray, y_scaled: np.ndarray) -> MovingAverageBaseline:
        """No requiere ajuste (baseless)."""
        return self

    def _k(self, n_steps: int) -> int:
        k = int(self.config.params.get("window", self.config.window))
        return max(1, min(k, n_steps))

    def predict(self, x_scaled: np.ndarray) -> np.ndarray:
        """Media del cierre en los ultimos ``k`` pasos de cada ventana."""
        array = np.asarray(x_scaled, dtype=np.float64)
        closes = array[:, :, self.target_column_index]
        k = self._k(closes.shape[1])
        return closes[:, -k:].mean(axis=1)

    def predict_direction(self, x_scaled: np.ndarray, last_close: np.ndarray) -> np.ndarray:
        """Direccion comparando la prediccion con el ultimo cierre real."""
        pred = self.predict(x_scaled)
        return direction_from_delta(pred - np.asarray(last_close, dtype=np.float64))


class LinearRegressionBaseline(BaseModel):
    """Regresion lineal sobre la ventana aplanada (R-06, S-04).

    Usa la misma ventana de entrada que el LSTM para que la comparacion sea
       parable, y `LinearRegression` con solucion por minimos cuadrados.
    """

    def __init__(self, config: ModelConfig | None = None) -> None:
        self.config = config or ModelConfig(
            model_key="linear_regression", family="LinearRegression", window=30
        )
        self._model = LinearRegression()

    def fit(self, x_scaled: np.ndarray, y_scaled: np.ndarray) -> LinearRegressionBaseline:
        """Ajusta el modelo sobre las ventanas aplanadas."""
        n_samples, window, n_features = np.asarray(x_scaled).shape
        flat = np.asarray(x_scaled, dtype=np.float64).reshape(n_samples, window * n_features)
        self._model.fit(flat, np.asarray(y_scaled, dtype=np.float64).ravel())
        return self

    def predict(self, x_scaled: np.ndarray) -> np.ndarray:
        """Predice el cierre siguiente en escala escalada."""
        n_samples, window, n_features = np.asarray(x_scaled).shape
        flat = np.asarray(x_scaled, dtype=np.float64).reshape(n_samples, window * n_features)
        return np.asarray(self._model.predict(flat), dtype=np.float64).ravel()

    def predict_direction(self, x_scaled: np.ndarray, last_close: np.ndarray) -> np.ndarray:
        """Direccion comparando la prediccion con el ultimo cierre real."""
        pred = self.predict(x_scaled)
        return direction_from_delta(pred - np.asarray(last_close, dtype=np.float64))
