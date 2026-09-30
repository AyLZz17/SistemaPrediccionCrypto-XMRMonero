"""Particion cronologica y construccion de ventanas (R-01, R-02, R-23).

Reglas garantizadas por este modulo:

* **R-01** Particion estrictamente cronologica. Prohibido ``shuffle`` y
  ``train_test_split`` aleatorio.
* **R-23** Cada muestra pertenece al subconjunto de la **fecha de su objetivo**.
  Su ventana de entrada puede incluir dias anteriores (incluso del subconjunto
  previo) pero **nunca** su propio objetivo ni datos posteriores.
* **R-02** El escalador se ajusta **solo con train**; val/test usan ``transform``
  y las predicciones se desescalan antes de calcular metricas.
"""

from __future__ import annotations

from dataclasses import dataclass

import numpy as np
import pandas as pd
from sklearn.preprocessing import MinMaxScaler

__all__ = [
    "SUBSETS",
    "FittedScaler",
    "TemporalSplit",
    "WindowSet",
    "assert_chronological",
    "chronological_split",
    "fit_scaler",
    "make_windows",
    "split_windows",
]

#: Nombres canonicos de los subconjuntos, en orden temporal.
SUBSETS: tuple[str, str, str] = ("train", "val", "test")


@dataclass(frozen=True)
class TemporalSplit:
    """Indices posicionales (no etiquetas) de cada subconjunto.

    ``train`` precede estrictamente a ``val``, que precede a ``test``.
    """

    train: np.ndarray
    val: np.ndarray
    test: np.ndarray

    def indices(self, subset: str) -> np.ndarray:
        """Devuelve los indices del subconjunto pedido."""
        if subset not in SUBSETS:
            raise ValueError(f"Subconjunto desconocido: {subset!r}")
        return getattr(self, subset)

    @property
    def all_indices(self) -> np.ndarray:
        """Todos los indices concatenados en orden cronologico."""
        return np.concatenate([self.train, self.val, self.test])

    def subset_of(self, position: int) -> str:
        """Devuelve el subconjunto al que pertenece la posicion ``position``."""
        if position in set(self.train.tolist()):
            return "train"
        if position in set(self.val.tolist()):
            return "val"
        if position in set(self.test.tolist()):
            return "test"
        raise ValueError(f"La posicion {position} no pertenece a ningun subconjunto")


@dataclass(frozen=True)
class WindowSet:
    """Conjunto de muestras (ventana, objetivo) listo para modelar.

    Cada fila de ``x`` cubre las posiciones ``target_position - window`` ..
    ``target_position - 1``. El valor en ``target_position`` **no** aparece en
    la ventana (R-23).
    """

    x: np.ndarray
    y: np.ndarray
    feature_names: tuple[str, ...]
    target_positions: np.ndarray
    target_dates: pd.DatetimeIndex
    subsets: np.ndarray
    window: int

    def __len__(self) -> int:
        return int(self.x.shape[0])

    def select(self, subset: str) -> WindowSet:
        """Devuelve un ``WindowSet`` con solo las muestras de un subconjunto."""
        if subset not in SUBSETS:
            raise ValueError(f"Subconjunto desconocido: {subset!r}")
        mask = self.subsets == subset
        return WindowSet(
            x=self.x[mask],
            y=self.y[mask],
            feature_names=self.feature_names,
            target_positions=self.target_positions[mask],
            target_dates=self.target_dates[mask],
            subsets=self.subsets[mask],
            window=self.window,
        )

    def fingerprint(self) -> np.ndarray:
        """Huella de las fechas objetivo; sirve para comprobar igualdad (R-05)."""
        return self.target_dates.to_numpy(dtype="datetime64[ns]")


def chronological_split(
    n_rows: int, train_ratio: float = 0.70, val_ratio: float = 0.15
) -> TemporalSplit:
    """Divide las posiciones ``0..n_rows-1`` en train/val/test por orden temporal.

    Args:
        n_rows: Numero total de filas (posiciones contiguas y ordenadas).
        train_ratio: Proporcion de entrenamiento (D-05: 0.70).
        val_ratio: Proporcion de validacion (D-05: 0.15).

    Returns:
        TemporalSplit con arrays de posiciones, sin solapamiento.

    Raises:
        ValueError: si las proporciones son invalidas o algun subconjunto queda vacio.
    """
    if n_rows <= 0:
        raise ValueError("n_rows debe ser positivo")
    if not 0.0 < train_ratio < 1.0:
        raise ValueError("train_ratio debe estar en (0, 1)")
    if not 0.0 < val_ratio < 1.0:
        raise ValueError("val_ratio debe estar en (0, 1)")
    if train_ratio + val_ratio >= 1.0:
        raise ValueError("train_ratio + val_ratio debe ser < 1")

    n_train = int(np.floor(n_rows * train_ratio))
    n_val = int(np.floor(n_rows * val_ratio))
    n_test = n_rows - n_train - n_val

    for name, size in (("train", n_train), ("val", n_val), ("test", n_test)):
        if size < 1:
            raise ValueError(
                f"El subconjunto {name!r} queda vacio ({size} filas); "
                "aumenta el dataset o ajusta las proporciones"
            )

    return TemporalSplit(
        train=np.arange(0, n_train, dtype=np.int64),
        val=np.arange(n_train, n_train + n_val, dtype=np.int64),
        test=np.arange(n_train + n_val, n_rows, dtype=np.int64),
    )


def assert_chronological(dates: pd.DatetimeIndex) -> None:
    """Verifica que las fechas son estrictamente crecientes.

    Raises:
        ValueError: si no son monotonas o contienen duplicados.
    """
    if len(dates) < 2:
        return
    if not dates.is_monotonic_increasing:
        raise ValueError("Las fechas objetivo no son monotonas crecientes (R-01)")
    if bool(dates.duplicated().any()):
        raise ValueError("Hay fechas objetivo duplicadas (R-01/R-23)")


def make_windows(
    frame: pd.DataFrame,
    feature_names: tuple[str, ...],
    target_column: str = "close",
    window: int = 30,
    split: TemporalSplit | None = None,
    drop_incomplete: bool = True,
) -> WindowSet:
    """Construye pares (ventana, objetivo) asignando cada muestra por su TARGET.

    Para la posicion de objetivo ``t``, la ventana cubre exactamente
    ``[t - window, t - 1]``: no incluye ``t`` ni nada posterior (R-23). El
    subconjunto de la muestra es el de la **fecha de su objetivo**, por lo que
    la ventana puede contener dias del subconjunto anterior (permitido) pero el
    objetivo nunca se share entre subconjuntos.

    Args:
        frame: Frame con indice ``DatetimeIndex`` monotono y las columnas pedidas.
        feature_names: Columnas que forman la entrada del modelo.
        target_column: Columna objetivo (``close``).
        window: Numero de dias de historico por muestra.
        split: Particion cronologica; si es ``None`` se calcula con 70/15/15.
        drop_incomplete: Si es ``True`` descarta las muestras cuya ventana
            quedaria incompleta por el inicio del dataset.

    Returns:
        WindowSet con arrays alineados y la etiqueta de subconjunto por muestra.

    Raises:
        ValueError: si faltan columnas, el indice no es monotono o no hay muestras.
    """
    if window < 1:
        raise ValueError("window debe ser >= 1")
    missing = [c for c in (*feature_names, target_column) if c not in frame.columns]
    if missing:
        raise ValueError(f"Faltan columnas en el frame: {missing}")
    if not isinstance(frame.index, pd.DatetimeIndex):
        raise ValueError("make_windows requiere un indice DatetimeIndex")
    if not frame.index.is_monotonic_increasing:
        raise ValueError("make_windows requiere fechas monotonas crecientes")

    n_rows = len(frame)
    if split is None:
        split = chronological_split(n_rows)

    target_positions: list[int] = []
    first_valid = window if drop_incomplete else 0
    for position in split.all_indices.tolist():
        if position >= first_valid:
            target_positions.append(position)
    target_positions.sort()

    if not target_positions:
        raise ValueError(
            "No hay muestras validas; el dataset es mas corto que la ventana "
            f"(window={window}, n_rows={n_rows})"
        )

    features = frame.loc[:, list(feature_names)].to_numpy(dtype=np.float64)
    targets = frame[target_column].to_numpy(dtype=np.float64)

    positions = np.asarray(target_positions, dtype=np.int64)
    # indices de inicio de cada ventana: [t-window, ..., t-1]
    offsets = np.arange(-window, 0, dtype=np.int64)
    window_idx = positions[:, None] + offsets[None, :]
    x = features[window_idx]

    # Asignacion por fecha del OBJETIVO (R-23), no por la ventana.
    lookup = np.empty(n_rows, dtype=object)
    lookup[split.train] = "train"
    lookup[split.val] = "val"
    lookup[split.test] = "test"
    subsets = lookup[positions].astype("<U5")

    dates = frame.index[positions]
    assert_chronological(pd.DatetimeIndex(dates))

    return WindowSet(
        x=x,
        y=targets[positions],
        feature_names=tuple(feature_names),
        target_positions=positions,
        target_dates=pd.DatetimeIndex(dates),
        subsets=subsets,
        window=window,
    )


def split_windows(windows: WindowSet) -> dict[str, WindowSet]:
    """Parte un ``WindowSet`` en train/val/test por etiqueta de subconjunto."""
    return {name: windows.select(name) for name in SUBSETS}


@dataclass(frozen=True)
class FittedScaler:
    """Escalador ``MinMaxScaler`` ajustado **solo con train** (R-02)."""

    scaler: MinMaxScaler
    feature_names: tuple[str, ...]
    target_name: str
    target_column_index: int

    def transform(self, windows: WindowSet) -> WindowSet:
        """Aplica ``transform`` (nunca ``fit``) a las ventanas."""
        n_samples, window, n_features = windows.x.shape
        flat = windows.x.reshape(n_samples * window, n_features)
        scaled = self.scaler.transform(flat).reshape(n_samples, window, n_features)
        return WindowSet(
            x=scaled,
            y=windows.y,
            feature_names=windows.feature_names,
            target_positions=windows.target_positions,
            target_dates=windows.target_dates,
            subsets=windows.subsets,
            window=windows.window,
        )

    @property
    def n_features(self) -> int:
        """Numero de features que espera el escalador."""
        return int(self.scaler.n_features_in_)

    def _as_full_width(self, values: np.ndarray) -> np.ndarray:
        """Coloca ``values`` en la columna del objetivo de una matriz de ancho completo.

        ``MinMaxScaler.inverse_transform`` exige el numero de features con el que
        se ajusto, asi que se rellenan las demas columnas con su valor minimo
        (escala 0) y luego se extrae la columna del objetivo.
        """
        array = np.asarray(values, dtype=np.float64).reshape(-1, 1)
        full = np.zeros((array.shape[0], self.n_features), dtype=np.float64)
        full[:, self.target_column_index] = array[:, 0]
        return full

    def scale_target(self, y: np.ndarray) -> np.ndarray:
        """Escala el objetivo con la misma transformacion que sus columnas."""
        full = self._as_full_width(y)
        return np.asarray(self.scaler.transform(full))[:, self.target_column_index]

    def inverse_target(self, y_scaled: np.ndarray) -> np.ndarray:
        """Desescala predicciones a **USD** antes de calcular metricas (R-02)."""
        full = self._as_full_width(y_scaled)
        return np.asarray(self.scaler.inverse_transform(full))[:, self.target_column_index]


def fit_scaler(train: WindowSet, target_name: str = "close") -> FittedScaler:
    """Ajusta un ``MinMaxScaler`` con **unicamente** las ventanas de train (R-02).

    El objetivo ``close`` se escala con el mismo escalador usando su indice de
    columna, de modo que la desescala de predicciones devuelve USD.

    Raises:
        ValueError: si el subconjunto train esta vacio o no incluye el objetivo.
    """
    if len(train) == 0:
        raise ValueError("No se puede ajustar el escalador con train vacio (R-02)")
    if target_name not in train.feature_names:
        raise ValueError(
            f"El objetivo {target_name!r} debe estar entre las features para "
            "escalar objetivo y entradas con el mismo transformador"
        )

    n_samples, window, n_features = train.x.shape
    flat = train.x.reshape(n_samples * window, n_features)
    scaler = MinMaxScaler()
    scaler.fit(flat)

    return FittedScaler(
        scaler=scaler,
        feature_names=train.feature_names,
        target_name=target_name,
        target_column_index=train.feature_names.index(target_name),
    )
