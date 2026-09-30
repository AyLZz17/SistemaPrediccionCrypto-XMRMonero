# SKILLS.md — Habilidades del proyecto XMR-Forecast

> Catálogo de habilidades necesarias, con **cuándo usarlas, herramientas, receta y errores típicos**.
> Regla: si durante una tarea aparece una habilidad nueva, se **añade aquí** (ver `AGENTS.md` §7).
> Las reglas `R-xx` están definidas en `AGENTS.md`.

**Leyenda de fase:** F1 = Desarrollo inicial (MVP) · F2 = Producción · F3 = Escalado y extensiones.

| # | Habilidad | Fase | Área |
|---|-----------|------|------|
| S-01 | Ingeniería de datos de series de tiempo | F1 | Datos |
| S-02 | Ingeniería de características (indicadores técnicos) | F1 | Datos |
| S-03 | Validación temporal y prevención de fugas | F1 | ML |
| S-04 | Modelos estadísticos base (MA, regresión lineal, ARIMA) | F1 | ML |
| S-05 | Redes recurrentes LSTM/GRU | F1 | ML |
| S-06 | Evaluación, métricas y comparación | F1 | ML |
| S-07 | Análisis de fallos | F1 | ML |
| S-08 | Reproducibilidad y tracking (MLflow) | F1 | MLOps |
| S-09 | Backend API (FastAPI) | F1-F2 | Backend |
| S-10 | Bases de datos y migraciones (PostgreSQL) | F1-F2 | Backend |
| S-11 | Tareas asíncronas (Celery/Redis) | F1-F2 | Backend |
| S-12 | Frontend (React + TypeScript + ECharts) | F1-F2 | Frontend |
| S-13 | Contenedores y CI/CD | F1-F2 | DevOps |
| S-14 | Testing y calidad | F1-F2 | Calidad |
| S-15 | Documentación técnica y diagramas | F1-F2 | Docs |
| S-16 | Comunicación responsable de resultados financieros | F1-F2 | Compliance |
| S-17 | Seguridad básica de aplicaciones web | F2 | Seguridad |
| S-18 | Seguridad integral de API, infraestructura, cadena de suministro y ML | F2 | Seguridad |

---

## S-01 · Ingeniería de datos de series de tiempo
- **Cuándo:** al ingerir o limpiar OHLCV diario de XMR.
- **Herramientas:** pandas, NumPy, `yfinance` (o CSV), pandera (opcional, validación de esquema).
- **Receta:**
  1. Descargar y guardar **snapshot crudo** con checksum (SHA-256) y fecha de descarga.
  2. Índice `DatetimeIndex` ordenado, sin duplicados, zona horaria UTC.
  3. Detectar huecos de fechas y valores nulos; **documentar** el tratamiento (interpolar solo con datos pasados o eliminar; nunca rellenar con el futuro).
  4. Validar: `high ≥ max(open, close)`, `low ≤ min(open, close)`, `volume ≥ 0`, precios > 0.
- **Errores típicos:** interpolación bidireccional (fuga), mezclar husos horarios, sobrescribir el snapshot original.

## S-02 · Ingeniería de características
- **Cuándo:** al construir el *feature set* (OHLCV, variación % diaria, medias móviles, RSI, MACD).
- **Herramientas:** pandas, librería de indicadores (`ta` o `pandas-ta`; verificar mantenimiento vigente) o implementación propia probada.
- **Receta:** calcular con ventanas **hacia atrás**; descartar las primeras filas con NaN por calentamiento; registrar la lista de features en YAML del experimento.
- **Errores típicos:** `rolling(center=True)`, `shift(-1)` en features (viola R-03), usar el cierre de t+1 dentro de un indicador de t.

## S-03 · Validación temporal y prevención de fugas
- **Cuándo:** siempre que se particione, escale o cree ventanas.
- **Herramientas:** `TimeSeriesSplit` (scikit-learn), tests propios.
- **Receta:**
  1. Cortar cronológicamente (train/val/test) **antes** de escalar.
  2. `MinMaxScaler.fit(train)`; `transform` en val/test (R-02).
  3. Asignar cada muestra al subconjunto de la **fecha de su objetivo** (R-23); la ventana de entrada puede alcanzar días previos, nunca el objetivo ni el futuro.
  4. Escribir tests: `max(train.index) < min(val.index) < min(test.index)`; scaler entrenado solo con train; ninguna feature depende de t+1.
- **Errores típicos:** escalar todo el dataset antes de cortar, ventanas que cruzan la frontera train/test, usar test para *early stopping*.

## S-04 · Modelos estadísticos base
- **Cuándo:** entrenar y evaluar los baselines (R-06).
- **Herramientas:** scikit-learn (`LinearRegression`), statsmodels (`ARIMA`), `pmdarima` (opcional para `auto_arima`).
- **Receta:**
  - *Media móvil:* ŷₜ₊₁ = media de los últimos k cierres; k elegido en validación (p. ej. 5, 7, 14, 30).
  - *Regresión lineal:* entrada = ventana aplanada (mismos 30 días que el LSTM); salida = cierre t+1.
  - *ARIMA:* orden (p,d,q) elegido en train (AIC o `auto_arima`); pronóstico **de un paso adelante en modo rodante** sobre test, incorporando cada observación real sin reestimar (`results.append(..., refit=False)`) para que sea comparable con el LSTM.
  - *Persistencia:* ŷₜ₊₁ = yₜ.
- **Errores típicos:** pronóstico multi-paso de ARIMA comparado contra un LSTM de un paso; seleccionar (p,d,q) mirando el test.

## S-05 · Redes recurrentes LSTM/GRU
- **Cuándo:** entrenar el modelo principal (y su alternativa GRU).
- **Herramientas:** TensorFlow/Keras, Optuna (ajuste de hiperparámetros), MLflow.
- **Receta (configuración inicial):** entrada `(30, n_features)` → `LSTM(100, return_sequences=True)` → `Dropout(0.2)` → `LSTM(100)` → `Dropout(0.2)` → `Dense(1)`; Adam, lote 32, hasta 20 épocas con `EarlyStopping` sobre validación (`restore_best_weights=True`).
  - Tarea regresión: `Dense(1)` lineal, pérdida MSE (o Huber).
  - Tarea dirección: `Dense(1, sigmoid)`, pérdida binaria.
  - Ajustar en validación: unidades {50,100,150}, capas {1,2,3}, dropout {0.1,0.2,0.3}, ventana {7,14,30,60}, tasa de aprendizaje.
  - Repetir con ≥ 5 semillas (R-08).
- **Errores típicos:** sobreajuste con pocas filas, fijar semillas solo en NumPy y no en TensorFlow, precios de test fuera del rango de train con `MinMaxScaler`.

## S-06 · Evaluación, métricas y comparación
- **Cuándo:** al cerrar una corrida y al comparar modelos.
- **Herramientas:** scikit-learn metrics, NumPy, (opcional) prueba de Diebold–Mariano.
- **Receta:**
  - MAE = (1/n)Σ|yₜ−ŷₜ| · RMSE = √[(1/n)Σ(yₜ−ŷₜ)²] · MAPE = (100 %/n)Σ|(yₜ−ŷₜ)/yₜ| (en USD, tras des-escalar).
  - Dirección: proporción de aciertos; complementar con matriz de confusión y F1.
  - Dirección derivada del modelo de regresión: `sign(ŷₜ₊₁ − yₜ)`; compararla con el clasificador dedicado.
  - Misma serie de test para todos (R-05); tabla final + gráfico real vs. predicho.
- **Errores típicos:** comparar métricas calculadas sobre escalas distintas, elegir el "mejor" modelo por el test.

## S-07 · Análisis de fallos
- **Cuándo:** después de la evaluación final (R-10).
- **Receta:** (1) listar los k días de mayor error absoluto; (2) segmentar por régimen de volatilidad (desviación estándar móvil de retornos, por encima/debajo del percentil 75) y comparar error por régimen; (3) medir el suavizado de picos (correlación de cambios reales vs. predichos, amplitud relativa); (4) contrastar con eventos de mercado *solo como contexto*, sin afirmar causalidad; (5) redactar limitaciones.

## S-08 · Reproducibilidad y tracking
- **Herramientas:** MLflow, YAML, `joblib`, semillas (`random`, `numpy`, `tensorflow`), lockfile.
- **Receta:** cada corrida registra dataset (checksum), features, split, hiperparámetros, semilla, métricas por subconjunto, curvas de aprendizaje y artefactos. Un experimento se puede reproducir con `make experiment CONFIG=…`.

## S-09 · Backend API (FastAPI)
- **Receta:** routers por dominio (`auth`, `market`, `experiments`, `predictions`); esquemas Pydantic de entrada/salida; dependencias para sesión de BD y usuario actual; respuestas `202 Accepted` para trabajos largos; paginación en listados; errores con formato uniforme; OpenAPI incluye el aviso legal (R-11).
- **Errores típicos:** cargar el modelo Keras en cada petición (usar carga perezosa con caché), lógica de negocio dentro del router.

## S-10 · Bases de datos y migraciones
- **Herramientas:** PostgreSQL, SQLAlchemy 2.0, Alembic.
- **Receta:** esquema en `docs/01_documentacion.md` (§7) y `docs/04_diagramas.md` (ER); restricciones `UNIQUE (asset_id, source_id, trade_date)`; JSONB para hiperparámetros; índices por fecha; migraciones versionadas (R-15); tests de integración contra PostgreSQL real (no SQLite, por JSONB).

## S-11 · Tareas asíncronas
- **Herramientas:** Celery, Redis, Celery Beat.
- **Receta:** tareas idempotentes (`ingest_daily`, `run_experiment`); estado en `training_run.status`; reintentos con *backoff* en ingesta; el worker reutiliza el mismo paquete `ml/` (R-13).

## S-12 · Frontend
- **Herramientas:** React + TypeScript (strict), Vite, Tailwind CSS, Apache ECharts (velas OHLC, líneas, barras), TanStack Query, React Router.
- **Receta:** cliente API tipado; una página por caso de uso (ver `docs/01_documentacion.md` §9); gráficos: velas + volumen, real vs. predicho, curvas de aprendizaje, barras comparativas de métricas; **aviso legal siempre visible** (R-11); estados de carga/error explícitos; accesibilidad básica (contraste, etiquetas).

## S-13 · Contenedores y CI/CD
- **Herramientas:** Docker, Docker Compose, GitHub Actions.
- **Receta:** servicios `db`, `redis`, `backend`, `worker`, `beat`, `mlflow`, `frontend`; CI: lint → tipos → tests unitarios → tests de integración (servicio PostgreSQL) → build; imágenes con dependencias fijadas.

## S-14 · Testing y calidad
- **Herramientas:** pytest, pytest-cov, httpx (`TestClient`), Vitest, ESLint, ruff, mypy, pre-commit.
- **Receta:** pirámide — muchas pruebas unitarias en `ml/` (incluidas las de **no fuga**), pruebas de API con BD de prueba, pocas pruebas end-to-end. Datos sintéticos y fuentes mockeadas (R-18). Objetivo: cobertura ≥ 80 % en `ml/` (D-07).

## S-15 · Documentación técnica y diagramas
- **Herramientas:** Markdown, **Mermaid** (flowchart, sequenceDiagram, classDiagram, erDiagram, stateDiagram, journey).
- **Receta:** un diagrama por decisión relevante; diagramas versionados junto al código; si cambia el diseño, se actualizan los diagramas en la misma tarea (DoD).
  - **Validar siempre** con `tools/validate_mermaid.py` (R-25) y revisar a ojo al menos los diagramas más grandes.
  - Sintaxis segura: etiquetas entre comillas; sin `;` en `sequenceDiagram`; relaciones `incluye/extiende` como texto del nodo si desordenan el layout de casos de uso.

## S-16 · Comunicación responsable de resultados financieros
- **Receta:** usar "capacidad predictiva evaluada", nunca "predice el mercado"; informar incertidumbre (media ± desviación entre semillas); mostrar siempre los baselines junto al modelo; incluir limitaciones (cambios bruscos, factores externos); sin simulaciones de inversión (R-11, R-12).

## S-17 · Seguridad básica
- **Receta:** autenticación JWT con expiración corta, hash de contraseñas con Argon2 o bcrypt, roles `viewer/analyst/admin`, CORS restringido, *rate limiting* en login y en endpoints de disparo de trabajos, validación estricta de entradas, secretos por entorno (R-14), auditoría de acciones sensibles (`audit_log`).

## S-18 · Seguridad integral
- **Cuándo:** al diseñar, implementar, revisar o desplegar cualquier componente de la API, SPA, infraestructura, CI/CD, datos o ML.
- **Referencias:** `05_seguridad.md`, OWASP ASVS/API Top 10, NIST CSF/SSDF/800-63B-4/800-61 Rev. 3, NIST AI RMF y MITRE ATLAS.
- **Receta:** (1) actualizar activos y threat model; (2) asignar controles `SEC-*`; (3) implementar mínimo privilegio, validación, límites, trazabilidad y procedencia; (4) ejecutar pruebas de seguridad y scans en CI/staging; (5) registrar evidencia, excepción o incidente; (6) revisar accesos, dependencias, backups y alertas según frecuencia.
- **Errores típicos:** confiar en el rol del frontend, guardar JWT en `localStorage`, aceptar URLs externas sin allowlist, usar `pickle`, exponer Redis/MLflow, cargar modelos no confiables, registrar secretos, promover por test o tratar un checksum como sustituto de la autorización.

---

## Cómo añadir una habilidad
1. Asigna el siguiente `S-nn` y añade la fila a la tabla superior.
2. Escribe: *Cuándo · Herramientas · Receta · Errores típicos*.
3. Enlaza la regla `R-xx` que la respalda si aplica.
4. Regístralo en el registro de tareas de `AGENTS.md`.
