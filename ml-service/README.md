# ml-service · Servicio de Machine Learning

> **Aviso legal.** XMR-Forecast muestra capacidad predictiva evaluada sobre datos
> históricos. No es asesoría financiera, no promete rentabilidad y no simula
> operaciones de trading ni backtesting (R-11).

Servicio especializado que implementa la lógica de Machine Learning de XMR-Forecast.
**No es público (R-32): solo el backend Spring Boot lo consume por HTTPS** dentro de la
red privada de Docker.

---

## 1. Regla estructural: `app/ml` es puro (R-13)

```
app/
├── main.py          # aplicación FastAPI, middlewares, /health
├── config.py        # configuración por entorno (pydantic-settings)
├── services.py      # puente web -> app/ml
├── api/             # RUTAS HTTP y esquemas de request/response
├── clients/         # cliente de MLflow (tolerante a caída)
└── ml/              # PAQUETE PURO: sin FastAPI, Pydantic ni SQLAlchemy
    ├── data/        #   ingest, cleaning, features, split
    ├── models/      #   baselines, arima, recurrent, registry
    ├── evaluation/  #   metrics, compare, error_analysis
    └── pipelines/   #   train, inference
```

`app/ml/**` **no importa** `fastapi`, `starlette`, `pydantic`, `sqlalchemy` ni ningún
otro framework web o de base de datos. Eso lo hace importable desde un notebook, una
CLI o un script, y `tests/test_no_framework_imports.py` lo verifica por análisis del
árbol de sintaxis, no por convención.

---

## 2. Puesta en marcha

```bash
python -m venv .venv
.venv\Scripts\activate        # Windows
source .venv/bin/activate     # Linux/macOS

pip install -r requirements.txt
pip install -r requirements-dev.txt

# Pruebas, lint y tipos
python -m pytest
python -m ruff check .
python -m mypy app

# Arranque local con TLS (usa los certificados de docker/certs)
uvicorn app.main:app --host 127.0.0.1 --port 8443 \
  --ssl-certfile ../docker/certs/ml-service/cert.pem \
  --ssl-keyfile   ../docker/certs/ml-service/key.pem
```

Con Docker, desde la raíz del proyecto:

```bash
docker compose up -d ml-service
docker compose logs -f ml-service
```

---

## 3. Variables de entorno

| Variable | Por defecto | Descripción |
|---|---|---|
| `ML_SSL_CERTFILE` | `/certs/ml-service/cert.pem` | Certificado TLS del servicio |
| `ML_SSL_KEYFILE` | `/certs/ml-service/key.pem` | Clave privada TLS |
| `ML_SSL_CAFILE` | `/certs/ca/ca.crt` | CA para validar a sus clientes |
| `ML_ALLOW_PLAINTEXT_DEV` | `false` | **Nunca** `true` fuera de desarrollo |
| `MLFLOW_TRACKING_URI` | `https://mlflow:5000` | Tracking por HTTPS |
| `MLFLOW_SSL_CAFILE` | `/certs/ca/ca.crt` | CA del MLflow |
| `ML_ARTIFACTS_DIR` | `/app/artifacts` | Artefactos versionados (R-28) |

**Ninguna credencial tiene valor por defecto.** Los secretos llegan por entorno (R-14).

---

## 4. HTTPS

- Uvicorn arranca **solo** con `--ssl-keyfile` y `--ssl-certfile`; no hay puerto plano.
- Un middleware rechaza cualquier petición que no llegue por TLS.
- La verificación de certificados **nunca** se desactiva; `ML_ALLOW_PLAINTEXT_DEV`
  solo habilita el desarrollo local y está en `false` en el Compose.
- `X-Request-Id` y `X-Trace-Id` se aceptan del backend y se devuelven en cada
  respuesta y en los logs, para la correlación extremo a extremo.

---

## 5. Contrato HTTP

Estos son los endpoints que consume el backend. Los esquemas usan `extra='forbid'`:
**enviar un campo que no exista aquí produce un 422**, y ese desajuste está
cubierto por `MlServiceClientContractTest` en el lado Java.

### `GET /health`
```json
{"status": "UP", "service": "ml-service", "version": "1.0.0"}
```

### `POST /v1/predict`
```json
// Entrada: solo estos cuatro campos
{"model_key": "lstm_base", "version": "v1", "lookback_days": 30, "symbol": "XMR-USD"}
```
```json
// Salida
{
  "model_key": "lstm_base", "version": "v1",
  "target_date": "2026-09-30",
  "predicted_close": 168.42,
  "predicted_direction": "UP",
  "actual_close": null,
  "confidence": 0.61,
  "trace": {
    "dataset_version": "xmr-usd-2026-09-29",
    "artifact_sha256": "…", "config_sha256": "…",
    "seed": 42, "generated_at": "2026-09-30T10:00:00Z"
  }
}
```

`lookback_days` se valida contra la ventana con la que se entrenó el modelo: una
longitud distinta devuelve `400 INVALID_REQUEST` en lugar de producir un error
opaco dentro de Keras.

### `GET /v1/models`
Lista de versiones con artefacto, checksum, dataset de origen y métricas.

### `GET /v1/metrics/compare?experiment_id=…`
Comparación entre modelos sobre **las mismas fechas de prueba**, con el número de
semillas y la desviación típica.

### Errores
```json
{"detail": "…", "code": "…", "request_id": "…"}
```

---

## 6. Rigor metodológico

Estas reglas están **verificadas por pruebas**, no solo documentadas:

| Regla | Qué garantiza | Test |
|---|---|---|
| R-01 | Partición estrictamente cronológica, sin `shuffle` | `test_split_chronological.py` |
| R-02 | `MinMaxScaler` ajustado **solo** con train | `test_scaler_fit_train_only.py` |
| R-03 | Indicadores causales, sin `shift(-n)` ni ventanas centradas | `test_features_no_leakage.py` |
| R-05 | Todos los modelos sobre las mismas fechas de test | `test_baselines.py`, `test_metrics.py` |
| R-08 | Semillas fijadas y reproducibilidad | `test_reproducibility.py` |
| R-13 | `app/ml` sin frameworks web/BD | `test_no_framework_imports.py` |
| R-23 | Una muestra pertenece al subconjunto de la fecha de su objetivo | `test_split_chronological.py` |
| R-24 | ARIMA con pronóstico rodante de **un paso** | `test_baselines.py` |

**Modelo campeón**: se elige por métricas de **validación**, nunca por test (R-24).
El conjunto de prueba se usa una sola vez (R-04). Un resultado negativo es un
resultado válido y se reporta tal cual (R-09): si el LSTM no supera a los baselines,
eso es el hallazgo, no un fallo.

---

## 7. Modelos

| Modelo | Tipo | Notas |
|---|---|---|
| **LSTM** | Recurrente | Keras; requiere el extra `tf` |
| **GRU** | Recurrente | Keras; requiere el extra `tf` |
| **Media móvil** | Baseline | Predictor trivial, sin parámetros entrenados |
| **Regresión lineal** | Baseline | sklearn |
| **ARIMA** | Statistical | statsmodels, **rodante de un paso** |

TensorFlow es **opcional**: `pip install -e .[tf]`. Sin él, LSTM y GRU no están
disponibles y el servicio responde con un error explícito, pero el resto del
sistema (los tres baselines, las métricas y las features) sigue funcionando.

Las métricas obligatorias son **MAE, RMSE, MAPE** y **acierto de dirección**, más
matriz de confusión, calculadas sobre predicciones des-escaladas a USD (R-02).

---

## 8. Estado verificado

| Comprobación | Resultado |
|---|---|
| `pytest` | **199 pasan, 15 omitidas** (las omitidas son de LSTM/GRU: requieren TensorFlow) |
| `pytest` con TensorFlow instalado | **15 adicionales pasan** (214 en total) |
| `ruff check .` | Sin incidencias |
| `mypy app` | Sin incidencias en 31 ficheros |
| Cobertura de `app/ml` | **80 %** |

### Qué NO está verificado

- **TensorFlow no está instalado** en el `.venv` de este repositorio: la instalación
  falla en Windows por el límite de longitud de ruta del *wheel*. El código recurrente
  se validó en un entorno virtual con ruta corta.
- **La imagen Docker nunca se construyó**: no había demonio Docker disponible.
- **El handshake TLS real de uvicorn no se probó**: la aplicación de TLS se valida por
  `X-Forwarded-Proto` con `TestClient`, no sobre un socket TLS vivo.
- **Python 3.11 nunca se ejecutó**: solo había 3.12.10. El `Dockerfile` apunta a 3.11.
- **No hay datos reales**: todas las pruebas usan OHLCV sintético. **No existe ningún
  modelo entrenado con datos de XMR reales**, por lo que **no se puede afirmar ninguna
  cifra de rendimiento** (R-21).

---

© AyLZz17 - AyLZz Software Solutions. Todos los derechos reservados.