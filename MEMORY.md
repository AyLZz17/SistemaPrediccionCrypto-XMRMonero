# MEMORY.md -- Memoria de corto plazo (max. 50 lineas)
> Se reescribe tras cada tarea. Lo critico y permanente se promueve a AGENTS.md.

## Contexto
- LSTM/GRU frente a media movil, regresion lineal y ARIMA. Metricas MAE, RMSE,
  MAPE y acierto de direccion.
- **No es asesoria financiera**, no promete rentabilidad, no simula trading
  (R-11/R-12). El aviso viaja en la UI, en `/api/v1/meta/disclaimer` y en cada
  respuesta de prediccion.

## Tarea en curso
- T-027: alineacion del contrato HTTP y verificacion de arranque. Cerrada; ver
  abajo y el registro de AGENTS.md.

## Estado verificado en esta sesion
- **Backend: `mvn clean test` = 94 tests, BUILD SUCCESS.** Java 21. El `pom`
  raiz bloquea si el JDK no es 21 (R-37). 13 controladores, **46 rutas** bajo
  `/api/v1`, 5 migraciones Flyway (22 tablas).
- **Frontend: `npm test` = 147 tests OK; `npm run lint` y `tsc --noEmit`
  limpios.** Tema oscuro permanente, 16 paginas, pie de pagina obligatorio
  verificado en todas las rutas.
- **ml-service: 199 tests OK, 15 omitidos** (los de TensorFlow, que es extra
  opcional; `pip install .[tf]`). `app/ml` sin imports de fastapi/pydantic.
- **Arranque real verificado**: el jar empaquetado levanta contra PostgreSQL
  15.19 en Docker, migra solo, y responde por HTTPS. **43/43 comprobaciones de
  `tools/verify-stack.ps1` pasan.**
- **Backup y restauracion ejecutados**: 30 filas de `market_data` y 5 de
  `models` destruidas y recuperadas al 100 %, checksum verificado, 0 huerfanos.

## Defectos de arranque encontrados y corregidos (ninguno lo detectaba un test)
1. `spring-boot:repackage` no se heredaba sin `spring-boot-starter-parent`: el
   jar salia de 300 KB sin `Main-Class` y el contenedor moria. Ver R-42.
2. Siete columnas `CHAR(64)` frente a `varchar(64)` de las entidades:
   `ddl-auto: validate` impedia arrancar. Corregido en V5.
3. JPQL con `com.aylzz.xmrforecast.job.Job.JobStatus.PENDING` en un UPDATE:
   Hibernate lo rechaza. Ahora se usan parametros ligados.
4. `spring.security.oauth2.client.registration.google` con `client-id` vacio
   hacia fallar el arranque. Eliminado; el flujo es manual.
5. `NotificationService` con `REQUIRES_NEW` hacia fallar **todo alta de
   usuario** por clave foranea. Ahora `REQUIRED`.
6. `:from is null or ...` en JPQL: PostgreSQL no deduce el tipo del parametro y
   devolvia 500. Ahora se usan extremos tipados.
7. `AuthenticatedUser` sin `@AuthenticationPrincipal` en `PredictionController`:
   500 en la primera peticion. `ControllerParameterTest` lo impede.

## Decisiones
- D-00 Monero · D-04 regresion + direccion · D-05 split 70/15/15 cronologico.
- D-06 cola = tabla `jobs` con `idempotency_key` unica; worker en proceso.
- **ids publicos opacos (cadenas)** y **traduccion de estados en el servidor**
  (`DRAFT`->`PENDING`, `COMPLETED`->`SUCCEEDED`, `PENDING`->`QUEUED`). R-43.
- Google OAuth se implementa a mano; `spring-boot-starter-oauth2-client` se
  elimino a proposito.

## Entorno (verificado, no asumir)
- `java` en PATH = **JDK 8**. JDK 21: `C:\Program Files\Eclipse Adoptium\jdk-21.0.11.10-hotspot`.
- **Siempre `mvn clean`**: hay clases compiladas por ECJ en `target/` que
  producen fallos fantasma ("Unresolved compilation problem").
- Python 3.12.10 (la spec pide 3.11) · Maven 3.9.16 · Node 24.19 · Docker 29.6.2.
- Bash: usar `C:\Program Files\Git\bin\bash.exe`; `bash` del PATH no existe.
- Hay un PostgreSQL **nativo** en 5432: no usar ese puerto para el contenedor.

## Pendiente
- `docs/07_pruebas_carga.md`: **sin cifras hasta ejecutar k6**. No afirmar carga.
- `npm audit`: vulnerabilidades transitivas por decidir (R-29).
- Limitaciones conocidas, ya documentadas: entrenamiento solo por CLI; estado
  OAuth en memoria del proceso; sin circuit breaker; sin MFA de administrador.

## Pie de pagina obligatorio
`Sistema esta realizado por (c) AyLZz17 - AyLZz Software Solutions. Todos los derechos reservados.`
Componente unico reutilizable; debe aparecer en TODAS las rutas y estados.
