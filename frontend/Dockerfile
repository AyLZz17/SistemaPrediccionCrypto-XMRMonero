# syntax=docker/dockerfile:1.7
# ---------------------------------------------------------------------------
# XMR-Forecast frontend — imagen multi-etapa.
#
# Etapa 1: build determinista con Node 22 LTS (npm ci usa el lockfile, R-17).
# Etapa 2: nginx alpine sirviendo la SPA, TLS y cabeceras de seguridad (R-33).
#
# Secretos: en tiempo de construccion SOLO se pasan variables VITE_* publicas
# definidas en build-args. Ningun secreto debe llegar al bundle (R-14).
# ---------------------------------------------------------------------------

# --------------------------------------------------------------------- build
FROM node:22.14.0-alpine AS build
WORKDIR /app

# Capa de dependencias (cacheable)
COPY package.json package-lock.json ./
RUN npm ci

# Codigo fuente
COPY . .

# Valores publicos de configuracion. Ajusta con --build-arg.
# VITE_API_BASE_URL DEBE empezar por https:// (lo valida src/config/env.ts).
ARG VITE_API_BASE_URL=https://api.xmr-forecast.example
ARG VITE_REFRESH_TOKEN_MODE=cookie
ARG VITE_REQUEST_TIMEOUT_MS=20000

ENV VITE_API_BASE_URL=${VITE_API_BASE_URL} \
    VITE_REFRESH_TOKEN_MODE=${VITE_REFRESH_TOKEN_MODE} \
    VITE_REQUEST_TIMEOUT_MS=${VITE_REQUEST_TIMEOUT_MS} \
    NODE_ENV=production

# `npm run build` ejecuta `tsc --noEmit && vite build`: la imagen no se construye
# si el TypeScript no compila.
RUN npm run build \
 && rm -rf node_modules

# ------------------------------------------------------------------- runtime
FROM nginxinc/nginx-unprivileged:1.27-alpine AS runtime

# 8080 porque el usuario no-root no puede enlazar el 443 privilegiado.
# El despliegue (ingress / load balancer) terminara TLS en el 443 y redirigira
# aqui, o montara los certificados y usara una imagen con permisos.
USER root
RUN apk add --no-cache openssl \
 && mkdir -p /etc/nginx/tls /var/cache/nginx \
 && chown -R nginx:nginx /var/cache/nginx
USER nginx

COPY --chown=nginx:nginx nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build --chown=nginx:nginx /app/dist /usr/share/nginx/html

EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=3s --start-period=5s --retries=3 \
  CMD wget --no-verbose --tries=1 --spider http://127.0.0.1:8080/healthz || exit 1

STOPSIGNAL SIGQUIT
CMD ["nginx", "-g", "daemon off;"]
