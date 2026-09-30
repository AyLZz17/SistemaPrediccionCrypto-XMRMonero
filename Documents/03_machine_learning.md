# XMR-Forecast — Diseño de Machine Learning

> **Aviso legal.** Análisis predictivo de series de tiempo. No es asesoría financiera ni promete rentabilidad; no incluye simulaciones de inversión (R-11).

Contenido: 1 Formulación · 2 Datos y variables · 3 Preprocesamiento · 4 Partición · 5 Modelos base · 6 Modelo recurrente · 7 Ajuste de hiperparámetros · 8 Evaluación · 9 Análisis de fallos · 10 Protocolo experimental · 11 Reproducibilidad · 12 Servicio de predicción · 13 Trampas frecuentes · 14 Extensiones

---

## 1. Formulación del problema

Sea `cₜ` el cierre diario de XMR, `W` el tamaño de ventana (por defecto **30**) y `Xₜ` la matriz de variables de los `W` días anteriores al día `t`.

| Tarea | Entrada | Salida | Modelos | Métricas |
|-------|---------|--------|---------|----------|
| **R — Regresión** | `X` (W días previos a `t`) | `ĉₜ` (cierre del día `t`) | MA, regresión lineal, ARIMA, persistencia, LSTM, GRU | MAE, RMSE, MAPE |
| **D — Dirección** | ídem | `p(cₜ > cₜ₋₁)` → sube/baja | LSTM/GRU clasificador; dirección derivada de R | Proporción de aciertos (+ F1, matriz de confusión) |

**Definición de dirección:** `dirₜ = 1` si `cₜ > cₜ₋₁`, `0` en otro caso.
**Dirección derivada de R:** `dir̂ₜ = 1` si `ĉₜ > cₜ₋₁`. Permite comparar "predecir el precio" vs. "predecir la dirección" (OE-7) sobre el mismo test.

**Referencia mínima de dirección:** la proporción de días "sube" en el conjunto de prueba (clase mayoritaria). Un clasificador solo aporta si supera este valor.

---

## 2. Datos y variables

**Fuente:** OHLCV diario de XMR (D-01). El período exacto se fija al iniciar la recolección (D-02). Cada corrida usa un `dataset_version` inmutable con checksum.

| Conjunto de variables | Contenido | Uso |
|-----------------------|-----------|-----|
| **FS-A** | `close` | Univariado; comparación con ARIMA |
| **FS-B** *(por defecto Fase 1)* | `open, high, low, close, volume` | Variables principales |
| **FS-C** *(extensión)* | FS-B + `return_1d`, `sma_7`, `sma_30`, `rsi_14`, `macd`, `macd_signal` | Mide el aporte de indicadores técnicos |
| **FS-D** *(extensión)* | FS-C + cierre de BTC | Variable exógena |

Validaciones de calidad al ingerir: `high ≥ max(open, close)`, `low ≤ min(open, close)`, `volume ≥ 0`, precios > 0, fechas únicas y ordenadas. Los huecos se documentan; nunca se rellenan con datos futuros (R-03).

---

## 3. Preprocesamiento

Orden obligatorio:

1. Ordenar cronológicamente y validar (S-01).
2. Calcular variables derivadas usando **solo pasado** (R-03).
3. Descartar las primeras filas con NaN por calentamiento.
4. **Particionar** (§4).
5. Ajustar `MinMaxScaler` **solo con train** (R-02); aplicar `transform` a val y test. Para el objetivo se usa un escalador propio del cierre, también ajustado con train, y se **des-escala** antes de calcular métricas.
6. Construir ventanas (§4.2).

```python
import numpy as np
from sklearn.preprocessing import MinMaxScaler

def fit_scalers(train_features: np.ndarray, train_close: np.ndarray):
    """Ajusta escaladores SOLO con train (R-02)."""
    fx = MinMaxScaler().fit(train_features)
    fy = MinMaxScaler().fit(train_close.reshape(-1, 1))
    return fx, fy
```

Nota sobre el volumen: si su distribución es muy asimétrica, se puede escalar `log1p(volume)` como variante documentada.

---

## 4. Partición temporal y ventanas

### 4.1 Partición cronológica (propuesta D-05)

| Subconjunto | Proporción | Uso |
|-------------|-----------|-----|
| Train | 70 % (los más antiguos) | Ajustar escaladores y parámetros del modelo |
| Validación | 15 % | *Early stopping*, elegir hiperparámetros, elegir campeón |
| Test | 15 % (los más recientes) | **Una sola evaluación final** (R-04) |

Para ajustar hiperparámetros con más robustez se puede usar `TimeSeriesSplit` (p. ej. 5 particiones) dentro de train+validación, sin tocar el test.

### 4.2 Ventanas sin fuga

**Regla:** una muestra pertenece al subconjunto de la **fecha de su objetivo**. Su entrada puede incluir días anteriores del subconjunto previo (información del pasado), pero nunca su objetivo ni datos posteriores.

```python
def make_windows(features: np.ndarray, target: np.ndarray, window: int,
                 target_start: int, target_end: int):
    """La muestra d predice target[d] con features[d-window:d].
    Requiere target_start >= window. Rango: d en [target_start, target_end)."""
    X = np.stack([features[d - window:d] for d in range(target_start, target_end)])
    y = np.asarray([target[d] for d in range(target_start, target_end)])
    return X, y

# n filas; i = fin de train, j = fin de validación
# train: d en [window, i) · val: d en [i, j) · test: d en [j, n)
```

Con `W = 30` cada muestra usa los 30 días previos para predecir el día 31. Con esta regla, el primer objetivo de validación (`d = i`) usa entradas de train (pasado), y ningún objetivo de val/test entra al ajuste.

---

## 5. Modelos base

Todos se evalúan con **un paso adelante** sobre el mismo test (R-05).

| Modelo | Definición | Ajuste |
|--------|------------|--------|
| **Persistencia** | `ĉₜ = cₜ₋₁` | Ninguno |
| **Media móvil** | `ĉₜ` = media de los últimos `k` cierres | `k` ∈ {5, 7, 14, 30} elegido en validación |
| **Regresión lineal** | Entrada = ventana aplanada (`W × n_features`); salida = `cₜ` | Mínimos cuadrados con train (opcional: Ridge, marcado como variante) |
| **ARIMA(p,d,q)** | Serie de cierres; `d` por pruebas de estacionariedad o `d=1` por defecto | (p,d,q) por AIC en train (o `auto_arima`); pronóstico **rodante de un paso**: se agrega cada observación real sin reestimar (`append(..., refit=False)`) |
| **Clase mayoritaria** *(solo tarea D)* | Predice siempre la clase más frecuente en train | Ninguno |

Para la tarea D, los baselines de regresión aportan una dirección derivada (`ĉₜ > cₜ₋₁`); la persistencia queda degenerada (siempre "baja") y se reporta solo como referencia.

---

## 6. Modelo recurrente (LSTM / GRU)

### 6.1 Arquitectura de partida

```
Entrada (W, n_features)
  → LSTM(100, return_sequences=True) → Dropout(0.2)
  → LSTM(100)                        → Dropout(0.2)
  → Dense(1)            [regresión: lineal]
  → Dense(1, sigmoid)   [dirección]
```

| Hiperparámetro | Valor de partida |
|----------------|------------------|
| Capas / unidades | 2 × 100 |
| Dropout | 0.2 |
| Optimizador | Adam |
| Lote | 32 |
| Épocas | 20 con parada temprana |
| *Patience* / monitor | 5 sobre `val_loss`, `restore_best_weights=True` |
| Pérdida | MSE (regresión) · entropía cruzada binaria (dirección) |
| Tasa de aprendizaje | 1e-3 |
| Ventana `W` | 30 |
| Alternativa | GRU con la misma estructura |

```python
import keras

def build_recurrent(cell: str, window: int, n_features: int,
                    units=(100, 100), dropout=0.2, task="regression", lr=1e-3):
    Cell = keras.layers.LSTM if cell == "lstm" else keras.layers.GRU
    inp = keras.Input(shape=(window, n_features))
    x = inp
    for k, u in enumerate(units):
        x = Cell(u, return_sequences=(k < len(units) - 1))(x)
        x = keras.layers.Dropout(dropout)(x)
    is_dir = task == "direction"
    out = keras.layers.Dense(1, activation="sigmoid" if is_dir else None)(x)
    model = keras.Model(inp, out)
    model.compile(
        optimizer=keras.optimizers.Adam(lr),
        loss="binary_crossentropy" if is_dir else "mse",
        metrics=["accuracy"] if is_dir else ["mae"],
    )
    return model

callbacks = [keras.callbacks.EarlyStopping(monitor="val_loss", patience=5,
                                           restore_best_weights=True)]
# model.fit(Xtr, ytr, validation_data=(Xva, yva), epochs=20, batch_size=32,
#           shuffle=False, callbacks=callbacks)
```

**`shuffle=False`:** dentro de train se mantiene el orden; la fuga temporal se evita sobre todo con la partición, pero conservar el orden simplifica la trazabilidad.

### 6.2 Por qué LSTM/GRU
- LSTM está diseñada para aprender dependencias a largo plazo y mitigar los problemas de las redes recurrentes clásicas; GRU es una variante más compacta.
- No se asume que el LSTM ganará; se evalúa objetivamente contra los baselines.

---

## 7. Ajuste de hiperparámetros

Solo con validación (R-04). Herramienta: Optuna, con presupuesto limitado (p. ej. 30–50 pruebas) y registro de cada prueba en MLflow.

| Hiperparámetro | Espacio de búsqueda |
|----------------|--------------------------------|
| Ventana `W` | {7, 14, 30, 60} |
| Capas | {1, 2, 3} |
| Unidades | {50, 100, 150} |
| Dropout | {0.1, 0.2, 0.3} |
| Tasa de aprendizaje | {1e-3, 5e-4} |
| Tipo de celda | {LSTM, GRU} |

Tras elegir la mejor configuración por **pérdida/métrica de validación**, se entrena con **≥ 5 semillas** y se reporta **media ± desviación estándar** en test (R-08). El **campeón** de cada tarea se designa por validación, no por test.

---

## 8. Evaluación

### 8.1 Métricas (en USD tras des-escalar)

```python
import numpy as np

def mae(y, yhat):   return float(np.mean(np.abs(y - yhat)))
def rmse(y, yhat):  return float(np.sqrt(np.mean((y - yhat) ** 2)))
def mape(y, yhat):  return float(100.0 * np.mean(np.abs((y - yhat) / y)))
def directional_accuracy(prev, y, yhat):
    return float(np.mean((yhat > prev) == (y > prev)))
```

| Tarea | Reporte obligatorio | Complementario |
|-------|--------------------|----------------|
| R | MAE, RMSE, MAPE | Gráfico real vs. predicho; histograma de residuos |
| D | Proporción de aciertos | F1, matriz de confusión, comparación con la clase mayoritaria |

### 8.2 Comparación
1. Mismo test y mismas fechas para todos los modelos (R-05).
2. Tabla: modelo × {MAE, RMSE, MAPE, aciertos de dirección}; para el LSTM/GRU, media ± desviación entre semillas.
3. Gráficos: real vs. predicho, barras de métricas, curvas de pérdida de entrenamiento/validación.
4. *(Opcional)* Prueba de **Diebold–Mariano** sobre los errores de un paso para comprobar si la diferencia entre dos modelos es estadísticamente distinguible.
5. Redactar la conclusión, **incluido el caso en que el LSTM no supere a los baselines** (R-09).

---

## 9. Análisis de fallos (R-10)

| Análisis | Método |
|----------|--------|
| Mayores errores | Listar los `k` días con mayor `|y − ŷ|` por modelo |
| Régimen de volatilidad | Volatilidad = desviación estándar móvil (hacia atrás) de retornos logarítmicos; umbral = percentil 75 **calculado con train**; comparar MAE/RMSE en régimen alto vs. bajo |
| Suavizado de picos | Razón `std(Δŷ) / std(Δy)`; valores < 1 indican suavizado; error en el 5 % de días con mayor |retorno| |
| Sesgo | Media de residuos (sub/sobrestimación sistemática) |
| Contexto | Relacionar períodos de falla con eventos de mercado **solo como contexto**, sin afirmar causalidad |

Los resultados se guardan en `failure_period` y se muestran en la pantalla de fallos.

---

## 10. Protocolo experimental

| Experimento | Descripción | Fase |
|-------------|-------------|------|
| **E-01** | Baselines (persistencia, MA, regresión lineal, ARIMA) con FS-B, `W=30` | F1 |
| **E-02** | LSTM, tarea R, FS-B, 5 semillas | F1 |
| **E-03** | GRU, tarea R, 5 semillas | F1 |
| **E-04** | LSTM/GRU tarea D (clasificador) y dirección derivada de R | F1 |
| **E-05** | Ajuste de hiperparámetros con Optuna (validación) y reevaluación en test | F1 |
| **E-06** | Variante con retornos logarítmicos (mitiga el desajuste de rango del `MinMaxScaler`) | F1 |
| **E-07** | Ablación FS-A vs. FS-B vs. FS-C (indicadores técnicos) | F3 |
| **E-08** | Variable exógena BTC (FS-D) y comparación XMR/BTC/ETH | F3 |
| **E-09** | Predicción multi-horizonte (varios días) | F3 |

Cada experimento se define en un YAML en `configs/` (dataset, features, `W`, split, modelos, hiperparámetros, semillas, tarea).

---

## 11. Reproducibilidad

| Elemento | Práctica |
|----------|----------|
| Semillas | Fijar `random`, `numpy` y TensorFlow/Keras (`keras.utils.set_random_seed`); semilla por corrida = `GLOBAL_SEED + k` |
| Datos | `dataset_version` con checksum; snapshot inmutable |
| Configuración | YAML versionado; se guarda copia en `artifacts/models/{run_id}/config.yaml` |
| Tracking | MLflow: parámetros, métricas, curvas, artefactos, `dataset_version_id` |
| Entorno | Lockfile + imagen Docker fijada |
| Verificación | Test que repite una corrida con la misma semilla y compara métricas dentro de una tolerancia (el determinismo bit a bit puede depender del hardware) |

**Seguridad de MLOps.** La procedencia no es solo reproducibilidad: el snapshot, feature set, scaler, modelo, configuración y código se tratan como una cadena de integridad. Cada artefacto debe tener versión, digest, origen y permisos; los datos sospechosos se ponen en cuarentena y ningún modelo se promueve sin validación de esquema, no fuga temporal, calidad, integridad y rollback. Los controles de poisoning, tampering, extracción, supply chain y abuso de inferencia están detallados en `05_seguridad.md` §4.6 y se verifican con `SEC-012`–`SEC-014`.

---

## 12. Servicio de predicción (aplicación web)

1. El *worker* entrena y evalúa; el administrador designa el **campeón** por tarea (por validación).
2. `GET /predictions/next`: la API obtiene los últimos `W` días de `ohlcv_daily` (verificando que no haya huecos), calcula las variables, aplica el escalador guardado, ejecuta el modelo (en **caché** en memoria) y des-escala.
3. La respuesta incluye modelo, `run_id`, métricas de test del modelo y el campo `disclaimer` (R-11).
4. Se guarda un registro `prediction` con `is_live = true`; cuando llega el dato real, un job completa `y_true` para medir el desempeño **en operación** y detectar deriva.

La inferencia usa un artefacto aprobado y verificado por digest; la API no acepta modelos, scalers ni rutas de artifacts proporcionados por el cliente. Se aplican límites de frecuencia, timeout, aislamiento del worker y logs de seguridad sin incluir el modelo ni secretos.

*(Extensión futura: alertas cuando el error en operación se desvía fuertemente de lo esperado.)*

---

## 13. Trampas frecuentes

| Trampa | Consecuencia | Defensa |
|--------|--------------|---------|
| Escalar todo el dataset antes de cortar | Fuga de información de test | R-02, test de no fuga |
| Indicadores con ventanas centradas o `shift(-n)` | Fuga | R-03, test de features |
| Usar test para *early stopping* o para elegir el modelo | Métricas optimistas | R-04; campeón por validación |
| Comparar ARIMA multi-paso con LSTM de un paso | Comparación injusta | ARIMA rodante de un paso |
| Precios de test fuera del rango de train | `MinMaxScaler` distorsiona | Variante E-06 sobre retornos |
| Reportar una sola semilla | Resultado no representativo | ≥ 5 semillas, media ± desviación |
| Ignorar la persistencia | Un modelo "bueno" puede solo copiar el último valor | Persistencia como baseline |
| Aciertos de dirección sin referencia | Accuracy engañosa con clases desbalanceadas | Comparar con clase mayoritaria |
| MAPE con valores cercanos a cero | Explota | No aplica al precio de XMR; comprobar en el EDA |
| Leer las métricas como rentabilidad | Conclusión errónea | R-11: las métricas de error no equivalen a desempeño en operaciones reales |

---

## 14. Extensiones (fuera de Fase 1, D-08)

- Ablación de variables (FS-A/B/C) y variable exógena BTC.
- Comparación Monero–Bitcoin–Ethereum.
- Otras arquitecturas (Bi-LSTM, Transformer).
- Predicción multi-horizonte.
- Dataset alternativo retail (Store Sales) mediante `DataSource`; allí se preferiría un **modelo global** (un solo modelo con embeddings de tienda/familia) en lugar de un modelo por serie, por costo computacional.
- Alertas por desviación entre lo esperado y lo observado.
