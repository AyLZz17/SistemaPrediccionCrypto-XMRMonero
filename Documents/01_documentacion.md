# XMR-Forecast — Documentación del proyecto

> **Aviso legal.** Esta aplicación proporciona análisis predictivo de series de tiempo. **No constituye asesoría financiera, no promete rentabilidad y no simula operaciones de inversión.**

**Versión:** 1.0 · **Fecha:** 2026-09-29 · **Documentos fuente:** `Propuesta_Monero_IEEE.docx`, `Fase1_Proyecto7.docx`

Contenido: 1 Resumen · 2 Problema y objetivos · 3 Alcance · 4 Requisitos · 5 Actores y casos de uso · 6 Arquitectura · 7 Modelo de datos · 8 API REST · 9 Interfaz · 10 Calidad y pruebas · 11 Plan de trabajo · 12 Riesgos · 13 Trazabilidad · 14 Seguridad transversal · 15 Referencias

---

## 1. Resumen

XMR-Forecast es una aplicación web comercial que permite a los usuarios predecir el **precio de cierre del día siguiente** de Monero (XMR) o la **dirección** del precio (sube/baja). Compara una red **LSTM** (GRU como alternativa) con **media móvil, regresión lineal y ARIMA**, con partición cronológica y métricas **MAE, RMSE y MAPE**.

La aplicación proporciona:
- Ingesta automática de datos OHLCV diarios
- Entrenamiento y evaluación de modelos predictivos
- Comparación de modelos con métricas objetivas
- Visualización interactiva de resultados
- Exportación de reportes
- Gestión de usuarios y permisos

---

## 2. Problema y objetivos

**Problema.** La alta volatilidad de Monero y la multiplicidad de factores que la determinan dificultan identificar tendencias. El sistema proporciona herramientas para evaluar modelos predictivos de forma objetiva.

**Objetivo general.** Desarrollar una aplicación web que permita predecir el precio de Monero con datos históricos y modelos de series de tiempo y aprendizaje automático.

**Objetivos específicos** (OE) y su materialización:

| OE | Objetivo | Componente del sistema |
|----|----------|------------------------|
| OE-1 | Recolectar datos históricos (apertura, cierre, máximo, mínimo, volumen) | Módulo de ingesta + tabla `ohlcv_daily` |
| OE-2 | Analizar tendencias, volatilidad y patrones temporales | Pantalla de exploración (EDA) |
| OE-3 | Preprocesar y normalizar | `ml/data` (limpieza, escalado, ventanas) |
| OE-4 | Construir modelos base | `ml/models/statistical` |
| OE-5 | Implementar LSTM/GRU | `ml/models/recurrent` |
| OE-6 | Comparar con MAE, RMSE, MAPE | `ml/evaluation` + pantalla de comparación |
| OE-7 | ¿Predice mejor el precio o la dirección? | Dos tareas: regresión y dirección |
| OE-8 | Analizar limitaciones ante cambios bruscos | `ml/evaluation/failure_analysis` + pantalla de fallos |

**Criterio de éxito.** Proporcionar predicciones con métricas objetivas y comparables contra modelos base.

---

## 3. Alcance

**Dentro (Fase 1):** datos diarios de XMR; salida = cierre t+1 y/o dirección; modelos base + LSTM/GRU; partición cronológica; MAE/RMSE/MAPE y proporción de aciertos; análisis de fallos; aplicación web para operar y visualizar.

**Fuera de Fase 1 (extensiones, D-08):**
- Predicción a varios días / multi-horizonte
- Comparación de solo precios vs. precios + indicadores técnicos
- Comparación Monero vs. Bitcoin vs. Ethereum y uso de BTC como variable exógena
- Dataset alternativo Store Sales (retail) y alertas por desviación
- **Nunca:** simulaciones de inversión o rentabilidad (R-11)

---

## 4. Requisitos

### 4.1 Funcionales

| ID | Requisito | OE |
|----|-----------|----|
| RF-01 | Ingerir datos OHLCV diarios de XMR desde una fuente configurable y guardar snapshot con checksum | 1 |
| RF-02 | Ingesta diaria automática e idempotente (sin duplicar fechas) | 1 |
| RF-03 | Explorar los datos: velas, volumen, retornos, volatilidad móvil, autocorrelación | 2 |
| RF-04 | Limpiar, ordenar, escalar (solo con train) y crear ventanas deslizantes | 3 |
| RF-05 | Entrenar y evaluar media móvil, regresión lineal, ARIMA (y persistencia) | 4 |
| RF-06 | Entrenar LSTM y GRU para regresión (cierre t+1) y dirección, con *early stopping* | 5, 7 |
| RF-07 | Ajustar hiperparámetros usando solo validación | 5 |
| RF-08 | Calcular MAE, RMSE, MAPE y proporción de aciertos sobre el mismo test | 6, 7 |
| RF-09 | Comparar modelos en tabla y gráficos; mostrar real vs. predicho | 6 |
| RF-10 | Analizar fallos: mayores errores, error por régimen de volatilidad, suavizado de picos | 8 |
| RF-11 | Registrar cada experimento con configuración, semilla, métricas y artefactos | transversal |
| RF-12 | Mostrar la predicción de t+1 del modelo campeón con aviso legal | 5, 6 |
| RF-13 | Autenticación con roles `viewer`, `analyst`, `admin` | transversal |
| RF-14 | Exportar resultados de un experimento (CSV; PDF opcional) con aviso legal | 6 |
| RF-15 | Auditar acciones sensibles (ingesta manual, lanzamiento de experimentos, cambios de usuarios) | transversal |

### 4.2 No funcionales

| ID | Requisito | Meta (propuesta, D-07) |
|----|-----------|------------------------|
| RNF-01 | **Reproducibilidad:** misma configuración + semilla + snapshot ⇒ mismos resultados (dentro de la tolerancia del framework) | verificable por test |
| RNF-02 | **Integridad temporal:** tests automáticos de no fuga de datos | obligatorio en CI |
| RNF-03 | Rendimiento de lecturas de la API | p95 < 500 ms |
| RNF-04 | Inferencia de la predicción diaria | < 1 s con modelo en caché |
| RNF-05 | Entrenamiento no bloquea la API (worker aparte) | obligatorio |
| RNF-06 | Portabilidad: todo arranca con `docker compose up` | obligatorio |
| RNF-07 | Seguridad: protocolo integral en `05_seguridad.md`, JWT/sesiones, contraseñas con hash, CORS restringido, secretos en entorno, RBAC, auditoría y controles API/ML | obligatorio |
| RNF-08 | Mantenibilidad: tipado, lint, cobertura en `ml/` | ≥ 80 % |
| RNF-09 | Transparencia: aviso legal visible en UI, API y reportes | obligatorio |
| RNF-10 | Usabilidad: gráficos legibles, estados de carga/error, contraste adecuado | revisión manual |
| RNF-11 | Cadena de suministro: lockfiles, SBOM, secret scanning, SCA/SAST, escaneo de imágenes y provenance de releases | obligatorio en CI |
| RNF-12 | Respuesta a incidentes: auditoría, alertas, backups restaurables y runbook versionado | obligatorio antes de producción |
| RNF-13 | Integridad ML: snapshots/modelos/scalers con procedencia y digest; promoción con gates de no fuga, calidad y rollback | obligatorio |

---

## 5. Actores y casos de uso

| Actor | Descripción | Permisos |
|-------|-------------|----------|
| **Visitante / Evaluador** (`viewer`) | Usuario con acceso de solo lectura | Solo lectura |
| **Analista** (`analista`) | Usuario que gestiona experimentos | Lectura + ingesta manual + crear/ejecutar experimentos + exportar |
| **Administrador** (`admin`) | Responsable técnico | Todo lo anterior + usuarios + fuentes de datos + modelo campeón |
| **Planificador** (sistema) | Celery Beat | Dispara la ingesta diaria |
| **Fuente de datos externa** | Yahoo Finance u otra (D-01) | Provee OHLCV |

| ID | Caso de uso | Actor principal |
|----|-------------|-----------------|
| CU-01 | Iniciar sesión | Todos |
| CU-02 | Ver dashboard (velas, volumen, predicción del día, aviso legal) | Viewer+ |
| CU-03 | Explorar datos (EDA) | Viewer+ |
| CU-04 | Ingerir datos manualmente y crear versión de dataset | Analyst+ |
| CU-05 | Crear y lanzar un experimento | Analyst+ |
| CU-06 | Consultar detalle de un experimento (curvas, real vs. predicho) | Viewer+ |
| CU-07 | Comparar modelos | Viewer+ |
| CU-08 | Ver análisis de fallos | Viewer+ |
| CU-09 | Exportar resultados | Analyst+ |
| CU-10 | Designar el modelo campeón | Admin |
| CU-11 | Gestionar usuarios y fuentes de datos | Admin |
| CU-12 | Ingesta diaria automática | Planificador |

> Los diagramas de casos de uso y de flujo de usuario están en `04_diagramas.md` (§2 y §3).

---

## 6. Arquitectura

**Estilo:** monolito modular en el backend (FastAPI) + worker asíncrono + SPA. El paquete `ml/` es independiente del framework web (R-13).

```
Navegador (React SPA)
        │  HTTPS / JSON
        ▼
   FastAPI (API REST) ───────► PostgreSQL  (datos, experimentos, métricas, usuarios)
        │                          ▲
        │ encola tareas            │
        ▼                          │
   Redis (broker) ──► Celery worker ┤──► ml/ (datos → modelos → evaluación)
        ▲                          │          │
   Celery Beat (ingesta diaria)     └──► MLflow + artifacts/ (modelos, scalers, configs)
                                          ▲
   Fuente externa (p. ej. Yahoo Finance) ─┘ (vía DataSource)
```

**Capas del backend**

| Capa | Responsabilidad | Ubicación |
|------|-----------------|-----------|
| Presentación (API) | Rutas, validación, autenticación | `app/api`, `app/schemas` |
| Aplicación | Casos de uso, orquestación, transacciones | `app/services` |
| Dominio / ML | Datos, modelos, métricas — sin dependencias web ni de BD | `app/ml` |
| Persistencia | ORM y repositorios | `app/db` |
| Asíncrona | Tareas Celery | `app/workers` |

**Decisiones de diseño relevantes**
- *Patrón Strategy* para modelos (`ForecastModel`): añadir un modelo no cambia el evaluador.
- *Patrón Adapter* para fuentes (`DataSource`): permite cambiar de Yahoo Finance a CSV, CoinGecko o al dataset retail.
- *Snapshot inmutable* de datos por experimento (`dataset_version`) para reproducibilidad.
- *Modelo campeón* por tarea: un solo modelo se sirve en producción; el resto queda como historial.
- Modelo Keras en **caché perezosa** dentro del proceso de la API.

**Frontera de seguridad.** En producción, un proxy inverso termina HTTPS y aplica headers, límites y observabilidad; solo la SPA/proxy es pública. FastAPI, PostgreSQL, Redis, Celery y MLflow viven en redes privadas con privilegios mínimos. Los controles completos, threat model, runbooks, matriz `SEC-*` y protocolo de incidentes están en `05_seguridad.md`.

Diagramas en `04_diagramas.md` (§4 arquitectura, §5 clases, §6 flujos, §7 secuencia, §8 ER, §9 estados).

---

## 7. Modelo de datos (PostgreSQL)

| Tabla | Propósito | Campos clave |
|-------|-----------|--------------|
| `app_user` | Usuarios y roles | `email` (único), `password_hash`, `role`, `is_active` |
| `audit_log` | Auditoría append-only | `user_id`, `role`, `action`, `entity`, `entity_id`, `result`, `request_id`, `metadata` (JSONB redactado), `created_at` |
| `data_source` | Fuentes registradas | `name`, `type`, `base_url`, `is_active` |
| `asset` | Activo (XMR) | `symbol`, `name`, `quote_currency` |
| `ohlcv_daily` | Serie diaria | `asset_id`, `source_id`, `trade_date`, `open`, `high`, `low`, `close`, `volume` · **UNIQUE (asset_id, source_id, trade_date)** |
| `ingestion_log` | Historial de ingestas | `source_id`, `started_at`, `finished_at`, `rows_inserted`, `status`, `error` |
| `dataset_version` | Snapshot inmutable | `asset_id`, `source_id`, `start_date`, `end_date`, `n_rows`, `checksum`, `snapshot_path` |
| `feature_set` | Conjunto de variables | `name`, `features` (JSONB), `window_size` |
| `data_split` | Partición cronológica | `dataset_version_id`, fechas de inicio/fin de train, val, test; `strategy` |
| `model_definition` | Catálogo de modelos | `name`, `family`, `algorithm`, `default_params` (JSONB) |
| `experiment` | Estudio | `name`, `dataset_version_id`, `split_id`, `feature_set_id`, `task_type`, `seed`, `status`, `created_by` |
| `training_run` | Corrida por modelo (y semilla) | `experiment_id`, `model_definition_id`, `hyperparams` (JSONB), `seed`, `status`, `best_epoch`, `train_time_s`, `artifact_path`, `mlflow_run_id`, `is_champion` |
| `evaluation_metric` | Métricas (formato largo) | `training_run_id`, `subset` (val/test), `metric_name`, `value` |
| `prediction` | Predicciones | `training_run_id`, `target_date`, `y_true`, `y_pred`, `direction_true`, `direction_pred`, `subset`, `is_live` |
| `failure_period` | Períodos de fallo | `training_run_id`, `period_start`, `period_end`, `volatility_regime`, `error_value`, `notes` |

Reglas: fechas en UTC; precios `NUMERIC(20,8)`; `prediction.is_live = true` para pronósticos generados en operación real (se completa `y_true` cuando llega el dato). El diagrama ER está en `04_diagramas.md` (§8).

---

## 8. API REST (v1)

Prefijo `/api/v1`. Autenticación por `Bearer` JWT. Todas las respuestas de predicción incluyen el campo `disclaimer`.

| Método | Ruta | Rol | Descripción |
|--------|------|-----|-------------|
| GET | `/health` | público | Estado del servicio |
| POST | `/auth/login` | público | Devuelve JWT |
| GET | `/auth/me` | viewer+ | Usuario actual |
| GET | `/market/ohlcv` | viewer+ | Serie OHLCV (`start`, `end`, `limit`) |
| GET | `/market/summary` | viewer+ | Estadísticos, volatilidad, retornos |
| POST | `/market/ingest` | analyst+ | Ingesta manual → `202` |
| GET / POST | `/datasets` | viewer+ / analyst+ | Listar / crear versión de dataset |
| POST | `/experiments` | analyst+ | Crear y encolar experimento → `202` |
| GET | `/experiments` | viewer+ | Listado paginado |
| GET | `/experiments/{id}` | viewer+ | Detalle y estado |
| GET | `/experiments/{id}/runs` | viewer+ | Corridas del experimento |
| GET | `/experiments/{id}/comparison` | viewer+ | Tabla comparativa de métricas |
| GET | `/experiments/{id}/failures` | viewer+ | Análisis de fallos |
| GET | `/runs/{id}/metrics` | viewer+ | Métricas de una corrida |
| GET | `/runs/{id}/predictions` | viewer+ | Real vs. predicho |
| GET | `/predictions/next` | viewer+ | Predicción t+1 del campeón (`task=regression\|direction`) |
| PUT | `/runs/{id}/champion` | admin | Designar campeón |
| GET | `/experiments/{id}/export` | analyst+ | Exportar CSV/PDF |
| GET / POST | `/admin/users` | admin | Gestión de usuarios |

Ejemplo de respuesta de `GET /predictions/next?task=regression`:

```json
{
  "asset": "XMR",
  "as_of_date": "YYYY-MM-DD",
  "target_date": "YYYY-MM-DD",
  "task": "regression",
  "predicted_close": 0.0,
  "model": { "run_id": "…", "algorithm": "LSTM", "test_mae": 0.0 },
  "disclaimer": "Análisis predictivo. No es asesoría financiera ni garantiza rentabilidad."
}
```

---

## 9. Interfaz de usuario

| Pantalla | Casos de uso | Contenido |
|----------|--------------|-----------|
| Login | CU-01 | Formulario |
| Dashboard | CU-02 | Velas + volumen, tarjeta "predicción t+1" (precio y dirección), banner de aviso legal |
| Exploración de datos | CU-03 | Retornos, volatilidad móvil, ACF/PACF, estadísticos |
| Datos y dataset | CU-04 | Estado de ingesta, botón de ingesta, versiones de dataset |
| Nuevo experimento | CU-05 | Formulario: tarea, features, ventana, modelos, hiperparámetros, semillas |
| Detalle de experimento | CU-06 | Estado, curvas de pérdida, real vs. predicho, hiperparámetros |
| Comparación | CU-07, CU-09 | Tabla MAE/RMSE/MAPE/aciertos, barras, media ± desviación entre semillas, exportar |
| Análisis de fallos | CU-08 | Días de mayor error, error por régimen de volatilidad, suavizado de picos |
| Administración | CU-10, CU-11 | Usuarios, fuentes, campeón |
| Acerca de / Limitaciones | — | Metodología, límites, aviso legal completo |

---

## 10. Calidad y pruebas

| Nivel | Qué se prueba | Herramientas |
|-------|---------------|--------------|
| Unitarias `ml/` | Limpieza, ventanas, split, scaler, métricas (con valores calculados a mano), modelos con datos sintéticos | pytest |
| **No fuga de datos** | Orden temporal train < val < test; scaler solo con train; features sin `t+1`; ARIMA rodante solo con pasado | pytest (obligatorio, CI) |
| Integración | API + PostgreSQL real, migraciones, ingesta idempotente con fuente mockeada | pytest + httpx + servicio PostgreSQL en CI |
| Reproducibilidad | Dos corridas con la misma semilla dan métricas equivalentes | pytest |
| Frontend | Componentes y clientes API | Vitest |
| Estática | Estilo y tipos | ruff, mypy, ESLint, `tsc --noEmit` |
| Manual | Flujos de usuario y aviso legal visible | checklist |

**Pipeline de CI:** lint → tipos → unitarias → integración → build de imágenes.

**Gates de seguridad:** secret scanning, SAST, SCA de Python/npm, SBOM, escaneo de imágenes, pruebas negativas de autenticación/autorización, DAST en staging y tests de integridad/procedencia de datos y modelos (ver `05_seguridad.md` §7–§8).

---

## 11. Plan de trabajo (semanas sugeridas, ajustables)

| Hito | Contenido | Semanas | Entregable |
|------|-----------|---------|-----------|
| H0 | Andamiaje del repo, Docker Compose, CI, `.env.example` | 1 | Repo que levanta con `make up` |
| H1 | Ingesta y limpieza; snapshot; EDA | 1–2 | `ohlcv_daily`, `dataset_version`, notebook EDA |
| H2 | Split, escalado, ventanas, tests de no fuga | 2 | Módulo `ml/data` + tests |
| H3 | Baselines (MA, regresión lineal, ARIMA, persistencia) | 3 | Métricas base en test |
| H4 | LSTM/GRU: regresión y dirección; ajuste en validación; multi-semilla | 3–5 | Corridas registradas en MLflow |
| H5 | Evaluación final, comparación y análisis de fallos | 5–6 | Tablas y gráficos |
| H6 | Backend: API, BD, workers, autenticación | 4–7 | API v1 documentada (OpenAPI) |
| H7 | Frontend: pantallas §9 | 6–8 | SPA integrada |
| H8 | Integración, pruebas, documentación final | 8–9 | Versión final |

H6–H7 avanzan en paralelo con H4–H5 una vez existen los contratos de API.

---

## 12. Riesgos y mitigaciones

| Riesgo | Efecto | Mitigación |
|--------|--------|-----------|
| Fuga de datos (escalar antes de cortar, features con futuro) | Métricas irreales | R-01 a R-03 + tests obligatorios |
| Precios de test fuera del rango de train (p. ej. picos/valles no vistos) | `MinMaxScaler` distorsiona predicciones | Variante sobre retornos logarítmicos documentada como experimento aparte |
| El LSTM no supera a la persistencia o a ARIMA | Resultado no esperado | Se reporta con análisis de fallos |
| Sobreajuste (pocas filas diarias) | Mal desempeño en test | Dropout, *early stopping*, ventana y capacidad moderadas, multi-semilla |
| Cambio o indisponibilidad de la fuente de datos | Ingesta rota | `DataSource` intercambiable, snapshots inmutables |
| Suavizado de picos por el LSTM | Subestima cambios bruscos | Análisis de suavizado (S-07) y limitación explícita |
| Incompatibilidad de dependencias (TensorFlow, Python) | Entorno no reproducible | Lockfile + imagen Docker fijada + verificar versiones |
| Lectura del sistema como consejo financiero | Riesgo reputacional/ético | R-11, R-12, aviso legal en toda salida |
| BOLA, escalada de rol o abuso de endpoints de trabajo | Acceso indebido, fuga o agotamiento de recursos | RBAC server-side, autorización por objeto, rate limit, tests 401/403 y SEC-003/006 |
| Dependencia, imagen o acción CI comprometida | Ejecución o alteración de artefactos | lockfiles, SBOM, scans, provenance, imágenes sin privilegios y SEC-015/016 |
| Data poisoning o sustitución del modelo | Resultados no confiables | snapshot inmutable, digest, validación, revisión y rollback; SEC-012/014 |
| Exposición de secretos, DB, Redis o MLflow | Toma de control o pérdida de datos | gestores de secretos, rotación, red privada, mínimo privilegio y SEC-004/010 |

---

## 13. Trazabilidad OE → RF → pruebas

| OE | RF | Verificación |
|----|----|--------------|
| OE-1 | RF-01, RF-02 | Test de ingesta idempotente; checksum del snapshot |
| OE-2 | RF-03 | Revisión de pantalla EDA |
| OE-3 | RF-04 | Tests de no fuga, ventanas y escalado |
| OE-4 | RF-05 | Tests de baselines con datos sintéticos |
| OE-5 | RF-06, RF-07 | Corridas en MLflow; test de reproducibilidad |
| OE-6 | RF-08, RF-09, RF-14 | Tests de métricas con valores calculados a mano |
| OE-7 | RF-06, RF-08 | Comparación regresión vs. dirección en el mismo test |
| OE-8 | RF-10 | Reporte de fallos por régimen de volatilidad |

---

## 14. Seguridad transversal

La seguridad del sistema se gestiona mediante `05_seguridad.md`, que consolida los protocolos de identidad, API, navegador, datos, PostgreSQL/Redis/Celery/MLflow, MLOps, cadena de suministro, contenedores, observabilidad, incidentes, excepciones y revisión continua. Este documento es la fuente operativa para los controles `SEC-*`; cualquier cambio de arquitectura debe actualizarlo junto con esta documentación y las pruebas correspondientes.

---

## 15. Referencias

Las referencias [1]–[9] son las del documento `Propuesta_Monero_IEEE.docx` (Patel et al.; Hansun et al.; Seabe et al.; Pambudi et al.; Kaur et al.; Hochreiter y Schmidhuber; Cho et al.; Walther et al.; documentación de `TimeSeriesSplit` de scikit-learn). No se añaden referencias nuevas sin verificarlas (R-21).
