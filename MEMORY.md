# MEMORY.md -- Memoria de corto plazo (max. 50 lineas)
> Se reescribe tras cada tarea. Lo critico y permanente se promueve a AGENTS.md.

## Contexto
- LSTM/GRU frente a media movil, regresion lineal y ARIMA. Metricas MAE, RMSE,
  MAPE y acierto de direccion.
- **No es asesoria financiera**, no promete rentabilidad, no simula trading
  (R-11/R-12). El aviso viaja en la UI, en `/api/v1/meta/disclaimer` y en cada
  respuesta de prediccion.

## Tarea en curso
- T-028: revision de documentacion oficial y correccion del backend (cerrada).

## Estado verificado en esta sesion
- **Backend: `mvn clean verify` = 129 tests, BUILD SUCCESS**, Java 21.
- **Arranque real**: el jar levanta contra PostgreSQL 15.19 y Redis 7 reales,
  migra V1..V5 y responde por HTTPS.
- **`tools/verify-stack.ps1`: 48/48**, incluidas 4 comprobaciones nuevas que
  obligan a que la cola AVANCE (antes solo miraban que el trabajo apareciera).

## Bloqueantes corregidos (ninguno lo detectaba un test)
1. **Cola de trabajos muerta**: `claimIfPending` era `@Modifying` sin
   `@Transactional` (Spring Data JPA no los aplica a consultas declaradas) y el
   worker se llamaba a si mismo, saltandose el proxy. Todo trabajo quedaba en
   PENDING para siempre. Resuelto con `@Transactional` explicito y un bean
   `JobQueue` separado.
2. **OAuth Google nunca completaba**: la firma se verificaba con `parts[1]` (el
   payload) en vez de `parts[2]`. Fallaba siempre. Ahora hay tests con RSA real.
3. **`@Valid` ausente** en `POST /experiments/{id}/runs`; al anadirlo aparecio
   `List<@Size Integer>` (`@Size` no existe para `Integer`, HV000030) y el
   endpoint daba 500 en la primera llamada.
4. `AuditService` capturaba el fallo **dentro** de su transaccion: el alta de
   usuario respondia 500 con `UnexpectedRollbackException`.

## Decisiones
- D-00 Monero · D-04 regresion + direccion · D-05 split 70/15/15 cronologico.
- D-06 cola = tabla `jobs`; `idempotency_key` ahora `train:<sha256>` (no depende
  del ancho de dos columnas ajenas).
- ids opacos; traduccion de estados en el servidor (R-43).
- **No hay bandera para desactivar TLS** (se elimino `ML_VERIFY_TLS`, que se leia
  y no se usaba). `ML_SERVICE_URL` se exige `https://` **en el arranque**.
- `RestClient.Builder` con ambito **prototype**: como singleton, el `baseUrl` del
  servicio ML se filtraba al cliente de Google.

## Entorno (verificado, no asumir)
- `java` y `JAVA_HOME` = **Corretto 21.0.12** (antes JDK 8; nota ya corregida).
- **Siempre `mvn clean`**, y **detener el backend antes**: con el jar abierto,
  `maven-clean-plugin` falla con "Failed to delete ... jar".
- Maven 3.9.16 · Node 24.19 · Docker 29.6.2 · Python 3.12.10.
- PostgreSQL nativo en 5432: usar **55432** para el contenedor de pruebas.

## Pendiente / riesgos
- `docs/07_pruebas_carga.md`: **sin cifras hasta ejecutar k6**.
- `npm audit`: vulnerabilidades transitivas por decidir (R-29).
- Limitaciones ya documentadas: entrenamiento solo por CLI; estado y nonce de
  OAuth **en memoria del proceso** (no escala en horizontal); sin circuit breaker;
  sin MFA de administrador.
- **OAuth no probado contra Google real** (sin credenciales): lo verificado es la
  validacion del ID Token con tokens firmados de verdad.

## Pie de pagina obligatorio
`Sistema esta realizado por (c) AyLZz17 - AyLZz Software Solutions. Todos los derechos reservados.`
