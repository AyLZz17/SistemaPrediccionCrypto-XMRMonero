# MEMORY.md Ã¢â‚¬â€ Memoria de corto plazo (mÃƒÂ¡x. 50 lÃƒÂ­neas)
> Se reescribe tras cada tarea. Lo crÃƒÂ­tico y permanente se promueve a AGENTS.md.

## Contexto
- LSTM/GRU vs. media mÃƒÂ³vil, regresiÃƒÂ³n lineal y ARIMA. MÃƒÂ©tricas MAE, RMSE, MAPE + acierto de direcciÃƒÂ³n.
- **No es asesorÃƒÂ­a financiera**, no promete rentabilidad, no simula trading (R-11).

## Tarea en curso (T-024): construcciÃƒÂ³n real del sistema
El registro anterior (T-013Ã¢â‚¬Â¦T-022) afirmaba un backend inexistente: **RETRACTADO en T-023**.

## Estado verificado
- **Backend: `mvn -B clean test` Ã¢â€ â€™ 35 tests, BUILD SUCCESS.**
 `pom.xml` raÃƒÂ­z agregador + Enforcer `[21,22)` (R-37). 18 tablas Flyway (V1+V2). Auth completa (registro, login, refresh con rotaciÃƒÂ³n y detecciÃƒÂ³n de reutilizaciÃƒÂ³n, logout, reset/cambio de contraseÃƒÂ±a, verificaciÃƒÂ³n de correo, bloqueo progresivo). JWT HS512 con sub/iss/aud/iat/exp/jti/roles, `alg` estricto, revocaciÃƒÂ³n por jti. Google OAuth 2.0 Auth Code + OIDC: valida firma JWKS RS256, iss, aud, exp, nonce y state. BCrypt coste 12; refresh/reset/verify solo como HMAC-SHA256 en base de datos. Rate limiting Redis Ã¢â€ â€™ 429 + `Retry-After`; CORS solo HTTPS; `X-Request-Id` + MDC.
- **Frontend: lint 0, build OK, 145 tests OK.** Tema oscuro permanente, 16 pÃƒÂ¡ginas,
 pie de pÃƒÂ¡gina obligatorio con 22 aserciones en `footer-on-every-route.test.tsx`.
- **ml-service: `app/ml` con 19 mÃƒÂ³dulos, 0 imports de fastapi/pydantic/sqlalchemy (R-13).**
- **Infra: `docker-compose.yml` vÃƒÂ¡lido (6 servicios), CI YAML vÃƒÂ¡lido (6 jobs).**

## Contrato OAuth (aplicado en ambos lados)
Google redirige con **GET** `/api/v1/auth/google/callback`; el backend valida state+nonce
y responde **302** al frontend con la sesiÃƒÂ³n en el **fragmento** `#access_token=Ã¢â‚¬Â¦`
(el fragmento nunca llega al servidor ni a los logs); el frontend lo borra con
`history.replaceState`. **No hay canje de cÃƒÂ³digo desde el navegador.**

## Entorno (verificado, no asumir)
- `java` en PATH = **JDK 8**. JDK 21: `C:\Program Files\Eclipse Adoptium\jdk-21.0.11.10-hotspot`.
 Exportar `JAVA_HOME` antes de cada `mvn`.
- Maven 3.9.16 Ã‚Â· Node 24.19 Ã‚Â· npm 11.17 Ã‚Â· Python **3.12.10** (la spec pide 3.11) Ã‚Â· Docker 29.6.2.
- **Siempre `mvn clean`**: hay clases compiladas por Eclipse (ECJ) en `target/` que Maven
 reutiliza por compilaciÃƒÂ³n incremental y producen fallos fantasma.

## Decisiones
- D-00 Monero Ã‚Â· D-04 regresiÃƒÂ³n + direcciÃƒÂ³n Ã‚Â· D-05 split 70/15/15 cronolÃƒÂ³gico.
- D-06 cola = tabla `jobs` con `idempotency_key` ÃƒÂºnica (no Celery en el backend Java).
- Flyway 9.22.3 incluye PostgreSQL en `flyway-core`; `flyway-database-postgresql` es de Flyway 10 Ã¢â€ â€™ no aÃƒÂ±adir.
- Lombok 1.18.34 `provided` en entidades; DTOs como `record`.
- Los validadores de CI **no deben escanear el propio `ci.yml`**: contiene sus patrones.

## Pendiente
- Cerrar `ml-service` (pytest/ruff/mypy) y verificar arranque HTTPS.
- `docs/07_pruebas_carga.md`: **sin cifras hasta ejecutar k6**. No afirmar carga.
- Actualizar `docs/01-06` para que coincidan con el cÃƒÂ³digo real.
- `npm audit` reporta vulnerabilidades transitive (R-29): decidir con criterio.
- Backups/restauraciÃƒÂ³n de PostgreSQL: script + prueba, aÃƒÂºn no ejecutados.

## Pie de pÃƒÂ¡gina obligatorio (Ã‚Â§7)
`Sistema esta realizado por Ã‚Â© AyLZz17 - AyLZz Software Solutions. Todos los derechos reservados.`
Componente ÃƒÂºnico reutilizable; debe aparecer en TODAS las rutas y estados.
