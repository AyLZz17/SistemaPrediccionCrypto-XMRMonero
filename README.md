# XMR-Forecast

> **Aviso legal (R-11).** Análisis predictivo de series de tiempo para Monero (XMR).
> **No es asesoría financiera, no promete rentabilidad y no simula operaciones de trading
> ni backtesting.** El sistema muestra *capacidad predictiva evaluada* sobre datos históricos.

Realizado por © AyLZz17 - AyLZz Software Solutions. Todos los derechos reservados.

Plataforma de análisis predictivo y operaciones para XMR: **Spring Boot 3.2 (Java 21)** +
**FastAPI-ML (Python)** + **React 18 + TypeScript** + **PostgreSQL 15** + **Redis 7** +
**MLflow**. HTTPS obligatorio en todos los entornos.

---

## 1. Estado de verificación

Todo lo que se afirma aquí se comprobó ejecutándolo. Lo que **no** se ha ejecutado
aparece marcado como pendiente en la [sección 10](#10-limitaciones-y-pendientes).

| Componente | Comprobación | Resultado |
|---|---|---|
| Backend Spring Boot 3.2.5 / Java 21 | `mvn clean verify` | **186 tests, BUILD SUCCESS** |
| Arranque real del backend | `tools/verify-stack.ps1` (jar empaquetado contra PostgreSQL real) | **53/53 comprobaciones OK** |
| Esquema PostgreSQL | Flyway `V1`..`V6` sobre PostgreSQL 15.19 | **23 tablas**, migradas sin intervención manual |
| Contrato HTTP frontend ↔ backend | `HttpContractTest` (extrae rutas de ambos lados) | **Rutas del cliente ⊆ controladores y viceversa** |
| Contrato de registro | `RegisterPayloadContractTest` (form → record) | **6 campos idénticos en los dos lados** |
| Versión legal única | `LegalVersionsContractTest` (lee los dos archivos de origen) | **Versión, fecha, contacto y 5 rutas coinciden** |
| Consentimiento demostrable | `consent_records` en la BD real | **2 filas por alta con la versión vigente** |
| Inyección del principal | `ControllerParameterTest` (reflexión sobre los controladores) | **Todos los parámetros con `@AuthenticationPrincipal`** |
| Restricciones de integridad | Inserciones inválidas contra la BD real | **Rechazadas correctamente** |
| Migración de permisos | Comprobación de mínimo privilegio | VIEWER: 8 permisos, 0 de escritura |
| Backup y restauración | Ciclo completo borrar → restaurar | **30 + 5 filas recuperadas, 0 huérfanos** |
| Frontend React + TS | `npm run build` / `lint` / `test` | **build OK · lint 0 · 169 tests** |
| Pie de página obligatorio | `footer-on-every-route.test.tsx` | **23 rutas (12 públicas + 11 autenticadas)** |
| Documentos legales en el pie | `legal-and-consent.test.tsx` | **5 documentos + contacto + baja, en todas las rutas** |
| `docker-compose.yml` | `docker compose config` | **Válido · 6 servicios** |
| CI/CD | Parseo de `ci.yml` | **Válido · 6 jobs** |
| Servicio ML (R-13) | Análisis de imports de `app/ml` | **19 módulos, 0 imports web/BD** |

---

## 2. Arquitectura

```
                    ┌──────────────────────────────────────┐
   navegador ──HTTPS──▶  frontend :3000  (nginx, SPA)       │
                    └───────────────┬──────────────────────┘
                                    │ HTTPS (red edge/backend)
                    ┌───────────────▼──────────────────────┐
                    │  backend :8443  (Spring Boot, Java 21)│
                    │  auth · users · market · datasets     │
                    │  experiments · predictions · metrics  │
                    │  jobs · audit · notifications · ml    │
                    └───┬───────────────┬──────────────┬────┘
              HTTPS      │               │              │
            ┌────────────▼───┐     ┌─────▼──────┐  ┌────▼─────────┐
            │ ml-service     │     │ PostgreSQL │  │ Redis        │
            │ :8443          │     │ 15  (datos)│  │ 7 (caché/cola│
            │ FastAPI (R-32) │     └─────┬──────┘  └──────────────┘
            └───────┬────────┘           │
                    │  HTTPS              │
            ┌───────▼────────┐   ┌────────▼─────────┐
            │ MLflow :5000   │   │ PostgreSQL       │
            └────────────────┘   └──────────────────┘
```

**Reglas estructurales aplicadas**

- **R-13** — `ml-service/app/ml/` es un paquete puro: no importa FastAPI, Pydantic ni
  SQLAlchemy, y es usable desde un notebook o una CLI.
- **R-32** — `ml-service` no es público; solo el backend lo consume. Publica el puerto
  únicamente en `127.0.0.1` para depuración.
- **R-34** — el esquema es exclusivo de Flyway. El servicio ML no administra PostgreSQL.
- **R-27** — PostgreSQL y Redis viven en una red `internal: true` **sin puertos publicados**.
- **R-33** — HTTPS en todos los servicios; el cliente HTTP plano está deshabilitado.

---

## 3. Puesta en marcha

### Requisitos

| Herramienta | Versión | Nota |
|---|---|---|
| JDK | **21** | Obligatorio. El `pom.xml` raíz bloquea la compilación si no es 21 (R-37) |
| Maven | 3.9+ | |
| Node.js | 20+ | 22 en CI |
| Python | 3.11+ | El servicio ML |
| Docker | 24+ | Compose v2 |

> **Windows:** si `java -version` muestra 1.8, exporta el JDK 21 antes de compilar:
> ```powershell
> $env:JAVA_HOME='C:\Program Files\Eclipse Adoptium\jdk-21.0.11.10-hotspot'
> $env:PATH="$env:JAVA_HOME\bin;$env:PATH"
> ```

### Arranque

```bash
# 1. Certificados TLS de desarrollo (autofirmados, solo local)
bash docker/generate-dev-certs.sh

# 2. Variables de entorno
cp .env.example .env
# Genera el secreto de JWT (mínimo 64 bytes):
openssl rand -base64 64
# Edita .env: JWT_SECRET, POSTGRES_PASSWORD, REDIS_PASSWORD y credenciales de Google.

# 3. Levantar todo
docker compose up -d --build
docker compose ps
docker compose logs -f
```

El arranque **falla de forma deliberada** si falta `JWT_SECRET`, `POSTGRES_PASSWORD` o
`REDIS_PASSWORD`: no hay valores por defecto utilizables (R-14).

### URLs (todas HTTPS)

| Servicio | URL |
|---|---|
| Frontend | https://localhost:3000 |
| API | https://localhost:8443/api/v1 |
| Salud del backend | https://localhost:8443/actuator/health |
| OpenAPI (ADMIN) | https://localhost:8443/swagger-ui.html |
| Servicio ML | https://localhost:8000/health |
| MLflow | https://localhost:5000 |

---

## 4. Autenticación

### Registro tradicional

`POST /api/v1/auth/register` crea la cuenta en `PENDING_VERIFICATION` con rol `VIEWER`.
Contraseñas con **BCrypt (coste 12)**; nunca se almacenan en claro. Política: ≥ 12
caracteres con mayúscula, minúscula, dígito y símbolo, sin espacios.

- **Consentimiento obligatorio y demostrable (R-53).** El alta exige `acceptTerms` y
  `acceptDataPolicy`; sin ellos responde `400 VALIDATION_FAILED` señalando ambos campos
  (y el servicio responde además `400 CONSENT_REQUIRED`, código que ve el flujo de
  Google). `acceptMarketing` es opcional. Cada aceptacion se guarda en
  `consent_records` (migracion `V6`) con el **documento, la version vigente, la fecha,
  el canal y la IP**, en la misma transaccion que crea la cuenta: si falla la fila, no
  hay cuenta.
- Confirmacion de correo por token opaco (hash HMAC-SHA256 en base de datos), con
  reenvio publico `POST /api/v1/auth/verify-email/resend` que responde **`204` exista o
  no el correo** (antienumeracion), caduca los enlaces anteriores y va limitado por
  tasa. La pantalla `/verify-email` ofrece ese reenvio cuando el enlace caduco.
- Recuperación y cambio de contraseña. Tras un cambio **se revocan todas las sesiones**.
- **Bloqueo progresivo**: tras N fallos consecutivos la cuenta se bloquea temporalmente
  y se notifica al usuario.
- Cada intento (exitoso o no) queda en `login_attempts`.
- El enlace de confirmacion se envia automaticamente. El canal se elige con
  `MAIL_TRANSPORT`: `smtp` (desarrollo local: `MAIL_HOST`, `MAIL_PORT`,
  `MAIL_USERNAME`, `MAIL_PASSWORD`, `MAIL_SMTP_AUTH`, `MAIL_STARTTLS`) o `gmail`
  (Gmail API por HTTPS, que es lo que usa Render Free porque bloquea los puertos
  SMTP 25/465/587; requiere `GOOGLE_MAIL_REFRESH_TOKEN`). En ambos casos hacen
  falta `MAIL_FROM` y `FRONTEND_BASE_URL`. Opcionales: `MAIL_FROM_NAME`, `MAIL_USE_TLS`
  y `APP_PUBLIC_URL`.
- El envio se hace **despues** de confirmar la transaccion: si el canal falla, la
  cuenta se crea igualmente y la bandeja de notificaciones lo indica en lugar de
  anunciar un enlace que no salio.

### JWT propio

Tras un login tradicional o un OAuth, la aplicación emite su propio JWT:

| Claim | Valor |
|---|---|
| `sub` | id del usuario |
| `iss` / `aud` | emisor y audiencia de la API |
| `iat` / `exp` | 15 minutos de vida por defecto |
| `jti` | identificador único, revocable |
| `roles` | `VIEWER` · `ANALYST` · `ADMIN` |

Firmado con **HS512**, clave de ≥ 64 bytes que solo llega por entorno. El algoritmo se
fija en el servidor: `alg=none` y el cambio de clave se rechazan. Los refresh tokens se
**rotan** en cada uso y se guardan como hash; reutilizar uno ya rotado revoca toda su
familia (detección de robo).

### Documentos legales

Cinco documentos publicos, enlazados desde el pie en **todas** las rutas: `/terms`
(términos y condiciones), `/privacy` (privacidad), `/data-policy` (tratamiento de
datos: Ley 1581 de 2012 y Decreto 1074 de 2015, GDPR/CCPA cuando aplique),
`/cookies` y `/legal-notice` (aviso legal y canal de contacto).

La **fuente unica de la version** es `LegalDocuments.CURRENT_VERSION` en el backend y
`LEGAL_VERSION` en `frontend/src/config/legal.ts`; `GET /api/v1/meta/legal` la publica
sin sesion y `LegalVersionsContractTest` falla si los dos archivos dejan de coincidir.
El texto esta redactado para este proyecto y **debe revisarlo un abogado colombiano**
antes de presentarlo como conforme a la normativa.

### Google OAuth 2.0 / OpenID Connect

Authorization Code Flow, con el canje **siempre en el backend**:

1. El botón apunta a `GET /api/v1/auth/google/authorize` → el backend redirige a Google
   con `state` y `nonce` de un solo uso (10 min de ventana). Los aceptes de los
   documentos viajan **dentro de ese `state`**.
2. Google redirige al navegador a `GET /api/v1/auth/google/callback`.
3. El backend valida el ID Token: **firma RSA contra el JWKS**, `iss`, `aud`, `exp`,
   `nonce` y `state`.
4. Responde **302** al frontend con la sesión en el **fragmento** (`#access_token=…`), que
   nunca llega al servidor, ni a los logs, ni a la cabecera `Referer`.
5. El frontend la consume y borra el fragmento con `history.replaceState`.

Si la cuenta **no existe** y el `state` no trae los dos aceptes, el backend responde
`error_code=CONSENT_REQUIRED` y **no crea la cuenta**: el frontend presenta los
documentos y reintenta con el consentimiento recogido. Las cuentas existentes inician
sesion sin ese paso.

La **identidad es el `sub`**, no el correo. El correo solo permite *enlazar* una cuenta
local ya existente: nunca concede acceso por sí solo, y solo si Google afirma
`email_verified`. El `client_secret` no sale del servidor.

Para activar el boton en un entorno concreto tambien deben configurarse
`GOOGLE_CLIENT_ID`, `GOOGLE_CLIENT_SECRET` y `GOOGLE_REDIRECT_URI`. Si faltan las
credenciales, `/auth/google/authorize` responde `503 OAUTH_NOT_CONFIGURED` de forma
intencionada; no es posible completar OAuth sin una aplicacion registrada en Google.

### Roles

| Capacidad | VIEWER | ANALYST | ADMIN |
|---|:--:|:--:|:--:|
| Leer mercado, predicciones, métricas | ✔ | ✔ | ✔ |
| Crear predicciones / experimentos | — | ✔ | ✔ |
| Lanzar y cancelar trabajos | — | ✔ | ✔ |
| Promover modelo a campeón | — | — | ✔ |
| Gestionar usuarios y roles | — | — | ✔ |
| Consultar auditoría | — | — | ✔ |

8 / 12 / 16 permisos respectivamente. Comprobado contra la base de datos.

---

## 5. Base de datos

22 tablas criadas por Flyway (`V1` a `V5`). El backend corre con `ddl-auto: validate`: el esquema solo
cambia por migración versionada (R-34).

**Identidad:** `users`, `roles`, `permissions`, `role_permissions`, `user_roles`,
`oauth_accounts`, `refresh_tokens`, `revoked_tokens`, `login_attempts`,
`password_reset_tokens`, `email_verification_tokens`.
**Negocio:** `market_data`, `dataset_versions`, `experiments`, `experiment_runs`,
`models`, `model_versions`, `predictions`, `metrics`, `jobs`, `notifications`.
**Auditoría:** `audit_events`.

Restricciones verificadas contra una base real:

| Restricción | Comportamiento comprobado |
|---|---|
| `uq_users_email` | Rechaza correo duplicado |
| `ck_users_local_password` | Una cuenta `LOCAL` exige hash de contraseña |
| `ck_users_status` | Rechaza estados fuera del catálogo |
| `ck_market_ohlc` | Rechaza velas incoherentes (`high < low`) |
| `uq_market_data` | Evita velas duplicadas |
| `uq_predictions` | Una predicción por modelo, símbolo y fecha |
| `uq_model_versions_single_champion` | Un solo campeón por modelo |
| `ck_runs_split` | Las proporciones deben sumar exactamente 1 |
| FK (27 en total) | Impiden datos huérfanos |

### Backup y restauración

```bash
bash docker/backup.sh backup           # volca, calcula SHA-256, aplica retención
bash docker/backup.sh verify  <fichero>
bash docker/backup.sh restore <fichero> # exige verificación previa
bash docker/backup.sh list
```

**Probado de extremo a extremo:** con 2 usuarios, 2 velas, 3 roles y 16 permisos, se
generó el volcado, se borró todo y se restauró. Se recuperaron los 2 usuarios con sus
roles correctos, las 2 velas y el catálogo completo, con **0 filas huérfanas**.

Los volcados contienen datos de usuarios: ciifralos y restáuralos solo en entornos
controlados. Retención por defecto: 30 días.

---

## 6. Servicio de Machine Learning

Paquete puro `app/ml/` (R-13), envuelto por FastAPI solo en la capa HTTP.

**Ingesta → validación → limpieza → features → modelos → métricas → trazabilidad.**

Indicadores causales (RSI, MACD, medias móviles) construidos con `rolling` y `ewm`:
prohibidos `shift(-n)` y ventanas centradas (R-03). El `MinMaxScaler` se ajusta
**únicamente con train** (R-02) y las predicciones se des-escalan a USD antes de medir.

Los cinco modelos: **LSTM**, **GRU**, **media móvil**, **regresión lineal** y **ARIMA**.
ARIMA se evalúa con **pronóstico rodante de un paso**, para que sea comparable con el LSTM.
Todos se miden sobre **las mismas fechas de test** (R-05) y el campeón se elige por
**validación**, nunca por test (R-24). Un resultado negativo es un resultado válido (R-09).

---

## 7. Seguridad

| Control | Implementación |
|---|---|
| Contraseñas | BCrypt 12; nunca en claro ni en logs |
| Tokens opacos | HMAC-SHA256 en base de datos; el valor en claro solo en memoria |
| JWT | HS512, `alg` fijo, `iss`/`aud`/`exp`/`jti`, revocación por `jti` |
| CSRF | Desactivado **porque el JWT de acceso no viaja en cookie**: viaja en la cabecera `Authorization`. La cookie de refresh `xmr_refresh` sí existe, con `HttpOnly` + `Secure` + `SameSite=Strict` + `Path=/api/v1/auth`; se protege por construcción de la cookie, no por el filtro. En OAuth se usa `state` + `nonce` |
| CORS | Solo orígenes HTTPS explícitos; sin comodines |
| Rate limiting | Redis, ventana fija; 429 con `Retry-After` |
| IDOR | Consultas acotadas al propietario; 404 en lugar de 403 |
| Inyección SQL | JPA con parámetros vinculados; JPQL en las consultas propias |
| XSS | React escapa por defecto; CSP y `X-Content-Type-Options` en nginx |
| Cabeceras | HSTS (1 año, `includeSubDomains`), `X-Frame-Options: DENY`, `Referrer-Policy` |
| Logs | JSON estructurado con `request_id`/`trace_id`; sin secretos ni tokens |
| Auditoría | `audit_events` append-only, con redacción automática de claves sensibles |
| Consentimiento legal | `consent_records` con documento + versión + IP en la misma transacción que el alta; sin fila no hay cuenta (R-53) |
| Correo de verificación | Token de un solo uso con hash; reenvío `204` exista o no el correo y limitado por tasa |

La auditoría **redacta** cualquier clave `password`, `token`, `secret`, `authorization` y
similares antes de persistir el detalle.

---

## 8. Comandos

```bash
# Backend
mvn clean verify                       # tests + empaquetado
mvn -pl backend spring-boot:run         # ejecución local

# Frontend
cd frontend && npm ci && npm run build && npm run test

# Servicio ML
cd ml-service && python -m pytest && python -m ruff check . && python -m mypy app

# Docker
docker compose up -d --build
docker compose ps
docker compose logs -f backend
docker compose down -v

# Carga (k6) — requiere el sistema levantado
k6 run -e BASE_URL=https://localhost:8443 -e VUS=100 -e DURATION=1m loadtests/api.js
```

> Ejecuta siempre `mvn clean`: si se omite, Maven reutiliza clases compiladas
> previamente con Eclipse y los fallos que aparecen no corresponden al código.

---

## 9. Documentación

| Documento | Contenido |
|---|---|
| [`docs/01_documentacion.md`](docs/01_documentacion.md) | Visión, requisitos, casos de uso, API |
| [`docs/02_stack_tecnologico.md`](docs/02_stack_tecnologico.md) | Stack, alternativas, despliegue |
| [`docs/03_machine_learning.md`](docs/03_machine_learning.md) | Formulación, features, modelos, métricas |
| [`docs/04_diagramas.md`](docs/04_diagramas.md) | Diagramas Mermaid |
| [`docs/05_seguridad.md`](docs/05_seguridad.md) | Threat model, controles, runbook de incidentes |
| [`docs/07_pruebas_carga.md`](docs/07_pruebas_carga.md) | Resultados de carga (o su ausencia) |
| [`docs/08_operacion.md`](docs/08_operacion.md) | Operación, recuperación, retención |
| [`AGENTS.md`](AGENTS.md) | Reglas del proyecto · `SKILLS.md` · `MEMORY.md` |

---

## 10. Limitaciones y pendientes

Esta sección es deliberadamente explícita: **el sistema no está listo para producción**
hasta que estos puntos se cierren.

**No verificado**
- **Pruebas de carga**: el script `loadtests/api.js` existe, pero **no se ha ejecutado**.
  No se afirma ninguna capacidad de usuarios concurrentes ni de latencia. Véase
  [`docs/07_pruebas_carga.md`](docs/07_pruebas_carga.md).
- **OAuth 2.0 con Google**: el código implementa y valida el flujo completo
  (firma, `iss`, `aud`, `nonce`, `state`), pero **no se ha ejecutado contra Google**
  porque requiere credenciales reales de un proyecto de Google Cloud.
- **Correo real en producción**: el canal `gmail` está implementado, pero **no se ha
  ejecutado con credenciales reales** (falta habilitar la Gmail API con el scope
  `gmail.send` y fijar `MAIL_TRANSPORT=gmail` + `GOOGLE_MAIL_REFRESH_TOKEN` en Render).
  En local el canal está muerto y se comprueba que el alta sobrevive a eso.
- **Contraste WCAG y pruebas responsive**: los tokens están calculados para cumplir AA,
  pero **no se midieron con axe sobre páginas renderizadas**.
- **Cobertura de tests**: los tests del backend cubren seguridad y contratos. Faltan los
  de integración contra base de datos y los de controlador con `@WebMvcTest`.

**Revisión legal**
- Los cinco documentos públicos (términos, privacidad, tratamiento de datos, cookies y
  aviso legal) están redactados para este proyecto y enlazados desde el pie, con
  consentimiento registrado en `consent_records`. **No han sido revisados por un
  abogado colombiano**: deben revisarse antes de presentarlos como conformes a la
  Ley 1581 de 2012, el Decreto 1074 de 2015 o el GDPR.

**Vulnerabilidades conocidas (R-29)**
- `npm audit` reporta vulnerabilidades transitivas en el árbol de desarrollo
  (principalmente la cadena esbuild/Vite y ESLint 8). Requiere una decisión deliberada:
  actualizar a Vite 5+/ESLint 9 o documentar la excepción con fecha de caducidad.

**Diferencias con la especificación original**
- Python **3.12.10** en el entorno de desarrollo, donde la especificación pedía 3.11.
  El código es compatible con ambas.
- `ml-service` está en su propia raíz de proyecto, no en `backend/app/ml/` como
  preveía el §5 de `AGENTS.md`, porque R-13 exige que el paquete ML no dependa del
  framework HTTP.

**Decisiones abiertas sin resolver**
- Modelo campeón por defecto (LSTM frente a GRU, D-03).
- Fuente de datos definitiva y periodo de recolección (D-01, D-02).
- MFA para administradores: no implementado.
- Límites numéricos de calidad acordados con el cliente (D-07).
- Superficie pública: hoy es la landing con los documentos legales (D-14). Un panel
  público con datos de mercado exige abrir `/api/v1/market/**` a anónimos, decisión
  pendiente de confirmar.
- Credenciales expuestas en el pasado pendientes de rotar y `ML_SERVICE_URL` con
  `sync: false` en `render.yaml`.

---

© AyLZz17 - AyLZz Software Solutions. Todos los derechos reservados.