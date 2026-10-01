# MEMORY.md -- Memoria de corto plazo (max. 50 lineas)
> Se reescribe tras cada tarea. Lo critico y permanente se promueve a AGENTS.md.

## Contexto
- LSTM/GRU frente a media movil, regresion lineal y ARIMA. MAE, RMSE, MAPE y
  acierto de direccion. **No es asesoria financiera**, no simula trading
  (R-11/R-12): aviso en UI, `/api/v1/meta/disclaimer` y respuestas de prediccion.

## Tarea en curso
- T-041 (registro/login en produccion): **arreglado y subido (`d83d053`, `a417fe1`)**.
  **Pendiente del cliente**: habilitar la Gmail API con scope `gmail.send`,
  anadir `https://developers.google.com/oauthplayground` a los redirect URIs,
  generar el refresh token en el Playground y fijar en Render
  `MAIL_TRANSPORT=gmail` + `GOOGLE_MAIL_REFRESH_TOKEN`. Entonces reintentar registro.
- Sin resolver: URI de callback en Google Cloud (si no se anadio), rotacion de
  credenciales expuestas, `ML_SERVICE_URL` con `sync: false`.

## Estado verificado en esta sesion
- Backend `mvn test` = **158 tests, BUILD SUCCESS** (Corretto 21.0.12).
- E2E local con el canal de correo MUERTO: registro **201 en 0,53 s**, fila en
  `users`, notificacion WARNING creada desde `task-1`, login `403
  EMAIL_NOT_VERIFIED`, `password/forgot` = 204.
- En Render Free el arranque tarda ~170 s: el "timeout" del primer login era el
  arranque del servicio, no un bug. DBeaver conectado al Postgres de Render.

## Lo que NO se pudo verificar (no inventarlo)
- Ningun correo ha llegado aun: la Gmail API no esta habilitada ni hay refresh
  token. El envio real en produccion esta **sin probar**.
- Render y Google Cloud: sin sesion en Chrome, no se leen logs ni variables.

## Bloqueantes historicos (ningun test los detectaba)
1. Cola muerta (@Modifying sin @Transactional + auto-invocacion sin proxy).
2. `build(true)` de `UriComponentsBuilder` = "ya codificado" (R-49/R-50/R-51).
3. Correo dentro de la transaccion sin timeouts: 2 min de cuelgue + 503 + alta
   revertida (T-041); Render Free ademas bloquea 25/465/587 (D-13).
4. `afterCommit` en el hilo de la peticion **pierde la escritura en silencio**
   (R-52): descubierto al medirlo, no por un test.

## Decisiones
- D-00 Monero · D-04 regresion + direccion · D-05 split 70/15/15 cronologico.
- D-06 cola = tabla `jobs`; ids opacos; estados traducidos en servidor (R-43).
- D-13 correo por Gmail API; `MAIL_TRANSPORT=smtp` solo en local. Sin bandera
  para desactivar TLS (R-33). `RestClient.Builder` prototype.

## Entorno (verificado, no asumir)
- `JAVA_HOME` = Corretto 21.0.12. **Siempre `mvn clean` con backend detenido.**
- Maven 3.9.16 · Node 24.19 · Docker 29.6.2 · Python 3.12.10 · PG local en 5432
  (el contenedor de pruebas usa **55432**).
- URLs: Vercel `sistema-prediccion-crypto-xmr-moner` · backend `xmr-backend-bcml`
  · ml-service `ml-service-adbd`.
