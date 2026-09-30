# XMR-Forecast — Stack tecnológico

> **Nota sobre versiones.** Las versiones se fijan en los archivos de configuración (`pom.xml`, `requirements.txt`, `package.json`).

---

## 1. Resumen por capa

| Capa | Elección | Motivo principal |
|------|----------|------------------|
| **Backend principal** | **Spring Boot 3.2** (Java 21) | Monolito modular, seguridad, persistencia, API REST |
| **Servicio ML** | **FastAPI** (Python 3.11) | Ecosistema ML: TensorFlow, scikit-learn, statsmodels, Optuna |
| **Base de datos** | **PostgreSQL 15** | Integridad relacional + JSONB |
| **Caché/Colas** | **Redis 7** | Caché, colas, sesiones distribuidas |
| **Tracking ML** | **MLflow 2.8** | Parámetros, métricas, artefactos |
| **Frontend** | **React 18 + TypeScript + Vite** | SPA tipada |
| **Estilos** | **Tailwind CSS** | Desarrollo rápido |
| **Gráficos** | **Recharts** | Gráficos reactivos |
| **Contenedores** | **Docker + Docker Compose** | `docker compose up` para todo |
| **CI/CD** | **GitHub Actions** | Lint, tipos, pruebas, build |

---

## 2. Backend (Spring Boot)

| Componente | Detalle |
|------------|---------|
| **Spring Boot 3.2** | Monolito modular con módulos: `auth`, `users`, `market`, `experiments`, `predictions`, `ml-integration`, `audit`, `shared` |
| **Spring Security** | Autenticación JWT, autorización por roles |
| **Spring Data JPA** | Persistencia con PostgreSQL |
| **Spring Validation** | Validación estricta de DTOs |
| **Spring WebFlux (WebClient)** | Cliente HTTP para servicio ML con timeout, reintentos |
| **Flyway** | Migraciones versionadas |
| **JWT (jjwt 0.12)** | Tokens de corta duración (10 min) |
| **BCrypt** | Hash de contraseñas |

---

## 3. Servicio ML (FastAPI)

| Componente | Detalle |
|------------|---------|
| **FastAPI** | Framework web asíncrono |
| **Pydantic v2** | Validación de esquemas |
| **NumPy, pandas** | Manipulación de datos |
| **scikit-learn** | Modelos clásicos, escalado, métricas |
| **statsmodels** | ARIMA |
| **TensorFlow/Keras** | LSTM/GRU |
| **Optuna** | Ajuste de hiperparámetros |
| **MLflow** | Tracking de experimentos |

---

## 4. Base de datos

### 4.1 PostgreSQL

- **Por qué relacional:** experimentos, corridas, métricas y predicciones tienen relaciones claras.
- **JSONB:** hiperparámetros y listas de features varían por modelo.
- **Migraciones:** Flyway (versionadas, revisadas).
- **Índices:** optimizados para consultas por fecha y estado.

### 4.2 Redis

- Caché de predicciones
- Colas para trabajos pesados
- Sesiones distribuidas

---

## 5. Frontend

| Componente | Uso |
|------------|-----|
| **React 18 + TypeScript (strict)** | Componentes y tipado del cliente API |
| **Vite** | Servidor de desarrollo y build |
| **React Router** | Navegación |
| **TanStack Query** | Peticiones, caché, reintentos |
| **Tailwind CSS** | Estilos |
| **Recharts** | Gráficos |

---

## 6. Infraestructura y DevOps

| Elemento | Detalle |
|----------|---------|
| **Docker** | Imágenes separadas para backend, ml-service, frontend |
| **Docker Compose** | Orquesta todos los servicios |
| **GitHub Actions** | CI: lint → tipos → tests → build |
| **HTTPS** | Forzado en todos los entornos |

---

## 7. Calidad

| Herramienta | Propósito |
|-------------|-----------|
| **pytest** | Tests del servicio ML |
| **JUnit 5** | Tests del backend |
| **Vitest** | Tests del frontend |
| **ruff, mypy** | Lint y tipos de Python |
| **ESLint, tsc** | Calidad del frontend |

---

## 8. Seguridad

El protocolo aplicable está centralizado en [`05_seguridad.md`](05_seguridad.md). El stack implementa defensa en profundidad:

- **Identidad:** JWT de corta duración, BCrypt, roles `viewer/analyst/admin`
- **API:** Validación estricta, CORS restringido, rate limiting
- **Datos:** PostgreSQL, Redis y MLflow en red privada
- **ML:** Snapshots inmutables, digest, procedencia obligatoria
- **Cadena de suministro:** lockfiles, SBOM, secret scanning
- **Contenedores:** Imágenes mínimas, usuario no root
- **HTTPS:** Forzado en todos los entornos

---

## 9. Alternativas descartadas

| Alternativa | Motivo de descarte |
|-------------|-------------------|
| Microservicios completos | Dominio acotado, complejidad operativa no justificada |
| Node.js/NestJS en backend | Obligaría a separar el ML en otro servicio |
| MongoDB | Las relaciones encajan mejor en SQL |
| PyTorch | Keras es más sencillo para LSTM/GRU |

---

## 10. Servicios de Docker Compose

| Servicio | Imagen / origen | URL/puerto (dev) |
|----------|-----------------|--------------|
| `db` | PostgreSQL 15 | 5432 |
| `redis` | Redis 7 | 6379 |
| `backend` | Spring Boot 3.2 | `https://localhost:8443` (`8080` solo redirige) |
| `ml-service` | FastAPI | `https://localhost:8000` |
| `mlflow` | MLflow 2.8 | `https://localhost:5000` |
| `frontend` | React/Vite | `https://localhost:3000` |
| PostgreSQL / Redis | Servicios internos | Red privada Docker; sin exposición pública |
| `frontend` | React + Vite | 3000 |

---

## 11. Variables de entorno

| Variable | Descripción |
|----------|-------------|
| `DB_URL` | Conexión a PostgreSQL |
| `DB_USER` | Usuario de PostgreSQL |
| `DB_PASSWORD` | Contraseña de PostgreSQL |
| `REDIS_HOST` | Host de Redis |
| `REDIS_PASSWORD` | Contraseña de Redis |
| `JWT_SECRET` | Secreto para firmar JWT |
| `JWT_EXPIRATION_MINUTES` | Expiración del token |
| `CORS_ALLOWED_ORIGINS` | Orígenes permitidos |
| `ML_SERVICE_URL` | URL del servicio ML |
| `MLFLOW_TRACKING_URI` | URI de MLflow |
| `ML_SERVICE_URL` | URL HTTPS del servicio FastAPI-ML |
| `SSL_ENABLED` / `SSL_KEY_STORE*` | Activación y almacén PKCS12 TLS de Spring Boot |

En producción, los secretos se inyectan desde un gestor de secretos. Ningún valor real se versiona (R-14).
