# XMR-Forecast — Diseño de Machine Learning

> **Aviso legal.** Análisis predictivo de series de tiempo. No es asesoría financiera ni promete rentabilidad; no incluye simulaciones de inversión (R-11).

---

## 1. Formulación del problema

Sea `cₜ` el cierre diario de XMR, `W` el tamaño de ventana (por defecto **30**) y `Xₜ` la matriz de variables de los `W` días anteriores al día `t`.

| Tarea | Entrada | Salida | Modelos | Métricas |
|-------|---------|--------|---------|----------|
| **R — Regresión** | `X` (W días previos a `t`) | `ĉₜ` (cierre del día `t`) | MA, regresión lineal, ARIMA, persistencia, LSTM, GRU | MAE, RMSE, MAPE |
| **D — Dirección** | ídem | `p(cₜ > cₜ₋₁)` → sube/baja | LSTM/GRU clasificador; dirección derivada de R | Proporción de aciertos |

---

## 2. Separación entre backend Spring Boot y servicio ML

### 2.1 Responsabilidades

| Componente | Responsabilidad |
|------------|-----------------|
| **Spring Boot** | API REST, autenticación, autorización, persistencia, orquestación, métricas de rendimiento |
| **FastAPI-ML** | Inferencia, entrenamiento, evaluación ML, métricas ML, carga de modelos |

### 2.2 Contrato de integración

| Endpoint Spring Boot | Endpoint FastAPI-ML | Método | Descripción |
|---------------------|---------------------|--------|-------------|
| `GET /api/v1/ml/health` | `/health` | GET | Health check |
| `POST /api/v1/ml/infer` | `/api/v1/ml/infer` | POST | Inferencia |
| `POST /api/v1/ml/train` | `/api/v1/ml/train` | POST | Entrenamiento |

### 2.3 Seguridad de la integración

- FastAPI-ML **no es accesible públicamente** (solo red interna).
- Spring Boot usa WebClient con timeout, reintentos limitados y circuit breaker.
- Todas las peticiones incluyen `X-Request-ID` para correlación.
- Los modelos se cargan solo desde artefactos versionados y verificados por digest.

---

## 3. Métricas ML

### 3.1 Regresión

| Métrica | Descripción |
|---------|-------------|
| **MAE** | Error absoluto medio |
| **RMSE** | Raíz del error cuadrático medio |
| **MAPE** | Error porcentual absoluto medio |

### 3.2 Dirección

| Métrica | Descripción |
|---------|-------------|
| **Proporción de aciertos** | Porcentaje de predicciones correctas |
| **Precision** | Verdaderos positivos / (verdaderos positivos + falsos positivos) |
| **Recall** | Verdaderos positivos / (verdaderos positivos + falsos negativos) |
| **F1-score** | Media armónica de precision y recall |
| **Matriz de confusión** | TP, TN, FP, FN |

### 3.3 Comparación de modelos

Todos los modelos se evalúan sobre **el mismo conjunto de prueba y las mismas fechas** (R-05):

- Media móvil
- Regresión lineal
- ARIMA (pronóstico rodante de un paso)
- LSTM
- GRU (si está implementado)

El **modelo campeón** se selecciona usando **validación**, nunca usando el conjunto de prueba (R-24).

### 3.4 Multi-semilla

Para modelos estocásticos se utilizan **≥ 5 semillas** y se reporta:
- Media
- Desviación estándar
- Resultados por semilla
- Configuración utilizada
- Dataset y checksum
- Versión del modelo
- Fecha de la corrida

---

## 4. Reglas de no fuga de datos

| Regla | Descripción |
|-------|-------------|
| **R-01** | Partición estrictamente cronológica. Prohibido `shuffle`. |
| **R-02** | `MinMaxScaler` se ajusta **solo con train**. |
| **R-03** | Indicadores técnicos usan **solo información ≤ t**. |
| **R-23** | Una muestra pertenece al subconjunto de la **fecha de su objetivo**. |
| **R-24** | El campeón se elige por **validación**, no por test. |

---

## 5. Validación de modelos y artefactos

- Los modelos se cargan solo desde artefactos versionados y verificados por digest.
- Se verifica la integridad de los archivos antes de cargarlos.
- No se aceptan rutas arbitrarias ni URLs externas no autorizadas.
- Los datos sospechosos se ponen en cuarentena.

---

## 6. Protocolo experimental

| Experimento | Descripción | Fase |
|-------------|-------------|------|
| **E-01** | Baselines con FS-B, `W=30` | F1 |
| **E-02** | LSTM, tarea R, FS-B, 5 semillas | F1 |
| **E-03** | GRU, tarea R, 5 semillas | F1 |
| **E-04** | LSTM/GRU tarea D y dirección derivada de R | F1 |
| **E-05** | Ajuste de hiperparámetros con Optuna | F1 |

---

## 7. Reproducibilidad

| Elemento | Práctica |
|----------|----------|
| Semillas | Fijar `random`, `numpy` y TensorFlow/Keras |
| Datos | `dataset_version` con checksum |
| Configuración | YAML versionado |
| Tracking | MLflow |
| Entorno | Lockfile + imagen Docker fijada |

---

## 8. Extensiones (fuera de Fase 1, D-08)

- Ablación de variables (FS-A/B/C) y variable exógena BTC.
- Comparación Monero–Bitcoin–Ethereum.
- Otras arquitecturas (Bi-LSTM, Transformer).
- Predicción multi-horizonte.
- Dataset alternativo retail (Store Sales).
- Alertas por desviación.
