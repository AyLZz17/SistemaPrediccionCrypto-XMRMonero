"""Inferencia: round-trip de artefactos, integridad y trazabilidad (R-28)."""

from __future__ import annotations

from pathlib import Path

import numpy as np
import pandas as pd
import pytest

from app.ml.data.ingest import file_sha256
from app.ml.models.base import Direction
from app.ml.pipelines.inference import (
    ArtifactBundle,
    InferenceError,
    build_window_from_history,
    load_bundle,
    predict_with_bundle,
    save_bundle,
    write_checksum,
)
from app.ml.pipelines.train import train_all


@pytest.fixture()
def trained(tmp_path: Path, synthetic_ohlcv: pd.DataFrame) -> tuple[Path, Path, Path]:
    """Entrena un modelo sintetico y devuelve ``(snapshot, artefacto, historico)``."""
    snapshot = tmp_path / "xmr.csv"
    frame = synthetic_ohlcv.copy()
    frame.index.name = "date"
    frame.to_csv(snapshot)

    result = train_all(
        snapshot,
        output_dir=tmp_path / "artifacts",
        models=("moving_average",),
        window=30,
    )
    return snapshot, result.artifacts["moving_average"], snapshot


def _history(snapshot: Path) -> pd.DataFrame:
    """Relee el snapshot como serie OHLCV con indice ``DatetimeIndex`` UTC.

    ``parse_dates`` con un CSV generado desde un indice tz-aware produce ya
    marcas ``tz-aware``; no hay que volver a localizar la zona horaria.
    """
    frame = pd.read_csv(snapshot, index_col="date", parse_dates=["date"])
    frame.index = pd.DatetimeIndex(frame.index)
    if frame.index.tz is None:
        frame.index = frame.index.tz_localize("UTC")
    return frame[["open", "high", "low", "close", "volume"]]


def test_artifact_round_trip(trained: tuple[Path, Path, Path]) -> None:
    """Guardar y cargar un bundle conserva modelo, escalador y procedencia."""
    _snapshot, artifact_path, _ = trained
    bundle = load_bundle(artifact_path)

    assert isinstance(bundle, ArtifactBundle)
    assert bundle.model_key == "moving_average"
    assert bundle.window == 30
    assert len(bundle.feature_names) > 1
    assert "close" in bundle.feature_names
    assert bundle.provenance["dataset_version"].startswith("sha256:")


def test_load_bundle_rejects_missing_file(tmp_path: Path) -> None:
    """Un artefacto inexistente produce un error de dominio."""
    with pytest.raises(InferenceError, match="No existe el artefacto"):
        load_bundle(tmp_path / "nope.joblib")


def test_load_bundle_rejects_tampered_checksum(trained: tuple[Path, Path, Path]) -> None:
    """Un checksum que no coincide invalida el artefacto (gate de integridad)."""
    _snapshot, artifact_path, _ = trained
    artifact_path.write_bytes(artifact_path.read_bytes() + b"\x00")

    with pytest.raises(InferenceError, match="integridad"):
        load_bundle(artifact_path)


def test_write_checksum_creates_sidecar(trained: tuple[Path, Path, Path]) -> None:
    """``write_checksum`` deja un fichero ``.sha256`` verificable."""
    _snapshot, artifact_path, _ = trained
    sidecar = write_checksum(artifact_path)

    assert sidecar.is_file()
    recorded = sidecar.read_text(encoding="utf-8").split()[0]
    assert recorded == file_sha256(artifact_path)


def test_build_window_shape(trained: tuple[Path, Path, Path], synthetic_ohlcv) -> None:
    """La ventana tiene forma ``(1, window, n_features)``."""
    _snapshot, _artifact, _ = trained
    window = build_window_from_history(synthetic_ohlcv, 30)
    # ``close`` mas las 11 features derivadas del canon.
    assert window.shape == (1, 30, 12)


def test_build_window_requires_enough_history(synthetic_ohlcv: pd.DataFrame) -> None:
    """Sin filas suficientes se lanza un error explicito."""
    with pytest.raises(InferenceError, match="al menos"):
        build_window_from_history(synthetic_ohlcv.iloc[:5], 30)


def test_predict_returns_traceable_record(trained: tuple[Path, Path, Path]) -> None:
    """La prediccion incluye precio, direccion, confianza y traza."""
    _snapshot, artifact_path, _ = trained
    bundle = load_bundle(artifact_path)
    history = _history(_snapshot)

    record = predict_with_bundle(bundle, history, target_date="2026-09-30", window=30)

    assert record.model_key == "moving_average"
    assert record.target_date == "2026-09-30"
    assert record.predicted_close > 0.0
    assert record.predicted_direction in {Direction.UP, Direction.DOWN, Direction.FLAT}
    assert 0.5 <= record.confidence <= 0.99
    assert record.actual_close is None

    trace = record.trace
    assert trace["dataset_version"].startswith("sha256:")
    assert len(trace["artifact_sha256"]) == 64
    assert len(trace["config_sha256"]) == 64
    assert isinstance(trace["seed"], int)
    assert trace["generated_at"]


def test_prediction_is_deterministic_for_same_inputs(trained: tuple[Path, Path, Path]) -> None:
    """Misma entrada y mismo artefacto dan la misma prediccion (R-08)."""
    _snapshot, artifact_path, _ = trained
    bundle = load_bundle(artifact_path)
    history = _history(_snapshot)

    first = predict_with_bundle(bundle, history, target_date="2026-09-30", window=30)
    second = predict_with_bundle(bundle, history, target_date="2026-09-30", window=30)

    assert first.predicted_close == pytest.approx(second.predicted_close)
    assert first.predicted_direction == second.predicted_direction
    assert first.confidence == pytest.approx(second.confidence)


def test_prediction_changes_with_new_data(trained: tuple[Path, Path, Path]) -> None:
    """Alterar el ultimo cierre cambia la prediccion."""
    _snapshot, artifact_path, _ = trained
    bundle = load_bundle(artifact_path)
    history = _history(_snapshot)

    baseline = predict_with_bundle(bundle, history, target_date="2026-09-30", window=30)

    bumped = history.copy()
    bumped.iloc[-1, bumped.columns.get_loc("close")] *= 1.5
    altered = predict_with_bundle(bundle, bumped, target_date="2026-09-30", window=30)

    assert altered.predicted_close != pytest.approx(baseline.predicted_close)


def test_predict_rejects_feature_count_mismatch(trained: tuple[Path, Path, Path]) -> None:
    """Un artefacto con otra configuracion de features se rechaza claramente."""
    _snapshot, artifact_path, _ = trained
    bundle = load_bundle(artifact_path)
    history = _history(_snapshot)

    # Caso 1: el artefacto exige una columna que el historico no produce.
    unknown_column = ArtifactBundle(
        model=bundle.model,
        scaler=bundle.scaler,
        feature_names=("close", "columna_inexistente"),
        provenance=bundle.provenance,
        window=bundle.window,
    )
    with pytest.raises(InferenceError, match="columnas exigidas"):
        predict_with_bundle(unknown_column, history, target_date="2026-09-30", window=30)

    # Caso 2: columnas validas pero con dimensionalidad incompatible con el
    # escalador del artefacto: el gate de integridad lo detiene.
    wrong_width = ArtifactBundle(
        model=bundle.model,
        scaler=bundle.scaler,
        feature_names=("close", "volume"),
        provenance=bundle.provenance,
        window=bundle.window,
    )
    with pytest.raises(InferenceError, match="escalador"):
        predict_with_bundle(wrong_width, history, target_date="2026-09-30", window=30)


def test_prediction_serializes_to_api_shape(trained: tuple[Path, Path, Path]) -> None:
    """El dict producido tiene exactamente las claves del contrato de API."""
    _snapshot, artifact_path, _ = trained
    bundle = load_bundle(artifact_path)
    history = _history(_snapshot)

    payload = predict_with_bundle(bundle, history, target_date="2026-09-30", window=30).to_dict()

    assert set(payload) == {
        "model_key",
        "version",
        "target_date",
        "predicted_close",
        "predicted_direction",
        "actual_close",
        "confidence",
        "trace",
    }
    assert set(payload["trace"]) == {
        "dataset_version",
        "artifact_sha256",
        "config_sha256",
        "seed",
        "generated_at",
    }


def test_save_bundle_creates_parent_directories(tmp_path: Path, trained) -> None:
    """``save_bundle`` crea el directorio destino si no existe."""
    _snapshot, artifact_path, _ = trained
    bundle = load_bundle(artifact_path)
    target = tmp_path / "deep" / "nested" / "bundle.joblib"
    save_bundle(bundle, target)
    assert target.is_file()


def test_bundle_rejects_wrong_payload(tmp_path: Path) -> None:
    """Un fichero que no es un ArtifactBundle se rechaza."""
    import joblib

    bogus = tmp_path / "bogus.joblib"
    joblib.dump({"not": "a bundle"}, bogus)
    with pytest.raises(InferenceError, match="ArtifactBundle"):
        load_bundle(bogus)


def test_confidence_stays_in_valid_range(trained: tuple[Path, Path, Path]) -> None:
    """La confianza heuristica siempre queda en ``[0.5, 0.99]``."""
    _snapshot, artifact_path, _ = trained
    bundle = load_bundle(artifact_path)
    history = _history(_snapshot)

    record = predict_with_bundle(bundle, history, target_date="2026-09-30", window=30)
    assert 0.5 <= record.confidence <= 0.99
    assert np.isfinite(record.predicted_close)
