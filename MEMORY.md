# MEMORY.md -- Memoria de corto plazo (max. 50 lineas)
> Se reescribe tras cada tarea. Lo critico y permanente se promueve a AGENTS.md.

## Contexto
- LSTM/GRU frente a media movil, regresion lineal y ARIMA. Metricas MAE, RMSE,
  MAPE y acierto de direccion.
- **No es asesoria financiera**, no promete rentabilidad, no simula trading
  (R-11/R-12). Aviso en UI, `/api/v1/meta/disclaimer` y respuestas de prediccion.

## Tarea en curso
- T-038: validador acepta `VITE_VERCEL_*` (pagina en blanco en Vercel).
  Auto-deploy en curso; falta probar registro contra Render.

## Estado verificado en esta sesion
- **Stack Docker UP**: 6/6 `healthy`. **`tools/verify-stack.ps1`: 48/48.**
- Frontend TLS verificado con la CA de desarrollo: `200` en `/` y en
  `/api/v1/meta/disclaimer` via proxy (aviso legal intacto).
- Backend: `mvn clean verify` = 129 tests, BUILD SUCCESS (Java 21).

## Bloqueantes historicos (ningun test los detectaba)
1. Cola muerta (@Modifying sin @Transactional + auto-invocacion sin proxy).
2. OAuth: firma con parts[1]; @Valid ausente + List<@Size Integer>; catch
   dentro de la transaccion (R-44/R-45/R-46/R-47).

## Fallos del primer despliegue (solo en local, nunca en tests)
1. mlflow v2.8.0 inexistente → v2.8.1. 2. Pins py3.12 vs base 3.11 → 3.12.
3. mkdir tras USER ml. 4. truststore OpenSSL3 vacio → keytool. 5. MLflow
   sin psycopg2 → SQLite local. 6. wget sin -O; healthz en 8080 http.
7. assume-unchanged ocultaba cambios. 8. VITE_* en BUILD → build-arg.
9. CORS: http://localhost:3000 en .env. 10. Frontend sin TLS → nginx 8443
   ssl + cert `frontend`, 3000→8443. 11. Upstream `xmr_backend` rompia el
   SNI (Tomcat: illegal_parameter) → upstream `backend` = SAN del cert.
12. HTTP en claro al puerto TLS daba el 400 en crudo → `error_page 497`
    redirige a https; 500/502/503/504 sirven `50x.html` propia (oscura,
    con pie R-11, sin secretos). La SPA ya tenia NotFound/ErrorBoundary.

## Decisiones
- D-00 Monero · D-04 regresion + direccion · D-05 split 70/15/15 cronologico.
- D-06 cola = tabla `jobs`; ids opacos; estados traducidos en servidor (R-43).
- Sin bandera para desactivar TLS; frontend local en https (R-33).
- `RestClient.Builder` prototype (no filtrar `baseUrl` al cliente de Google).

## Entorno (verificado, no asumir)
- `JAVA_HOME` = Corretto 21.0.12. **Siempre `mvn clean` con backend detenido.**
- Maven 3.9.16 · Node 24.19 · Docker 29.6.2 · Python 3.12.10.
- PostgreSQL nativo en 5432: el contenedor de pruebas usa **55432**.
## Pendiente / riesgos
- k6 sin ejecutar; `npm audit` por decidir; OAuth sin probar en Google.
- Entrenamiento CLI; OAuth en memoria; sin circuit breaker; sin MFA admin.
- T-039: SMTP para cuenta/recuperacion, pantalla `/verify-email`; Google requiere credenciales reales.
