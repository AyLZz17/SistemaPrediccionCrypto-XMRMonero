"""``GET /v1/models`` - versiones de modelo registradas con su procedencia.

Seguridad (R-35): solo accesible desde la red privada; no se expone ningun
secreto ni datos de mercado. Solo metricas agregadas y digests.
"""

from __future__ import annotations

from fastapi import APIRouter, Depends, Request

from app.api.schemas import ModelInfo, ModelsResponse, metric_set_from_payload
from app.services import ModelService, get_model_service

__all__ = ["router"]

router = APIRouter(prefix="/v1", tags=["modelos"])


def _service(request: Request) -> ModelService:
    """Obtiene el servicio de modelos desde el estado de la app."""
    return request.app.state.model_service


@router.get("/models", response_model=ModelsResponse, summary="Modelos registrados")
async def list_models(
    request: Request, service: ModelService = Depends(_service)
) -> ModelsResponse:
    """Lista las versiones de modelo registradas con procedencia verificable.

    Cada item incluye ``artifact_sha256``, ``dataset_version`` y las metricas
    de test, de modo que Spring Boot puede trazar cada respuesta hasta el
    artefacto exacto que la produjo.
    """
    items: list[ModelInfo] = []
    for artifact in service.list_models():
        payload = artifact.metrics.to_dict() if artifact.metrics else {}
        items.append(
            ModelInfo(
                model_key=artifact.model_key,
                family=artifact.family,
                version=artifact.version,
                artifact_sha256=artifact.artifact_sha256,
                dataset_version=artifact.dataset_version,
                trained_at=artifact.trained_at,
                metrics=metric_set_from_payload(payload),
                n_seeds=artifact.metrics.n_seeds if artifact.metrics else 1,
                notes=artifact.notes,
            )
        )
    return ModelsResponse(items=items)


def get_model_service_dep() -> ModelService:
    """Dependencia alternativa basada en cache (util en tests)."""
    return get_model_service()
