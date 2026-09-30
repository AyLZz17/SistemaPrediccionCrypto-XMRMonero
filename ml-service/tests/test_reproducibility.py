"""R-08: misma semilla y mismos datos implican predicciones identicas.

Cubre los baselines deterministas, la repeticion del pipeline completo y la
estructura de reporte media +/- desviacion con >= 5 semillas.
"""

from __future__ import annotations

from pathlib import Path

import numpy as np
import pytest

from app.ml.evaluation.compare import aggregate_over_seeds
from app.ml.models.baselines import LinearRegressionBaseline, MovingAverageBaseline
from app.ml.models.recurrent import set_all_seeds
from app.ml.pipelines.train import DEFAULT_SEEDS, train_all

SEEDS = DEFAULT_SEEDS


def _dataset(path: Path, synthetic_ohlcv, n_days: int = 400) -> Path:
    """Escribe un snapshot sintetico truncado (rapido pero suficiente)."""
    frame = synthetic_ohlcv.iloc[:n_days].copy()
    frame.index.name = "date"
    frame.to_csv(path)
    return path


def test_five_seeds_are_available() -> None:
    """R-08 exige al menos 5 semillas para modelos estocasticos."""
    assert len(SEEDS) >= 5
    assert len(set(SEEDS)) == len(SEEDS), "las semillas deben ser distintas"


def test_set_all_seeds_is_idempotent() -> None:
    """``set_all_seeds`` produce el mismo estado de NumPy con la misma semilla."""
    set_all_seeds(42)
    first = np.random.rand(5)
    set_all_seeds(42)
    second = np.random.rand(5)
    assert np.array_equal(first, second)


def test_set_all_seeds_different_seed_changes_draw() -> None:
    """Semillas distintas producen sorteos distintos."""
    set_all_seeds(1)
    first = np.random.rand(5)
    set_all_seeds(2)
    second = np.random.rand(5)
    assert not np.array_equal(first, second)


def test_moving_average_is_deterministic(tmp_path: Path) -> None:
    """La media movil da el mismo resultado con la misma entrada."""
    data = np.linspace(0.1, 0.9, 100).reshape(10, 10, 1)
    y = np.linspace(0.2, 0.8, 10)
    a = MovingAverageBaseline(target_column_index=0).fit(data, y)
    b = MovingAverageBaseline(target_column_index=0).fit(data, y)
    assert np.array_equal(a.predict(data), b.predict(data))


def test_linear_regression_is_deterministic() -> None:
    """La regresion lineal es exacta y repetible."""
    rng = np.random.default_rng(17)
    data = rng.uniform(0.0, 1.0, size=(50, 8, 2))
    y = rng.uniform(0.0, 1.0, size=50)
    a = LinearRegressionBaseline().fit(data, y)
    b = LinearRegressionBaseline().fit(data, y)
    assert np.array_equal(a.predict(data), b.predict(data))


def test_regression_reproduces_a_known_linear_relation() -> None:
    """Con una relacion lineal exacta, el ajuste la recupera (control de sanity)."""
    rng = np.random.default_rng(4)
    x = rng.uniform(-1.0, 1.0, size=(200, 1))
    y = 3.0 * x.ravel() + 1.0
    model = LinearRegressionBaseline()
    model.fit(x.reshape(-1, 1, 1), y)
    predictions = model.predict(x.reshape(-1, 1, 1))
    assert np.allclose(predictions, y, atol=1e-8)


def test_set_seed_updates_model_config() -> None:
    """``set_seed`` cambia la semilla registrada en la config (R-08)."""
    model = MovingAverageBaseline(target_column_index=0)
    model.set_seed(123)
    assert model.config.seed == 123
    assert model.provenance()["seed"] == 123


def test_pipeline_same_data_same_result(tmp_path: Path, synthetic_ohlcv) -> None:
    """Dos corridas del pipeline sobre los mismos datos dan las mismas metricas."""
    snapshot = _dataset(tmp_path / "xmr.csv", synthetic_ohlcv)

    first = train_all(
        snapshot,
        output_dir=tmp_path / "run_a",
        models=("moving_average", "linear_regression"),
        window=30,
    )
    second = train_all(
        snapshot,
        output_dir=tmp_path / "run_b",
        models=("moving_average", "linear_regression"),
        window=30,
    )

    assert first.dataset_version == second.dataset_version
    assert first.champion_key == second.champion_key

    by_key_a = {run.model_key: run.metrics_test for run in first.models}
    by_key_b = {run.model_key: run.metrics_test for run in second.models}
    assert set(by_key_a) == set(by_key_b)
    for key, metrics in by_key_a.items():
        for name, value in metrics.items():
            assert value == pytest.approx(by_key_b[key][name]), f"{key}.{name} no es reproducible"


def test_pipeline_artifacts_have_stable_checksums(tmp_path: Path, synthetic_ohlcv) -> None:
    """El bundle serializado tiene el mismo checksum en dos corridas (R-08/R-28)."""
    from app.ml.data.ingest import file_sha256

    snapshot = _dataset(tmp_path / "xmr.csv", synthetic_ohlcv)
    first = train_all(
        snapshot,
        output_dir=tmp_path / "run_a",
        models=("moving_average",),
        window=30,
    )
    second = train_all(
        snapshot,
        output_dir=tmp_path / "run_b",
        models=("moving_average",),
        window=30,
    )

    path_a = first.artifacts["moving_average"]
    path_b = second.artifacts["moving_average"]
    assert file_sha256(path_a) == file_sha256(path_b)


def test_pipeline_records_dataset_version(tmp_path: Path, synthetic_ohlcv) -> None:
    """El ``dataset_version`` deriva del checksum del snapshot (R-08)."""
    from app.ml.data.ingest import file_sha256

    snapshot = _dataset(tmp_path / "xmr.csv", synthetic_ohlcv)
    result = train_all(snapshot, output_dir=tmp_path / "run", models=("moving_average",), window=30)
    assert result.dataset_version == f"sha256:{file_sha256(snapshot)[:16]}"


def test_deterministic_models_run_single_seed(tmp_path: Path, synthetic_ohlcv) -> None:
    """Los baselines se entrenan una sola vez; las semillas reportan n_seeds=1."""
    snapshot = _dataset(tmp_path / "xmr.csv", synthetic_ohlcv)
    result = train_all(
        snapshot,
        output_dir=tmp_path / "run",
        models=("moving_average", "linear_regression"),
        window=30,
    )
    for run in result.models:
        assert run.n_seeds == 1
        assert run.stddev_test["mae"] == pytest.approx(0.0)


def test_aggregate_over_seeds_reports_mean_and_std() -> None:
    """La agregacion por semillas produce media y desviacion (R-08)."""
    runs = [{"mae": 1.0, "rmse": 2.0}, {"mae": 2.0, "rmse": 3.0}, {"mae": 3.0, "rmse": 4.0}]
    mean, stddev = aggregate_over_seeds(runs)

    assert mean["mae"] == pytest.approx(2.0)
    assert mean["rmse"] == pytest.approx(3.0)
    # Desviacion poblacional de [1, 2, 3].
    assert stddev["mae"] == pytest.approx(np.std([1.0, 2.0, 3.0]))


def test_aggregate_over_seeds_rejects_mismatched_keys() -> None:
    """Todas las semillas deben reportar las mismas metricas."""
    with pytest.raises(ValueError, match="mismas metricas"):
        aggregate_over_seeds([{"mae": 1.0}, {"rmse": 2.0}])


def test_aggregate_over_seeds_rejects_empty() -> None:
    """Una lista vacia no se agrega."""
    with pytest.raises(ValueError, match="al menos una corrida"):
        aggregate_over_seeds([])


def test_champion_selected_by_validation_not_test(tmp_path: Path, synthetic_ohlcv) -> None:
    """El campeon se elige por MAE de validacion, nunca de test (R-24)."""
    snapshot = _dataset(tmp_path / "xmr.csv", synthetic_ohlcv)
    result = train_all(
        snapshot,
        output_dir=tmp_path / "run",
        models=("moving_average", "linear_regression", "arima"),
        window=30,
    )

    best_val = min(run.metrics_val["mae"] for run in result.models)
    champion = next(r for r in result.models if r.model_key == result.champion_key)
    assert champion.metrics_val["mae"] == pytest.approx(best_val)

    # El campeon no es necesariamente el mejor en test: eso es justamente el punto.
    best_test = min(run.metrics_test["mae"] for run in result.models)
    best_test_model = next(
        r for r in result.models if r.metrics_test["mae"] == pytest.approx(best_test)
    )
    assert result.champion_key in {r.model_key for r in result.models}
    assert best_test_model.model_key in {r.model_key for r in result.models}
