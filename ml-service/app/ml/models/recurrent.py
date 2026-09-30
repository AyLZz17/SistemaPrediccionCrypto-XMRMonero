"""Redes recurrentes LSTM y GRU (D-03) con TensorFlow **opcional**.

TensorFlow se importa de forma **perezosa** dentro de funciones, de modo que
este modulo se puede importar (y los tests pueden correr) sin tener TF
instalado. Si se intenta entrenar sin TF se lanza :class:`TensorFlowMissingError`
con un mensaje accionable.

Configuracion inicial por SKILLS.md S-05:
``(30, n_features) -> LSTM(100, return_sequences) -> Dropout(0.2) ->
LSTM(100) -> Dropout(0.2) -> Dense(1)``, Adam, lote 32, hasta 20 epocas con
``EarlyStopping`` sobre validacion.
"""

from __future__ import annotations

from dataclasses import dataclass
from typing import Any

import numpy as np

from app.ml.models.base import BaseModel, ModelConfig, direction_from_delta

__all__ = [
    "GRURegressor",
    "LSTMRegressor",
    "RecurrentConfig",
    "RecurrentNet",
    "TensorFlowMissingError",
    "set_all_seeds",
    "tensorflow_available",
]


class TensorFlowMissingError(RuntimeError):
    """Se lanza al usar LSTM/GRU sin TensorFlow instalado."""


def tensorflow_available() -> bool:
    """Indica si TensorFlow puede importarse en este entorno."""
    try:
        import tensorflow  # noqa: F401
    except ImportError:
        return False
    return True


def set_all_seeds(seed: int) -> None:
    """Fija ``random``, ``numpy`` y ``tensorflow`` (R-08)."""
    import random as _random

    _random.seed(seed)
    np.random.seed(seed)
    if tensorflow_available():
        import tensorflow as tf

        tf.random.set_seed(seed)
        tf.keras.utils.set_random_seed(seed)


@dataclass(frozen=True)
class RecurrentConfig:
    """Hiperparametros de la red recurrente."""

    hidden_units: int = 100
    n_layers: int = 2
    dropout: float = 0.2
    batch_size: int = 32
    max_epochs: int = 20
    learning_rate: float = 0.001
    early_stopping_patience: int = 3
    validation_split: float = 0.1

    def to_dict(self) -> dict[str, Any]:
        """Representacion serializable."""
        return {
            "hidden_units": self.hidden_units,
            "n_layers": self.n_layers,
            "dropout": self.dropout,
            "batch_size": self.batch_size,
            "max_epochs": self.max_epochs,
            "learning_rate": self.learning_rate,
            "early_stopping_patience": self.early_stopping_patience,
            "validation_split": self.validation_split,
        }


def _require_tensorflow() -> Any:
    try:
        import tensorflow as tf
    except ImportError as exc:  # pragma: no cover - depende del entorno
        raise TensorFlowMissingError(
            "TensorFlow no esta instalado. Instala el extra opcional con "
            "'pip install .[tf]' y reinicia el proceso para cargar el modelo."
        ) from exc
    return tf


class RecurrentNet(BaseModel):
    """Base comun de LSTM y GRU para la tarea de regresion del cierre t+1.

    Args:
        config: Configuracion logica del modelo (clave, familia, semilla).
        recurrent_config: Hiperparametros de la red.
    """

    #: Nombre de la capa de celda Keras usada por la subclase.
    cell_name: str = "LSTM"

    def __init__(
        self,
        config: ModelConfig | None = None,
        recurrent_config: RecurrentConfig | None = None,
    ) -> None:
        self.config = config or ModelConfig(model_key="lstm_base", family="LSTM", window=30)
        self.recurrent_config = recurrent_config or RecurrentConfig()
        self._model: Any = None
        self._history: Any = None

    def build(self, n_features: int) -> Any:
        """Construye la red Keras segun SKILLS.md S-05.

        Raises:
            TensorFlowMissingError: si TensorFlow no esta instalado.
        """
        tf = _require_tensorflow()
        cfg = self.recurrent_config
        keras = tf.keras

        cell_layer = getattr(keras.layers, self.cell_name)
        n_layers = max(1, int(cfg.n_layers))
        layers: list[Any] = [keras.layers.Input(shape=(self.config.window, n_features))]
        for index in range(n_layers):
            # Solo la ultima capa recurrente devuelve el estado final; las
            # anteriores devuelven la secuencia completa.
            is_last = index == n_layers - 1
            layers.append(cell_layer(cfg.hidden_units, return_sequences=not is_last))
            layers.append(keras.layers.Dropout(cfg.dropout))
        layers.append(keras.layers.Dense(1))

        model = keras.Sequential(layers, name=self.config.model_key)
        model.compile(
            optimizer=keras.optimizers.Adam(learning_rate=cfg.learning_rate),
            loss="mse",
        )
        return model

    def fit(
        self,
        x_scaled: np.ndarray,
        y_scaled: np.ndarray,
        x_val: np.ndarray | None = None,
        y_val: np.ndarray | None = None,
    ) -> RecurrentNet:
        """Entrena la red con early stopping sobre validacion.

        Args:
            x_scaled: Ventanas de train ``(n, W, f)`` ya escaladas.
            y_scaled: Objetivos de train escalados ``(n,)``.
            x_val: Ventanas de validacion (opcional; si falta se usa split interno).
            y_val: Objetivos de validacion.

        Raises:
            TensorFlowMissingError: si TensorFlow no esta instalado.
        """
        tf = _require_tensorflow()
        set_all_seeds(self.config.seed)
        cfg = self.recurrent_config

        x_train = np.asarray(x_scaled, dtype=np.float32)
        y_train = np.asarray(y_scaled, dtype=np.float32).reshape(-1, 1)

        self._model = self.build(x_train.shape[2])

        callbacks: list[Any] = []
        validation_data: tuple[np.ndarray, np.ndarray] | None = None
        if x_val is not None and y_val is not None and len(np.asarray(y_val)) > 0:
            x_val_arr = np.asarray(x_val, dtype=np.float32)
            y_val_arr = np.asarray(y_val, dtype=np.float32).reshape(-1, 1)
            validation_data = (x_val_arr, y_val_arr)
            callbacks.append(
                tf.keras.callbacks.EarlyStopping(
                    monitor="val_loss",
                    patience=cfg.early_stopping_patience,
                    restore_best_weights=True,
                )
            )

        kwargs: dict[str, Any] = {
            "epochs": cfg.max_epochs,
            "batch_size": min(cfg.batch_size, len(x_train)),
            "shuffle": False,  # orden cronologico preservado (R-01)
            "callbacks": callbacks,
            "verbose": 0,
        }
        if validation_data is not None:
            kwargs["validation_data"] = validation_data
        else:
            kwargs["validation_split"] = cfg.validation_split

        self._history = self._model.fit(x_train, y_train, **kwargs)
        return self

    def predict(self, x_scaled: np.ndarray) -> np.ndarray:
        """Predice el cierre siguiente en escala escalada."""
        if self._model is None:
            raise RuntimeError("El modelo recurrente no esta entrenado; llama a fit primero")
        x_arr = np.asarray(x_scaled, dtype=np.float32)
        preds = self._model.predict(x_arr, verbose=0)
        return np.asarray(preds, dtype=np.float64).ravel()

    def predict_direction(self, x_scaled: np.ndarray, last_close: np.ndarray) -> np.ndarray:
        """Direccion comparando la prediccion con el ultimo cierre real."""
        preds = self.predict(x_scaled)
        return direction_from_delta(preds - np.asarray(last_close, dtype=np.float64).ravel())

    def save(self, path: str) -> str:
        """Guarda el modelo en disco (formato Keras nativo)."""
        if self._model is None:
            raise RuntimeError("No hay modelo que guardar")
        self._model.save(path)
        return str(path)

    def load(self, path: str) -> RecurrentNet:
        """Carga un modelo previamente guardado."""
        tf = _require_tensorflow()
        self._model = tf.keras.models.load_model(path)
        return self

    def training_history(self) -> dict[str, list[float]]:
        """Curva de aprendizaje serializable."""
        if self._history is None:
            return {}
        return {key: [float(v) for v in values] for key, values in self._history.history.items()}


class LSTMRegressor(RecurrentNet):
    """Modelo recurrente principal (D-03: LSTM)."""

    def __init__(
        self,
        config: ModelConfig | None = None,
        recurrent_config: RecurrentConfig | None = None,
    ) -> None:
        """Configura el LSTM con los valores por defecto de SKILLS.md S-05."""
        super().__init__(
            config or ModelConfig(model_key="lstm_base", family="LSTM", window=30),
            recurrent_config,
        )

        self.cell_name = "LSTM"


class GRURegressor(RecurrentNet):
    """Alternativa recurrente (D-03: GRU)."""

    def __init__(
        self,
        config: ModelConfig | None = None,
        recurrent_config: RecurrentConfig | None = None,
    ) -> None:
        """Configura el GRU con los valores por defecto de SKILLS.md S-05."""
        super().__init__(
            config or ModelConfig(model_key="gru_base", family="GRU", window=30),
            recurrent_config,
        )

        self.cell_name = "GRU"
