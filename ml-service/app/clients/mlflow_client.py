"""Cliente MLflow por HTTPS, tolerante a MLflow inalcanzable (R-27, R-28).

Reglas:

* La verificacion de certificados TLS **nunca** se desactiva.
* Si MLflow no responde, el servicio **degrada**: registra el fallo y continua
  sirviendo peticiones. Nunca revienta una prediccion por un fallo de tracking.
* No se registran secretos ni datos crudos de mercado.
"""

from __future__ import annotations

import logging
import os
from dataclasses import dataclass, field
from functools import lru_cache
from typing import Any

from app.config import Settings

__all__ = ["MlflowClient", "MlflowStatus", "TrackingResult"]

logger = logging.getLogger(__name__)


@dataclass(frozen=True)
class MlflowStatus:
    """Ultimo estado conocido de la conexion con MLflow."""

    enabled: bool
    available: bool
    tracking_uri: str
    detail: str = ""

    def to_dict(self) -> dict[str, Any]:
        """Representacion serializable para el endpoint de estado."""
        return {
            "enabled": self.enabled,
            "available": self.available,
            "tracking_uri": self.tracking_uri,
            "detail": self.detail,
        }


@dataclass(frozen=True)
class TrackingResult:
    """Resultado de intentar registrar una corrida en MLflow."""

    tracked: bool
    run_id: str | None = None
    degraded_reason: str = ""
    params: dict[str, Any] = field(default_factory=dict)


class MlflowClient:
    """Envoltorio fino sobre ``mlflow.skinny``.

    Args:
        settings: Configuracion del servicio.
    """

    def __init__(self, settings: Settings) -> None:
        self._settings = settings
        self._last_status = MlflowStatus(
            enabled=settings.mlflow_enabled,
            available=False,
            tracking_uri=settings.mlflow_tracking_uri,
            detail="aun no consultado",
        )

    @property
    def status(self) -> MlflowStatus:
        """Ultimo estado conocido de MLflow."""
        return self._last_status

    def _update(self, available: bool, detail: str) -> None:
        self._last_status = MlflowStatus(
            enabled=self._settings.mlflow_enabled,
            available=available,
            tracking_uri=self._settings.mlflow_tracking_uri,
            detail=detail,
        )

    @staticmethod
    @lru_cache(maxsize=1)
    def _import() -> Any:
        """Import perezoso y **cacheado** de la API de tracking de MLflow.

        Importar ``mlflow`` es caro (varios segundos), asi que el resultado se
        cachea a nivel de clase: solo se paga una vez por proceso.

        Returns:
            El paquete ``mlflow`` o ``None`` si no esta disponible.
        """
        try:
            import mlflow
        except ImportError:
            logger.warning("mlflow no esta instalado; el tracking queda desactivado")
            return None
        if not hasattr(mlflow, "set_tracking_uri"):  # pragma: no cover - defensivo
            logger.warning("mlflow instalado sin API de tracking utilizable")
            return None
        # MLflow no expone un timeout global por variable de entorno, pero su
        # cliente HTTPunderlying usa `requests`, que sí respeta estas variables.
        # Sin ellas, un host inalcanzable bloquea el hilo durante minutos.
        os.environ.setdefault("MLFLOW_HTTP_REQUEST_TIMEOUT", "5")
        os.environ.setdefault("MLFLOW_HTTP_REQUEST_MAX_RETRIES", "1")
        os.environ.setdefault("MLFLOW_HTTP_REQUEST_BACKOFF_FACTOR", "0.2")
        return mlflow

    def log_run(
        self, experiment_id: str, params: dict[str, Any], metrics: dict[str, float]
    ) -> TrackingResult:
        """Registra una corrida. Degrada con log si MLflow no esta disponible.

        Args:
            experiment_id: Nombre del experimento.
            params: Parametros de la corrida (sin secretos ni datos crudos).
            metrics: Metricas de la corrida.

        Returns:
            TrackingResult con ``tracked=False`` si no se pudo registrar.
        """
        if not self._settings.mlflow_enabled:
            self._update(available=False, detail="tracking desactivado por configuracion")
            return TrackingResult(tracked=False, degraded_reason="tracking desactivado")

        mlflow = self._import()
        if mlflow is None:
            self._update(available=False, detail="mlflow no instalado")
            return TrackingResult(tracked=False, degraded_reason="mlflow no instalado")

        try:
            # Verificacion de TLS siempre activa: nunca se pasan
            # `verify=False` ni `insecure_tls=True` (R-27, R-33).
            mlflow.set_tracking_uri(self._settings.mlflow_tracking_uri)
            mlflow.set_experiment(experiment_id)
            with mlflow.start_run(run_name=f"{experiment_id}-corrida"):
                mlflow.log_params(dict(params))
                mlflow.log_metrics(
                    {k: float(v) for k, v in metrics.items()}, synchronous=True
                )
                run = mlflow.active_run()
                run_id = run.info.run_id if run is not None else None
        except Exception as exc:
            detail = f"{type(exc).__name__}: {exc}"
            logger.warning("MLflow inalcanzable, se continua en modo degradado: %s", detail)
            self._update(available=False, detail=detail)
            return TrackingResult(tracked=False, degraded_reason=detail, params=dict(params))

        self._update(available=True, detail="ok")
        return TrackingResult(tracked=True, run_id=run_id, params=dict(params))

    def ping(self) -> MlflowStatus:
        """Comprueba si MLflow responde, sin lanzar excepcion."""
        if not self._settings.mlflow_enabled:
            self._update(available=False, detail="tracking desactivado por configuracion")
            return self._last_status

        mlflow = self._import()
        if mlflow is None:
            self._update(available=False, detail="mlflow no instalado")
            return self._last_status

        try:
            mlflow.set_tracking_uri(self._settings.mlflow_tracking_uri)
            mlflow.search_experiments(max_results=1)
        except Exception as exc:
            detail = f"{type(exc).__name__}: {exc}"
            logger.info("MLflow no disponible: %s", detail)
            self._update(available=False, detail=detail)
            return self._last_status

        self._update(available=True, detail="ok")
        return self._last_status
