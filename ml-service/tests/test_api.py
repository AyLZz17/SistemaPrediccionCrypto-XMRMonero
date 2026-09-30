"""Pruebas de la API con ``TestClient`` (sin red externa, R-18).

Cubre: salud, listado de modelos, prediccion, comparacion, formato uniforme de
errores, propagacion de ``X-Request-Id`` y rechazo de HTTP plano (R-33).
"""

from __future__ import annotations

from pathlib import Path

import pytest
from fastapi.testclient import TestClient

from app.config import Settings
from app.main import create_app
from app.ml.pipelines.train import train_all


@pytest.fixture(scope="module")
def trained_env(tmp_path_factory: pytest.TempPathFactory, synthetic_ohlcv) -> dict:
    """Entrena modelos sinteticos y devuelve rutas y snapshot."""
    root = tmp_path_factory.mktemp("api")
    snapshot = root / "xmr_usd.csv"
    frame = synthetic_ohlcv.iloc[:400].copy()
    frame.index.name = "date"
    frame.to_csv(snapshot)

    artifacts = root / "artifacts"
    train_all(
        snapshot,
        output_dir=artifacts,
        experiment_id="exp-test",
        models=("moving_average", "linear_regression"),
        window=30,
    )
    return {
        "snapshot": snapshot,
        "artifacts": artifacts,
        "registry": artifacts / "registry.json",
    }


@pytest.fixture()
def settings(trained_env: dict) -> Settings:
    """Configuracion de test: datos presentes, plaintext permitido."""
    return Settings(
        environment="dev",
        allow_plaintext_dev=True,
        data_snapshot=str(trained_env["snapshot"]),
        registry_path=str(trained_env["registry"]),
        artifacts_dir=str(trained_env["artifacts"]),
        mlflow_enabled=False,
        log_level="WARNING",
    )


@pytest.fixture()
def client(settings: Settings) -> TestClient:
    """Cliente de prueba sobre la app configurada."""
    return TestClient(create_app(settings), raise_server_exceptions=False)


def _base_settings(trained_env: dict, **overrides) -> Settings:
    """Configuracion de test compartida por varios casos."""
    values = {
        "environment": "dev",
        "allow_plaintext_dev": True,
        "data_snapshot": str(trained_env["snapshot"]),
        "registry_path": str(trained_env["registry"]),
        "artifacts_dir": str(trained_env["artifacts"]),
        "mlflow_enabled": False,
        "log_level": "WARNING",
    }
    values.update(overrides)
    return Settings(**values)  # type: ignore[arg-type]


class TestHealth:
    """``GET /health``."""

    def test_health_returns_exact_contract(self, client: TestClient) -> None:
        """El cuerpo coincide exactamente con el contrato de Spring Boot."""
        response = client.get("/health")
        assert response.status_code == 200
        assert response.json() == {"status": "UP", "service": "ml-service", "version": "1.0.0"}

    def test_health_needs_no_auth(self, client: TestClient) -> None:
        """La sonda de salud es publica para el orquestador."""
        response = client.get("/health", headers={})
        assert response.status_code == 200

    def test_health_echoes_request_id(self, client: TestClient) -> None:
        """La cabecera de correlacion se devuelve siempre."""
        request_id = "test-req-0001"
        response = client.get("/health", headers={"X-Request-Id": request_id})
        assert response.headers["X-Request-Id"] == request_id


class TestModels:
    """``GET /v1/models``."""

    def test_models_lists_registered_versions(self, client: TestClient) -> None:
        """Devuelve las versiones con procedencia completa."""
        response = client.get("/v1/models")
        assert response.status_code == 200

        payload = response.json()
        assert set(payload) == {"items"}
        assert len(payload["items"]) >= 2

        keys = {item["model_key"] for item in payload["items"]}
        assert "moving_average" in keys
        assert "linear_regression" in keys

        for item in payload["items"]:
            assert set(item) >= {
                "model_key",
                "family",
                "version",
                "artifact_sha256",
                "dataset_version",
                "trained_at",
                "metrics",
            }
            assert len(item["artifact_sha256"]) == 64
            assert item["dataset_version"].startswith("sha256:")
            metrics = item["metrics"]
            assert set(metrics) == {"mae", "rmse", "mape", "direction_accuracy"}
            assert metrics["mae"] > 0.0
            assert 0.0 <= metrics["direction_accuracy"] <= 1.0


class TestPredict:
    """``POST /v1/predict``."""

    def test_predict_returns_exact_contract(self, client: TestClient) -> None:
        """El cuerpo coincide con el contrato documentado."""
        response = client.post(
            "/v1/predict",
            json={
                "model_key": "moving_average",
                "version": "v1",
                "lookback_days": 30,
                "symbol": "XMR-USD",
            },
        )
        assert response.status_code == 200

        payload = response.json()
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
        assert payload["model_key"] == "moving_average"
        assert payload["version"] == "v1"
        assert payload["predicted_close"] > 0.0
        assert payload["predicted_direction"] in {"UP", "DOWN", "FLAT"}
        assert payload["actual_close"] is None
        assert 0.5 <= payload["confidence"] <= 0.99

        trace = payload["trace"]
        assert set(trace) == {
            "dataset_version",
            "artifact_sha256",
            "config_sha256",
            "seed",
            "generated_at",
        }
        assert len(trace["artifact_sha256"]) == 64
        assert len(trace["config_sha256"]) == 64

    def test_predict_defaults_version_to_latest(self, client: TestClient) -> None:
        """Si no se indica version, se usa la mas reciente."""
        response = client.post(
            "/v1/predict", json={"model_key": "moving_average", "lookback_days": 30}
        )
        assert response.status_code == 200
        assert response.json()["version"] == "v1"

    def test_predict_normalizes_symbol(self, client: TestClient) -> None:
        """El simbolo se normaliza a mayusculas."""
        response = client.post(
            "/v1/predict", json={"model_key": "moving_average", "symbol": "xmr-usd"}
        )
        assert response.status_code == 200

    def test_predict_unknown_model_returns_404(self, client: TestClient) -> None:
        """Un modelo inexistente da 404 con el formato de error uniforme."""
        response = client.post("/v1/predict", json={"model_key": "no_existe"})
        assert response.status_code == 404

        payload = response.json()
        assert set(payload) == {"detail", "code", "request_id"}
        assert payload["code"] == "MODEL_NOT_FOUND"
        assert "no_existe" in payload["detail"]
        assert payload["request_id"]

    def test_predict_unknown_version_returns_404(self, client: TestClient) -> None:
        """Una version inexistente tambien da MODEL_NOT_FOUND."""
        response = client.post(
            "/v1/predict", json={"model_key": "moving_average", "version": "v99"}
        )
        assert response.status_code == 404
        assert response.json()["code"] == "MODEL_NOT_FOUND"

    @pytest.mark.parametrize(
        "body",
        [
            {},  # sin model_key
            {"model_key": ""},  # model_key vacio
            {"model_key": "moving_average", "lookback_days": 0},  # fuera de rango
            {"model_key": "moving_average", "lookback_days": 10_000},  # demasiado
            {"model_key": "moving_average", "symbol": "X MR"},  # simbolo invalido
            {"model_key": "moving_average", "campo_extra": 1},  # campo no permitido
        ],
        ids=[
            "sin-model_key",
            "model_key-vacio",
            "lookback-0",
            "lookback-grande",
            "simbolo",
            "extra",
        ],
    )
    def test_predict_validation_errors(self, client: TestClient, body: dict) -> None:
        """Entradas invalidas dan 422 con el formato de error uniforme."""
        response = client.post("/v1/predict", json=body)
        assert response.status_code == 422

        payload = response.json()
        assert set(payload) == {"detail", "code", "request_id"}
        assert payload["code"] == "VALIDATION_ERROR"


class TestCompare:
    """``GET /v1/metrics/compare``."""

    def test_compare_returns_metrics_table(self, client: TestClient) -> None:
        """Compara los modelos registrados sobre el mismo test (R-05)."""
        response = client.get("/v1/metrics/compare")
        assert response.status_code == 200

        payload = response.json()
        assert set(payload) >= {"models", "test_dates_shared", "n_test_samples"}
        assert payload["test_dates_shared"] is True
        assert payload["n_test_samples"] > 0

        assert len(payload["models"]) >= 2
        for row in payload["models"]:
            assert set(row) >= {
                "model_key",
                "family",
                "n_seeds",
                "mae",
                "rmse",
                "mape",
                "direction_accuracy",
            }
            assert row["mae"] > 0.0

        # Ordenado por MAE ascendente.
        maes = [row["mae"] for row in payload["models"]]
        assert maes == sorted(maes)

    def test_compare_accepts_experiment_filter(self, client: TestClient) -> None:
        """El filtro por experimento no rompe el contrato."""
        response = client.get("/v1/metrics/compare", params={"experiment_id": "exp-test"})
        assert response.status_code == 200
        assert response.json()["experiment_id"] == "exp-test"

    def test_compare_rejects_long_experiment_id(self, client: TestClient) -> None:
        """Un identificador desmedido se rechaza por validacion."""
        response = client.get("/v1/metrics/compare", params={"experiment_id": "x" * 200})
        assert response.status_code == 422


class TestRequestId:
    """Correlacion ``X-Request-Id``."""

    def test_client_request_id_is_echoed(self, client: TestClient) -> None:
        """El id que envia Spring Boot se devuelve intacto."""
        request_id = "spring-boot-abc-123"
        response = client.get("/health", headers={"X-Request-Id": request_id})
        assert response.headers["X-Request-Id"] == request_id

    def test_generated_request_id_when_absent(self, client: TestClient) -> None:
        """Si falta la cabecera, se genera un UUID."""
        response = client.get("/health")
        assert response.headers["X-Request-Id"]
        assert len(response.headers["X-Request-Id"]) >= 8

    def test_request_id_present_in_errors(self, client: TestClient) -> None:
        """Los errores tambien llevan el request_id."""
        response = client.post(
            "/v1/predict", json={"model_key": "no_existe"}, headers={"X-Request-Id": "req-9"}
        )
        assert response.status_code == 404
        assert response.json()["request_id"] == "req-9"
        assert response.headers["X-Request-Id"] == "req-9"

    def test_trace_id_header_is_always_present(self, client: TestClient) -> None:
        """``X-Trace-Id`` se genera si no viene."""
        response = client.get("/health")
        assert response.headers.get("X-Trace-Id")

    def test_overlong_request_id_is_replaced(self, client: TestClient) -> None:
        """Un id desmedido no se refleja tal cual en la respuesta."""
        response = client.get("/health", headers={"X-Request-Id": "z" * 500})
        assert response.headers["X-Request-Id"] != "z" * 500


class TestTlsEnforcement:
    """R-33: HTTPS forzado."""

    def test_plaintext_rejected_when_tls_required(self, trained_env: dict) -> None:
        """En staging, HTTP plano se rechaza con 400 TLS_REQUIRED."""
        settings = Settings(
            environment="staging",
            allow_plaintext_dev=False,
            data_snapshot=str(trained_env["snapshot"]),
            registry_path=str(trained_env["registry"]),
            artifacts_dir=str(trained_env["artifacts"]),
            mlflow_enabled=False,
            log_level="WARNING",
        )
        client = TestClient(create_app(settings), raise_server_exceptions=False)

        response = client.get("/health")
        assert response.status_code == 400
        payload = response.json()
        assert set(payload) == {"detail", "code", "request_id"}
        assert payload["code"] == "TLS_REQUIRED"

    def test_plaintext_allowed_only_in_dev_with_flag(self, trained_env: dict) -> None:
        """Sin el interruptor, ni siquiera en dev se acepta HTTP plano."""
        settings = Settings(
            environment="dev",
            allow_plaintext_dev=False,
            data_snapshot=str(trained_env["snapshot"]),
            registry_path=str(trained_env["registry"]),
            artifacts_dir=str(trained_env["artifacts"]),
            mlflow_enabled=False,
            log_level="WARNING",
        )
        client = TestClient(create_app(settings), raise_server_exceptions=False)
        assert client.get("/health").status_code == 400

    def test_https_request_passes_tls_check(self, trained_env: dict) -> None:
        """Una peticion marcada como https (via proxy) se acepta."""
        settings = Settings(
            environment="staging",
            allow_plaintext_dev=False,
            data_snapshot=str(trained_env["snapshot"]),
            registry_path=str(trained_env["registry"]),
            artifacts_dir=str(trained_env["artifacts"]),
            mlflow_enabled=False,
            log_level="WARNING",
        )
        client = TestClient(create_app(settings), raise_server_exceptions=False)

        response = client.get("/health", headers={"X-Forwarded-Proto": "https"})
        assert response.status_code == 200
        assert response.json()["status"] == "UP"

    def test_plaintext_flag_rejected_outside_dev(self, trained_env: dict) -> None:
        """Activar plaintext fuera de dev es un error de configuracion."""
        settings = Settings(
            environment="prod",
            allow_plaintext_dev=True,
            ssl_certfile="/certs/cert.pem",
            ssl_keyfile="/certs/key.pem",
            registry_path=str(trained_env["registry"]),
            artifacts_dir=str(trained_env["artifacts"]),
        )
        with pytest.raises(ValueError, match="solo puede ser true en el entorno"):
            settings.validate_runtime()

    def test_production_requires_certificates(self, trained_env: dict) -> None:
        """Produccion sin certificados TLS no arranca (R-33)."""
        settings = Settings(environment="prod", registry_path=str(trained_env["registry"]))
        with pytest.raises(ValueError, match="ML_SSL_CERTFILE"):
            settings.validate_runtime()

    def test_production_with_certificates_passes(self, trained_env: dict) -> None:
        """Produccion con los dos certificados satisface la invariante."""
        settings = Settings(
            environment="prod",
            ssl_certfile="/certs/cert.pem",
            ssl_keyfile="/certs/key.pem",
            registry_path=str(trained_env["registry"]),
            artifacts_dir=str(trained_env["artifacts"]),
        )
        settings.validate_runtime()
        assert settings.require_tls is True


class TestErrorShapes:
    """Formato uniforme de errores."""

    def test_unknown_route_returns_structured_error(self, client: TestClient) -> None:
        """Una ruta inexistente devuelve el mismo formato de error."""
        response = client.get("/v1/no-existe")
        assert response.status_code == 404

        payload = response.json()
        assert set(payload) == {"detail", "code", "request_id"}
        assert payload["code"] == "NOT_FOUND"

    def test_missing_market_data_returns_503(self, trained_env: dict) -> None:
        """Sin snapshot configurado, la prediccion degrada a 503."""
        settings = Settings(
            environment="dev",
            allow_plaintext_dev=True,
            data_snapshot=None,
            registry_path=str(trained_env["registry"]),
            artifacts_dir=str(trained_env["artifacts"]),
            mlflow_enabled=False,
            log_level="WARNING",
        )
        client = TestClient(create_app(settings), raise_server_exceptions=False)

        response = client.post("/v1/predict", json={"model_key": "moving_average"})
        assert response.status_code == 503
        assert response.json()["code"] == "MARKET_DATA_UNAVAILABLE"

    def test_window_mismatch_returns_400(self, trained_env: dict) -> None:
        """Pedir una ventana distinta de la del modelo da 400 INVALID_REQUEST.

        Un modelo tiene una geometria de entrada fija; aceptarla en silencio
        produciria una prediccion invalida.
        """
        client = TestClient(create_app(_base_settings(trained_env)), raise_server_exceptions=False)

        response = client.post(
            "/v1/predict", json={"model_key": "moving_average", "lookback_days": 60}
        )
        assert response.status_code == 400
        payload = response.json()
        assert payload["code"] == "INVALID_REQUEST"
        assert "lookback_days" in payload["detail"]

    def test_market_data_too_short_returns_503(
        self, tmp_path: Path, synthetic_ohlcv, trained_env: dict
    ) -> None:
        """Con menos historico que la ventana del modelo, la prediccion da 503."""
        short = tmp_path / "short.csv"
        frame = synthetic_ohlcv.iloc[:20].copy()
        frame.index.name = "date"
        frame.to_csv(short)

        settings = _base_settings(trained_env, data_snapshot=str(short))
        client = TestClient(create_app(settings), raise_server_exceptions=False)

        response = client.post(
            "/v1/predict", json={"model_key": "moving_average", "lookback_days": 30}
        )
        assert response.status_code == 503
        assert response.json()["code"] == "MARKET_DATA_UNAVAILABLE"


class TestOpenApi:
    """Documentacion de la API."""

    def test_openapi_is_available(self, client: TestClient) -> None:
        """El esquema OpenAPI se genera sin errores."""
        response = client.get("/openapi.json")
        assert response.status_code == 200

        spec = response.json()
        assert "/health" in spec["paths"]
        assert "/v1/models" in spec["paths"]
        assert "/v1/predict" in spec["paths"]
        assert "/v1/metrics/compare" in spec["paths"]

    def test_legal_notice_is_published(self, client: TestClient) -> None:
        """El aviso legal aparece en la documentacion (R-11)."""
        response = client.get("/openapi.json")
        spec = response.json()
        text = str(spec).lower()
        assert "asesoria financiera" in text or "asesor" in text
        assert "rentabilidad" in text


class TestConfig:
    """Ajustes de configuracion."""

    def test_env_prefix_is_ml(self) -> None:
        """Las variables de entorno llevan el prefijo ``ML_`` (R-14)."""
        assert Settings.model_config["env_prefix"] == "ML_"

    def test_no_secret_defaults(self) -> None:
        """No hay secretos con valor por defecto (R-14)."""
        fields = Settings.model_fields
        assert "password" not in fields
        assert "secret" not in fields
        assert "token" not in fields

    def test_tls_switch_depends_on_env_and_flag(self) -> None:
        """``require_tls`` es la combinacion de entorno e interruptor."""
        dev_on = Settings(environment="dev", allow_plaintext_dev=True)
        assert dev_on.plaintext_allowed is True
        assert dev_on.require_tls is False

        dev_off = Settings(environment="dev", allow_plaintext_dev=False)
        assert dev_off.require_tls is True

        prod = Settings(
            environment="prod",
            allow_plaintext_dev=False,
            ssl_certfile="c",
            ssl_keyfile="k",
        )
        assert prod.require_tls is True

    def test_artifact_dir_default_is_local(self, trained_env: dict) -> None:
        """El directorio de artefactos por defecto es relativo al servicio."""
        settings = Settings(registry_path=str(Path("x.json")))
        assert settings.artifacts_dir == "./artifacts"


class TestMlflowClient:
    """El cliente MLflow degrada sin romper el servicio."""

    def test_unreachable_mlflow_does_not_raise(self, trained_env: dict) -> None:
        """Un MLflow inalcanzable se registra pero no propaga la excepcion (R-28)."""
        from app.clients.mlflow_client import MlflowClient

        settings = Settings(
            environment="dev",
            mlflow_enabled=True,
            mlflow_tracking_uri="http://127.0.0.1:1/",
            mlflow_timeout_seconds=0.5,
            registry_path=str(trained_env["registry"]),
        )
        client = MlflowClient(settings)

        result = client.log_run("exp-test", {"seed": 1}, {"mae": 0.5})
        assert result.tracked is False
        assert result.degraded_reason

        status = client.ping()
        assert status.available is False
        assert status.detail

    def test_disabled_mlflow_is_reported(self, trained_env: dict) -> None:
        """Con tracking desactivado no se intenta conectar."""
        from app.clients.mlflow_client import MlflowClient

        client = MlflowClient(Settings(mlflow_enabled=False))
        result = client.log_run("exp", {"seed": 1}, {"mae": 1.0})
        assert result.tracked is False
        assert "desactivado" in result.degraded_reason
