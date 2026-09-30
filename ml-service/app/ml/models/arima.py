"""Baseline ARIMA con **pronostico rodante de un paso** (R-06, R-24).

R-24 exige que ARIMA sea comparable con el LSTM: para cada paso del test se
emite un pronostico de ``h=1`` usando **solo** informacion hasta ``t``, y se
extiende la serie con la observacion real que se acaba de conocer. Nunca se
emite un unico pronostico multi-paso sobre todo el test.

El orden ``(p, d, q)`` debe fijarse en validacion; este modulo no lo busca
automaticamente para no introducir una fuga (R-01).
"""

from __future__ import annotations

from dataclasses import dataclass, field
from typing import Any

import numpy as np

from app.ml.models.base import BaseModel, ModelConfig, direction_from_delta

__all__ = ["ArimaBaseline", "ArimaOrder", "fit_arima_order"]


@dataclass(frozen=True)
class ArimaOrder:
    """Orden ``(p, d, q)`` del modelo ARIMA."""

    p: int = 5
    d: int = 1
    q: int = 0

    def as_tuple(self) -> tuple[int, int, int]:
        """Devuelve la tupla que consume ``statsmodels``."""
        return (int(self.p), int(self.d), int(self.q))

    def to_dict(self) -> dict[str, int]:
        """Representacion serializable."""
        return {"p": self.p, "d": self.d, "q": self.q}


@dataclass
class _ArimaState:
    """Estado interno de ARIMA tras el ajuste."""

    results: Any
    order: ArimaOrder
    history: list[float] = field(default_factory=list)
    refits: int = 0


class ArimaBaseline(BaseModel):
    """ARIMA con walk-forward de un paso sobre la serie de cierres.

    A diferencia del resto de modelos, ARIMA opera sobre la **serie** de
    cierres, no sobre ventanas de features. ``fit`` recibe igualmente la
    matriz de ventanas (por interfaz) y extrae la serie del objetivo; asi el
    pipeline unico puede comparar todos los modelos sobre las mismas fechas.

    Args:
        config: Configuracion del modelo.
        order: Orden ``(p, d, q)`` elegido en validacion.
    """

    def __init__(self, config: ModelConfig | None = None, order: ArimaOrder | None = None) -> None:
        self.config = config or ModelConfig(model_key="arima", family="ARIMA", window=30)
        self.order = order or ArimaOrder(
            p=int(self.config.params.get("p", 5)),
            d=int(self.config.params.get("d", 1)),
            q=int(self.config.params.get("q", 0)),
        )
        self._state: _ArimaState | None = None

    def fit(self, x_scaled: np.ndarray, y_scaled: np.ndarray) -> ArimaBaseline:
        """Ajusta ARIMA sobre la serie de objetivos de train."""
        series = np.asarray(y_scaled, dtype=np.float64).ravel()
        results = self._fit_series(series, self.order)
        self._state = _ArimaState(results=results, order=self.order, history=list(series))
        return self

    @staticmethod
    def _fit_series(series: np.ndarray, order: ArimaOrder) -> Any:
        """Import perezoso de statsmodels y ajuste con el orden indicado."""
        from statsmodels.tsa.arima.model import ARIMA

        model = ARIMA(np.asarray(series, dtype=np.float64), order=order.as_tuple())
        return model.fit()

    def forecast_one_step(self, series_scaled: np.ndarray, n_steps: int) -> np.ndarray:
        """Walk-forward: un pronostico por paso, incorporando la real (R-24).

        Args:
            series_scaled: Serie detrain + test en escala escalada, en orden.
            n_steps: Numero de pasos del periodo de test a pronosticar.

        Returns:
            Array de ``n_steps`` pronosticos, cada uno emitido **antes** de
            observar su propio objetivo.
        """
        if self._state is None or self._state.results is None:
            raise RuntimeError("ArimaBaseline.fit debe llamarse antes de forecast_one_step")

        series = np.asarray(series_scaled, dtype=np.float64).ravel()
        train_len = len(self._state.history)
        predictions: list[float] = []
        results = self._state.results

        for step in range(n_steps):
            index = train_len + step
            if index >= len(series):
                raise ValueError(
                    "La serie no contiene el valor real del paso "
                    f"{index}; el walk-forward necesita las observaciones reales"
                )
            # Pronostico emitido ANTES de observar el objetivo del paso.
            forecast = np.asarray(results.forecast(steps=1), dtype=np.float64).ravel()
            predictions.append(float(forecast[0]))
            # Extendemos el estado con la observacion real, no con la prediccion.
            results = results.append([series[index]], refit=False)

        return np.asarray(predictions, dtype=np.float64)

    def predict(self, x_scaled: np.ndarray) -> np.ndarray:
        """No usado directamente: ARIMA exige la serie completa (ver pipeline)."""
        raise NotImplementedError(
            "ARIMA requiere la serie completa; usa forecast_one_step desde el pipeline"
        )

    def predict_direction(self, x_scaled: np.ndarray, last_close: np.ndarray) -> np.ndarray:
        """Direccion a partir del walk-forward de un paso."""
        preds = self.forecast_one_step(
            np.asarray(x_scaled, dtype=np.float64).ravel(), len(np.atleast_1d(last_close))
        )
        return direction_from_delta(preds - np.asarray(last_close, dtype=np.float64).ravel())

    def provenance(self) -> dict[str, Any]:
        """Procedencia incluyendo el orden ARIMA."""
        data = super().provenance()
        data["order"] = self.order.to_dict()
        return data


def fit_arima_order(series_scaled: np.ndarray, max_p: int = 3, max_q: int = 2) -> ArimaOrder:
    """Elige ``(p, d, q)`` minimizando el AIC **sobre train** (nunca test).

    Args:
        series_scaled: Serie de entrenamiento ya escalada.
        max_p: Orden autorregresivo maximo a probar.
        max_q: Orden de media movil maximo a probar.

    Returns:
        ArimaOrder con el mejor AIC dentro de la rejilla.

    Raises:
        ValueError: si la serie es demasiado corta o ninguna combinacion ajusta.
    """
    from statsmodels.tsa.arima.model import ARIMA

    series = np.asarray(series_scaled, dtype=np.float64).ravel()
    if len(series) < 12:
        raise ValueError("Serie demasiado corta para seleccionar el orden ARIMA")

    best: tuple[float, ArimaOrder] | None = None
    errors: list[str] = []
    for d in (0, 1):
        for p in range(max_p + 1):
            for q in range(max_q + 1):
                if p == 0 and q == 0:
                    continue
                order = ArimaOrder(p=p, d=d, q=q)
                try:
                    fitted = ARIMA(series, order=order.as_tuple()).fit()
                except (ValueError, np.linalg.LinAlgError) as exc:
                    errors.append(f"{order.as_tuple()}: {exc}")
                    continue
                aic = float(fitted.aic)
                if best is None or aic < best[0]:
                    best = (aic, order)

    if best is None:
        raise ValueError(f"Ningun orden ARIMA ajusto bien. Intentos: {errors[:5]}")
    return best[1]
