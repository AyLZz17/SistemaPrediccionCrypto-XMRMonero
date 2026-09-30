"""Limpieza de datos: nulos, duplicados y valores atipicos.

Solo se aplican operaciones **causales** (R-03): sin interpolacion que use el
futuro. Las filas problematicas se eliminan o se recortan usando informacion
pasada, y todo queda registrado en un :class:`CleaningReport`.
"""

from __future__ import annotations

from dataclasses import dataclass, field

import numpy as np
import pandas as pd

__all__ = ["CleaningReport", "clean_frame", "detect_gaps", "detect_outliers"]


@dataclass(frozen=True)
class CleaningReport:
    """Trazabilidad de la limpieza aplicada a un snapshot."""

    n_rows_in: int
    n_rows_out: int
    n_dropped_null: int = 0
    n_dropped_duplicate: int = 0
    n_dropped_non_finite: int = 0
    n_clipped_outliers: int = 0
    n_gaps_filled: int = 0
    notes: list[str] = field(default_factory=list)

    @property
    def n_dropped(self) -> int:
        """Total de filas eliminadas."""
        return self.n_dropped_null + self.n_dropped_duplicate + self.n_dropped_non_finite

    def to_dict(self) -> dict[str, object]:
        """Representacion serializable del informe."""
        return {
            "n_rows_in": self.n_rows_in,
            "n_rows_out": self.n_rows_out,
            "n_dropped_null": self.n_dropped_null,
            "n_dropped_duplicate": self.n_dropped_duplicate,
            "n_dropped_non_finite": self.n_dropped_non_finite,
            "n_clipped_outliers": self.n_clipped_outliers,
            "n_gaps_filled": self.n_gaps_filled,
            "notes": list(self.notes),
        }


def detect_gaps(frame: pd.DataFrame) -> pd.Index:
    """Devuelve las fechas del indicedaily que no existen en el snapshot."""
    if frame.empty:
        return pd.DatetimeIndex([], tz="UTC")
    full = pd.date_range(frame.index.min(), frame.index.max(), freq="D", tz=frame.index.tz)
    return full.difference(frame.index)


def detect_outliers(series: pd.Series, n_std: float = 8.0) -> pd.Series:
    """Marca atipicos por desviacion tipica **expandida hacia atras** (causal).

    La media y la desviacion se calculan con ``expanding().mean()`` sobre el
    propio ``series``, de modo que el valor de la fila ``t`` solo depende de
    ``<= t`` (R-03).
    """
    mean = series.expanding(min_periods=2).mean()
    std = series.expanding(min_periods=2).std(ddof=0).replace(0.0, np.nan)
    z = (series - mean) / std
    return (z.abs() > n_std).fillna(False)


def clean_frame(
    frame: pd.DataFrame,
    *,
    outlier_n_std: float = 8.0,
    drop_outliers: bool = False,
    clip_outliers: bool = False,
) -> tuple[pd.DataFrame, CleaningReport]:
    """Limpia el frame de mercado de forma causal y auditable.

    Pasos, en este orden:

    1. Fechas nulas o no parseables -> se eliminan.
    2. Duplicados de fecha -> se conserva la ultima observacion.
    3. Valores no finitos en columnas numericas -> se eliminan.
    4. Atipicos extremos de ``close``/``volume`` -> opcional recorte (causal).

    Args:
        frame: Serie OHLCV con indice ``DatetimeIndex`` monotono.
        outlier_n_std: Umbral en desviaciones tipicas (comparacion causal).
        drop_outliers: Si es ``True`` elimina las filas marcadas.
        clip_outliers: Si es ``True`` recorta al rango historical permissible.

    Returns:
        Tupla ``(frame_limpio, informe)``.

    Raises:
        ValueError: si el frame no tiene indice datetime o queda vacio.
    """
    if not isinstance(frame.index, pd.DatetimeIndex):
        raise ValueError("clean_frame requiere un indice DatetimeIndex")

    n_in = len(frame)
    notes: list[str] = []
    clean = frame.sort_index(kind="stable").copy()

    mask_bad_date = clean.index.isna()
    n_bad_date = int(mask_bad_date.sum())
    if n_bad_date:
        clean = clean.loc[~mask_bad_date]
        notes.append(f"{n_bad_date} filas con fecha nula eliminadas")

    n_dup = int(clean.index.duplicated(keep="last").sum())
    if n_dup:
        clean = clean.loc[~clean.index.duplicated(keep="last")]
        notes.append(f"{n_dup} fechas duplicadas conservando la ultima observacion")

    numeric_cols = [c for c in clean.columns if pd.api.types.is_numeric_dtype(clean[c])]
    finite_mask = np.ones(len(clean), dtype=bool)
    for col in numeric_cols:
        finite_mask &= np.isfinite(clean[col].to_numpy(dtype="float64", na_value=np.nan))
    n_non_finite = int((~finite_mask).sum())
    if n_non_finite:
        clean = clean.loc[finite_mask]
        notes.append(f"{n_non_finite} filas con valores no finitos eliminadas")

    n_clipped = 0
    if outlier_n_std > 0:
        flag = pd.Series(False, index=clean.index)
        for col in numeric_cols:
            flag = flag | detect_outliers(clean[col], outlier_n_std)
        if bool(flag.any()):
            if drop_outliers:
                clean = clean.loc[~flag]
                notes.append(f"{int(flag.sum())} filas atipicas eliminadas")
            elif clip_outliers:
                for col in numeric_cols:
                    series = clean[col]
                    lower = series.expanding(min_periods=2).quantile(0.001)
                    upper = series.expanding(min_periods=2).quantile(0.999)
                    clipped = series.clip(lower=lower, upper=upper)
                    n_clipped += int((clipped != series).sum())
                    clean[col] = clipped
                notes.append(f"{n_clipped} valores atipicos recortados")

    if clean.empty:
        raise ValueError("La limpieza dejo el dataset vacio")

    report = CleaningReport(
        n_rows_in=n_in,
        n_rows_out=len(clean),
        n_dropped_null=n_bad_date,
        n_dropped_duplicate=n_dup,
        n_dropped_non_finite=n_non_finite,
        n_clipped_outliers=n_clipped,
        n_gaps_filled=0,
        notes=notes,
    )
    return clean, report
