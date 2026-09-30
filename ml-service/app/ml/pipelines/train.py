"""Pipeline de entrenamiento extremo a extremo (R-01, R-02, R-04, R-08, R-24).

Orden de operaciones, no negociable:

1. Ingesta del snapshot CSV con checksum.
2. Limpieza causal.
3. Construccion de features causales.
4. Particion cronologica 70/15/15 y ventanas **por fecha de objetivo** (R-23).
5. Ajuste del escalador **solo con train** (R-02).
6. Entrenamiento por modelo (con >= 5 semillas si es estocastico, R-08).
7. Seleccion del campeon por **validacion**, nunca por test (R-24).
8. Evaluacion sobre test, una sola vez (R-04), sobre fechas identicas (R-05).
9. Analisis de fallos (R-10) y empaquetado del artefacto con procedencia.
"""

from __future__ import annotations

import json
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any

import numpy as np
import pandas as pd

from app.ml.data.cleaning import CleaningReport, clean_frame
from app.ml.data.features import build_features, drop_warmup, feature_columns
from app.ml.data.ingest import IngestResult, read_snapshot
from app.ml.data.split import (
    SUBSETS,
    FittedScaler,
    WindowSet,
    chronological_split,
    fit_scaler,
    make_windows,
    split_windows,
)
from app.ml.evaluation.compare import (
    ComparisonReport,
    ModelPrediction,
    aggregate_over_seeds,
    compare_models,
)
from app.ml.evaluation.error_analysis import ErrorAnalysisReport, analyze_errors
from app.ml.evaluation.metrics import EvaluationMetrics
from app.ml.models.arima import ArimaBaseline, ArimaOrder
from app.ml.models.base import BaseModel, ModelConfig
from app.ml.models.baselines import LinearRegressionBaseline, MovingAverageBaseline
from app.ml.models.recurrent import (
    GRURegressor,
    LSTMRegressor,
    TensorFlowMissingError,
)
from app.ml.models.registry import MetricsSummary, ModelRegistry
from app.ml.pipelines.inference import ArtifactBundle, save_bundle, write_checksum

__all__ = [
    "DEFAULT_SEEDS",
    "ModelRunResult",
    "PreparedData",
    "TrainResult",
    "build_comparison",
    "build_model",
    "prepare_dataset",
    "train_all",
]

#: Semillas usadas para modelos estocasticos (R-08 exige >= 5).
DEFAULT_SEEDS: tuple[int, ...] = (11, 22, 33, 44, 55)

#: Modelos cuyo resultado varia entre semillas.
STOCHASTIC_MODELS: frozenset[str] = frozenset({"lstm_base", "gru_base"})


@dataclass
class PreparedData:
    """Resultado de la preparacion de datos compartida por todos los modelos."""

    featured: pd.DataFrame
    features: tuple[str, ...]
    split_parts: dict[str, WindowSet]
    scaler: FittedScaler
    ingest: IngestResult
    cleaning: CleaningReport
    target_column_index: int
    price_column: str

    @property
    def window(self) -> int:
        """Longitud de la ventana usada."""
        return int(self.split_parts["train"].window)

    @property
    def close_scaled(self) -> np.ndarray:
        """Serie completa de cierres en escala escalada."""
        closes = self.featured[self.price_column].to_numpy(dtype=np.float64)
        return np.asarray(self.scaler.scale_target(closes), dtype=np.float64)

    @property
    def first_target_position(self) -> int:
        """Posicion en el frame del primer objetivo de train."""
        return int(self.split_parts["train"].target_positions[0])


@dataclass
class ModelRunResult:
    """Resultado de entrenar y evaluar un modelo concreto."""

    model_key: str
    family: str
    version: str
    metrics_val: dict[str, float]
    metrics_test: dict[str, float]
    stddev_val: dict[str, float]
    stddev_test: dict[str, float]
    n_seeds: int
    model: BaseModel
    error_analysis: ErrorAnalysisReport | None = None
    config: dict[str, Any] = field(default_factory=dict)
    seeds: tuple[int, ...] = ()

    @property
    def mean_val_mae(self) -> float:
        """MAE medio en validacion; criterio de campeon (R-24)."""
        return float(self.metrics_val["mae"])


@dataclass
class TrainResult:
    """Salida completa de :func:`train_all`."""

    experiment_id: str
    dataset_version: str
    models: list[ModelRunResult]
    champion_key: str
    champion_version: str
    artifacts: dict[str, Path]
    output_dir: Path
    n_test_samples: int
    degraded: list[str] = field(default_factory=list)

    def to_dict(self) -> dict[str, Any]:
        """Resumen serializable del experimento."""
        return {
            "experiment_id": self.experiment_id,
            "dataset_version": self.dataset_version,
            "champion": {
                "model_key": self.champion_key,
                "version": self.champion_version,
                "selected_by": "validation_mae",
            },
            "n_test_samples": self.n_test_samples,
            "degraded": list(self.degraded),
            "models": [
                {
                    "model_key": m.model_key,
                    "family": m.family,
                    "version": m.version,
                    "n_seeds": m.n_seeds,
                    "seeds": list(m.seeds),
                    "metrics_val": m.metrics_val,
                    "metrics_test": m.metrics_test,
                    "stddev": m.stddev_test,
                }
                for m in self.models
            ],
        }


def json_safe(payload: Any) -> str:
    """Serializa a JSON de forma estable (checksum reproducible, R-08)."""
    return json.dumps(payload, sort_keys=True, default=str)


def prepare_dataset(
    snapshot_path: str | Path,
    window: int = 30,
    train_ratio: float = 0.70,
    val_ratio: float = 0.15,
    price_column: str = "close",
    outlier_n_std: float = 8.0,
) -> PreparedData:
    """Ejecuta ingesta, limpieza, features, split y escalado en el orden correcto.

    Raises:
        IngestError: si el snapshot no valida.
        ValueError: si los datos no permiten construir ventanas o train es vazio.
    """
    ingest = read_snapshot(snapshot_path)
    cleaned, cleaning = clean_frame(ingest.frame, outlier_n_std=outlier_n_std)

    featured = drop_warmup(build_features(cleaned, price_column))
    if featured.empty:
        raise ValueError("No quedan filas tras el calentamiento de indicadores")
    if price_column not in featured.columns:
        raise ValueError(f"El precio {price_column!r} no esta entre las columnas del frame")

    derived = feature_columns(featured)
    # 'close' debe formar parte del feature set: asi el escalador escala entrada
    # y objetivo con la misma transformacion (R-02).
    feature_tuple = (price_column, *derived)

    split = chronological_split(len(featured), train_ratio, val_ratio)
    windows = make_windows(
        featured,
        feature_names=feature_tuple,
        target_column=price_column,
        window=window,
        split=split,
    )
    parts = split_windows(windows)
    for name in SUBSETS:
        if len(parts[name]) == 0:
            raise ValueError(f"El subconjunto {name!r} no tiene muestras utilizables")

    scaler = fit_scaler(parts["train"], target_name=price_column)

    return PreparedData(
        featured=featured,
        features=feature_tuple,
        split_parts=parts,
        scaler=scaler,
        ingest=ingest,
        cleaning=cleaning,
        target_column_index=feature_tuple.index(price_column),
        price_column=price_column,
    )


def build_model(
    model_key: str,
    *,
    window: int,
    seed: int,
    target_column_index: int,
    arima_order: ArimaOrder | None = None,
    recurrent_max_epochs: int = 20,
) -> BaseModel:
    """Instancia un modelo por clave logica.

    ``model_key`` admitidos: ``moving_average``, ``linear_regression``,
    ``arima``, ``lstm_base``, ``gru_base``.

    Raises:
        ValueError: si la clave no es conocida.
    """
    if model_key == "moving_average":
        return MovingAverageBaseline(
            ModelConfig(model_key=model_key, family="MovingAverage", window=window, seed=seed),
            target_column_index=target_column_index,
        )
    if model_key == "linear_regression":
        return LinearRegressionBaseline(
            ModelConfig(model_key=model_key, family="LinearRegression", window=window, seed=seed)
        )
    if model_key == "arima":
        return ArimaBaseline(
            ModelConfig(model_key=model_key, family="ARIMA", window=window, seed=seed),
            order=arima_order or ArimaOrder(),
        )
    if model_key in ("lstm_base", "gru_base"):
        from app.ml.models.recurrent import RecurrentConfig

        recurrent_config = RecurrentConfig(max_epochs=recurrent_max_epochs)
        model_cls = LSTMRegressor if model_key == "lstm_base" else GRURegressor
        config = ModelConfig(
            model_key=model_key,
            family="LSTM" if model_key == "lstm_base" else "GRU",
            window=window,
            seed=seed,
            params=recurrent_config.to_dict(),
        )
        return model_cls(config, recurrent_config)
    raise ValueError(f"model_key desconocido: {model_key!r}")


def _arima_series(data: PreparedData) -> np.ndarray:
    """Serie que ve el walk-forward de ARIMA: desde el primer objetivo de val.

    ``forecast_one_step`` usa ``len(train)`` como desplazamiento, asi que la
    serie debe empezar exactamente en la posicion del primer objetivo de
    validacion.
    """
    return data.close_scaled[data.first_target_position :]


def _forecast(model: BaseModel, data: PreparedData, subset: str) -> np.ndarray:
    """Predicciones en **USD** para un subconjunto (ya desescaladas, R-02)."""
    windows = data.scaler.transform(data.split_parts[subset])
    if isinstance(model, ArimaBaseline):
        series = _arima_series(data)
        n_val = len(data.split_parts["val"])
        if subset == "val":
            scaled = model.forecast_one_step(series, len(windows))
        else:
            scaled = model.forecast_one_step(series, n_val + len(windows))[n_val:]
        return np.asarray(data.scaler.inverse_target(scaled), dtype=np.float64)
    scaled = np.asarray(model.predict(windows.x), dtype=np.float64).ravel()
    return np.asarray(data.scaler.inverse_target(scaled), dtype=np.float64)


def _last_close_usd(data: PreparedData, subset: str) -> np.ndarray:
    """Ultimo cierre observado antes de cada objetivo, en USD."""
    windows = data.split_parts[subset]
    prev_scaled = np.asarray(windows.x[:, -1, data.target_column_index], dtype=np.float64)
    return np.asarray(data.scaler.inverse_target(prev_scaled), dtype=np.float64)


def _metrics(data: PreparedData, subset: str, y_pred_usd: np.ndarray) -> EvaluationMetrics:
    """Metricas de un subconjunto en USD (R-07)."""
    windows = data.split_parts[subset]
    y_true = np.asarray(windows.y, dtype=np.float64).ravel()
    return compute_metrics_for(y_true, y_pred_usd, _last_close_usd(data, subset))


def compute_metrics_for(
    y_true: np.ndarray, y_pred: np.ndarray, last_close: np.ndarray
) -> EvaluationMetrics:
    """Envoltura de :func:`compute_metrics` (alias legible para el pipeline)."""
    from app.ml.evaluation.metrics import compute_metrics

    return compute_metrics(y_true, y_pred, last_close)


def _numeric_metrics(metrics: EvaluationMetrics) -> dict[str, float]:
    """Convierte :class:`EvaluationMetrics` en dict numerico para agregar."""
    return {
        "mae": metrics.mae,
        "rmse": metrics.rmse,
        "mape": metrics.mape,
        "direction_accuracy": metrics.direction_accuracy,
        "f1": metrics.f1,
    }


def _fit_model(
    model: BaseModel,
    data: PreparedData,
) -> None:
    """Ajusta el modelo con train (y validacion si es recurrente)."""
    train = data.scaler.transform(data.split_parts["train"])
    if isinstance(model, ArimaBaseline):
        # El offset del walk-forward es el numero de muestras de train.
        model.config = ModelConfig(
            model_key=model.config.model_key,
            family=model.config.family,
            window=model.config.window,
            seed=model.config.seed,
            params=model.config.params,
        )
        model.fit(train.x, train.y)
    elif isinstance(model, (LSTMRegressor, GRURegressor)):
        val = data.scaler.transform(data.split_parts["val"])
        model.fit(train.x, train.y, val.x, val.y)
    else:
        model.fit(train.x, train.y)


def _train_single_seed(
    model_key: str,
    data: PreparedData,
    seed: int,
    arima_order: ArimaOrder | None,
    recurrent_max_epochs: int,
) -> tuple[BaseModel, dict[str, float], dict[str, float]]:
    """Entrena con una semilla y devuelve metricas medias de val y test (USD)."""
    model = build_model(
        model_key,
        window=data.window,
        seed=seed,
        target_column_index=data.target_column_index,
        arima_order=arima_order,
        recurrent_max_epochs=recurrent_max_epochs,
    )
    _fit_model(model, data)
    val_metrics = _numeric_metrics(_metrics(data, "val", _forecast(model, data, "val")))
    test_metrics = _numeric_metrics(_metrics(data, "test", _forecast(model, data, "test")))
    return model, val_metrics, test_metrics


def train_all(
    snapshot_path: str | Path,
    output_dir: str | Path,
    experiment_id: str = "exp-local",
    models: tuple[str, ...] = ("moving_average", "linear_regression", "arima"),
    window: int = 30,
    seeds: tuple[int, ...] = DEFAULT_SEEDS,
    train_ratio: float = 0.70,
    val_ratio: float = 0.15,
    arima_order: ArimaOrder | None = None,
    recurrent_max_epochs: int = 20,
    registry: ModelRegistry | None = None,
) -> TrainResult:
    """Entrena, evalua y empaqueta los modelos indicados.

    Los modelos deterministas se entrenan una sola vez; los estocasticos
    (LSTM/GRU) se repiten por cada semilla y se reporta media +/- desviacion
    (R-08). El campeon se elige por MAE de **validacion** (R-24).

    Raises:
        ValueError: si no hay modelos validos o el dataset es insuficiente.
    """
    if not models:
        raise ValueError("Hay que indicar al menos un modelo")
    if not seeds:
        raise ValueError("Hay que indicar al menos una semilla")

    out_dir = Path(output_dir)
    out_dir.mkdir(parents=True, exist_ok=True)

    data = prepare_dataset(snapshot_path, window, train_ratio, val_ratio)
    dataset_version = data.ingest.dataset_version
    reg = registry if registry is not None else ModelRegistry(out_dir / "registry.json")

    runs: list[ModelRunResult] = []
    artifacts: dict[str, Path] = {}
    degraded: list[str] = []

    for model_key in models:
        use_seeds = seeds if model_key in STOCHASTIC_MODELS else seeds[:1]
        trained_seeds: list[int] = []
        val_runs: list[dict[str, float]] = []
        test_runs: list[dict[str, float]] = []
        last_model: BaseModel | None = None

        for seed in use_seeds:
            try:
                model, m_val, m_test = _train_single_seed(
                    model_key, data, seed, arima_order, recurrent_max_epochs
                )
            except TensorFlowMissingError as exc:
                degraded.append(f"{model_key}: {exc}")
                break
            last_model = model
            trained_seeds.append(seed)
            val_runs.append(m_val)
            test_runs.append(m_test)

        if last_model is None:
            continue

        mean_val, std_val = aggregate_over_seeds(val_runs)
        mean_test, std_test = aggregate_over_seeds(test_runs)
        version = f"v1-{len(trained_seeds)}s" if len(trained_seeds) > 1 else "v1"

        test_pred = _forecast(last_model, data, "test")
        error_report = analyze_errors(
            model_key,
            data.split_parts["test"].target_dates,
            np.asarray(data.split_parts["test"].y, dtype=np.float64),
            test_pred,
            _last_close_usd(data, "test"),
        )

        bundle = ArtifactBundle(
            model=last_model,
            scaler=data.scaler,
            feature_names=data.features,
            provenance={
                "model_key": model_key,
                "family": last_model.config.family,
                "version": version,
                "dataset_version": dataset_version,
                "seed": trained_seeds[0],
                "seeds": list(trained_seeds),
                "metrics": mean_test,
                "window": data.window,
                "feature_names": list(data.features),
                "experiment_id": experiment_id,
            },
            window=data.window,
        )
        model_path = out_dir / f"{model_key}_{version}.joblib"
        save_bundle(bundle, model_path)
        write_checksum(model_path)
        artifacts[model_key] = model_path

        registered = reg.register(
            model_key=model_key,
            family=last_model.config.family,
            version=version,
            artifact_path=model_path,
            dataset_version=dataset_version,
            seed=trained_seeds[0],
            seeds=tuple(trained_seeds),
            config=last_model.config.to_dict(),
            feature_names=data.features,
            metrics=MetricsSummary(
                mae=mean_test["mae"],
                rmse=mean_test["rmse"],
                mape=mean_test["mape"],
                direction_accuracy=mean_test["direction_accuracy"],
                n_samples=len(data.split_parts["test"]),
                n_seeds=len(trained_seeds),
                stddev=std_test,
                split="test",
            ),
            notes=json_safe(error_report.to_dict()),
        )

        # La trazabilidad del artefacto incluye los digests ya calculados.
        # NO se incrusta aqui ninguna marca de tiempo: el artefacto debe ser
        # reproducible byte a byte entre corridas con los mismos datos (R-08).
        # El campo ``generated_at`` se genera en cada inferencia, y ``trained_at``
        # vive en el indice del registro, que es metadato y no parte del modelo.
        bundle.provenance["artifact_sha256"] = registered.artifact_sha256
        bundle.provenance["config_sha256"] = registered.config_sha256
        save_bundle(bundle, model_path)
        write_checksum(model_path)

        runs.append(
            ModelRunResult(
                model_key=model_key,
                family=last_model.config.family,
                version=version,
                metrics_val=mean_val,
                metrics_test=mean_test,
                stddev_val=std_val,
                stddev_test=std_test,
                n_seeds=len(trained_seeds),
                model=last_model,
                error_analysis=error_report,
                config=last_model.config.to_dict(),
                seeds=tuple(trained_seeds),
            )
        )

    if not runs:
        detail = "; ".join(degraded) if degraded else "revisa la configuracion"
        raise ValueError(f"Ningun modelo pudo entrenarse: {detail}")

    # Campeon por validacion (R-24): el test no participa en la eleccion.
    champion = min(runs, key=lambda r: r.mean_val_mae)

    return TrainResult(
        experiment_id=experiment_id,
        dataset_version=dataset_version,
        models=runs,
        champion_key=champion.model_key,
        champion_version=champion.version,
        artifacts=artifacts,
        output_dir=out_dir,
        n_test_samples=len(data.split_parts["test"]),
        degraded=degraded,
    )


def build_comparison(result: TrainResult, data: PreparedData) -> ComparisonReport:
    """Reconstruye la tabla comparativa sobre fechas de test identicas (R-05).

    Raises:
        ValueError: si las fechas de test no coinciden entre modelos.
    """
    test_win = data.split_parts["test"]
    y_true = np.asarray(test_win.y, dtype=np.float64)
    prev = _last_close_usd(data, "test")
    predictions = [
        ModelPrediction(
            model_key=run.model_key,
            family=run.family,
            target_dates=test_win.target_dates,
            y_pred=_forecast(run.model, data, "test"),
            y_true=y_true,
            last_close=prev,
        )
        for run in result.models
    ]
    return compare_models(predictions)
