# MEMORY.md — Memoria de corto plazo (máx. 50 líneas)
> Se reescribe tras cada tarea. Lo crítico y permanente se promueve a AGENTS.md.

## Contexto
- XMR-Forecast: análisis predictivo de series de tiempo para Monero (XMR).
- LSTM/GRU vs. media móvil, regresión lineal y ARIMA. Métricas MAE, RMSE, MAPE + acierto de dirección.
- **No es asesoría financiera**, no promete rentabilidad, no simula trading (R-11).

## Estado verificado (T-024 a T-027)
- **Backend:** `mvn clean verify` → 40 tests, BUILD SUCCESS, jar generado. Spring Boot 3.2.5 / Java 21.
- **Servicio ML:** `pytest` 199 pasan + 15 con TF; `ruff` y `mypy` limpios; cobertura 80 %.
- **Frontend:** `build` OK, `lint` 0, **145 tests**. Tema oscuro, 16 rutas.
- **Pie de página:** 22 aserciones en `footer-on-every-route.test.tsx`.
- **BD:** migraciones aplicadas sobre PostgreSQL 15.19 → 22 tablas, 27 FK, 71 índices.
- **Backup/restore:** ciclo completo probado, 0 filas huérfanas al restaurar.
- **Mermaid:** 9/9 diagramas validan con parse + render (R-25).

## Contrato OAuth (aplicado en ambos lados)
Google redirige con **GET** `/api/v1/auth/google/callback`; el backend valida state+nonce
y responde **302** al frontend con la sesión en el **fragmento** `#access_token=…`
(nunca llega al servidor ni a los logs); el frontend la borra con `history.replaceState`.

## Entorno (verificado, no asumir)
- `java` en PATH = **JDK 8**. JDK 21: `C:\Program Files\Eclipse Adoptium\jdk-21.0.11.10-hotspot`.
  Exportar `JAVA_HOME` antes de cada `mvn`.
- Maven 3.9.16 · Node 24.19 · npm 11.17 · Python **3.12.10** (la spec pide 3.11) · Docker 29.6.2.
- **Siempre `mvn clean`**: hay clases compiladas por Eclipse (ECJ) en `target/` que Maven
  reutiliza por compilación incremental y producen fallos fantasma.

## Decisiones
- D-00 Monero · D-04 regresión + dirección · D-05 split 70/15/15 cronológico.
- D-06 cola = tabla `jobs` con `idempotency_key` única (no Celery en el backend Java).
- `spring-boot-starter-data-validation` no existe → `spring-boot-starter-validation`.
- Flyway 9.22.3 incluye PostgreSQL en `flyway-core`; `flyway-database-postgresql` es de Flyway 10.
- `permissionsPolicyHeader` **no existe en Spring Security 6.2.4** (la de Boot 3.2.5), por eso
  se quitó; sí existe en 6.5+. No es prohibición permanente: comprobar la versión antes de añadirlo.
- `opencode.json` con MCP de proyecto: `context7` y `playwright` conectados; `postgres`
  **desactivado** a propósito (ejecuta SQL y declara dependencias como `latest`, contra R-17).

## Pendiente
- `docs/01-06` siguen describiendo el sistema anterior: actualizar a la realidad.
- Pruebas de carga: `loadtests/api.js` escrito pero **no ejecutado**. Sin cifras hasta correrlo.
- `npm audit`: vulnerabilidades transitivas en el árbol de desarrollo (R-29), por decidir.

## Pie de página obligatorio (§7)
`Sistema esta realizado por © AyLZz17 - AyLZz Software Solutions. Todos los derechos reservados.`
Componente único reutilizable; debe aparecer en TODAS las rutas y estados.