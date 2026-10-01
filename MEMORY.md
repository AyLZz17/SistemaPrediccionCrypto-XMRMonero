# MEMORY.md -- Memoria de corto plazo (max. 50 lineas)
> Se reescribe tras cada tarea. Lo critico y permanente se promueve a AGENTS.md.

## Contexto
- LSTM/GRU frente a media movil, regresion lineal y ARIMA. Metricas MAE, RMSE,
  MAPE y acierto de direccion.
- **No es asesoria financiera**, no promete rentabilidad, no simula trading
  (R-11/R-12). Aviso en UI, `/api/v1/meta/disclaimer` y respuestas de prediccion.

## Tarea en curso
- T-040: corregido el 500 de `GET /api/v1/auth/google/authorize` (causa raiz en
  codigo, no en variables). **Pendiente**: push a main y verificar el 307 real
  contra el backend desplegado + pantalla de Google en Chrome.

## Estado verificado en esta sesion
- Backend `mvn clean verify` = **146 tests**, BUILD SUCCESS (Java 21).
- Frontend **148/148** + `npm run build` OK. (Un test de `route-protection`
  dio timeout con la suite entera y pasa aislado: carga, no regresion.)
- `GoogleOAuthFlowTest` (nueva, 15 tests) falla con el codigo viejo y pasa con
  el arreglo: comprobado con `git stash`, no supuesto.
- `curl` al backend de Render **antes** del arreglo = 500 `INTERNAL_ERROR`.

## Lo que NO se pudo verificar (no inventarlo)
- Panel de Render: sin sesion en Chrome, no se pudieron leer logs, variables ni
  el estado del deploy. **No se modifico ninguna variable de entorno.**
- Google Cloud: sin acceso, no se reviso el cliente OAuth ni sus redirect URIs.
- `ML_SERVICE_URL` sigue con `sync: false`; el nombre real del servicio ML no
  esta confirmado (`ml-service-h6u5` responde 404). **PENDIENTE PARA EL CLIENTE.**

## Bloqueantes historicos (ningun test los detectaba)
1. Cola muerta (@Modifying sin @Transactional + auto-invocacion sin proxy).
2. OAuth: firma con parts[1]; @Valid ausente + List<@Size Integer>; catch
   dentro de la transaccion (R-44/R-45/R-46/R-47).
3. **`build(true)` de `UriComponentsBuilder`**: no es "codifica", es "ya esta
   codificado" → `scope=openid email profile` con espacios → 500 (R-49). El
   mismo error estaba en `MlServiceClient.get()`; corregido tambien.

## Decisiones
- D-00 Monero · D-04 regresion + direccion · D-05 split 70/15/15 cronologico.
- D-06 cola = tabla `jobs`; ids opacos; estados traducidos en servidor (R-43).
- Sin bandera para desactivar TLS; frontend local en https (R-33).
- `RestClient.Builder` prototype (no filtrar `baseUrl` al cliente de Google).

## Entorno (verificado, no asumir)
- `JAVA_HOME` = Corretto 21.0.12. **Siempre `mvn clean` con backend detenido.**
- Maven 3.9.16 · Node 24.19 · Docker 29.6.2 · Python 3.12.10.
- PostgreSQL nativo en 5432: el contenedor de pruebas usa **55432**.
- Spring Boot 3.2.5 (Spring 6.1.6): `build(true)` valida caracteres; ver R-49.
