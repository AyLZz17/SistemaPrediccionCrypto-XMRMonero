# =====================================================================
# XMR-Forecast :: servicio de Machine Learning
#
# R-32: este servicio NO es publico. Solo Spring Boot lo consume por HTTPS
#        dentro de la red privada de Docker.
# R-13: el paquete `app/ml` es puro (sin FastAPI/Pydantic/SQLAlchemy) y se
#        importa desde la capa HTTP, nunca al reves.
# R-33: HTTPS obligatorio. Uvicorn arranca con --ssl-certfile/--ssl-keyfile y
#        el middleware rechaza peticiones en claro.
#
# TensorFlow es opcional: sin el, LSTM y GRU no estan disponibles y el
# servicio responde con un error claro, pero el resto del sistema funciona.
# =====================================================================
FROM python:3.12-slim AS base

ENV PYTHONUNBUFFERED=1 \
    PYTHONDONTWRITEBYTECODE=1 \
    PIP_NO_CACHE_DIR=1 \
    PIP_DISABLE_PIP_VERSION_CHECK=1

WORKDIR /app

# Dependencias del sistema: openssl para TLS y curl para el healthcheck.
RUN set -eux; \
    apt-get update; \
    apt-get install -y --no-install-recommends openssl curl ca-certificates; \
    rm -rf /var/lib/apt/lists/*

# ---- capa de dependencias (se reaprovecha si no cambian los requisitos)
# Contexto RAIZ del repo (igual que el backend): Render construye siempre
# desde la raiz, asi que las rutas van con prefijo `ml-service/`.
COPY ml-service/requirements.txt ./
RUN set -eux; \
    pip install --no-cache-dir -r requirements.txt

# ---- codigo de aplicacion
COPY ml-service/app ./app

# ---- usuario sin privilegios
RUN groupadd --system --gid 1001 ml \
 && useradd --system --uid 1001 --gid ml --create-home --shell /usr/sbin/nologin ml \
 && mkdir -p /app/artifacts /certs \
 && chown -R ml:ml /app /certs

USER ml

# Certificados TLS montados en /certs (solo lectura).

ENV ML_SSL_CERTFILE=/certs/ml-service/cert.pem \
    ML_SSL_KEYFILE=/certs/ml-service/key.pem \
    ML_SSL_CAFILE=/certs/ca/ca.crt \
    ML_ALLOW_PLAINTEXT_DEV=false \
    ML_ARTIFACTS_DIR=/app/artifacts

# El healthcheck valida TLS contra la CA propia del entorno.
HEALTHCHECK --interval=30s --timeout=8s --start-period=60s --retries=3 \
  CMD curl --fail --silent --show-error \
      --cacert "${ML_SSL_CAFILE}" "https://127.0.0.1:8443/health" || exit 1

EXPOSE 8443

# uvicorn con TLS. El puerto HTTP plano no se abre en ningun momento (R-33).
# Puerto inyectado por el orquestador ($PORT en Render, 8443 en local).
# Tras un borde TLS (ML_BEHIND_TLS_EDGE=true) se sirve HTTP interno: el borde
# ya termino TLS y el secreto X-Internal-Token autentica al llamante.
ENTRYPOINT ["sh", "-c", "PORT=\"${PORT:-8443}\"; if [ \"${ML_BEHIND_TLS_EDGE:-false}\" = \"true\" ]; then exec uvicorn app.main:app --host 0.0.0.0 --port \"$PORT\" --proxy-headers --forwarded-allow-ips '*' --workers 1; else exec uvicorn app.main:app --host 0.0.0.0 --port \"$PORT\" --ssl-keyfile /certs/ml-service/key.pem --ssl-certfile /certs/ml-service/cert.pem --proxy-headers --forwarded-allow-ips '*' --workers 1; fi"]