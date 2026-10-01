# XMR-Forecast -- Stack tecnologico

> **Aviso legal.** Este documento describe el stack de una aplicacion de analisis
> predictivo de series de tiempo. No es asesoria financiera, no promete
> rentabilidad y no simula operaciones de inversion (R-11, R-12).

> **Nota sobre versiones.** Las versiones se fijan en los archivos de
> configuracion: `pom.xml` (backend), `requirements.txt` (servicio ML),
> `package.json` (frontend) e imagenes Docker. Este documento describe **lo que
> el codigo declara hoy**, no lo que esta previsto.

---

## 1. Resumen por capa

| Capa | Eleccion | Motivo principal |
|------|----------|------------------|
| **Backend principal** | **Spring Boot 3.2.5** (Java 21, Maven 3.9) | Monolito modular, seguridad, persistencia, API REST |
| **Servicio ML** | **FastAPI** (Python 3.11) | Ecosistema ML: TensorFlow, scikit-learn, statsmodels, Optuna |
| **Base de datos** | **PostgreSQL 15** | Integridad relacional + JSONB |
| **Cache y limitador** | **Redis 7** | Cache con TTL y limitador de peticiones; **no** es la cola de trabajos |
| **Cola de trabajos** | **Tabla `jobs` de PostgreSQL** | Consumida por `job/JobWorker.java`; sobrevive a un reinicio |
| **Tracking ML** | **MLflow 2.8** | Parametros, metricas, artefactos |
| **Frontend** | **React 18 + TypeScript + Vite** | SPA tipada |
| **Estilos** | **Tailwind CSS** | Desarrollo rapido |
| **Graficos** | **Recharts** | Graficos reactivos |
| **Contenedores** | **Docker + Docker Compose** | `docker compose up` para todo |
| **CI/CD** | **GitHub Actions** | Lint, tipos, pruebas, build, contrato HTTP |

---

## 2. Backend (Spring Boot)

### 2.1 Version y construccion

| Aspecto | Detalle real |
|---------|--------------|
| **JDK** | Java 21, validado por `maven-enforcer-plugin` 3.4.1 con `requireJavaVersion [21,22)` |
| **Maven** | 3.9 o superior (`requireMavenVersion [3.9,)`) |
| **Spring Boot** | **3.2.5**, importado como BOM en `dependencyManagement` del `pom.xml` raiz |
| **Gestor de dependencias** | `spring-boot-dependencies` con `<scope>import</scope>` |
| **Empaquetado** | `spring-boot-maven-plugin` con una ejecucion **explicita** de `repackage` |

Dos decisiones del `pom.xml` que conviene no dar por supuestas:

- **El proyecto no usa `spring-boot-starter-parent`.** El `pom.xml` raiz importa
  `spring-boot-dependencies` en lugar de declarar ese parent.
- **Por eso `repackage` se declara de forma explicita** en `backend/pom.xml`:
  el objetivo `repackage` **no se hereda** sin el parent. Sin esa ejecucion,
  `mvn package` produce un jar de unos 300 KB sin atributo `Main-Class`: las
  pruebas pasan, el jar compila, y el contenedor arranca y muere con
  "no hay ningun atributo de manifiesto principal". Un fallo de empaquetado
  invisible al ciclo de pruebas, asi que la ejecucion esta declarada y
  verificada, no supuesta.

### 2.2 Modulos (paquetes Java reales)

`backend/src/main/java/com/aylzz/xmrforecast/`:

| Paquete | Responsabilidad |
|---------|-----------------|
| `auth` | Registro, login, refresh, OAuth de Google, recuperacion de contrasena, notificaciones |
| `user` | `users`, roles, cuentas OAuth, tokens, intentos de login |
| `security` | JWT, filtros, roles, BCrypt, limitador de peticiones, cookies |
| `market` | Lectura de velas y series OHLCV |
| `dataset` | Versiones de dataset con checksum calculado en el servidor |
| `experiment` | Experimentos, corridas, metricas comparadas |
| `mlmodel` | Modelos, versiones, promocion del campeon |
| `prediction` | Predicciones solicitadas por el usuario y sus gates |
| `metrics` | Metricas de negocio y de calidad |
| `job` | Cola de trabajos: entidad, repositorio y `JobWorker` |
| `ml` | **Cliente tipado** del servicio FastAPI-ML (`MlServiceClient`) |
| `audit` | Auditoria append-only |
| `common` | Excepciones, envelopes de respuesta, contexto de solicitud |
| `config` | `AppProperties`, seguridad, cache, OpenAPI |

Superficie de API: **13 controladores y 46 rutas** bajo `/api/v1`. Todo `id`
publico es una **cadena opaca**, y el servidor traduce su estado interno al
vocabulario publicado (R-43): `DRAFT` -> `PENDING`, `COMPLETED` -> `SUCCEEDED`,
y en trabajos `PENDING` -> `QUEUED`, `COMPLETED` -> `SUCCEEDED`.

### 2.3 Componentes

| Componente | Detalle |
|------------|---------|
| **Spring Boot 3.2.5** | Monolito modular (lista de paquetes en 2.2) |
| **Spring Security** | Autenticacion JWT, autorizacion por roles, BCrypt coste 12 |
| **Spring Data JPA** | Persistencia con PostgreSQL, `open-in-view: false` |
| **Spring Validation** | Validacion estricta de DTOs |
| **Spring Cache + Data Redis** | Cache con TTL 300 s y `cache-null-values: false` |
| **RestClient** | Cliente HTTP **sincrono** hacia el servicio ML |
| **Flyway 9.22.3** | Migraciones versionadas; unica fuente del esquema (R-34) |
| **jjwt 0.12.5** | HS512; access 900 s, refresh 2 592 000 s (30 dias) |
| **springdoc-openapi 2.3.0** | OpenAPI y Swagger UI (solo `ADMIN`) |
| **logstash-logback-encoder 7.4** | Logs estructurados JSON |
| **Actuator** | `health`, `info`, `metrics`, `prometheus` |

### 2.4 Cliente HTTP hacia el servicio ML

`backend/.../ml/MlServiceClient.java` usa **`RestClient`**, no WebClient ni
WebFlux. El backend es un monolito **servlet** (`spring-boot-starter-web`), no
reactivo.

| Parametro | Valor | Origen |
|-----------|-------|--------|
| Timeout de conexion | 3000 ms | `ML_CONNECT_TIMEOUT_MS` |
| Timeout de lectura | 60000 ms | `ML_READ_TIMEOUT_MS` |
| Reintentos | 2 (3 intentos en total) | `ML_MAX_RETRIES` |
| Espera entre reintentos | 500 ms x intento, tope 2000 ms | Backoff interno |
| Circuit breaker | **No existe** | Limitacion registrada, ver 2.7 |
| Verificacion TLS | **Siempre activa**; `https://` obligatorio | ninguna bandera: la CA se anade al truststore |

Correlacion: el backend envia **`X-Request-Id` y `X-Trace-Id`**, y el servicio ML
devuelve ambas cabeceras en la respuesta.

### 2.5 OAuth de Google: dependencia eliminada a proposito

`spring-boot-starter-oauth2-client` **no esta** en `backend/pom.xml` y el bloque
`spring.security.oauth2.client.registration.google` **no esta** en
`application.yml`. No es un olvido: es una correccion deliberada.

Con `GOOGLE_CLIENT_ID` vacio, `OAuth2ClientProperties` lanza
`Client id must not be empty` en el **arranque**, de modo que la aplicacion no
arrancaba sin credenciales de Google, cuando el propio codigo si contempla que
Google no este configurado (`AppProperties.OAuth2.Google.isConfigured()`). Es
decir: la configuracion de Spring hacia imposible el escenario opcional que el
diseno dice soportar.

El flujo esta implementado a mano en `auth/GoogleOAuthService.java`:

| Paso | Endpoint real |
|------|---------------|
| Autorizacion | `https://accounts.google.com/o/oauth2/v2/auth` |
| Canje del codigo | `https://oauth2.googleapis.com/token` |
| Validacion del `id_token` | JWKS `https://www.googleapis.com/oauth2/v3/certs`, solo `RS256` |

Se validan `iss` (lista de emisores de Google), `aud`, `exp` y `nonce`.

### 2.6 Esquema de base de datos

- **Flyway 9.22.3** incluye el soporte de PostgreSQL dentro de `flyway-core`.
  **No** se anade `flyway-database-postgresql`: ese modulo es de Flyway 10 y no
  aplica aqui.
- **5 migraciones**, `V1`..`V5`, que crean **22 tablas**.
- `spring.jpa.hibernate.ddl-auto: validate`, nunca `update` ni `create`: Flyway
  es la unica fuente de verdad del esquema (R-34).
- `open-in-view: false` y zona horaria JDBC en UTC.

### 2.7 Limitaciones reales del backend (registradas, no ocultas)

| Limitacion | Consecuencia |
|------------|--------------|
| **Entrenamiento solo por CLI** | Un trabajo `TRAIN` termina siempre en `FAILED` con codigo `TRAINING_NOT_EXPOSED`, por diseno |
| **`state`/`nonce` OAuth en memoria** | `GoogleOAuthService` los guarda en un `ConcurrentHashMap` del proceso: no es seguro con varias replicas y se pierde al reiniciar. El propio codigo indica que deberian vivir en Redis |
| **Sin circuit breaker** | No hay resilience4j hacia el servicio ML; solo timeout, reintentos limitados y backoff |
| **Sin MFA para administradores** | Requisito de ASVS pendiente en produccion |
| **Sin autenticacion interna del servicio ML** | No hay API key entre backend y `ml-service`; la proteccion es de red |

---

## 3. Servicio ML (FastAPI)

| Componente | Detalle |
|------------|---------|
| **FastAPI** | Framework web asincrono |
| **Pydantic v2** | Validacion de esquemas con `extra='forbid'` |
| **NumPy, pandas** | Manipulacion de datos |
| **scikit-learn** | Modelos clasicos, escalado, metricas |
| **statsmodels** | ARIMA |
| **TensorFlow/Keras** | LSTM/GRU |
| **Optuna** | Ajuste de hiperparametros |
| **MLflow** | Tracking de experimentos |

El servicio expone **exactamente cuatro rutas**, con prefijo `/v1`:

| Ruta | Metodo | Uso |
|------|--------|-----|
| `/health` | GET | Estado del servicio |
| `/v1/models` | GET | Modelos registrados con su procedencia |
| `/v1/predict` | POST | Inferencia de cierre y direccion |
| `/v1/metrics/compare` | GET | Comparacion de modelos sobre el mismo test |

**No existe** una ruta `/train`, ni un proxy `/api/v1/ml/*` en el backend. El
detalle del contrato esta en [`03_machine_learning.md`](03_machine_learning.md)
seccion 2.

`app/ml/` es un paquete puro: no importa FastAPI ni SQLAlchemy, para que siga
siendo testeable y usable desde notebooks o CLI (R-13).

---

## 4. Base de datos

### 4.1 PostgreSQL

- **Por que relacional:** experimentos, corridas, metricas y predicciones tienen
  relaciones claras y constraints que importan.
- **JSONB:** hiperparametros y listas de features varian por modelo.
- **Migraciones:** Flyway, versionadas y revisadas; 22 tablas en `V1`..`V5`.
- **Indices:** optimizados para consultas por fecha y por estado.

### 4.2 Redis: que usa y que NO usa

| Uso | Detalle |
|-----|---------|
| **Cache de Spring** | TTL 300 s, `cache-null-values: false` |
| **Limitador de peticiones** | Claves con prefijo `ratelimit:`; `RATE_LIMIT_LOGIN` y `RATE_LIMIT_API` |
| **Valores OAuth en vuelo** | Reservado para `state` y `nonce` |

**Redis no es la cola de trabajos.** La cola es la tabla `jobs` de PostgreSQL y su
consumidor es `backend/.../job/JobWorker.java`:

| Aspecto | Comportamiento real |
|---------|---------------------|
| Planificacion | `@Scheduled(initialDelay = 5000 ms, fixedDelay = 15000 ms)` |
| Lote | 5 trabajos por pasada |
| Reclamo | `UPDATE ... WHERE status = PENDING`: dos replicas no ejecutan el mismo trabajo |
| Reintentos | Solo ante **502, 503 y 504**; un 4xx falla de inmediato |
| Intentos maximos | `app.jobs.max-attempts` = 3 |
| Recuperacion | `recoverStalledJobs()` devuelve a `PENDING` los trabajos `RUNNING` cuyo `heartbeat_at` es anterior a `app.jobs.heartbeat-timeout-seconds` (900 s) |
| Tipos | `INGEST`, `TRAIN`, `PREDICT`, `BACKFILL` |

`INGEST` y `BACKFILL` terminan con resultado explicito
`{"delegatedTo": "ml-service CLI", "executed": false}`: no se finge que se
ejecutaron (R-21). `TRAIN` falla siempre con `TRAINING_NOT_EXPOSED` porque el
entrenamiento es un camino de CLI sobre `configs/*.yaml`.

---

## 5. Frontend

| Componente | Uso |
|------------|-----|
| **React 18 + TypeScript (strict)** | Componentes y tipado del cliente API |
| **Vite** | Servidor de desarrollo y build |
| **React Router** | Navegacion |
| **TanStack Query** | Peticiones, cache, reintentos |
| **Tailwind CSS** | Estilos |
| **Recharts** | Graficos |

El bundle no lleva secretos: `VITE_API_BASE_URL` y `VITE_AUTH_GOOGLE_URL` se
incrustan en tiempo de build y no son credenciales (R-14).

---

## 6. Infraestructura y DevOps

| Elemento | Detalle |
|----------|---------|
| **Docker** | Imagenes separadas para backend, ml-service y frontend |
| **Docker Compose** | Orquesta todos los servicios |
| **GitHub Actions** | CI: lint -> tipos -> tests -> build -> contrato HTTP |
| **HTTPS** | Forzado en todos los entornos, desarrollo incluido (R-33) |
| **`docker/generate-dev-certs.sh`** | Genera la CA de desarrollo y los certificados de backend, ml-service y mlflow |
| **`docker-compose.dev.yml`** | Override **exclusivo de desarrollo** para alcanzar PostgreSQL y Redis desde el host |
| **`docker/backup.sh`** | `backup`, `verify`, `restore`, `list` con puerta de huerfanos |
| **`tools/verify-stack.ps1`** | Arranca el jar empaquetado contra PostgreSQL y Redis locales y ejecuta 43 comprobaciones HTTP |

### 6.1 Certificados de desarrollo

El script es **`docker/generate-dev-certs.sh`**. No existe
`docker/certs/generate-dev-certs.sh`; una ruta con esa forma no encuentra nada.

```bash
bash docker/generate-dev-certs.sh          # primera vez
FORCE=1 bash docker/generate-dev-certs.sh # regenerar la CA (invalida la confianza instalada)
```

- Crea una CA propia y certificados para `backend`, `ml-service` y `mlflow`.
- Empaqueta `docker/certs/backend/keystore.p12` y `docker/certs/ca/truststore.p12`
  con **`openssl pkcs12`**, no con `keytool`: asi no depende de un JDK concreto ni
  del `keytool` que aparezca primero en el `PATH` (R-36).
- Exporta `MSYS_NO_PATHCONV=1` porque Git-for-Windows convierte cualquier
  argumento que empiece por `/` en una ruta de Windows: el `-subj "/C=ES/..."`
  se convertiria en una ruta y `openssl` abortaria sin mencionar la causa.
- Usa rutas relativas y un fichero de extension real en disco: el `openssl`
  nativo de Windows no lee `/dev/fd/N`, que es donde aterriza la salida de `<(...)`.

### 6.2 Override de desarrollo

`docker-compose.yml` mantiene PostgreSQL y Redis en la red `data`, marcada
`internal: true` y **sin puertos publicados**. Es lo correcto y es lo que exige
R-27. Para trabajar fuera de Docker (`mvn spring-boot:run`, IntelliJ, `psql`
desde el host) existe:

```bash
docker compose -f docker-compose.yml -f docker-compose.dev.yml up -d
```

| Servicio | Puerto publicado en el override | DB_URL |
|----------|--------------------------------|--------|
| `postgres` | `127.0.0.1:55432` -> 5432 | `jdbc:postgresql://127.0.0.1:55432/xmr_forecast` |
| `redis` | `127.0.0.1:56379` -> 6379 | |

Dos motivos no negociables de este fichero:

1. **Conecta tambien `postgres` y `redis` a la red `backend`, que no es interna.**
   Una red `internal: true` no tiene ruta de vuelta al host: Docker acepta el
   puerto y no publica nada, `docker port <contenedor>` sale vacio y el cliente
   acaba conectando a **otro** PostgreSQL del host.
2. **Usa puertos altos a proposito.** `5432` y `6379` los ocupan con frecuencia
   instalaciones locales, y el conflicto tampoco avisa con claridad: el sintoma es
   un desconcertante "password authentication failed" que viene de **otro**
   servidor y no tiene nada que ver con esta configuracion.

**En produccion no se usa este fichero**, solo el `docker-compose.yml` base.

### 6.3 Backup y restauracion

```bash
bash docker/backup.sh backup
bash docker/backup.sh list
bash docker/backup.sh verify  backups/xmr_forecast_<fecha>.dump
bash docker/backup.sh restore backups/xmr_forecast_<fecha>.dump
```

`restore` no se limita a comprobar que la base responde. Tras restaurar ejecuta
una **puerta de huerfanos** sobre cinco relaciones y **aborta con codigo de
salida distinto de cero** si queda alguna:

| Relacion comprobada |
|---------------------|
| `users` -> `user_roles` |
| `predictions` -> `model_versions` |
| `experiments` -> `dataset_versions` |
| `metrics` -> `experiment_runs` |
| `model_versions` -> `models` |

### 6.4 Verificacion de extremo a extremo

`tools/verify-stack.ps1` es un guion de PowerShell para Windows que **levanta el
jar empaquetado** contra PostgreSQL y Redis locales y ejecuta 43 comprobaciones
HTTP reales: salud, aviso legal sin sesion, 401 sin token, ausencia de listener
HTTP plano, registro, puerta `EMAIL_NOT_VERIFIED`, emision de JWT, cookie
`HttpOnly`, rol efectivo, ids opacos, extremos de lectura, familias de modelo,
403 por rol, y el camino de escritura de `ANALYST` (dataset con checksum
calculado en el servidor, experimento, 400 `INSUFFICIENT_SEEDS`, corrida, 409,
metricas y trabajo `TRAIN` en la cola).

Es la respuesta directa a R-42: **`mvn test` no demuestra que la aplicacion
arranque**.

---

## 7. Calidad

| Herramienta | Proposito |
|-------------|-----------|
| **JUnit 5 + Testcontainers** | Tests del backend, incluidos los que levantan PostgreSQL real |
| **pytest** | Tests del servicio ML |
| **Vitest** | Tests del frontend |
| **ruff, mypy** | Lint y tipos de Python |
| **ESLint, tsc** | Calidad del frontend |
| **`HttpContractTest`** | Contrato HTTP verificado en runtime entre backend y servicio ML (R-41) |

---

## 8. Seguridad

El protocolo aplicable esta centralizado en
[`05_seguridad.md`](05_seguridad.md). El stack implementa defensa en profundidad:

- **Identidad:** JWT de 15 min, refresh rotatorio de 30 dias con revocacion por
  familia, BCrypt coste 12, roles `VIEWER`/`ANALYST`/`ADMIN`
- **API:** Validacion estricta, CORS restringido, limitador de peticiones,
  OpenAPI solo para `ADMIN`
- **Datos:** PostgreSQL y Redis en red interna; MLflow solo en loopback
- **ML:** Artefactos versionados con digest y procedencia obligatoria
- **Cadena de suministro:** lockfiles, SBOM, secret scanning
- **Contenedores:** Imagenes minimas, usuario no root
- **HTTPS:** Forzado en todos los entornos, sin listener HTTP plano

Detalle operativo, limits y limitaciones registradas:
[`05_seguridad.md`](05_seguridad.md) y [`08_operacion.md`](08_operacion.md).

---

## 9. Alternativas descartadas

| Alternativa | Motivo de descarte |
|-------------|-------------------|
| Microservicios completos | Dominio acotado; la complejidad operativa no esta justificada (R-31) |
| Node.js/NestJS en backend | Obligaria a separar el ML en otro servicio |
| MongoDB | Las relaciones encajan mejor en SQL |
| PyTorch | Keras es mas sencillo para LSTM/GRU |
| WebClient / WebFlux | El backend es un monolito servlet; `RestClient` cubre el caso sin anadir una pila reactiva |
| `spring-boot-starter-oauth2-client` | Impide arrancar sin credenciales de Google; el flujo esta implementado a mano (ver 2.5) |

---

## 10. Servicios de Docker Compose

Puertos publicados **solo en loopback** (`127.0.0.1`). Ningun servicio es
alcanzable desde la red externa.

| Servicio | Imagen / origen | Puerto en el host | Puerto interno |
|----------|-----------------|-------------------|----------------|
| `frontend` | React + Vite (nginx) | `127.0.0.1:3000` | 8080 |
| `backend` | Spring Boot 3.2.5 (Java 21) | `127.0.0.1:8443` | 8443 (solo HTTPS) |
| `ml-service` | FastAPI | `127.0.0.1:8000` | 8443 (solo HTTPS) |
| `mlflow` | MLflow 2.8 | `127.0.0.1:5000` | 5000 |
| `postgres` | PostgreSQL 15 | **ninguno** | 5432, red `data` interna |
| `redis` | Redis 7 | **ninguno** | 6379, red `data` interna |

Dos precisiones que evitan diagnosticos falsos:

- **El backend no redirige `8080`.** Con `server.ssl.enabled: true`, Tomcat abre
  **unicamente** el conector HTTPS: no existe un listener de HTTP plano, y por eso
  no hay redireccion que hacer. (La propiedad `server.http.enabled`, citada en
  versiones anteriores de este documento, no existe en Spring Boot y no tenia
  efecto.)
- **`8000` es solo el mapeo de loopback.** El servicio ML escucha en `8443` dentro
  del contenedor; `8000` es el puerto del host. El backend lo llama por
  `https://ml-service:8443`.

En desarrollo, `docker-compose.dev.yml` publica ademas `postgres` en
`127.0.0.1:55432` y `redis` en `127.0.0.1:56379` (ver 6.2).

---

## 11. Variables de entorno

Solo estas. Todas llegan del entorno; ningun fichero versionado contiene
credenciales reales (R-14).

### Datos

| Variable | Descripcion |
|----------|-------------|
| `DB_URL` | JDBC de PostgreSQL |
| `DB_USERNAME` | Usuario de PostgreSQL |
| `DB_PASSWORD` | Contrasena de PostgreSQL |
| `DB_POOL_SIZE` | Tamano maximo del pool Hikari |

### Redis

| Variable | Descripcion |
|----------|-------------|
| `REDIS_HOST` | Host de Redis |
| `REDIS_PORT` | Puerto de Redis |
| `REDIS_PASSWORD` | Contrasena de Redis |
| `REDIS_SSL_ENABLED` | Conectar a Redis por TLS |

### JWT

| Variable | Descripcion |
|----------|-------------|
| `JWT_SECRET` | Clave HS512; sin valor por defecto, si falta el backend **no arranca** |
| `JWT_ISSUER` | Emisor (`iss`) |
| `JWT_AUDIENCE` | Audiencia (`aud`) |
| `JWT_ACCESS_TTL` | TTL del access token en segundos (900 = 15 min) |
| `JWT_REFRESH_TTL` | TTL del refresh token en segundos (2 592 000 = 30 dias) |

No existe `JWT_EXPIRATION_MINUTES`: el TTL se configura **en segundos**.

### OAuth de Google

| Variable | Descripcion |
|----------|-------------|
| `GOOGLE_CLIENT_ID` | Client ID de Google |
| `GOOGLE_CLIENT_SECRET` | Client secret de Google |
| `GOOGLE_REDIRECT_URI` | URI de redireccion, debe coincidir exactamente con la registrada |
| `FRONTEND_OAUTH_CALLBACK` | Callback del frontend donde el backend entrega la sesion |

Si estas vacias, Google queda **desactivado** y el resto del sistema funciona
igual: es el escenario que la configuracion de Spring impedia.

### Servicio ML y MLflow

| Variable | Descripcion |
|----------|-------------|
| `ML_SERVICE_URL` | Base URL HTTPS del servicio FastAPI-ML |
| `ML_CONNECT_TIMEOUT_MS` | Timeout de conexion (3000) |
| `ML_READ_TIMEOUT_MS` | Timeout de lectura (60000) |
| `ML_MAX_RETRIES` | Reintentos adicionales (2) |
| `MLFLOW_TRACKING_URI` | URI de MLflow |

**No existe bandera para desactivar la verificacion TLS.** La version anterior de
este documento recogia `ML_VERIFY_TLS`, pero el codigo la leia y no hacia nada con
ella: el cliente HTTP usaba el almacen de confianza por defecto y la propiedad era
decorativa. Una bandera de ese tipo es peligrosa por si misma —acaba activada en
algun entorno por descuido— asi que se elimino en lugar de implementarla. Para el
certificado de desarrollo, la CA se anade al almacen del proceso
(`-Djavax.net.ssl.trustStore=...`, ver `JAVA_OPTS` en `docker-compose.yml`).

`ML_SERVICE_URL` se valida **en el arranque**: si no empieza por `https://` la
aplicacion no levanta. Un `http://` se descubre en la primera prediccion en
produccion, con datos de un usuario en pantalla, o no se descubre.

### Servidor y TLS

| Variable | Descripcion |
|----------|-------------|
| `SERVER_PORT` | Puerto HTTPS (8443) |
| `SERVER_SSL_KEYSTORE` | Ruta del keystore PKCS12 |
| `SERVER_SSL_KEYSTORE_PASSWORD` | Contrasena del keystore |
| `SERVER_SSL_KEY_ALIAS` | Alias de la clave |
| `SERVER_SSL_KEYSTORE_TYPE` | Tipo de keystore (PKCS12) |
| `CORS_ALLOWED_ORIGINS` | Origenes permitidos; nunca `*` con credenciales |

### Seguridad de cuentas y peticiones

| Variable | Descripcion |
|----------|-------------|
| `RATE_LIMIT_LOGIN` | Intentos de login por minuto |
| `RATE_LIMIT_API` | Peticiones de API por minuto |
| `MAX_FAILED_LOGINS` | Fallos antes de bloquear la cuenta |
| `LOCK_DURATION_MINUTES` | Duracion del bloqueo |

### Correo

| Variable | Descripcion |
|----------|-------------|
| `MAIL_TRANSPORT` | Canal de envio: `smtp` o `gmail` |
| `MAIL_HOST` | Servidor SMTP (solo `smtp`) |
| `MAIL_PORT` | Puerto SMTP |
| `MAIL_USERNAME` | Usuario SMTP |
| `MAIL_PASSWORD` | Contrasena SMTP |
| `MAIL_SMTP_AUTH` | Autenticacion SMTP |
| `MAIL_STARTTLS` | Activar STARTTLS |
| `MAIL_CONNECT_TIMEOUT_MS` | Timeout de conexion SMTP (5000) |
| `MAIL_READ_TIMEOUT_MS` | Timeout de lectura SMTP (8000) |
| `MAIL_WRITE_TIMEOUT_MS` | Timeout de escritura SMTP (5000) |
| `MAIL_FROM` | Remitente de los correos de cuenta |
| `GOOGLE_MAIL_CLIENT_ID` / `GOOGLE_MAIL_CLIENT_SECRET` | Cliente OAuth de la Gmail API; por defecto reutilizan `GOOGLE_CLIENT_ID` / `GOOGLE_CLIENT_SECRET` |
| `GOOGLE_MAIL_REFRESH_TOKEN` | Refresh token de la Gmail API |

**Por que existen los dos canales.** Render bloquea el trafico saliente a los
puertos SMTP 25, 465 y 587 en los servicios gratuitos (vigente desde el
26/09/2025), de modo que `smtp.gmail.com:587` responde `Connection timed out`.
En produccion el correo sale por la **Gmail API** (`MAIL_TRANSPORT=gmail`), que
usa el puerto 443 y firma el mensaje el propio Google; en desarrollo local sigue
usandose SMTP (`MAIL_TRANSPORT=smtp`). En ambos casos el envio ocurre **despues
de confirmar la transaccion** y un fallo del canal nunca deshace el alta de
usuario.

**Limite conocido.** Con la pantalla de consentimiento de Google en modo
*Testing*, el refresh token caduca a los 7 dias (`invalid_grant` en los logs);
hay que generar uno nuevo con el OAuth Playground y actualizar
`GOOGLE_MAIL_REFRESH_TOKEN`.

Las variables de **nivel de contenedor** (`POSTGRES_DB`, `POSTGRES_USER`,
`POSTGRES_PASSWORD`, `ML_SSL_CERTFILE`, `ML_SSL_KEYFILE`, `ML_SSL_CAFILE`,
`MLFLOW_SSL_CERTFILE`, `MLFLOW_SSL_KEYFILE`) estan documentadas en
[`.env.example`](../.env.example), que es la referencia a mantener al anadir o
quitar secretos.

En produccion los secretos se inyectan desde un gestor de secretos, fuera de git,
artefactos y logs (R-27).