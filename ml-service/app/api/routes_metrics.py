"""``GET /v1/metrics/compare`` - comparacion de modelos sobre el mismo test.

Todas las filas comparten las mismas fechas de test (R-05) y el campeon se
eligio por validacion, no por test (R-24).
"""

from __future__ import annotations

from typing import Annotated

from fastapi import APIRouter, Depends, Query, Request

from app.api.schemas import CompareModelRow, CompareResponse
from app.services import ModelService

__all__ = ["router"]

router = APIRouter(prefix="/v1", tags=["metricas"])


def _service(request: Request) -> ModelService:
    """Obtiene el servicio de modelos desde el estado de la app."""
    return request.app.state.model_service


@router.get(
    "/metrics/compare",
    response_model=CompareResponse,
    summary="Comparacion de modelos",
)
async def compare(
    request: Request,
    service: ModelService = Depends(_service),
    experiment_id: Annotated[str | None, Query(max_length=64)] = None,
) -> CompareResponse:
    """Compara las metricas de los modelos registrados sobre el mismo test.

    Args:
        request: Peticion HTTP.
        service: Servicio de modelos.
        experiment_id: Filtro opcional por experimento.

    Returns:
        CompareResponse con las metricas, ``n_seeds`` y desviacion tipica.

    Raises:
        ModelNotFoundError: si no hay modelos con metricas de test (404).
    """
    payload = service.compare(experiment_id)
    return CompareResponse(
        models=[CompareModelRow(**row) for row in payload["models"]],
        test_dates_shared=bool(payload["test_dates_shared"]),
        n_test_samples=int(payload["n_test_samples"]),
        experiment_id=payload.get("experiment_id"),
    )
