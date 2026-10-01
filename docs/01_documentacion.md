# XMR-Forecast -- Documentacion del proyecto

> **Aviso legal.** Esta aplicacion proporciona analisis predictivo de series de tiempo. **No constituye asesoria financiera, no promete rentabilidad y no simula operaciones de inversion.**

**Version:** 4.0 - **Fecha:** 2026-09-30 - **Documentos fuente:** `Propuesta_Monero_IEEE.docx`, `Fase1_Proyecto7.docx`

---

## 1. Resumen

XMR-Forecast es una aplicacion web comercial que permite predecir el **precio de cierre del dia siguiente** de Monero (XMR) o la **direccion** del precio (sube/baja). Compara una red **LSTM** (GRU como alternativa) con **media movil, regresion lineal y ARIMA**.

**Arquitectura v3:** Monolito modular **Spring Boot 3.2** (Java 21) + servicio especializado **FastAPI-ML** (Python 3.11) + **React 18** + **PostgreSQL 15** + **Redis 7** + **MLflow 2.8**. **HTTPS forzado** en todos los entornos.

---

## 2. Arquitectura del sistema

### 2.1 Componentes principales

| Componente | Tecnologia | Responsabilidad |
|------------|------------|-----------------|
| **Frontend** | React 18 + TypeScript + Vite | Interfaz de usuario, visualizacion |
| **Backend principal** | Spring Boot 3.2 (Java 21) | API REST, autenticacion, autorizacion, persistencia, orquestacion, consumidor de la cola de trabajos |
| **Servicio ML** | FastAPI (Python 3.11) | **Solo inferencia y consulta de metricas** por HTTP; el entrenamiento es un camino de CLI |
| **Base de datos** | PostgreSQL 15 | Datos, experimentos, predicciones, usuarios (22 tablas, migraciones Flyway `V1`..`V5`) |
| **Cache/Colas** | Redis 7 | Cache con TTL; la cola de trabajos vive en la tabla `jobs` de PostgreSQL |
| **Tracking ML** | MLflow 2.8 | Tracking de experimentos y artefactos |

### 2.2 Flujo de comunicacion

```
Frontend (React) --HTTPS :3000--> Spring Boot (API REST /api/v1) --HTTPS :8443--> FastAPI-ML (/v1)
                                                              |                    |
                                                              v                    v
                                                  PostgreSQL 15 / Redis 7    MLflow 2.8
```

**Reglas de comunicacion:**
- El frontend **solo** se comunica con Spring Boot mediante HTTPS.
- Spring Boot **solo** se comunica con FastAPI-ML mediante HTTPS/TLS en red interna, con cliente tipado, timeout, reintentos limitados y correlacion de solicitudes (R-32).
- PostgreSQL, Redis, MLflow y FastAPI-ML **no** son accesibles desde el navegador.
- Todas las conexiones entre servicios utilizan HTTPS/TLS.
- El servicio ML **no administra el esquema de PostgreSQL**: las migraciones son solo Flyway (R-34).

### 2.3 Justificacion del monolito modular

Se adopta un **monolito modular Spring Boot** en lugar de microservicios completos porque:

1. **Dominio acotado:** El dominio inicial (prediccion de XMR) esta bien definido.
2. **Complejidad operativa reducida:** Un solo despliegue, un solo pipeline CI/CD.
3. **Reglas compartidas:** Los modulos comparten reglas de negocio, seguridad y persistencia.
4. **Facilidad de pruebas:** Un monolito modular facilita pruebas de integracion.
5. **Consistencia:** Un solo proceso garantiza consistencia transaccional.

**Condiciones para separar servicios en el futuro:** necesidad demostrada de escalado independiente, limites de dominio estables, requisitos de disponibilidad diferentes, despliegues desacoplados o carga ML que afecte al backend.

---

## 3. API

### 3.1 Spring Boot (`/api/v1`)

**Inventario real: 13 controladores y 48 rutas de metodo.** Todas exigen `Authorization: Bearer <JWT>` salvo las marcadas **PUBLICA**. Los identificadores que aparecen en las respuestas y en las variables de ruta son **cadenas opacas** (`common/Ids.java`), no numeros: el cliente no debe asumir que son enteros.

Roles: `VIEWER` (cualquier usuario autenticado), `ANALYST` (analista o administrador), `ADMIN` (solo administrador).

#### 3.1.1 Meta

| Metodo | Ruta | Rol | Descripcion |
|--------|------|-----|-------------|
| GET | `/api/v1/meta/disclaimer` | **PUBLICA** | Aviso legal: no es asesoria financiera, no promete rentabilidad y no simula operaciones (R-11) |
| GET | `/api/v1/meta/legal` | **PUBLICA** | Documentos legales publicados con su **version vigente**, fecha y canal de contacto; es la misma fuente que registra el consentimiento (R-43) |
| GET | `/api/v1/meta/info` | VIEWER+ | Nombre y version de la aplicacion mas el aviso legal |

#### 3.1.2 Autenticacion

| Metodo | Ruta | Rol | Descripcion |
|--------|------|-----|-------------|
| POST | `/api/v1/auth/register` | **PUBLICA** | Crea la cuenta -> `201`; nace `PENDING_VERIFICATION` con rol `VIEWER`. **Exige** `acceptTerms` y `acceptDataPolicy`: sin ellos `400 VALIDATION_FAILED` con `fieldErrors` que senalan ambos campos (la validacion de bean va antes, R-46) y el servicio responde ademas `400 CONSENT_REQUIRED`, que es el codigo que ve el flujo de Google. `acceptMarketing` es opcional. Cada aceptacion se guarda en `consent_records` con version, fecha, IP y agente |
| POST | `/api/v1/auth/login` | **PUBLICA** | Credenciales -> `200` con JWT; emite la cookie `HttpOnly` `xmr_refresh` |
| POST | `/api/v1/auth/refresh` | **PUBLICA** | Rota el refresh token -> `200`; reutilizar uno ya rotado revoca la familia completa |
| POST | `/api/v1/auth/logout` | VIEWER+ (principal opcional) | `204`; revoca el refresh token y borra la cookie. Idempotente |
| POST | `/api/v1/auth/password/forgot` | **PUBLICA** | `204`; respuesta identica exista o no el correo |
| POST | `/api/v1/auth/password/reset` | **PUBLICA** | `204`; restablece la contrasena con un token opaco |
| POST | `/api/v1/auth/password/change` | VIEWER+ | `204`; cambia la contrasena del autenticado y revoca sus sesiones |
| POST | `/api/v1/auth/verify-email` | **PUBLICA** | `204`; confirma el correo con el parametro de consulta `token` |
| POST | `/api/v1/auth/verify-email/resend` | **PUBLICA** | `204` exista o no la cuenta (anti-enumeracion); caduca el token anterior y envia uno nuevo. `429` por rate limit |
| GET | `/api/v1/auth/me` | VIEWER+ | Perfil del autenticado; `UserResponse` trae `role` (efectiva, la mayor) y `roles` (conjunto completo) |
| GET | `/api/v1/auth/request-id` | VIEWER+ | Identificadores de correlacion de la peticion actual |
| GET | `/api/v1/auth/google/authorize` | **PUBLICA** | `307` hacia el formulario de consentimiento de Google. Acepta `acceptTerms`, `acceptDataPolicy` y `acceptMarketing`, que viajan dentro del `state` de un solo uso |
| GET | `/api/v1/auth/google/callback` | **PUBLICA** | `302` al frontend con la sesion en el **fragmento** de la URL y `Set-Cookie: xmr_refresh`. Sin consentimiento en el `state` y sin cuenta previa responde `error_code=CONSENT_REQUIRED` |

> La cookie `xmr_refresh` se emite en el login, en el refresh y en el callback de Google con `HttpOnly; Secure; SameSite=Strict; Path=/api/v1/auth`, y se borra en el logout.

#### 3.1.3 Mercado

| Metodo | Ruta | Rol | Descripcion |
|--------|------|-----|-------------|
| GET | `/api/v1/market/latest` | VIEWER+ | Cotizacion actual: `symbol`, `price`, `open`, `high`, `low`, `previousClose`, `change`, `changePercent`, `volume`, `marketTime`, `updatedAt`, `source` |
| GET | `/api/v1/market/candles` | VIEWER+ | Pagina de velas: `id`, `date`, `open`, `high`, `low`, `close`, `volume`, `change`, `changePercent` |
| GET | `/api/v1/market/series` | VIEWER+ | Lista de velas en orden ascendente; `limit` entre 1 y 2000 |

#### 3.1.4 Notificaciones

| Metodo | Ruta | Rol | Descripcion |
|--------|------|-----|-------------|
| GET | `/api/v1/notifications` | VIEWER+ | Pagina de `AppNotification`: `id`, `title`, `body`, `severity`, `createdAt`, `read`, `link` |
| GET | `/api/v1/notifications/unread-count` | VIEWER+ | `{count}` de notificaciones no leidas |
| POST | `/api/v1/notifications/{id}/read` | VIEWER+ | Marca como leida -> `200`; `404` si no pertenece al usuario |

#### 3.1.5 Predicciones

| Metodo | Ruta | Rol | Descripcion |
|--------|------|-----|-------------|
| POST | `/api/v1/predictions` | ANALYST+ | Encola una inferencia -> `201`. Cuerpo: `{modelId\|modelVersionId, symbol?, targetDate?, lookbackDays?}`. `422` con `MODEL_NOT_VERIFIED` o `NO_CHAMPION_VERSION` |
| GET | `/api/v1/predictions` | VIEWER+ | Pagina de predicciones, **siempre acotada al propietario** |
| GET | `/api/v1/predictions/{id}` | VIEWER+ | Detalle -> `200` / `404` si no es del usuario |

#### 3.1.6 Datasets

| Metodo | Ruta | Rol | Descripcion |
|--------|------|-----|-------------|
| GET | `/api/v1/datasets` | VIEWER+ | Pagina de versiones de dataset con checksum y procedencia (R-28) |
| GET | `/api/v1/datasets/{id}` | VIEWER+ | Detalle -> `200` / `404` |
| POST | `/api/v1/datasets` | ANALYST+ | Registra una version -> `201`; `409` si ya existe `(symbol, version)` |

#### 3.1.7 Experimentos y corridas

| Metodo | Ruta | Rol | Descripcion |
|--------|------|-----|-------------|
| GET | `/api/v1/experiments` | VIEWER+ | Pagina de experimentos; la API publica `DRAFT` como `PENDING` y `COMPLETED` como `SUCCEEDED` (R-43) |
| GET | `/api/v1/experiments/{id}` | VIEWER+ | Detalle y estado -> `200` / `404` |
| POST | `/api/v1/experiments` | ANALYST+ | Crea el experimento -> `201`; `422` si no existe ninguna version de dataset |
| POST | `/api/v1/experiments/{id}/runs` | ANALYST+ | Crea una corrida -> `201`; `400 INSUFFICIENT_SEEDS` con menos de 5 semillas (R-08) y `409` si se repite el `runKey` |
| GET | `/api/v1/experiments/{id}/runs` | VIEWER+ | Pagina de corridas del experimento |
| GET | `/api/v1/experiments/{id}/runs/{runId}` | VIEWER+ | Detalle de la corrida -> `200` / `404` |

#### 3.1.8 Modelos

| Metodo | Ruta | Rol | Descripcion |
|--------|------|-----|-------------|
| GET | `/api/v1/models` | VIEWER+ | Pagina de modelos; cinco familias sembradas: `LSTM`, `GRU`, `MOVING_AVERAGE`, `LINEAR_REGRESSION`, `ARIMA` |
| GET | `/api/v1/models/{modelId}/versions` | VIEWER+ | Lista de versiones del modelo con digest de artefacto |
| POST | `/api/v1/models/{modelId}/promote` | **ADMIN** | Promueve una version a campeon; `422` si falla la integridad, la procedencia o el gate de R-24 (el campeon se elige por **validacion**) |

#### 3.1.9 Metricas

| Metodo | Ruta | Rol | Descripcion |
|--------|------|-----|-------------|
| GET | `/api/v1/metrics/compare` | VIEWER+ | Lista de `ComparisonRow`; filtros `?experimentId=` y `?split=VALIDATION\|TEST` |
| GET | `/api/v1/metrics/experiments/{experimentId}` | VIEWER+ | `MetricSet` con las metricas separadas por particion |

#### 3.1.10 Trabajos

| Metodo | Ruta | Rol | Descripcion |
|--------|------|-----|-------------|
| GET | `/api/v1/jobs` | VIEWER+ | Pagina de trabajos; `ADMIN` ve todos y el resto solo los suyos. La API publica `PENDING` como `QUEUED` y `COMPLETED` como `SUCCEEDED` |
| GET | `/api/v1/jobs/summary` | VIEWER+ | `{pending, running, failed}` |
| GET | `/api/v1/jobs/{id}` | VIEWER+ | Detalle -> `200` / `404` |
| POST | `/api/v1/jobs` | ANALYST+ | Encola un trabajo -> `200`; idempotente por `idempotencyKey`. Tipos: `INGEST`, `TRAIN`, `PREDICT`, `BACKFILL` |
| POST | `/api/v1/jobs/{id}/cancel` | VIEWER+ | Cancela -> `200`; `409` si el trabajo ya esta en un estado terminal |

> El consumidor es `job/JobWorker.java`, programado en el backend: lotes de 5, `initialDelay` 5000 ms, `fixedDelay` 15000 ms, reclamo optimista con `UPDATE ... WHERE status = PENDING`, hasta 3 intentos y solo reintento ante 502/503/504 (un 4xx falla de inmediato). Devuelve a `PENDING` los trabajos `RUNNING` cuyo latido supere `app.jobs.heartbeat-timeout-seconds` (900 s). Un trabajo `TRAIN` termina siempre en `FAILED` con el codigo `TRAINING_NOT_EXPOSED`.

#### 3.1.11 Auditoria y administracion de usuarios

| Metodo | Ruta | Rol | Descripcion |
|--------|------|-----|-------------|
| GET | `/api/v1/audit` | **ADMIN** | Pagina de `AuditEntry`; `outcome` en `SUCCESS`, `DENIED`, `FAILURE` |
| GET | `/api/v1/users` | **ADMIN** | Pagina de usuarios |
| GET | `/api/v1/users/{id}` | **ADMIN** | Detalle -> `200` / `404` |
| PATCH | `/api/v1/users/{id}/role` | **ADMIN** | **Reemplaza** el conjunto de roles y revoca las sesiones del usuario. `409 CANNOT_SELF_DEMOTE` y `409 LAST_ADMIN` |

#### 3.1.12 Rutas fuera de `/api/v1`

| Metodo | Ruta | Rol | Descripcion |
|--------|------|-----|-------------|
| GET | `/actuator/health` | **PUBLICA** | Sonda de salud; los detalles solo se muestran a un actor autorizado |
| GET | `/actuator/info` | **PUBLICA** | Informacion de la aplicacion |
| GET | `/actuator/**` (resto) | **ADMIN** | Metricas y Prometheus |
| GET | `/v3/api-docs/**`, `/swagger-ui/**` | **ADMIN** | Documentacion OpenAPI y Swagger UI |

#### 3.1.13 Rutas que no existen

Esta documentacion no las publica porque el codigo no las implementa: `/api/v1/health`, `/api/v1/market/ohlcv`, `/api/v1/market/summary`, `/api/v1/market/ingest`, `/api/v1/experiments/{id}/comparison`, `/api/v1/predictions/next`, `/api/v1/runs/{id}/champion`, `/api/v1/admin/users` y cualquier ruta bajo `/api/v1/ml/*`. El estado del backend se consulta en `/actuator/health`.

### 3.2 FastAPI-ML (servicio interno)

**El servicio ML expone exactamente cuatro rutas y no es accesible publicamente** (R-32): solo Spring Boot lo consume, mediante cliente tipado con timeout, reintentos limitados y correlacion de solicitudes. El prefijo es **`/v1`**, no `/api/v1/ml`.

| Metodo | Ruta | Descripcion |
|--------|------|-------------|
| GET | `/health` | Sonda de disponibilidad usada por el orquestador; no expone configuracion interna |
| GET | `/v1/models` | Versiones de modelo registradas con procedencia verificable: `model_key`, `family`, digest del artefacto, version de dataset y metricas |
| POST | `/v1/predict` | Inferencia de cierre y direccion para un modelo y una version concretos. Devuelve el aviso legal de R-11 |
| GET | `/v1/metrics/compare` | Comparacion de modelos sobre **el mismo conjunto de prueba** (R-05); admite `?experiment_id=` |

**El entrenamiento no se expone por HTTP.** No existe ninguna ruta de entrenamiento en el servicio ML: es una operacion larga y reproducible que se ejecuta por CLI sobre una configuracion versionada en `configs/*.yaml`. Por diseno, un trabajo de tipo `TRAIN` encolado en la API termina en `FAILED` con el codigo `TRAINING_NOT_EXPOSED`, en lugar de quedarse indefinidamente en ejecucion. La ingesta de velas es igualmente un camino de CLI (`python -m app.ml.pipelines.ingest`).

Los esquemas de Pydantic usan `extra='forbid'`: enviar un campo de mas devuelve `422` y rompe la funcionalidad entera sin fallo de compilacion (R-38).

---

## 4. Despliegue y dependencias

### 4.1 Servicios Docker Compose

| Servicio | URL/puerto publicado (dev) | Contenedor | Descripcion |
|----------|----------------------------|------------|-------------|
| `frontend` | `https://127.0.0.1:3000` | 8080 | React/Vite servido por nginx; consume unicamente HTTPS |
| `backend` | `https://127.0.0.1:8443` | 8443 | Spring Boot 3.2. **Solo HTTPS**: `server.ssl.enabled: true` (unico conector) |
| `ml-service` | `https://127.0.0.1:8000` | 8443 | FastAPI-ML con TLS |
| `mlflow` | `https://127.0.0.1:5000` | 5000 | MLflow 2.8 con TLS |
| `db` | sin puerto publicado | 5432 | PostgreSQL 15 en red interna |
| `redis` | sin puerto publicado | 6379 | Redis 7 en red interna |

Notas de red, todas verificadas en ejecucion:

- **No existe un puerto 8080 en el backend ni redireccion de HTTP a HTTPS.** El 8080 del Compose corresponde al nginx del contenedor `frontend`. El backend rechaza HTTP plano y el TLS lo termina el proxy o el ingress de la red privada (R-33).
- `db`, `redis` y `ml-service` viven en una red Docker `internal: true`, sin ruta de salida al host.
- `docker-compose.dev.yml` (solo desarrollo) publica ademas `postgres` en `127.0.0.1:55432` y `redis` en `127.0.0.1:56379`, y los adjunta a una red `backend` **no interna**: publicar un puerto sobre una red `internal: true` no hace nada, porque esa red no tiene ruta de vuelta al host.
- La migracion de esquema es exclusiva de **Flyway** (R-34). El servicio ML no administra el esquema de PostgreSQL.

### 4.2 Variables de entorno

Estos son los nombres que el codigo lee de verdad. **No existe `DB_USER`** (es `DB_USERNAME`) **ni `JWT_EXPIRATION_MINUTES`** (son `JWT_ACCESS_TTL` y `JWT_REFRESH_TTL`). Ningun secreto se versiona: solo se versiona `.env.example` (R-14).

**Base de datos y cache**

| Variable | Descripcion |
|----------|-------------|
| `DB_URL` | JDBC de PostgreSQL |
| `DB_USERNAME` | Usuario de PostgreSQL |
| `DB_PASSWORD` | Contrasena de PostgreSQL |
| `DB_POOL_SIZE` | Tamano maximo del pool HikariCP (por defecto 20) |
| `REDIS_HOST` | Host de Redis |
| `REDIS_PORT` | Puerto de Redis |
| `REDIS_PASSWORD` | Contrasena de Redis |
| `REDIS_SSL_ENABLED` | Activa TLS contra Redis |

**Autenticacion y OAuth**

| Variable | Descripcion |
|----------|-------------|
| `JWT_SECRET` | Secreto de firma. Sin valor por defecto: si falta, la aplicacion no arranca |
| `JWT_ISSUER` | Emisor (issuer) del token |
| `JWT_AUDIENCE` | Audiencia del token |
| `JWT_ACCESS_TTL` | Vigencia del access token en segundos (por defecto 900 s, 15 min) |
| `JWT_REFRESH_TTL` | Vigencia del refresh token en segundos (por defecto 2592000 s, 30 dias) |
| `GOOGLE_CLIENT_ID` | Client ID de Google OAuth 2.0 |
| `GOOGLE_CLIENT_SECRET` | Client secret de Google OAuth 2.0 |
| `GOOGLE_REDIRECT_URI` | URI de redireccion registrada en Google |
| `FRONTEND_OAUTH_CALLBACK` | Destino del `302` tras el callback de Google |

**Servicio ML y tracking**

| Variable | Descripcion |
|----------|-------------|
| `ML_SERVICE_URL` | URL base de FastAPI-ML (por defecto `https://ml-service:8443`) |
| `ML_CONNECT_TIMEOUT_MS` | Timeout de conexion del cliente ML (3000 ms) |
| `ML_READ_TIMEOUT_MS` | Timeout de lectura del cliente ML (60000 ms) |
| `ML_MAX_RETRIES` | Reintentos limitados del cliente ML (2) |
| `MLFLOW_TRACKING_URI` | URI de MLflow |

La verificacion del certificado del servicio ML **no tiene bandera**: es siempre
activa. Antes existia `ML_VERIFY_TLS`, que el codigo leia y no usaba.

**Servidor, red y correo**

| Variable | Descripcion |
|----------|-------------|
| `SERVER_PORT` | Puerto HTTPS del backend (8443) |
| `SERVER_SSL_KEYSTORE` | Ruta del keystore PKCS12 |
| `SERVER_SSL_KEYSTORE_PASSWORD` | Contrasena del keystore |
| `SERVER_SSL_KEY_ALIAS` | Alias de la clave |
| `SERVER_SSL_KEYSTORE_TYPE` | Tipo de keystore (`PKCS12`) |
| `CORS_ALLOWED_ORIGINS` | Lista de origenes permitidos |
| `MAIL_TRANSPORT` | Canal de envio: `smtp` (desarrollo) o `gmail` (Gmail API por HTTPS) |
| `MAIL_HOST` | Servidor SMTP (solo `MAIL_TRANSPORT=smtp`) |
| `MAIL_PORT` | Puerto SMTP |
| `MAIL_USERNAME` | Usuario SMTP |
| `MAIL_PASSWORD` | Contrasena SMTP |
| `MAIL_SMTP_AUTH` | Autenticacion SMTP |
| `MAIL_STARTTLS` | STARTTLS en SMTP |
| `MAIL_CONNECT_TIMEOUT_MS` | Timeout de conexion SMTP (5000) |
| `MAIL_READ_TIMEOUT_MS` | Timeout de lectura SMTP (8000) |
| `MAIL_WRITE_TIMEOUT_MS` | Timeout de escritura SMTP (5000) |
| `MAIL_FROM` | Remitente de los correos de cuenta |
| `MAIL_FROM_NAME` | Nombre visible del remitente en la cabecera `From` (opcional) |
| `MAIL_USE_TLS` | Alias generico de `MAIL_STARTTLS`; si estan los dos, manda el primero |
| `APP_PUBLIC_URL` | URL publica para los enlaces de los correos. Si no esta, se usa `FRONTEND_BASE_URL` |
| `LEGAL_CONTACT_EMAIL` | Canal de atencion a titulares mostrado en el pie y en las politicas |
| `GOOGLE_MAIL_REFRESH_TOKEN` | Refresh token de la Gmail API (`MAIL_TRANSPORT=gmail`) |

**Limites y bloqueo de cuentas**

| Variable | Descripcion |
|----------|-------------|
| `RATE_LIMIT_LOGIN` | Intentos de login por minuto (5) |
| `RATE_LIMIT_API` | Peticiones de API por minuto (300) |
| `MAX_FAILED_LOGINS` | Fallos consecutivos antes de bloquear (5) |
| `LOCK_DURATION_MINUTES` | Duracion del bloqueo en minutos (15) |

---

## 5. Seguridad

- **Autenticacion:** JWT Bearer de corta duracion (`JWT_ACCESS_TTL`, 900 s) mas refresh token rotado con deteccion de reutilizacion.
- **Cookie de refresco:** `xmr_refresh` con `HttpOnly; Secure; SameSite=Strict; Path=/api/v1/auth`, emitida en login, refresh y callback de Google; borrada en el logout.
- **Autorizacion:** roles `VIEWER`, `ANALYST`, `ADMIN`; cada ruta declara su requisito y hay pruebas negativas por ruta (R-35).
- **Contrasenas:** BCrypt con salt unico; en base de datos solo hashes. De los refresh tokens solo se guarda el hash HMAC-SHA256 (R-14).
- **HTTPS:** forzado en todos los entornos; `server.ssl.enabled: true` deja a Tomcat un unico conector, el HTTPS.
- **CORS:** allowlist de origenes HTTPS.
- **TLS local:** los certificados de desarrollo son autofirmados y solo sirven para el entorno local; produccion debe usar certificados emitidos por una autoridad confiable.
- **Conexiones internas:** Spring Boot usa `ML_SERVICE_URL=https://ml-service:8443` y ML usa `MLFLOW_TRACKING_URI=https://mlflow:5000`.
- **Rate limiting:** en login, en los endpoints de trabajo y en `password/forgot`, `password/reset` y `verify-email` (incluido el reenvio), que son publicos y generan tokens.
- **Auditoria:** registro de acciones sensibles en `audit_events` con resultado `SUCCESS`/`DENIED`/`FAILURE`.
- **Consentimiento:** cada aceptacion de terminos o de politica de datos queda en `consent_records` (migracion `V6`) con documento, version, fecha y hora, cuenta, canal y IP. El servidor lo comprueba dos veces: con `@AssertTrue` sobre una instancia real y de nuevo en `AuthService`, de modo que un registro sin aceptacion es imposible aunque se salte el formulario (R-46).
- **Documentos legales:** cinco paginas publicas (`/terms`, `/privacy`, `/data-policy`, `/cookies`, `/legal-notice`) enlazadas desde el pie en todas las rutas; la version que muestran es la misma que sirve `GET /api/v1/meta/legal` y la que se guarda al aceptar (`LegalVersionsContractTest` compara los dos archivos de origen).
- **Google OAuth:** identidad por `sub` (no por correo), `state` y `nonce` de un solo uso en Redis, y creacion de cuenta nueva solo si el `state` trae los dos aceptes; la verificacion del correo por parte de Google se acepta **antes** de comprobar si la cuenta esta activa.
- **Secretos:** solo por variables de entorno; ninguno en git, artefactos ni logs.

---

## 6. Calidad y pruebas

| Nivel | Herramientas |
|-------|--------------|
| Backend | JUnit 5, Spring Boot Test |
| Servicio ML | pytest, pytest-asyncio |
| Frontend | Vitest, Testing Library |
| Integracion | TestContainers, Docker Compose |
| Estatica | ruff, mypy, ESLint, tsc |

---

## 7. Referencias

Las referencias [1]-[9] son las del documento `Propuesta_Monero_IEEE.docx`. No se anaden referencias nuevas sin verificarlas (R-21).
