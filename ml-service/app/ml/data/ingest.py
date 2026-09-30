"""Ingesta de snapshots CSV de mercado (XMR-USD diario, OHLCV).

Valida esquema, tipos, rangos y monotonicidad de fechas. No hace red (R-18) ni
transformaciones que Rompan la causalidad (R-03).
"""

from __future__ import annotations

import hashlib
from dataclasses import dataclass, field
from pathlib import Path

import pandas as pd

__all__ = [
    "REQUIRED_COLUMNS",
    "IngestError",
    "IngestResult",
    "file_sha256",
    "read_snapshot",
    "validate_schema",
    "write_snapshot",
]

#: Columnas obligatorias del snapshot OHLCV diario.
REQUIRED_COLUMNS: tuple[str, ...] = ("date", "open", "high", "low", "close", "volume")

#: Rango minimo/maximo admitido por columna (valores fuera se rechazan).
NUMERIC_RANGES: dict[str, tuple[float | None, float | None]] = {
    "open": (1e-9, None),
    "high": (1e-9, None),
    "low": (1e-9, None),
    "close": (1e-9, None),
    "volume": (0.0, None),
}


class IngestError(ValueError):
    """Error de validacion del snapshot de entrada."""


@dataclass(frozen=True)
class IngestResult:
    """Snapshot cargado junto a su procedencia."""

    frame: pd.DataFrame
    path: Path
    sha256: str
    n_rows: int
    date_min: pd.Timestamp
    date_max: pd.Timestamp
    warnings: list[str] = field(default_factory=list)

    @property
    def dataset_version(self) -> str:
        """Identificador corto y estable del dataset basado en su checksum."""
        return f"sha256:{self.sha256[:16]}"


def file_sha256(path: str | Path, chunk_size: int = 1024 * 1024) -> str:
    """Devuelve el SHA-256 del fichero (R-08: snapshot verificable)."""
    digest = hashlib.sha256()
    with Path(path).open("rb") as handle:
        for chunk in iter(lambda: handle.read(chunk_size), b""):
            digest.update(chunk)
    return digest.hexdigest()


def validate_schema(frame: pd.DataFrame) -> list[str]:
    """Valida columnas, tipos, rangos y fechas. Devuelve avisos no fatales.

    Args:
        frame: DataFrame candidato ya indexado o con columna ``date``.

    Returns:
        Lista de avisos (rangos fuera de rango, fines de semana, etc.).

    Raises:
        IngestError: si falta una columna obligatoria, si los tipos no son
            numericos, si hay fechas nulas/duplicadas/no monotonas o si hay
            precios no positivos.
    """
    missing = [col for col in REQUIRED_COLUMNS if col not in frame.columns]
    if missing:
        raise IngestError(f"Faltan columnas obligatorias: {missing}")

    warnings: list[str] = []

    dates = frame["date"]
    if dates.isna().any():
        raise IngestError("La columna 'date' contiene valores nulos")
    if not pd.api.types.is_datetime64_any_dtype(dates):
        try:
            dates = pd.to_datetime(dates, utc=True)
        except (TypeError, ValueError) as exc:  # pragma: no cover - defensivo
            raise IngestError(f"La columna 'date' no es parseable: {exc}") from exc
    else:
        dates = pd.to_datetime(dates, utc=True)

    if dates.duplicated().any():
        dupes = dates[dates.duplicated()].dt.strftime("%Y-%m-%d").unique().tolist()
        raise IngestError(f"Fechas duplicadas: {dupes[:5]}")

    if not dates.is_monotonic_increasing:
        raise IngestError("Las fechas no son monotonas crecientes; ordene antes de ingerir")

    for col in REQUIRED_COLUMNS:
        if col == "date":
            continue
        if not pd.api.types.is_numeric_dtype(frame[col]):
            raise IngestError(f"La columna {col!r} no es numerica")

    for col, (low, high) in NUMERIC_RANGES.items():
        series = frame[col]
        bad = series.isna() | (series <= 0 if low == 1e-9 else series < (low or 0.0))
        if bool(bad.any()):
            n_bad = int(bad.sum())
            if n_bad > len(frame) * 0.01:
                raise IngestError(f"{col}: {n_bad} valores nulos o no positivos")
            warnings.append(f"{col}: {n_bad} filas con valores nulos/no positivos")
        if high is not None:
            over = series > high
            if bool(over.any()):
                warnings.append(f"{col}: {int(over.sum())} valores por encima de {high}")

    bad_ohlc = (frame["high"] < frame[["open", "close"]].max(axis=1)) | (
        frame["low"] > frame[["open", "close"]].min(axis=1)
    )
    if bool(bad_ohlc.any()):
        warnings.append(f"OHLC inconsistente en {int(bad_ohlc.sum())} filas")

    n_weekend = int((dates.dt.dayofweek >= 5).sum())
    if n_weekend:
        warnings.append(f"{n_weekend} filas en fin de semana (cripto opera 7d)")

    return warnings


def read_snapshot(path: str | Path) -> IngestResult:
    """Lee un snapshot CSV local y devuelve el DataFrame validado.

    Args:
        path: Ruta al CSV con columnas ``date,open,high,low,close,volume``.

    Returns:
        IngestResult: frame con indice ``DatetimeIndex`` UTC, mas su SHA-256.

    Raises:
        IngestError: si el fichero no existe o falla la validacion.
    """
    csv_path = Path(path)
    if not csv_path.is_file():
        raise IngestError(f"No existe el snapshot: {csv_path}")

    frame = pd.read_csv(csv_path)
    frame.columns = [str(col).strip().lower() for col in frame.columns]
    warnings = validate_schema(frame)

    frame = frame.loc[:, list(REQUIRED_COLUMNS)].copy()
    frame["date"] = pd.to_datetime(frame["date"], utc=True)
    frame = frame.sort_values("date", kind="stable").reset_index(drop=True)
    frame = frame.set_index("date")

    dates = frame.index
    return IngestResult(
        frame=frame,
        path=csv_path,
        sha256=file_sha256(csv_path),
        n_rows=len(frame),
        date_min=dates.min(),
        date_max=dates.max(),
        warnings=warnings,
    )


def write_snapshot(frame: pd.DataFrame, path: str | Path) -> Path:
    """Escribe un snapshot CSV y devuelve su ruta (usado por tests sinteticos)."""
    out = frame.copy()
    out.index.name = "date"
    out_path = Path(path)
    out_path.parent.mkdir(parents=True, exist_ok=True)
    out.to_csv(out_path, index=True)
    return out_path
