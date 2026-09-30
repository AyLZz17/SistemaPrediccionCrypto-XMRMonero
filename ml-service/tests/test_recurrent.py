"""Pruebas de LSTM/GRU: se **omiten** si TensorFlow no esta instalado.

Para ejecutarlas hay que instalar el extra opcional::

    pip install -e ".[tf]"

La CI sin TensorFlow debe pasar igualmente: aqui se usa ``skipif`` en lugar de
fallar, porque R-13 exige que el paquete ML sea testeable sin la capa web y el
spec deja TF como dependencia opcional.
"""

from __future__ import annotations

import numpy as np
import pytest

from app.ml.models.base import ModelConfig
from app.ml.models.recurrent import (
    GRURegressor,
    LSTMRegressor,
    RecurrentConfig,
    TensorFlowMissingError,
    set_all_seeds,
    tensorflow_available,
)

pytestmark = pytest.mark.skipif(
    not tensorflow_available(), reason="requiere TensorFlow (extra opcional 'tf')"
)

SEEDS = (11, 22, 33, 44, 55)


def _dataset(n: int = 256, window: int = 30, n_features: int = 4):
    """Ventanas y objetivos sinteticos deterministas."""
    rng = np.random.default_rng(0)
    x = rng.normal(size=(n, window, n_features)).astype("float32")
    y = x[:, :, 0].mean(axis=1).astype("float32")
    return x, y


def _model(cls, seed: int, window: int = 30, **cfg_kwargs):
    """Instancia el modelo recurrente con semilla explicita."""
    cfg = RecurrentConfig(max_epochs=2, hidden_units=8, **cfg_kwargs)
    instance = cls(recurrent_config=cfg)
    instance.config = ModelConfig(
        model_key=instance.config.model_key,
        family=instance.config.family,
        window=window,
        seed=seed,
    )
    return instance


@pytest.mark.parametrize("cls", [LSTMRegressor, GRURegressor], ids=["lstm", "gru"])
def test_output_shape_is_scalar_per_sample(cls) -> None:
    """``predict`` devuelve **una** prediccion por muestra, no una por timestep.

    Regresion: con ``n_layers > 1`` la celda emitia secuencias y la salida
    quedaba con forma ``(n, window, 1)``, lo que rompia ``predict_direction``.
    """
    x, y = _dataset(n=64)
    model = _model(cls, seed=1)
    model.fit(x, y)

    predictions = model.predict(x)
    assert predictions.shape == (64,)


@pytest.mark.parametrize("cls", [LSTMRegressor, GRURegressor], ids=["lstm", "gru"])
def test_predict_direction_shape_matches(cls) -> None:
    """La direccion tiene un elemento por muestra (fase de prediccion estable)."""
    x, y = _dataset(n=64)
    model = _model(cls, seed=1)
    model.fit(x, y)

    directions = model.predict_direction(x, x[:, -1, 0])
    assert directions.shape == (64,)
    assert set(directions.tolist()) <= {"UP", "DOWN", "FLAT"}


@pytest.mark.parametrize("n_layers", [1, 2, 3])
def test_stack_depth_does_not_change_output_shape(n_layers: int) -> None:
    """Con 1, 2 o 3 capas la salida sigue siendo un escalar por muestra."""
    x, y = _dataset(n=48)
    model = _model(LSTMRegressor, seed=1, n_layers=n_layers, dropout=0.0)
    model.fit(x, y)
    assert model.predict(x).shape == (48,)


def test_validation_data_is_used_for_early_stopping() -> None:
    """Con validacion supplied no se aplica ``validation_split`` (R-04)."""
    x, y = _dataset(n=128)
    split = 96
    model = _model(LSTMRegressor, seed=1)
    model.fit(x[:split], y[:split], x[split:], y[split:])

    history = model.training_history()
    assert "val_loss" in history
    assert len(history["loss"]) == len(history["val_loss"])


def test_same_seed_same_predictions() -> None:
    """Misma semilla produce predicciones identicas (R-08)."""
    x, y = _dataset(n=96)

    set_all_seeds(7)
    a = _model(LSTMRegressor, seed=7)
    a.fit(x, y)
    first = a.predict(x)

    set_all_seeds(7)
    b = _model(LSTMRegressor, seed=7)
    b.fit(x, y)
    second = b.predict(x)

    assert np.allclose(first, second)


def test_keras_round_trip(tmp_path) -> None:
    """Guardar y cargar el modelo Keras conserva las predicciones."""
    x, y = _dataset(n=96)
    model = _model(LSTMRegressor, seed=3)
    model.fit(x, y)
    expected = model.predict(x)

    path = tmp_path / "model.keras"
    model.save(str(path))
    restored = _model(LSTMRegressor, seed=3).load(str(path))

    assert np.allclose(restored.predict(x), expected)


def test_five_seeds_produce_distinct_runs() -> None:
    """Cinco semillas dan corridas distintas: se reporta media +/- desviacion."""
    x, y = _dataset(n=96)
    means = []
    for seed in SEEDS:
        set_all_seeds(seed)
        model = _model(LSTMRegressor, seed=seed)
        model.fit(x, y)
        means.append(float(model.predict(x).mean()))

    assert len(means) == 5
    assert not all(np.isclose(means[0], value) for value in means[1:])


def test_predict_before_fit_raises() -> None:
    """Predecir sin entrenar es un error explicito."""
    x, _ = _dataset(n=16)
    with pytest.raises(RuntimeError, match="no esta entrenado"):
        _model(LSTMRegressor, seed=1).predict(x)


def test_save_before_fit_raises(tmp_path) -> None:
    """Guardar sin entrenar es un error explicito."""
    with pytest.raises(RuntimeError, match="No hay modelo"):
        _model(LSTMRegressor, seed=1).save(str(tmp_path / "m.keras"))


def test_recurrent_pipeline_uses_five_seeds(tmp_path, synthetic_ohlcv) -> None:
    """El pipeline reporta ``n_seeds=5`` y desviacion para el LSTM (R-08)."""
    from app.ml.pipelines.train import train_all

    snapshot = tmp_path / "xmr.csv"
    frame = synthetic_ohlcv.iloc[:300].copy()
    frame.index.name = "date"
    frame.to_csv(snapshot)

    result = train_all(
        snapshot,
        output_dir=tmp_path / "artifacts",
        models=("moving_average", "lstm_base"),
        window=30,
        seeds=SEEDS,
        recurrent_max_epochs=2,
    )

    lstm = next(r for r in result.models if r.model_key == "lstm_base")
    assert lstm.n_seeds == len(SEEDS)
    assert lstm.stddev_test["mae"] >= 0.0
    assert np.isfinite(lstm.metrics_test["mae"])


def test_missing_tensorflow_error_is_actionable() -> None:
    """El mensaje de error indica como instalar el extra."""
    with pytest.raises(TensorFlowMissingError) as excinfo:
        raise TensorFlowMissingError("Instala con 'pip install .[tf]'")
    assert "pip install" in str(excinfo.value)
