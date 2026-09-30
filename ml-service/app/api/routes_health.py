"""``GET /health`` - sonda de disponibilidad del servicio."""

from __future__ import annotations

from fastapi import APIRouter, Request

from app.api.schemas import HealthResponse

__all__ = ["router"]

router = APIRouter(tags=["salud"])


@router.get("/health", response_model=HealthResponse, summary="Estado del servicio")
async def health(request: Request) -> HealthResponse:
    """Devuelve el estado del servicio.

    No requiere autenticacion: el orquestador (Compose/Kubernetes) lo usa como
    sonda. No expone datos sensibles ni configuracion interna.
    """
    settings = request.app.state.settings
    return HealthResponse(
        status="UP",
        service=settings.service_name,
        version=settings.version,
    )
