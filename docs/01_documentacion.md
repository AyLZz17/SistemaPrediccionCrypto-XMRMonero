# XMR-Forecast — Documentación del proyecto

> **Aviso legal.** Esta aplicación proporciona análisis predictivo de series de tiempo. **No constituye asesoría financiera, no promete rentabilidad y no simula operaciones de inversión.**

**Versión:** 3.0 · **Fecha:** 2026-09-30 · **Documentos fuente:** `Propuesta_Monero_IEEE.docx`, `Fase1_Proyecto7.docx`

---

## 1. Resumen

XMR-Forecast es una aplicación web comercial que permite predecir el **precio de cierre del día siguiente** de Monero (XMR) o la **dirección** del precio (sube/baja). Compara una red **LSTM** (GRU como alternativa) con **media móvil, regresión lineal y ARIMA**.

**Arquitectura v3:** Monolito modular **Spring Boot 3.2** (Java 21) + servicio especializado **FastAPI-ML** (Python 3.11) + **React 18** + **PostgreSQL 15** + **Redis 7** + **MLflow 2.8**. **HTTPS forzado** en todos los entornos.

---

## 2. Arquitectura del sistema

### 2.1 Componentes principales

| Componente | Tecnología | Responsibilidad |
|------------|------------|-----------------|
| **Frontend** | React 18 + TypeScript + Vite | Interfaz de usuario, visualización |
| **Backend principal** | Spring Boot 3.2 (Java 21) | API REST, autenticación, autorización, persistencia, orquestación |
| **Servicio ML** | FastAPI (Python 3.11) | Inferencia, entrenamiento, evaluación ML |
| **Base de datos** | PostgreSQL 15 | Datos, experimentos, predicciones, usuarios |
| **Caché/Colas** | Redis 7 | Caché, colas, sesiones distribuidas |
| **Tracking ML** | MLflow 2.8 | Tracking de experimentos y artefactos |

### 2.2 Flujo de comunicación

```
Frontend (React)
    ↓ HTTPS
Spring Boot (API REST /api/v1)
    ↓ HTTPS/TLS (red interna)
FastAPI-ML (servicio especializado)
    ↓
MLflow (tracking)
```

**Reglas de comunicación:**
- El frontend **solo** se comunica con Spring Boot mediante HTTPS.
- Spring Boot **solo** se comunica con FastAPI-ML mediante HTTPS/TLS en red interna.
- PostgreSQL, Redis, MLflow y FastAPI-ML **no** son accesibles desde el navegador.
- Todas las conexiones entre servicios utilizan HTTPS/TLS.

### 2.3 Justificación del monolito modular

Se adopta un **monolito modular Spring Boot** en lugar de microservicios completos porque:

1. **Dominio acotado:** El dominio inicial (predicción de XMR) es bien definido.
2. **Complejidad operativa reducida:** Un solo despliegue, un solo pipeline CI/CD.
3. **Reglas compartidas:** Los módulos comparten reglas de negocio, seguridad y persistencia.
4. **Facilidad de pruebas:** Un monolito modular facilita pruebas de integración.
5. **Consistencia:** Un solo proceso garantiza consistencia transaccional.

**Condiciones para separar servicios en el futuro:** necesidad demostrada de escalado independiente, límites de dominio estables, requisitos de disponibilidad diferentes, despliegues desacoplados o carga ML que afecte al backend.

---

## 3. Endpoints públicos

### 3.1 Spring Boot (API REST)

| Método | Ruta | Rol | Descripción |
|--------|------|-----|-------------|
| GET | `/api/v1/health` | público | Estado del servicio |
| POST | `/api/v1/auth/login` | público | Devuelve JWT |
| GET | `/api/v1/auth/me` | viewer+ | Usuario actual |
| GET | `/api/v1/market/ohlcv` | viewer+ | Serie OHLCV |
| GET | `/api/v1/market/summary` | viewer+ | Estadísticos |
| POST | `/api/v1/market/ingest` | analyst+ | Ingesta manual → `202` |
| GET / POST | `/api/v1/datasets` | viewer+ / analyst+ | Listar / crear versión de dataset |
| POST | `/api/v1/experiments` | analyst+ | Crear y encolar experimento → `202` |
| GET | `/api/v1/experiments` | viewer+ | Listado paginado |
| GET | `/api/v1/experiments/{id}` | viewer+ | Detalle y estado |
| GET | `/api/v1/experiments/{id}/runs` | viewer+ | Corridas del experimento |
| GET | `/api/v1/experiments/{id}/comparison` | viewer+ | Tabla comparativa |
| GET | `/api/v1/predictions/next` | viewer+ | Predicción t+1 del campeón |
| PUT | `/api/v1/runs/{id}/champion` | admin | Designar campeón |
| GET / POST | `/api/v1/admin/users` | admin | Gestión de usuarios |

### 3.2 FastAPI-ML (servicio interno)

| Método | Ruta | Descripción |
|--------|------|-------------|
| GET | `/health` | Health check |
| POST | `/api/v1/ml/infer` | Inferencia |
| POST | `/api/v1/ml/train` | Entrenamiento asíncrono |
| GET | `/api/v1/ml/models` | Listar modelos |
| GET | `/api/v1/ml/models/{model_id}` | Metadata de modelo |
| GET | `/api/v1/ml/metrics/regression` | Métricas de regresión |
| GET | `/api/v1/ml/metrics/direction` | Métricas de dirección |

---

## 4. Despliegue y dependencias

### 4.1 Servicios Docker Compose

| Servicio | URL/puerto (dev) | Descripción |
|----------|--------------|-------------|
| `db` | 5432 | PostgreSQL 15 |
| `redis` | 6379 | Redis 7 |
| `frontend` | `https://localhost:3000` | React/Vite; consume únicamente HTTPS |
| `backend` | `https://localhost:8443` (`8080` redirige a HTTPS) | Spring Boot 3.2 |
| `ml-service` | `https://localhost:8000` | FastAPI-ML con TLS |
| `mlflow` | `https://localhost:5000` | MLflow 2.8 con TLS |
| PostgreSQL / Redis | Red interna Docker | No deben exponerse al navegador ni a Internet |
| `frontend` | 3000 | React + Vite |

### 4.2 Variables de entorno

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

---

## 5. Seguridad

- **Autenticación:** JWT de corta duración (10 minutos)
- **Autorización:** Roles `viewer`, `analyst`, `admin`
- **Contraseñas:** BCrypt con salt único
- **HTTPS:** Forzado en todos los entornos
- **CORS:** Allowlist de orígenes HTTPS
- **TLS local:** los certificados de desarrollo son autofirmados y solo sirven para el entorno local; producción debe usar certificados emitidos por una autoridad confiable.
- **Conexiones internas:** Spring Boot usa `ML_SERVICE_URL=https://ml-service:8000` y ML usa `MLFLOW_TRACKING_URI=https://mlflow:5000`.
- **Rate limiting:** En login y endpoints de trabajo
- **Auditoría:** Registro de acciones sensibles
- **Secretos:** Solo por variables de entorno

---

## 6. Calidad y pruebas

| Nivel | Herramientas |
|-------|--------------|
| Backend | JUnit 5, Spring Boot Test |
| Servicio ML | pytest, pytest-asyncio |
| Frontend | Vitest, Testing Library |
| Integración | TestContainers, Docker Compose |
| Estática | ruff, mypy, ESLint, tsc |

---

## 7. Referencias

Las referencias [1]–[9] son las del documento `Propuesta_Monero_IEEE.docx`. No se añaden referencias nuevas sin verificarlas (R-21).
