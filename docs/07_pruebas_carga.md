# 07 · Pruebas de carga

> **Aviso legal.** XMR-Forecast muestra capacidad predictiva evaluada sobre datos
> históricos. No es asesoría financiera, no promete rentabilidad y no simula
> operaciones de trading ni backtesting (R-11).

---

## 1. Estado: NO EJECUTADO

**No se ha ejecutado ninguna prueba de carga contra este sistema.**

En consecuencia, este documento **no contiene cifras de usuarios concurrentes,
peticiones por segundo, latencias ni tasas de error**, porque ninguna ha sido medida.
Afirmar capacidades de carga sin resultados reproducibles está prohibido por la
sección 15 del enunciado y por R-21 (*no inventar datos ni resultados*).

| Métrica | Valor |
|---|---|
| Usuarios concurrentes | **sin medir** |
| Peticiones por segundo | **sin medir** |
| Latencia p50 / p95 / p99 | **sin medir** |
| Errores 4xx / 5xx / timeouts | **sin medir** |
| CPU, memoria, PostgreSQL, Redis, MLflow | **sin medir** |
| Recuperación tras reinicio y degradación | **sin medir** |

---

## 2. Qué sí está preparado

### 2.1 Script de carga

`loadtests/api.js` (k6) está escrito y cubre los escenarios exigidos:

| Escenario | Qué verifica |
|---|---|
| `01 Login` | Inicio de sesión, tokens, y que la respuesta **nunca** devuelva `password` |
| `02 Autorizacion concurrente` | Un `VIEWER` **no** puede promover un campeón (403/401/404) |
| `03 Mercado` | Última vela y velas paginadas |
| `04 Predicciones` | Listado de predicciones del usuario autenticado |
| `05 Metricas` | Métricas de experimento |
| `06 Experimentos` | Listado de experimentos |

Incluye etapas de calentamiento, carga sostenida, pico repentino y recuperación,
y métricas propias: `xmr_login_errors`, `xmr_rate_limited`, `xmr_authz_denials`,
`xmr_auth_errors`, y tendencias de latencia por módulo.

### 2.2 Configuración

Todo es parametrizable por variables de entorno, para no fijar cifras en el código:

```bash
# 100 usuarios concurrentes
k6 run -e BASE_URL=https://localhost:8443 -e VUS=100  -e DURATION=5m -e SPIKE_VUS=200  loadtests/api.js

# 1000 usuarios concurrentes
k6 run -e BASE_URL=https://localhost:8443 -e VUS=1000 -e DURATION=5m -e SPIKE_VUS=2000 loadtests/api.js

# 3000 usuarios concurrentes
k6 run -e BASE_URL=https://localhost:8443 -e VUS=3000 -e DURATION=10m -e SPIKE_VUS=4000 loadtests/api.js
```

Para 5000 usuarios, varios *workers* k6 y varias réplicas del backend:

```bash
k6 run --vus 5000 --duration 10m --out json=results.json loadtests/api.js
docker compose up -d --scale backend=3
```

### 2.3 Umbrales de referencia

El script trae umbrales iniciales, **que no son resultados sino objetivos**:

| Métrica | Umbral |
|---|---|
| `http_req_failed` | < 5 % |
| `http_req_duration` | p95 < 500 ms · p99 < 1000 ms |
| `checks` | > 95 % |

Los umbrales deben ajustarse **después** de observar datos reales, nunca al revés para
que la prueba pase.

---

## 3. Requisitos para poder ejecutar

1. Sistema levantado: `docker compose up -d`.
2. Cuentas de prueba creadas. El script usa `loadtest<N>@example.com`; deben existir
   `CREW_SIZE` cuentas (20 por defecto) con contraseña `CargaDePrueba123!` y en estado
   `ACTIVE` con correo confirmado.
3. k6 instalado, con confianza en la CA de desarrollo
   (`--insecure-skip-tls-verify` solo en local, nunca en un entorno real).
4. Instrumentación de métricas habilitada en backend, PostgreSQL, Redis y MLflow.

---

## 4. Procedimiento para completar este documento

1. Crear las cuentas de prueba (con correo confirmado: si no, el login devuelve 403 y la
   prueba mide el bloqueo, no la API).
2. Ejecutar k6 en los niveles 100 → 1000 → 3000, y 5000 si el entorno lo permite.
3. Registrar en las tablas de este documento: usuarios concurrentes, RPS, p50/p95/p99,
   errores 4xx y 5xx, timeouts, y consumo de CPU/memoria de cada servicio.
4. Ejecutar la fase de **recuperación**: reiniciar backend y Redis con carga en curso, y
   anotar el tiempo de recuperación y si hubo pérdida de datos.
5. Anotar la **degradación**: comportamiento al saturar (¿rechaza con 429? ¿degrada a
   caché?).
6. Guardar el `summary.json` que genera el script como evidencia.
7. Ajustar los umbrales del script con los datos obtenidos.
8. Registrar el hardware, el número de réplicas y el dataset usados; sin ese contexto las
   cifras no son comparables.

---

## 5. Factores que condicionarán el resultado

Conviene anticiparlos para interpretar los datos cuando existan:

- **BCrypt con coste 12** es deliberadamente caro. El login es la operación más pesada
  del sistema y probablemente el primer cuello de botella; el login es también la ruta
  con el límite de *rate limit* más bajo (5/min por identidad).
- **Conexión por usuario** a PostgreSQL: con miles de usuarios simultáneos el *pool*
  Hikari (20 por defecto) será el límite antes que la CPU.
- **Redis** sostiene el *rate limit* y la caché: hay que vigilar su uso de memoria
  (`maxmemory 256mb`, política `allkeys-lru`).
- **Servicio ML**: las inferencias con modelos recurrentes son órdenes de magnitud más
  lentas que las lecturas de mercado. Los trabajos largos van por la tabla `jobs`, no
  bloquean la API, pero consumen CPU y memoria del servicio ML.
- **HTTPS** añade coste de handshake; con `XMR-Data` sin cachear en nginx, la latencia
  por手续ación TLS es relevante a escala.
- Lacompression de gzip está activa en el backend; el coste depende del tamaño de respuesta.

---

## 6. Referencias

- Enunciado, sección 15: niveles 100 / 1000 / 3000 / 5000 usuarios, carga sostenida,
  picos, estrés hasta el límite, recuperación y varias instancias.
- R-21: toda cifra de rendimiento debe provenir de una corrida registrada.
- Requisitos de pruebas de base de datos y recuperación: `README.md` §5 y
  [`08_operacion.md`](08_operacion.md).