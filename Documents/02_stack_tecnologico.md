# XMR-Forecast — Stack tecnológico

> **Nota sobre versiones.** No se fijan números de versión en este documento. Al crear el entorno se debe **verificar la versión estable vigente** de cada paquete y fijarla en el lockfile (R-17), comprobando la compatibilidad entre Python, TensorFlow/Keras y NumPy.

Contenido: 1 Resumen · 2 Backend · 3 Base de datos y almacenamiento · 4 Machine learning · 5 Frontend · 6 Infraestructura y DevOps · 7 Calidad · 8 Seguridad · 9 Alternativas descartadas · 10 Servicios de Docker Compose · 11 Variables de entorno

---

## 1. Resumen por capa

| Capa | Elección | Motivo principal |
|------|----------|------------------|
| Lenguaje backend/ML | **Python 3.11** | Ecosistema de ciencia de datos; compatible con TensorFlow (verificar) |
| API | **FastAPI** + Pydantic v2 + Uvicorn | Tipado, validación, OpenAPI automática, rendimiento |
| ORM / migraciones | **SQLAlchemy 2.0** + **Alembic** | Estándar en Python, migraciones versionadas |
| Base de datos | **PostgreSQL** | Integridad relacional + JSONB para hiperparámetros |
| Cola y caché | **Redis** | Broker de Celery, caché ligera |
| Tareas | **Celery** + Celery Beat | Entrenamiento asíncrono e ingesta diaria programada |
| Datos | **pandas**, **NumPy** | Manipulación de series de tiempo |
| ML clásico | **scikit-learn**, **statsmodels** (+ `pmdarima` opcional) | Regresión lineal, escalado, `TimeSeriesSplit`, ARIMA |
| Deep learning | **TensorFlow / Keras** | LSTM/GRU con `EarlyStopping` |
| Ajuste de hiperparámetros | **Optuna** | Búsqueda eficiente, integrable con MLflow |
| Tracking | **MLflow** | Parámetros, métricas, curvas y artefactos por corrida |
| Frontend | **React + TypeScript** (Vite) | SPA tipada, ecosistema amplio |
| Estilos | **Tailwind CSS** | Desarrollo rápido y consistente |
| Gráficos | **Apache ECharts** | Soporta **velas (candlestick)**, series largas y *zoom* |
| Datos en cliente | **TanStack Query** | Caché, reintentos, estados de carga |
| Contenedores | **Docker + Docker Compose** | `docker compose up` para todo el sistema |
| CI/CD | **GitHub Actions** | Lint, tipos, pruebas y build |

---

## 2. Backend

| Componente | Detalle |
|-----------|---------|
| **FastAPI** | Routers por dominio (`auth`, `market`, `experiments`, `predictions`, `admin`); dependencias para sesión y usuario; documentación OpenAPI con el aviso legal. |
| **Pydantic v2** | Esquemas de entrada/salida y configuración (`pydantic-settings`) desde variables de entorno. |
| **SQLAlchemy 2.0** | Modelos declarativos, sesiones por petición. |
| **Alembic** | Migraciones (R-15). |
| **Celery + Redis** | Tareas `ingest_daily`, `run_experiment`, `evaluate_run`. Beat programa la ingesta diaria. |
| **Google OAuth 2.0** | Inicio de sesión con cuentas de Google como opción de autenticación externa. |
| **Autenticación** | JWT (`PyJWT`), contraseñas con Argon2 o bcrypt, roles `viewer/analyst/admin`. |
| **Registro (logging)** | Logs estructurados en JSON; identificador de correlación por petición. |

**Aislamiento del núcleo ML.** `app/ml` no importa FastAPI ni SQLAlchemy (R-13): recibe DataFrames/arrays y devuelve resultados. Los *services* traducen entre BD y ML.

---

## 3. Base de datos y almacenamiento

### 3.1 PostgreSQL
- **Por qué relacional:** experimentos, corridas, métricas y predicciones tienen relaciones claras y necesitan integridad (claves foráneas, `UNIQUE (asset_id, source_id, trade_date)` para una ingesta idempotente).
- **JSONB:** hiperparámetros y listas de features varían por modelo.
- **Volumen:** una serie diaria de XMR tiene del orden de miles de filas; no se necesita una base especializada en series de tiempo (TimescaleDB, InfluxDB) en Fase 1. Se mantiene como opción si se ampliara a datos intradía o a muchas criptomonedas.
- **Pruebas:** integración contra PostgreSQL real (no SQLite) por el uso de JSONB.
- Esquema: `01_documentacion.md` §7 y ER en `04_diagramas.md` §8.

### 3.2 Redis
- Broker de Celery (y caché opcional de la predicción diaria).

### 3.3 Sistema de archivos / volúmenes
| Contenido | Ruta | Nota |
|-----------|------|------|
| Snapshots de datos | `data/snapshots/` | Con checksum; git ignora los datos, versiona el *manifest* |
| Modelos y scalers | `artifacts/models/{run_id}/` | `model.keras`, `scaler.joblib`, `config.yaml` |
| Reportes | `artifacts/reports/` | CSV/PDF exportados |
| Backend de MLflow | Base de datos separada en PostgreSQL | Artefactos en volumen |

---

## 4. Machine learning

### 4.1 Cómo se usa el ML en el proyecto

| Etapa | Qué ocurre | Herramientas |
|-------|-----------|--------------|
| 1. Datos | Ingesta OHLCV, limpieza, orden cronológico | pandas, `yfinance` (o CSV) |
| 2. Características | Variación %, medias móviles, RSI, MACD (solo pasado) | pandas, `ta`/`pandas-ta` o implementación propia |
| 3. Partición | Train / validación / test **cronológicos** | scikit-learn `TimeSeriesSplit` |
| 4. Escalado | `MinMaxScaler` ajustado **solo con train** | scikit-learn |
| 5. Ventanas | 30 días → día 31 (configurable) | NumPy |
| 6. Baselines | Media móvil, regresión lineal, ARIMA, persistencia | scikit-learn, statsmodels |
| 7. Modelo recurrente | LSTM (GRU alternativa), regresión y dirección | TensorFlow/Keras |
| 8. Ajuste | Búsqueda en validación | Optuna |
| 9. Evaluación | MAE, RMSE, MAPE, aciertos; análisis de fallos | scikit-learn, NumPy |
| 10. Registro | Todo lo anterior por corrida | MLflow |
| 11. Servicio | Modelo campeón sirve la predicción t+1 | FastAPI + caché en memoria |

Detalle completo, con arquitecturas e hiperparámetros, en `03_machine_learning.md`.

### 4.2 Por qué Keras/TensorFlow
- API simple para LSTM/GRU, `EarlyStopping` y `ModelCheckpoint`.
- PyTorch es una alternativa válida (ver §9); el diseño de `ForecastModel` permitiría cambiarlo sin tocar el resto.

### 4.3 Fuente de datos (decisión abierta D-01)

| Opción | Ventaja | Riesgo |
|--------|---------|--------|
| Yahoo Finance vía `yfinance` (XMR-USD) | Sencillo | Biblioteca no oficial; puede cambiar o limitar |
| CryptoDataDownload (CSV) | Reproducible | Descarga manual / cambio de formato |
| CoinGecko API | API documentada | Límites de uso; datos agregados |

Mitigación: `DataSource` como interfaz y **snapshot inmutable con checksum** de lo que realmente se usó.

---

## 5. Frontend

| Componente | Uso |
|-----------|-----|
| **React 18+ / TypeScript (strict)** | Componentes y tipado del cliente API |
| **Vite** | Servidor de desarrollo y build |
| **React Router** | Navegación entre las pantallas de `01_documentacion.md` §9 |
| **TanStack Query** | Peticiones, caché, reintentos |
| **Tailwind CSS** | Estilos; modo claro/oscuro opcional |
| **Apache ECharts** (`echarts-for-react`) | Velas + volumen, real vs. predicho, curvas de pérdida, barras de métricas |
| **Zod** (opcional) | Validación de formularios y respuestas |
| **Vitest + Testing Library** | Pruebas de componentes |

Reglas de UI: aviso legal siempre visible (R-11); mostrar siempre los baselines junto al modelo; estados de carga y error explícitos.

Generación del cliente: `openapi-typescript` (opcional) para tipar el cliente desde la especificación OpenAPI del backend.

---

## 6. Infraestructura y DevOps

| Elemento | Detalle |
|----------|---------|
| Docker | Imágenes separadas para backend/worker (misma base) y frontend (build estático servido por Nginx) |
| Docker Compose | Orquesta los servicios de §10 |
| GitHub Actions | `ci.yml`: ruff → mypy → pytest (con servicio PostgreSQL) → Vitest → build de imágenes |
| pre-commit | ruff, formato, comprobaciones de secretos |
| Makefile | `up`, `test`, `lint`, `migrate`, `ingest`, `experiment` |
| Gestión de dependencias | `pyproject.toml` + lockfile (uv o pip-tools); `package-lock.json` en frontend |

**Despliegue.** Ejecución local o en un servidor con Docker. Si se despliega públicamente, poner un proxy inverso con HTTPS y no exponer PostgreSQL ni Redis.

---

## 7. Calidad

| Herramienta | Propósito |
|-------------|-----------|
| pytest, pytest-cov | Pruebas y cobertura (objetivo ≥ 80 % en `ml/`, D-07) |
| httpx / `TestClient` | Pruebas de API |
| ruff | Lint y formato de Python |
| mypy | Tipos |
| ESLint + `tsc --noEmit` | Calidad del frontend |
| Vitest | Pruebas del frontend |
| Tests de no fuga | Verifican R-01 a R-03 |

---

## 8. Seguridad

El protocolo aplicable está centralizado en [`05_seguridad.md`](05_seguridad.md). El stack implementa defensa en profundidad:

- **Identidad:** JWT de corta duración, refresh rotatorio revocable si se necesita sesión prolongada, Argon2id, MFA obligatorio para `admin` en producción y RBAC con autorización por objeto.
- **API:** Pydantic con allowlist y límites, SQL parametrizado, CORS por origen, CSRF si se usan cookies, rate limiting, timeouts, headers seguros y protección SSRF para fuentes externas.
- **Datos y servicios:** PostgreSQL, Redis y MLflow en red privada; usuarios de servicio separados; Celery con JSON, tareas idempotentes y límites de recursos; `audit_log` append-only y logs sin secretos.
- **ML:** snapshots inmutables, manifest y digest de datos/modelos/scalers, validación contra poisoning y procedencia obligatoria antes de promover un campeón.
- **Cadena de suministro:** lockfiles, SBOM, secret scanning, SAST/SCA, escaneo de imágenes, provenance y acciones CI fijadas por commit.
- **Contenedores:** imágenes mínimas, usuario no root, sin `privileged`, capabilities mínimas, filesystem de solo lectura cuando sea posible, seccomp, límites y Docker rootless/user namespaces.
- **Operación:** HTTPS/TLS, backups cifrados y restaurables, alertas, runbooks de incidentes, revisión de accesos y matriz de controles `SEC-*`.

El baseline se verifica con OWASP ASVS 5.0.0, OWASP Top 10 2025, OWASP API Security Top 10 2023, NIST CSF 2.0, NIST SSDF, NIST SP 800-63B-4, NIST SP 800-61 Rev. 3, NIST AI RMF y MITRE ATLAS. Las instrucciones, evidencias, frecuencias y excepciones están en el documento dedicado.

---

## 9. Alternativas descartadas (y por qué)

| Alternativa | Motivo de descarte / condición para reconsiderarla |
|-------------|-----------------------------------------------------|
| Streamlit / Dash como interfaz | Muy rápido para prototipos, pero limita la separación API/UI, los roles y el trabajo asíncrono. Válido si se prioriza velocidad sobre arquitectura. |
| PyTorch | Igual de válido. Se eligió Keras por la sencillez; reconsiderar si se implementan Transformers. |
| Django / DRF | Más pesado para una API orientada a ML; FastAPI ofrece OpenAPI y tipado nativos. |
| Node.js/NestJS en backend | Obligaría a separar el ML en otro servicio; se prefiere un solo lenguaje para backend + ML. |
| MongoDB | Las relaciones (experimento → corridas → métricas → predicciones) encajan mejor en SQL. |
| TimescaleDB / InfluxDB | Innecesarios con datos diarios; opción si se amplía a intradía. |
| Prophet | No forma parte de los modelos base acordados; podría ser una extensión. |
| Plotly / Recharts | Recharts no ofrece velas de forma nativa; Plotly es válido pero más pesado. |
| RQ / ARQ en lugar de Celery | Más ligeros; reconsiderar si Celery resulta excesivo (D-06). |

---

## 10. Servicios de Docker Compose

| Servicio | Imagen / origen | Puerto (dev) | Depende de |
|----------|-----------------|--------------|-----------|
| `db` | PostgreSQL | 5432 (solo red interna en producción) | — |
| `redis` | Redis | 6379 (interno) | — |
| `backend` | Imagen propia (Uvicorn) | 8000 | `db`, `redis` |
| `worker` | Imagen del backend, comando Celery | — | `db`, `redis`, `mlflow` |
| `beat` | Imagen del backend, comando Celery Beat | — | `redis` |
| `mlflow` | Imagen de MLflow | 5000 | `db` |
| `frontend` | Vite (dev) / Nginx (prod) | 5173 / 80 | `backend` |

Volúmenes: `pgdata`, `artifacts`, `data`.

---

## 11. Variables de entorno (ejemplo `.env.example`)

| Variable | Descripción |
|----------|-------------|
| `DATABASE_URL` | Conexión a PostgreSQL |
| `REDIS_URL` | Conexión a Redis |
| `JWT_SECRET`, `JWT_EXPIRE_MINUTES` | Autenticación |
| `CORS_ORIGINS` | Orígenes permitidos |
| `MLFLOW_TRACKING_URI` | Servidor de MLflow |
| `ARTIFACTS_DIR`, `DATA_DIR` | Rutas de artefactos y datos |
| `DEFAULT_ASSET`, `DEFAULT_SOURCE` | `XMR` y la fuente elegida (D-01) |
| `INGEST_CRON` | Hora de la ingesta diaria |
| `GLOBAL_SEED` | Semilla base para reproducibilidad |

En producción, los secretos se inyectan desde un gestor de secretos o mecanismo equivalente y se rotan. `JWT_SECRET`, credenciales de DB/Redis/MLflow, OAuth y claves de firma no se guardan en `.env.example`, imágenes, logs, notebooks, MLflow ni artifacts.

Ningún valor real se versiona (R-14).
