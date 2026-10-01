"""Fabrica de la aplicacion FastAPI del servicio ML de XMR-Forecast.

Controles de seguridad aplicados (R-26, R-30, R-33, R-35):

* **HTTPS forzado**: el middleware rechaza peticiones que no llegan por TLS con
  ``400``, salvo en desarrollo **y** con ``ML_ALLOW_PLAINTEXT_DEV=true``.
  La verificacion de certificados nunca se desactiva.
* **Correlacion**: ``X-Request-Id`` se acepta de Spring Boot, se propaga a los
  logs y se devuelve en cada respuesta. Si falta, se genera un UUID.
* **Errores uniformes**: ``{"detail", "code", "request_id"}``.
* **Logs estructurados JSON** sin secretos.
"""

from __future__ import annotations

import hmac
import json
import logging
import sys
import time
import uuid
from collections.abc import Awaitable, Callable
from typing import Any

from fastapi import FastAPI, Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse
from starlette.exceptions import HTTPException as StarletteHTTPException
from starlette.middleware.base import BaseHTTPMiddleware
from starlette.types import ASGIApp

from app import __version__
from app.api.routes_health import router as health_router
from app.api.routes_metrics import router as metrics_router
from app.api.routes_models import router as models_router
from app.api.routes_predict import LEGAL_NOTICE
from app.api.routes_predict import router as predict_router
from app.config import Settings, get_settings
from app.ml.pipelines.inference import InferenceError
from app.services import (
    InvalidRequestError,
    MarketDataError,
    ModelNotFoundError,
    ModelService,
)

__all__ = [
    "InternalTokenMiddleware",
    "RequestIdMiddleware",
    "TlsEnforcementMiddleware",
    "app",
    "create_app",
]

#: Cabeceras que nunca deben quedar en los logs (defensa en profundidad).
_SENSITIVE_HEADERS = frozenset(
    {"authorization", "cookie", "x-api-key", "proxy-authorization", "x-internal-token"}
)


class StructuredFormatter(logging.Formatter):
    """Formatea los registros como JSON con ``request_id`` y ``trace_id``.

    Nunca imprime cabeceras sensibles ni cuerpos de peticion.
    """

    def format(self, record: logging.LogRecord) -> str:
        """Serializa el registro a una linea JSON."""
        payload: dict[str, Any] = {
            "ts": self.formatTime(record, "%Y-%m-%dT%H:%M:%S%z"),
            "level": record.levelname,
            "logger": record.name,
            "message": record.getMessage(),
        }
        for key in ("request_id", "trace_id", "method", "path", "status_code", "duration_ms"):
            value = getattr(record, key, None)
            if value is not None:
                payload[key] = value
        if record.exc_info:
            payload["exception"] = self.formatException(record.exc_info)
        return json.dumps(payload, ensure_ascii=False, default=str)


def configure_logging(level: str) -> None:
    """Configura el logging estructurado del proceso."""
    handler = logging.StreamHandler(sys.stdout)
    handler.setFormatter(StructuredFormatter())
    root = logging.getLogger()
    root.handlers = [handler]
    root.setLevel(level)


def _request_id(request: Request, settings: Settings) -> str:
    """Obtiene el ``request_id``: cabecera valida o UUID nuevo.

    Se rechazan valores excesivamente largos o con caracteres de control para
    evitar inyeccion en logs (R-26).
    """
    raw = request.headers.get(settings.request_id_header)
    if raw:
        cleaned = raw.strip()
        if (
            cleaned
            and len(cleaned) <= settings.max_request_id_length
            and all(char.isprintable() for char in cleaned)
        ):
            return cleaned
    return str(uuid.uuid4())


def _is_tls(request: Request) -> bool:
    """Indica si la peticion ha llegado por TLS.

    Se reconoce el protocolo indicado por el proxy de terminacion TLS mediante
    ``X-Forwarded-Proto`` o el estandar ``Forwarded``.
    """
    if getattr(request.url, "scheme", "") == "https":
        return True
    forwarded_proto = request.headers.get("x-forwarded-proto", "")
    if forwarded_proto:
        return forwarded_proto.split(",")[0].strip().lower() == "https"
    return False


class TlsEnforcementMiddleware(BaseHTTPMiddleware):
    """Rechaza peticiones HTTP plano cuando TLS es obligatorio (R-33)."""

    def __init__(self, app: ASGIApp, settings: Settings) -> None:
        super().__init__(app)
        self._settings = settings

    async def dispatch(
        self, request: Request, call_next: Callable[[Request], Awaitable[Any]]
    ) -> Any:
        """Aplica el control de TLS y delega en el resto de la cadena."""
        if self._settings.require_tls and not _is_tls(request):
            request_id = _request_id(request, self._settings)
            logger = logging.getLogger("ml-service.security")
            logger.warning(
                "Peticion sin TLS rechazada",
                extra={
                    "request_id": request_id,
                    "method": request.method,
                    "path": request.url.path,
                },
            )
            return JSONResponse(
                status_code=400,
                content={
                    "detail": (
                        "Se requiere HTTPS: esta peticion no ha llegado por TLS. "
                        "La verificacion de certificados nunca se desactiva."
                    ),
                    "code": "TLS_REQUIRED",
                    "request_id": request_id,
                },
            )
        return await call_next(request)


class InternalTokenMiddleware(BaseHTTPMiddleware):
    """Exige el secreto compartido con el backend (R-32 tras un borde TLS).

    Solo actua cuando ``internal_token`` esta configurado; en local sigue todo
    igual. ``/health`` queda exento para las sondas del orquestador. El valor
    recibido nunca se registra ni se devuelve: solo se compara en tiempo
    constante y se informa del resultado.
    """

    #: Cabecera con el secreto compartido backend <-> ML.
    TOKEN_HEADER = "X-Internal-Token"

    #: Rutas exentas (sondas sin datos sensibles).
    OPEN_PATHS = frozenset({"/health"})

    def __init__(self, app: ASGIApp, settings: Settings) -> None:
        super().__init__(app)
        self._settings = settings

    async def dispatch(
        self, request: Request, call_next: Callable[[Request], Awaitable[Any]]
    ) -> Any:
        """Rechaza con 401 lo que no traiga el secreto vigente."""
        expected = self._settings.internal_token
        if not expected or request.url.path in self.OPEN_PATHS:
            return await call_next(request)
        presented = request.headers.get(self.TOKEN_HEADER, "")
        if not presented or not hmac.compare_digest(presented, expected):
            logging.getLogger("ml-service.security").warning(
                "Llamada sin secreto interno rechazada",
                extra={
                    "request_id": _request_id(request, self._settings),
                    "method": request.method,
                    "path": request.url.path,
                },
            )
            return JSONResponse(
                status_code=401,
                content={
                    "detail": "Llamante no autorizado para el servicio ML.",
                    "code": "UNAUTHORIZED_CALLER",
                    "request_id": _request_id(request, self._settings),
                },
            )
        return await call_next(request)


class RequestIdMiddleware(BaseHTTPMiddleware):
    """Correlacion ``X-Request-Id`` en todas las peticiones y respuestas."""

    def __init__(self, app: ASGIApp, settings: Settings) -> None:
        super().__init__(app)
        self._settings = settings
        self._logger = logging.getLogger("ml-service.request")

    async def dispatch(
        self, request: Request, call_next: Callable[[Request], Awaitable[Any]]
    ) -> Any:
        """Asigna ``request.state.request_id`` y lo devuelve en la respuesta."""
        request_id = _request_id(request, self._settings)
        request.state.request_id = request_id
        trace_id = request.headers.get("X-Trace-Id") or str(uuid.uuid4())
        request.state.trace_id = trace_id

        start = time.perf_counter()
        response = await call_next(request)
        duration_ms = round((time.perf_counter() - start) * 1000, 3)

        safe_headers = {
            key: value
            for key, value in request.headers.items()
            if key.lower() not in _SENSITIVE_HEADERS
        }
        self._logger.info(
            "request_completed",
            extra={
                "request_id": request_id,
                "trace_id": trace_id,
                "method": request.method,
                "path": request.url.path,
                "status_code": response.status_code,
                "duration_ms": duration_ms,
                # Solo metadatos de cabeceras, nunca sus valores sensibles.
                "headers_present": sorted(safe_headers),
            },
        )
        response.headers[self._settings.request_id_header] = request_id
        response.headers["X-Trace-Id"] = trace_id
        return response


def _error_response(request: Request, status_code: int, detail: str, code: str) -> JSONResponse:
    """Construye la respuesta de error uniforme ``{detail, code, request_id}``."""
    request_id = getattr(request.state, "request_id", None) or str(uuid.uuid4())
    return JSONResponse(
        status_code=status_code, content={"detail": detail, "code": code, "request_id": request_id}
    )


def _register_exception_handlers(app: FastAPI) -> None:
    """Registra los manejadores que unifican el formato de error (R-35)."""

    @app.exception_handler(ModelNotFoundError)
    async def _model_not_found(request: Request, exc: ModelNotFoundError) -> JSONResponse:
        return _error_response(request, 404, str(exc), "MODEL_NOT_FOUND")

    @app.exception_handler(InvalidRequestError)
    async def _invalid_request(request: Request, exc: InvalidRequestError) -> JSONResponse:
        return _error_response(request, 400, str(exc), "INVALID_REQUEST")

    @app.exception_handler(MarketDataError)
    async def _market_data(request: Request, exc: MarketDataError) -> JSONResponse:
        return _error_response(request, 503, str(exc), "MARKET_DATA_UNAVAILABLE")

    @app.exception_handler(InferenceError)
    async def _inference(request: Request, exc: InferenceError) -> JSONResponse:
        return _error_response(request, 500, str(exc), "INFERENCE_ERROR")

    @app.exception_handler(RequestValidationError)
    async def _validation(request: Request, exc: RequestValidationError) -> JSONResponse:
        return _error_response(
            request, 422, "Entrada invalida: revisa los campos del peticion", "VALIDATION_ERROR"
        )

    @app.exception_handler(StarletteHTTPException)
    async def _http(request: Request, exc: StarletteHTTPException) -> JSONResponse:
        code = "NOT_FOUND" if exc.status_code == 404 else "HTTP_ERROR"
        return _error_response(request, exc.status_code, str(exc.detail), code)

    @app.exception_handler(Exception)
    async def _unhandled(request: Request, exc: Exception) -> JSONResponse:
        logging.getLogger("ml-service.error").exception(
            "error_no_gestionado", extra={"request_id": getattr(request.state, "request_id", "")}
        )
        # No se filtra el detalle interno al cliente (R-26).
        return _error_response(request, 500, "Error interno del servicio ML", "INTERNAL_ERROR")


def create_app(settings: Settings | None = None) -> FastAPI:
    """Crea y configura la aplicacion FastAPI.

    Args:
        settings: Configuracion a usar; si es ``None`` se obtiene del entorno.

    Returns:
        Aplicacion lista para servir con Uvicorn.

    Raises:
        ValueError: si la configuracion viola las invariantes de despliegue.
    """
    resolved = settings or get_settings()
    configure_logging(resolved.log_level)

    app = FastAPI(
        title="XMR-Forecast ML Service",
        version=__version__,
        description=LEGAL_NOTICE,
        docs_url="/docs",
        openapi_url="/openapi.json",
    )

    # El orden importa: RequestId primero para que TLS y la app vean el id.
    app.add_middleware(TlsEnforcementMiddleware, settings=resolved)
    app.add_middleware(InternalTokenMiddleware, settings=resolved)
    app.add_middleware(RequestIdMiddleware, settings=resolved)

    app.include_router(health_router)
    app.include_router(models_router)
    app.include_router(predict_router)
    app.include_router(metrics_router)

    app.state.settings = resolved
    app.state.model_service = ModelService(resolved)

    _register_exception_handlers(app)
    return app


app = create_app()
