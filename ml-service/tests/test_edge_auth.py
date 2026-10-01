"""Modo borde TLS + secreto interno (R-32, R-33).

Cubre: el borde exige secreto configurado (fail-fast), ``/health`` sigue
abierta para las sondas, las rutas protegidas devuelven 401 sin el secreto o
con uno incorrecto, y el HTTP plano se acepta tras el borde porque TLS ya
termino fuera. Sin red externa (R-18).
"""

from __future__ import annotations

import pytest
from fastapi.testclient import TestClient

from app.config import Settings
from app.main import create_app

TOKEN = "secreto-de-prueba-para-borde"


@pytest.fixture()
def edge_settings() -> Settings:
    """Configuracion de borde: prod, HTTP interno, secreto exigido."""
    return Settings(
        environment="prod",
        behind_tls_edge=True,
        internal_token=TOKEN,
        mlflow_enabled=False,
        log_level="WARNING",
    )


@pytest.fixture()
def edge_client(edge_settings: Settings) -> TestClient:
    """Cliente de prueba sobre la app en modo borde."""
    return TestClient(create_app(edge_settings), raise_server_exceptions=False)


def test_edge_mode_without_token_fails_fast() -> None:
    """Un borde publico sin secreto no arranca: fallar aqui, no en produccion."""
    settings = Settings(environment="prod", behind_tls_edge=True, log_level="WARNING")
    with pytest.raises(ValueError, match="ML_INTERNAL_TOKEN"):
        settings.validate_runtime()


def test_health_stays_open_without_token(edge_client: TestClient) -> None:
    """Las sondas del orquestador no llevan secreto y deben pasar."""
    response = edge_client.get("/health")
    assert response.status_code == 200
    assert response.json()["status"] == "UP"


def test_protected_route_rejects_missing_token(edge_client: TestClient) -> None:
    """Sin cabecera no hay llamada: 401 con codigo estable, sin fugas."""
    response = edge_client.get("/v1/models")
    assert response.status_code == 401
    body = response.json()
    assert body["code"] == "UNAUTHORIZED_CALLER"
    assert TOKEN not in response.text


def test_protected_route_rejects_wrong_token(edge_client: TestClient) -> None:
    """Un secreto incorrecto no abre: comparacion en tiempo constante."""
    response = edge_client.get("/v1/models", headers={"X-Internal-Token": "incorrecto"})
    assert response.status_code == 401
    assert response.json()["code"] == "UNAUTHORIZED_CALLER"


def test_protected_route_accepts_valid_token(edge_client: TestClient) -> None:
    """Con el secreto vigente el middleware deja pasar (no decide el negocio)."""
    response = edge_client.get("/v1/models", headers={"X-Internal-Token": TOKEN})
    assert response.status_code != 401


def test_no_enforcement_when_token_unset() -> None:
    """En local (sin secreto) todo sigue igual: sin 401 nuevo."""
    settings = Settings(
        environment="dev",
        allow_plaintext_dev=True,
        mlflow_enabled=False,
        log_level="WARNING",
    )
    client = TestClient(create_app(settings), raise_server_exceptions=False)
    assert client.get("/v1/models").status_code != 401
