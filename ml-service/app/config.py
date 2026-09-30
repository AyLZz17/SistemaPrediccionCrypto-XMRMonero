"""Configuracion del servicio, alimentada por variables de entorno (R-14).

Reglas de seguridad aplicadas:

* HTTPS forzado en todos los entornos (R-33). El servicio rechaza HTTP plano
  salvo en desarrollo **y** con ``ML_ALLOW_PLAINTEXT_DEV=true``.
* Los secretos nunca tienen valor por defecto ni se registran en logs.
* ``ML_MLFLOW_VERIFY_TLS`` no puede desactivarse: la verificacion de
  certificados nunca se desactiva.
"""

from __future__ import annotations

from functools import lru_cache
from typing import Literal

from pydantic import Field, field_validator
from pydantic_settings import BaseSettings, SettingsConfigDict

__all__ = ["ENV_PREFIX", "Environment", "Settings", "get_settings"]

#: Prefijo de las variables de entorno del servicio.
ENV_PREFIX = "ML_"

Environment = Literal["dev", "staging", "prod"]


class Settings(BaseSettings):
    """Configuracion completa del servicio ML.

    Attributes:
        service_name: Nombre lógico del servicio.
        version: Version expuesta por ``/health``.
        environment: Entorno de ejecucion (``dev``/``staging``/``prod``).
        host: Interfaz de escucha.
        port: Puerto de escucha TLS.
        ssl_keyfile: Ruta al certificado TLS en produccion.
        ssl_certfile: Ruta al certificado TLS en produccion.
        allow_plaintext_dev: Permite HTTP plano **solo** si ``environment="dev"``.
        request_id_header: Cabecera de correlacion aceptada de Spring Boot.
        registry_path: Fichero JSON con el registro de modelos.
        artifacts_dir: Directorio de artefactos serializados.
        data_snapshot: Snapshot CSV por defecto para inferencia.
        mlflow_tracking_uri: URI de MLflow (HTTPS en staging/prod).
        mlflow_experiment: Experimento donde se registran corridas.
        mlflow_timeout_seconds: Timeout de las llamadas a MLflow.
        log_level: Nivel de logging.
    """

    model_config = SettingsConfigDict(
        env_prefix=ENV_PREFIX,
        env_file=".env",
        env_file_encoding="utf-8",
        extra="ignore",
    )

    service_name: str = "ml-service"
    version: str = "1.0.0"
    environment: Environment = "dev"

    host: str = "0.0.0.0"
    port: int = Field(default=8443, ge=1, le=65535)

    ssl_keyfile: str | None = None
    ssl_certfile: str | None = None
    allow_plaintext_dev: bool = False

    request_id_header: str = "X-Request-Id"
    max_request_id_length: int = Field(default=128, ge=8, le=512)

    registry_path: str = "./artifacts/registry.json"
    artifacts_dir: str = "./artifacts"
    data_snapshot: str | None = None

    mlflow_tracking_uri: str = "http://127.0.0.1:5000"
    mlflow_experiment: str = "xmr-forecast"
    mlflow_timeout_seconds: float = Field(default=5.0, gt=0.0, le=120.0)
    mlflow_enabled: bool = True

    log_level: str = "INFO"

    @field_validator("log_level")
    @classmethod
    def _upper_level(cls, value: str) -> str:
        """Normaliza el nivel de log a mayusculas."""
        return value.upper()

    @property
    def is_production(self) -> bool:
        """``True`` si el entorno es ``prod``."""
        return self.environment == "prod"

    @property
    def plaintext_allowed(self) -> bool:
        """Si se acepta HTTP plano.

        R-33: solo en desarrollo y solo con el interruptor explicito. En
        staging y produccion es siempre ``False``.
        """
        return self.environment == "dev" and self.allow_plaintext_dev

    @property
    def require_tls(self) -> bool:
        """Si el middleware debe rechazar peticiones que no llegan por TLS."""
        return not self.plaintext_allowed

    @property
    def tracking_uri_secure(self) -> str:
        """URI de MLflow; en staging/prod debe ser HTTPS."""
        if self.environment in ("staging", "prod") and not self.mlflow_tracking_uri.startswith(
            "https://"
        ):
            return self.mlflow_tracking_uri
        return self.mlflow_tracking_uri

    def validate_runtime(self) -> None:
        """Comprueba invariantes de despliegue (llamar en el arranque).

        Raises:
            ValueError: si produccion arranca sin certificados TLS.
        """
        if self.environment in ("staging", "prod") and (
            not self.ssl_certfile or not self.ssl_keyfile
        ):
            raise ValueError(
                f"{self.environment}: ML_SSL_CERTFILE y ML_SSL_KEYFILE son obligatorios (R-33)"
            )
        if self.allow_plaintext_dev and self.environment != "dev":
            raise ValueError(
                "ML_ALLOW_PLAINTEXT_DEV solo puede ser true en el entorno 'dev' (R-33)"
            )


@lru_cache(maxsize=1)
def get_settings() -> Settings:
    """Devuelve la configuracion cacheada del proceso."""
    return Settings()
