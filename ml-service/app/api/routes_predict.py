"""``POST /v1/predict`` - prediccion de cierre y direccion.

Aviso (R-11, R-12): esto **no** es asesoria financiera. La respuesta es la
capacidad predictiva evaluada de un modelo concreto, con su incertidumbre y su
trazabilidad. No se simulan operaciones ni rentabilidades.
"""

from __future__ import annotations

from fastapi import APIRouter, Depends, Request

from app.api.schemas import PredictionResponse, PredictRequest, TraceBlock
from app.ml.pipelines.inference import PredictionRecord
from app.services import ModelService

__all__ = ["router"]

router = APIRouter(prefix="/v1", tags=["prediccion"])

#: Aviso legal obligatorio en la documentacion de la API (R-11).
LEGAL_NOTICE = (
    "XMR-Forecast no ofrece asesoria financiera y no promete rentabilidad. "
    "La respuesta es la capacidad predictiva evaluada de un modelo concreto; "
    "no simula operaciones ni backtesting de trading (R-11, R-12)."
)


def _service(request: Request) -> ModelService:
    """Obtiene el servicio de modelos desde el estado de la app."""
    return request.app.state.model_service


def _to_response(record: PredictionRecord) -> PredictionResponse:
    """Convierte un ``PredictionRecord`` al esquema de respuesta."""
    return PredictionResponse(
        model_key=record.model_key,
        version=record.version,
        target_date=record.target_date,
        predicted_close=record.predicted_close,
        predicted_direction=record.predicted_direction,
        actual_close=record.actual_close,
        confidence=record.confidence,
        trace=TraceBlock(
            dataset_version=str(record.trace.get("dataset_version", "")),
            artifact_sha256=str(record.trace.get("artifact_sha256", "")),
            config_sha256=str(record.trace.get("config_sha256", "")),
            seed=int(record.trace.get("seed", 0)),
            generated_at=str(record.trace.get("generated_at", "")),
        ),
    )


@router.post(
    "/predict",
    response_model=PredictionResponse,
    summary="Prediccion de precio y direccion",
    description=LEGAL_NOTICE,
)
async def predict(
    payload: PredictRequest, request: Request, service: ModelService = Depends(_service)
) -> PredictionResponse:
    """Predice el cierre siguiente y su direccion.

    Args:
        payload: Modelo, version, ventana de historico y simbolo.
        request: Peticion HTTP (para resolver dependencias).
        service: Servicio de modelos.

    Returns:
        PredictionResponse con prediccion, direccion, confianza y trazabilidad.

    Raises:
        ModelNotFoundError: si el modelo o la version no existen (404).
        MarketDataError: si no hay datos de mercado suficientes (503).
        InferenceError: si el artefacto falla la carga o integridad (500).
    """
    record = service.predict(
        model_key=payload.model_key,
        version=payload.version,
        lookback_days=payload.lookback_days,
        symbol=payload.symbol,
    )
    return _to_response(record)
