# MEMORY.md -- Memoria de corto plazo (max. 50 lineas)
> Se reescribe tras cada tarea. Lo critico y permanente se promueve a AGENTS.md.

## Contexto
- LSTM/GRU frente a media movil, regresion lineal y ARIMA. Metricas MAE, RMSE,
  MAPE y acierto de direccion.
- **No es asesoria financiera**, no promete rentabilidad, no simula trading
  (R-11/R-12). Aviso en UI, `/api/v1/meta/disclaimer` y respuestas de prediccion.

## Tarea en curso
- T-041 (registro/login en produccion): **arreglado y subido (`d83d053`)**.
  **Pendiente del cliente**: (1) habilitar Gmail API + scope `gmail.send`,
  (2) anadir `https://developers.google.com/oauthplayground` a los redirect
  URIs, (3) generar el refresh token en el Playground, (4) fijar en Render
  `MAIL_TRANSPORT=gmail` y `GOOGLE_MAIL_REFRESH_TOKEN`, (5) reintentar registro.
- Sin resolver aun: URI de callback de Google en Cloud Console (si no se anadio),
  rotacion de credenciales expuestas, `ML_SERVICE_URL` con `sync: false`.

## Estado verificado en esta sesion
- Backend `mvn test` = **158 tests, BUILD SUCCESS** (Java 21, Corretto 21.0.12).
- E2E local con el canal de correo MUERTO: registro **201 en 0,53 s**, fila en
  `users`, notificacion WARNING creada desde `task-1`, login `403
  EMAIL_NOT_VERIFIED`, `password/forgot` = 204.
- Backend local arranca en 8,7 s; en Render Free tarda ~170 s (CPU compartida):
  el "timeout" del primer login era el arranque del servicio, no un bug.
- DBeaver conectado al Postgres de Render (55432 en local). `users` = 3 filas,
  todas `ACTIVE`, `email_verified = false` en las tres.

## Lo que NO se pudo verificar (no inventarlo)
- Ningun correo ha llegado aun: la Gmail API no esta habilitada ni hay refresh
  token. El envio real en produccion esta **sin probar**.
- Panel de Render y Google Cloud: sin sesion en Chrome, no se leen logs ni
  variables. No se modifico ninguna variable de entorno.

## Bloqueantes historicos (ningun test los detectaba)
1. Cola muerta (@Modifying sin @Transactional + auto-invocacion sin proxy).
2. `build(true)` de `UriComponentsBuilder` = "ya codificado" (R-49/R-50/R-51).
3. **Correo dentro de la transaccion sin timeouts**: 2 min de cuelgue + 503 +
   alta revertida (T-041). Render Free ademas bloquea 25/465/587 (D-13).
4. **`afterCommit` en el hilo de la peticion pierde la escritura en silencio**
   (R-52): la notificacion entraba en la transaccion cerrada y la conexion la
   revetia. Descubierto al medir, no por un test.

## Decisiones
- D-00 Monero · D-04 regresion + direccion · D-05 split 70/15/15 cronologico.
- D-06 cola = tabla `jobs`; ids opacos; estados traducidos en servidor (R-43).
- D-13 correo por Gmail API (HTTPS); `MAIL_TRANSPORT=smtp` solo en local.
- Sin bandera para desactivar TLS; frontend local en https (R-33).
- `RestClient.Builder` prototype; GmailApiTransport lleva timeouts propios
  (3 s / 8 s) porque su llamada compite con el timeout de 20 s del frontend.

## Entorno (verificado, no asumir)
- `JAVA_HOME` = Corretto 21.0.12. **Siempre `mvn clean` con backend detenido.**
- Maven 3.9.16 · Node 24.19 · Docker 29.6.2 · Python 3.12.10.
- PostgreSQL nativo en 5432: el contenedor de pruebas usa **55432**.
- URLs: Vercel `sistema-prediccion-crypto-xmr-moner` · backend `xmr-backend-bcml`
  · ml-service `ml-service-adbd` (las tres con el sufijo exacto de Render).
