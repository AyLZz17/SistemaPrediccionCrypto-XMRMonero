"""Esquemas Pydantic v2 de entrada y salida de la API.

Los nombres y las formas coinciden exactamente con el contrato que consume
Spring Boot (ver ``ml-service/README.md``).
"""

from __future__ import annotations

from datetime import datetime
from typing import Any

from pydantic import BaseModel, ConfigDict, Field, field_validator

__all__ = [
    "DEFAULT_LOOKBACK_DAYS",
    "MAX_LOOKBACK_DAYS",
    "CompareModelRow",
    "CompareResponse",
    "Direction",
    "ErrorResponse",
    "HealthResponse",
    "MetricSet",
    "ModelInfo",
    "ModelsResponse",
    "PredictRequest",
    "PredictionResponse",
    "TraceBlock",
]

#: Ventana por defecto usada por Spring Boot.
DEFAULT_LOOKBACK_DAYS = 30

#: Tope de la ventana para evitar uso abusivo de CPU.
MAX_LOOKBACK_DAYS = 365

Direction = str


class HealthResponse(BaseModel):
    """Respuesta de ``GET /health``."""

    model_config = ConfigDict(extra="forbid")

    status: str = Field(examples=["UP"])
    service: str = Field(examples=["ml-service"])
    version: str = Field(examples=["1.0.0"])


class ErrorResponse(BaseModel):
    """Formato uniforme de error (R-35)."""

    model_config = ConfigDict(extra="forbid")

    detail: str
    code: str
    request_id: str


class MetricSet(BaseModel):
    """Metricas obligatorias de una version de modelo (R-07)."""

    model_config = ConfigDict(extra="forbid")

    mae: float
    rmse: float
    mape: float
    direction_accuracy: float


class ModelInfo(BaseModel):
    """Version de modelo registrada con su procedencia (R-28)."""

    model_config = ConfigDict(extra="forbid")

    model_key: str
    family: str
    version: str
    artifact_sha256: str
    dataset_version: str
    trained_at: str
    metrics: MetricSet | None = None
    n_seeds: int = 1
    notes: str = ""


class ModelsResponse(BaseModel):
    """Respuesta de ``GET /v1/models``."""

    model_config = ConfigDict(extra="forbid")

    items: list[ModelInfo]


class PredictRequest(BaseModel):
    """Cuerpo de ``POST /v1/predict``."""

    model_config = ConfigDict(extra="forbid")

    model_key: str = Field(min_length=1, max_length=64, examples=["lstm_base"])
    version: str | None = Field(default=None, max_length=32, examples=["v1"])
    lookback_days: int = Field(
        default=DEFAULT_LOOKBACK_DAYS, ge=1, le=MAX_LOOKBACK_DAYS, examples=[30]
    )
    symbol: str = Field(default="XMR-USD", min_length=2, max_length=16, examples=["XMR-USD"])

    @field_validator("symbol")
    @classmethod
    def _validate_symbol(cls, value: str) -> str:
        """Valida el simbolo: alfanumerico, guion y punto, sin espacios."""
        cleaned = value.strip().upper()
        if not cleaned or not all(char.isalnum() or char in "-." for char in cleaned):
            raise ValueError("symbol solo admite letras, digitos, '-' y '.'")
        return cleaned


class TraceBlock(BaseModel):
    """Bloque de trazabilidad de una prediccion."""

    model_config = ConfigDict(extra="forbid")

    dataset_version: str
    artifact_sha256: str
    config_sha256: str
    seed: int
    generated_at: str


class PredictionResponse(BaseModel):
    """Respuesta de ``POST /v1/predict``."""

    model_config = ConfigDict(extra="forbid")

    model_key: str
    version: str
    target_date: str
    predicted_close: float
    predicted_direction: Direction
    actual_close: float | None = None
    confidence: float = Field(ge=0.0, le=1.0)
    trace: TraceBlock


class CompareModelRow(BaseModel):
    """Fila de la comparacion de modelos."""

    model_config = ConfigDict(extra="allow")

    model_key: str
    family: str
    n_seeds: int
    mae: float
    rmse: float
    mape: float
    direction_accuracy: float


class CompareResponse(BaseModel):
    """Respuesta de ``GET /v1/metrics/compare``."""

    model_config = ConfigDict(extra="allow")

    models: list[CompareModelRow]
    test_dates_shared: bool
    n_test_samples: int
    experiment_id: str | None = None
    generated_at: str = Field(default_factory=lambda: datetime.now().astimezone().isoformat())


def metric_set_from_payload(payload: dict[str, Any]) -> MetricSet | None:
    """Construye :class:`MetricSet` desde un dict de metricas.

    Returns:
        MetricSet, o ``None`` si faltan las metricas obligatorias.
    """
    required = ("mae", "rmse", "mape", "direction_accuracy")
    if not all(key in payload for key in required):
        return None
    return MetricSet(
        mae=float(payload["mae"]),
        rmse=float(payload["rmse"]),
        mape=float(payload["mape"]),
        direction_accuracy=float(payload["direction_accuracy"]),
    )
