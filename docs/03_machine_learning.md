# XMR-Forecast -- Diseno de Machine Learning

> **Aviso legal.** Analisis predictivo de series de tiempo. No es asesoria
> financiera ni promete rentabilidad; no incluye simulaciones de inversion
> (R-11, R-12).

---

## 1. Formulacion del problema

Sea `c_t` el cierre diario de XMR, `W` el tamano de ventana (por defecto **30**)
y `X_t` la matriz de variables de los `W` dias anteriores al dia `t`.

| Tarea | Entrada | Salida | Modelos | Metricas |
|-------|---------|--------|----------|----------|
| **R -- Regresion** | `X` (W dias previos a `t`) | `c_t` (cierre del dia `t`) | MA, regresion lineal, ARIMA, persistencia, LSTM, GRU | MAE, RMSE, MAPE |
| **D -- Direccion** | idem | `p(c_t > c_t-1)` -> sube/baja | LSTM/GRU clasificador; direccion derivada de R | Proporcion de aciertos |

La prediccion se publica como **capacidad predictiva evaluada** de un modelo
concreto, con su incertidumbre y su trazabilidad. El sistema no predice "el
mercado" (R-12).

---

## 2. Separacion entre backend Spring Boot y servicio ML

### 2.1 Responsabilidades

| Componente | Responsabilidad |
|------------|-----------------|
| **Spring Boot** | API REST, autenticacion, autorizacion, persistencia, orquestacion, cola de trabajos, gates de integridad y de campeon |
| **FastAPI-ML** | **Inferencia y consulta de metricas por HTTP.** El entrenamiento es un camino de CLI, no una ruta |

### 2.2 Contrato de integracion

El servicio ML expone **cuatro rutas**, con prefijo `/v1`. El backend las llama
todas:

| Ruta de FastAPI-ML | Metodo | Quien la llama | Descripcion |
|--------------------|--------|----------------|-------------|
| `/health` | GET | `MlServiceClient.isHealthy()` | Health check; nunca lanza excepcion |
| `/v1/models` | GET | `MlServiceClient.models()` | Modelos registrados con digest y procedencia |
| `/v1/metrics/compare` | GET | `MlServiceClient.compareMetrics(id)` | Comparacion de modelos sobre el mismo test |
| `/v1/predict` | POST | `MlServiceClient.predict(payload)` | Prediccion de cierre y direccion |

Lo que **no existe**, y que esta documentado aqui precisamente para que nadie lo
busque:

- **No hay proxy `/api/v1/ml/*` en el backend.** El servicio ML no se publica en
  la API publica: lo consume unicamente el backend (R-32).
- **No hay ruta `/train`** en el servicio ML, ni en el backend. El entrenamiento
  se ejecuta por CLI sobre una configuracion versionada en `configs/*.yaml`
  (ver 2.6).

### 2.3 Cuerpo de `POST /v1/predict`

El esquema `PredictRequest` acepta **exactamente cuatro campos**:

| Campo | Tipo | Valor por defecto | Limite |
|-------|------|--------------------|--------|
| `model_key` | `str` | obligatorio | 1..64 caracteres |
| `version` | `str \| None` | `null` | hasta 32 caracteres |
| `symbol` | `str` | `XMR-USD` | 2..16 caracteres, alfanumerico mas `-` y `.` |
| `lookback_days` | `int` | 30 | 1..365 |

Ejemplo de peticion valida:

```json
{
  "model_key": "lstm_base",
  "version": "v1",
  "symbol": "XMR-USD",
  "lookback_days": 30
}
```

`PredictRequest` se declara con `extra='forbid'`. **Consecuencia directa: enviar
un campo de mas devuelve `422` y rompe la funcionalidad entera sin fallo de
compilacion.** Esto ya ocurrio en produccion: el backend enviaba un campo `seed`,
`422` rechazaba todas las predicciones y la suite de pruebas seguia en verde. El
contrato quedo fijado despues con una prueba que compara los campos realmente
enviados con los realmente aceptados (R-38, R-41).

**La semilla viaja en la version del modelo, nunca en la peticion.** El
`servicio ML` la lee de `model_versions.seed` y la devuelve en el bloque `trace`
de la respuesta (`trace.seed`), junto con `dataset_version`, `artifact_sha256`,
`config_sha256` y `generated_at`. Enviar `seed` en el cuerpo no es "ignorado":
es un `422`.

### 2.4 Seguridad de la integracion

- FastAPI-ML **no es accesible publicamente**: vive en la red interna y solo lo
  consume Spring Boot con un cliente tipado (R-32).
- El cliente es **`RestClient`** (`ml/MlServiceClient.java`), sincrono, con
  `SimpleClientHttpRequestFactory`. **No hay circuit breaker**: no existe
  resilience4j en el proyecto. La degradacion controlada consiste en timeout,
  reintentos limitados y backoff incremental.
- Timeouts reales: **conexion 3000 ms**, **lectura 60000 ms**, **2 reintentos**
  (3 intentos en total) con espera `min(500 ms x intento, 2000 ms)`.
- Solo se reintenta el **fallo de transporte**. Si el servicio responde con un
  error de negocio, la excepcion se propaga sin reintentar.
- `https://` es obligatorio: `assertSecureBaseUrl()` aborta el arranque si
  `app.ml.base-url` no es HTTPS, y la verificacion TLS nunca se desactiva en
  produccion (R-33).
- **Correlacion en dos cabeceras:** el backend envia `X-Request-Id` **y
  `X-Trace-Id`**, y el servicio ML devuelve ambas en cada respuesta. Si falta la
  primera, el servicio genera un UUID; los valores se rechazan si exceden la
  longitud maxima o contienen caracteres de control, para no inyectar logs.
- Los modelos se cargan solo desde artefactos versionados y verificados por
  digest; la verificacion ocurre **antes** de cargar (R-28).

### 2.5 Gates del backend antes de llamar al servicio ML

El backend no reenvia a ciegas la peticion del cliente. Antes de llamar al
servicio ML aplica dos gates que devuelven `422`:

| Codigo | Cuando se dispara | Que significa |
|--------|--------------------|---------------|
| `MODEL_NOT_VERIFIED` | La version de modelo no supera la verificacion de integridad | El artefacto no pasa digest ni procedencia (R-28) |
| `NO_CHAMPION_VERSION` | Se pidio un `modelId` sin version campeon | No hay campeon promovido **por validacion**, que es lo que R-24 exige |

Ambos se comprueban antes de la llamada, de modo que un cliente nunca obtiene un
artefacto dudoso como si fuera una prediccion valida.

### 2.6 Corridas multi-semilla y el camino de entrenamiento

| Endpoint | Que hace |
|----------|----------|
| `POST /api/v1/experiments/{id}/runs` | Registra una corrida con su lista de semillas y encola el trabajo |

Reglas del endpoint:

| Regla | Comportamiento |
|-------|----------------|
| **Minimo de semillas (R-08)** | Con menos de 5 semillas responde `400 INSUFFICIENT_SEEDS` |
| Maximo de semillas | Por encima del tope responde `400 TOO_MANY_SEEDS` |
| Sin lista | Se usan las semillas por defecto, que ya cumplen el minimo |
| **Idempotencia** | El `runKey` es unico por experimento; repetirlo devuelve `409 RUN_ALREADY_EXISTS` |
| Clave del trabajo | `train:<experimentCode>:<runKey>` en `jobs.idempotency_key` |

**Un trabajo `TRAIN` termina siempre en `FAILED` con el codigo
`TRAINING_NOT_EXPOSED`.** No es un defecto pendiente de arreglar: es la
decision de diseno. El entrenamiento es una operacion larga y reproducible que
se ejecuta por CLI sobre un YAML versionado, y exponerlo por HTTP abriria una
via remota para disparar consumo de CPU sin control. El endpoint HTTP registra
la corrida y su configuracion; el `JobWorker` marca el trabajo como fallido
explicando el motivo, en lugar de dejarlo en `RUNNING` para siempre.

Los trabajos `INGEST` y `BACKFILL` terminan con resultado explicito
`{"delegatedTo": "ml-service CLI", "executed": false}`. No se finge que se
ejecuto nada que no se ejecuto (R-21).

### 2.7 Limitaciones registradas

| Limitacion | Consecuencia |
|------------|--------------|
| Entrenamiento solo por CLI | El trabajo `TRAIN` falla siempre por diseno |
| Sin circuit breaker | Un fallo prolongado del servicio ML se traduce en `503 ML_SERVICE_UNAVAILABLE` tras agotar reintentos |
| El servicio ML no se autentica de forma interna | La proteccion es de red (no hay API key backend-ML) |
| `state`/`nonce` OAuth en memoria del proceso | No seguro con varias replicas; se pierde al reiniciar |

---

## 3. Metricas ML

### 3.1 Regresion

| Metrica | Descripcion |
|---------|-------------|
| **MAE** | Error absoluto medio |
| **RMSE** | Raiz del error cuadratico medio |
| **MAPE** | Error porcentual absoluto medio |

### 3.2 Direccion

| Metrica | Descripcion |
|---------|-------------|
| **Proporcion de aciertos** | Porcentaje de predicciones correctas |
| **Precision** | Verdaderos positivos / (verdaderos positivos + falsos positivos) |
| **Recall** | Verdaderos positivos / (verdaderos positivos + falsos negativos) |
| **F1-score** | Media armonica de precision y recall |
| **Matriz de confusion** | TP, TN, FP, FN |

### 3.3 Comparacion de modelos

Todos los modelos se evaluan sobre **el mismo conjunto de prueba y las mismas
fechas** (R-05). La respuesta de `/v1/metrics/compare` lo declara de forma
explicita con `test_dates_shared` y `n_test_samples`.

- Media movil
- Regresion lineal
- ARIMA, con **pronostico rodante de un paso** para que sea comparable con el
  LSTM y no con una serie de pasos futuros (R-24)
- LSTM
- GRU, si esta implementado

El **modelo campeon** se selecciona usando **validacion**, nunca el conjunto de
prueba (R-24). El conjunto de prueba se usa **una sola vez**, para la evaluacion
final (R-04). Un resultado negativo es un resultado valido: si el LSTM no supera
a los baselines, se reporta tal cual y esta prohibido ajustar contra el test
hasta "ganar" (R-09).

### 3.4 Multi-semilla

Para modelos estocasticos se utilizan **al menos 5 semillas** y se reporta:

- Media
- Desviacion estandar
- Resultados por semilla
- Configuracion utilizada
- Dataset y checksum
- Version del modelo
- Fecha de la corrida

`/v1/models` expone `n_seeds` por version, y `/v1/metrics/compare` lo expone
por fila, de modo que una corrida con una sola semilla es visible como tal y no
se confunde con el resultado de cinco.

---

## 4. Reglas de no fuga de datos

| Regla | Descripcion |
|-------|-------------|
| **R-01** | Particion estrictamente cronologica. Prohibido `shuffle` y `train_test_split` aleatorio; validacion cruzada solo con `TimeSeriesSplit` o walk-forward |
| **R-02** | El `MinMaxScaler` se ajusta **solo con train**. Validacion y prueba usan unicamente `transform`, y las predicciones se desescalan antes de calcular metricas |
| **R-03** | Indicadores tecnicos (RSI, MACD, medias moviles) usan **solo informacion menor o igual que t**. Prohibido `shift(-n)` y ventanas centradas |
| **R-23** | Una muestra pertenece al subconjunto de la **fecha de su objetivo**. Su ventana de entrada puede incluir dias anteriores del subconjunto previo, nunca su objetivo ni datos posteriores |
| **R-24** | El campeon se elige por **validacion**, no por test |

Los tests de no fuga (R-01 a R-03) son obligatorios en `ml/` (R-16).

---

## 5. Validacion de modelos y artefactos

- Los modelos se cargan solo desde artefactos versionados y verificados por
  digest.
- Se verifica la integridad del archivo antes de cargarlo.
- No se aceptan rutas arbitrarias ni URLs externas no autorizadas.
- Los datos sospechosos se ponen en cuarentena.
- La promocion de campeon exige gates de integridad, no fuga y validacion
  (R-28).
- El backend aplica `MODEL_NOT_VERIFIED` antes de permitir una prediccion sobre
  una version sin verificar.

---

## 6. Protocolo experimental

| Experimento | Descripcion | Fase |
|-------------|-------------|------|
| **E-01** | Baselines con FS-B, `W=30` | F1 |
| **E-02** | LSTM, tarea R, FS-B, 5 semillas | F1 |
| **E-03** | GRU, tarea R, 5 semillas | F1 |
| **E-04** | LSTM/GRU tarea D y direccion derivada de R | F1 |
| **E-05** | Ajuste de hiperparametros con Optuna | F1 |

La entrada al protocolo es un YAML versionado por experimento en `configs/`, que
es lo que ejecuta el camino de CLI.

---

## 7. Reproducibilidad

| Elemento | Practica |
|----------|----------|
| Semillas | Fijar `random`, `numpy` y TensorFlow/Keras; minimo 5 por modelo estocastico (R-08) |
| Datos | `dataset_version` con checksum; el checksum lo calcula el servidor, nunca el cliente |
| Configuracion | YAML versionado, con su `config_sha256` |
| Modelos | Artefacto con `artifact_sha256` y `seed` associated |
| Scalers | `scaler_sha256`, nunca se cargan sin verificar |
| Tracking | MLflow |
| Entorno | Lockfile + imagen Docker fijada |
| Trazabilidad | Cada prediccion guarda `request_id`, version de modelo y bloque `trace` |

---

## 8. Analisis de fallos (R-10)

Todo experimento debe incluir, como minimo:

| Analisis | Que responde |
|----------|--------------|
| Cambios bruscos del mercado | Si el error se concentra en dias de ruptura de tendencia o de noticias |
| Suavizado de picos | Si el modelo subestima sistematicamente la magnitud de los movimientos |
| Distribucion del error | Si el error es homogene o depende de regimen, volatilidad o liquidez |
| Desviacion de calibracion | Si la confianza declarada se corresponde con el acierto real |

Un resultado negativo se documenta con su analisis. No se esconde ni se reintenta
contra el conjunto de prueba.

---

## 9. Extensiones (fuera de Fase 1, D-08)

- Ablacion de variables (FS-A/B/C) y variable exogena BTC.
- Comparacion Monero-Bitcoin-Ethereum.
- Otras arquitecturas (Bi-LSTM, Transformer).
- Prediccion multi-horizonte.
- Dataset alternativo retail (Store Sales).
- Alertas por desviacion.