# AGENTS.md — Instrucciones del proyecto XMR-Forecast

> **Léelo completo antes de cualquier tarea.** Este archivo es la fuente de verdad de las reglas del proyecto.
> **Se actualiza al terminar CADA tarea** (ver §7 y §10). Si algo aquí contradice a `MEMORY.md`, gana `AGENTS.md`.

Archivos de gobierno: `AGENTS.md` (reglas permanentes) · `SKILLS.md` (habilidades y recetas) · `MEMORY.md` (memoria corta, máx. 50 líneas).

---

## 1. Contexto del proyecto

- **Nombre de trabajo:** XMR-Forecast.
- **Qué es:** aplicación web comercial que permite a los usuarios predecir el **precio de cierre del día siguiente** o la **dirección** (sube/baja) de **Monero (XMR)**, comparando modelos **LSTM/GRU** contra modelos base: **media móvil, regresión lineal y ARIMA**.
- **Tipo de producto:** aplicación web SaaS para análisis predictivo de criptomonedas.
- **Documentos fuente (en `/docs/fuentes/`):** `Propuesta_Monero_IEEE.docx` (especificación original del cliente) y `Fase1_Proyecto7.docx` (requisitos generales de pronóstico de series de tiempo).
- **Documentación derivada:** `docs/01_documentacion.md`, `docs/02_stack_tecnologico.md`, `docs/03_machine_learning.md`, `docs/04_diagramas.md`, `docs/05_seguridad.md`.

---

## 2. Reglas inviolables

### 2.1 Rigor científico (ML)
| ID | Regla |
|----|-------|
| R-01 | Partición **estrictamente cronológica**. Prohibido `shuffle` y `train_test_split` aleatorio. Validación cruzada solo con `TimeSeriesSplit` / walk-forward. |
| R-02 | El `MinMaxScaler` se ajusta **solo con train**; validación y prueba solo usan `transform`. Las predicciones se **des-escalan** antes de calcular métricas (en USD). |
| R-03 | Indicadores técnicos (RSI, MACD, medias móviles) usan **solo información ≤ t**. Prohibido `shift(-n)` o ventanas centradas en features. |
| R-04 | El conjunto de **prueba se usa una sola vez** para la evaluación final. El ajuste de hiperparámetros usa únicamente validación. |
| R-05 | Todos los modelos se evalúan sobre **el mismo conjunto de prueba y las mismas fechas**. |
| R-06 | Baselines obligatorios: media móvil, regresión lineal, ARIMA. |
| R-07 | Métricas obligatorias: **MAE, RMSE, MAPE** y **proporción de aciertos** para dirección. |
| R-08 | Reproducibilidad: semillas fijas, configuración versionada (YAML), snapshot del dataset con checksum, tracking en MLflow. Modelos estocásticos: **≥ 5 semillas**, reportar media ± desviación. |
| R-09 | **Un resultado negativo es válido.** Si el LSTM no supera a los baselines, se reporta tal cual. Prohibido ajustar usando el test hasta "ganar". |
| R-10 | Análisis de fallos obligatorio: cambios bruscos del mercado y suavizado de picos. |
| R-23 | Una muestra pertenece al subconjunto de la **fecha de su objetivo**. Su ventana de entrada puede incluir días anteriores del subconjunto previo, nunca su objetivo ni datos posteriores. |
| R-24 | El **modelo campeón** se elige por métricas de **validación**, no de test. El test solo se reporta. ARIMA se evalúa con pronóstico rodante de **un paso** para ser comparable con el LSTM. |

### 2.2 Ética y comunicación
| ID | Regla |
|----|-------|
| R-11 | El sistema **no es asesoría financiera** ni promete rentabilidad. **Prohibido** simular operaciones/backtesting de trading. El aviso legal debe estar visible en la UI, la documentación de la API, el README y los reportes exportados. |
| R-12 | No escribir que el sistema "predice el mercado". Usar lenguaje de *capacidad predictiva evaluada*. |

### 2.3 Ingeniería
| ID | Regla |
|----|-------|
| R-13 | `backend/app/ml/` **no importa** FastAPI ni SQLAlchemy (queda aislado, testeable y usable desde notebooks/CLI). |
| R-14 | Secretos solo por variables de entorno. Nada de credenciales en git. Se versiona `.env.example`. |
| R-15 | Cambios de esquema **solo con migraciones Flyway** en `backend/src/main/resources/db/migration/`. Corrige una contradiccion previa: decia "Alembic", que pertenece al python de `ml-service` y jamas se aplico. R-34 es la regla vigente; el servicio ML no administra el esquema de PostgreSQL. |
| R-16 | Toda función pública de `ml/` lleva type hints y tests. Los **tests de no-fuga de datos** (R-01 a R-03) son obligatorios. |
| R-17 | Dependencias con versión fijada (lockfile). **Verificar la versión vigente** al crear el entorno; no asumir versiones. |
| R-18 | Los tests no hacen llamadas de red (se mockean las fuentes). Datos de prueba sintéticos. |

### 2.4 Proceso
| ID | Regla |
|----|-------|
| R-19 | Tras **cada tarea**: (a) actualizar `MEMORY.md`, (b) promover a `AGENTS.md` lo crítico, (c) añadir fila al registro (§10). |
| R-20 | `MEMORY.md` tiene **máximo 50 líneas** (verificar con `wc -l`). Resumir o eliminar lo que no aporte. |
| R-21 | No inventar datos, citas ni resultados. Toda cifra de rendimiento proviene de una corrida registrada. |
| R-22 | Los supuestos y decisiones abiertas se registran en §9. No se resuelven en silencio. |
| R-25 | Todo diagrama Mermaid se **valida (parse + render)** antes de entregar: `python tools/validate_mermaid.py <archivo.md> <mermaid.min.js>`. Si cambia el diseño, se actualizan los diagramas en la misma tarea. |
| R-26 | Los controles de seguridad se trazan a `05_seguridad.md` y a ASVS/API Top 10; toda ruta nueva requiere autenticación/autorización explícita o justificación pública, validación de entrada, límites y pruebas negativas. |
| R-27 | Producción usa secretos fuera de git/artifacts/logs, mínimo privilegio, MFA para `admin`, red privada para DB/Redis/MLflow y rotación/revocación ante compromiso. |
| R-28 | Datos, modelos, scalers y configuraciones ML se cargan solo desde artefactos versionados y verificables por digest/procedencia; la promoción de campeón requiere gates de integridad, no fuga y validación. |
| R-29 | CI bloquea secretos, vulnerabilidades críticas y artefactos no verificables; genera SBOM y escanea dependencias/imágenes antes de publicar. Las excepciones requieren vencimiento y aprobación registrada. |
| R-30 | Todo incidente se gestiona con el runbook de `05_seguridad.md`, preservando evidencia, rotando credenciales comprometidas y documentando causa raíz, impacto y acciones correctivas. |
| R-31 | **Monolito modular Spring Boot** como backend principal. **FastAPI-ML** como servicio especializado de ML. No adoptar microservicios completos sin justificación (dominio acotado, complejidad operativa). |
| R-32 | FastAPI-ML **no es accesible públicamente**. Solo Spring Boot consume el servicio ML mediante cliente tipado con timeout, reintentos limitados y correlación de solicitudes. |
| R-33 | **HTTPS forzado** en todos los entornos. Certificados autofirmados para desarrollo, certificados válidos para producción. |
| R-34 | Migraciones de base de datos **solo con Flyway** (Spring Boot). El servicio ML no administra el esquema de PostgreSQL. |
| R-35 | Toda ruta nueva requiere documentación de seguridad: autenticación, autorización, validación, límites, datos tratados, amenazas, controles y pruebas. |
| R-36 | El backend Spring Boot usa Java 21 de forma consistente en desarrollo, CI y Docker; las imágenes de compilación y ejecución deben coincidir con esa versión. |
| R-37 | El `pom.xml` raíz es el agregador Maven del backend y debe bloquear la compilación si el JDK activo no pertenece al rango Java 21. |
| R-38 | **Contratos entre servicios verificados, no supuestos.** Los esquemas del servicio ML usan `extra='forbid'`: enviar un campo de más devuelve 422 y rompe la funcionalidad entera sin fallo de compilación. Todo contrato HTTP compartido (backend ↔ ML, backend ↔ frontend) debe tener una prueba que compare los campos realmente enviados con los realmente aceptados. |
| R-39 | **Un registro de tarea afirma solo lo verificado en esa sesión.** Si una tarea se registra como "Hecho" sin que su artefacto exista en git o en disco, es un registro falso y debe retractarse en la tarea siguiente (aplicado en T-023). |
| R-40 | **Los diagramas se validan con parse + render.** Un diagrama que parsea pero no renderiza está mal; `tools/validate_mermaid.py` ejecuta ambas fases y devuelve código de salida 1 ante cualquier fallo. El runner no puede escanearse a sí mismo en los validadores de CI, porque contiene sus propios patrones de búsqueda. |
| R-41 | **Un contrato verificado es un contrato que responde en runtime, no que compila.** `HttpContractTest` extrae las rutas que el cliente emite y las que el backend declara y exige que coincidan. Sin el, 21 endpoints ausentes y 7 desalineaciones convivieron con la suite en verde. |
| R-42 | **`mvn test` no demuestra que la aplicacion arranque.** El empaquetado (`spring-boot:repackage`), el esquema real contra Hibernate (`ddl-auto: validate`), el JPQL que solo falla en runtime y la configuracion que impide arrancar se verifican levantando el servicio. Ver T-027: cuatro defectos de arranque que ninguna prueba de unidades detectaba. |
| R-43 | **El estado persistido y el estado publicado pueden usar vocabularios distintos, y la traduccion va en el servidor.** `Experiment` guarda `DRAFT`/`COMPLETED`; la API publica `PENDING`/`SUCCEEDED`. El cliente no conoce los dos vocabularios ni decide cual usar. Solo una copia de cada estado puede ser la fuente de verdad. |
| R-44 | **Un `@Modifying` sin `@Transactional` no falla al compilar: falla en el primer barrido.** La documentacion oficial de Spring Data JPA dice que los metodos de consulta declarados no reciben configuracion transaccional por defecto. Con `flushAutomatically = true` el fallo es `InvalidDataAccessApiUsageException: No EntityManager with actual transaction available`, y el efecto es que la cola entera deja de trabajar con el health check en verde. Todo `@Modifying` lleva `@Transactional` explicito. Verificado en T-028. |
| R-45 | **La auto-invocacion no atraviesa el proxy, y por tanto no aplica `@Transactional`.** Spring AOP es basado en proxy: `this.metodo()` es una llamada directa. Si un metodo necesita su propia transaccion y lo invoca otro metodo de la misma clase, la operacion se delega a **otro bean**. Aplicado en `JobWorker` → `JobQueue` y en `AuditService`, que se inyecta a si mismo con `@Lazy`. Documentacion oficial de Spring Framework, "Understanding AOP proxies". |
| R-46 | **Una anotacion de validacion puede ser imposible de ejecutar, y no fallar nunca.** `@Size` sobre un `Integer` no tiene validador: Hibernate Validator lanza `HV000030` en la primera peticion que lo alcanza. Si ademas falta `@Valid`, el validador no se ejecuta nunca y el defecto queda oculto indefinidamente. Las restricciones de bean se fijan **validando instancias reales** de cada cuerpo de peticion, que es lo que hace Spring. Ver `BeanValidationConstraintTest`. |
| R-47 | **Un `catch` dentro de una transaccion la deja inservible.** Si la operacion falla, la transaccion queda marcada rollback-only y el `catch` no la rescata: al confirmarla, Spring lanza `UnexpectedRollbackException` y el fallo sale como un 500 ajeno a la causa. El `catch` va **fuera** del limite transaccional. Verificado en T-028. |
| R-48 | **Un health check en verde no demuestra que el sistema haga su trabajo.** El backend arrancaba, migraba y respondia, mientras la cola de trabajos estaba muerta. Toda verificacion de extremo a extremo debe comprobar el **efecto** (el trabajo alcanza su estado terminal), no solo la **existencia** del recurso (el trabajo aparece en la lista). Verificado en T-028. |

---

## 3. Stack decidido v2 (detalle y alternativas en `docs/02_stack_tecnologico.md`)

| Capa | Tecnología |
|------|-----------|
| **Backend principal** | **Java 21, Spring Boot 3.2, Spring Security, Spring Data JPA** |
| **Servicio ML** | **Python 3.11, FastAPI, Pydantic v2, Uvicorn** |
| ORM / migraciones | Spring Data JPA + Flyway |
| Base de datos | PostgreSQL 15 (datos, experimentos, predicciones) · Redis 7 (caché y colas) |
| ML / datos | pandas, NumPy, scikit-learn, statsmodels, TensorFlow/Keras (LSTM/GRU), Optuna |
| Tracking | MLflow 2.8 |
| Frontend | React 18 + TypeScript + Vite, Tailwind CSS, Recharts, TanStack Query |
| Infra | Docker + Docker Compose, GitHub Actions |
| Calidad | JUnit 5, pytest, ruff, mypy, Vitest, ESLint, pre-commit |

---

## 4. Convenciones

- **Idioma:** documentación en español; identificadores de código en inglés; commits en inglés con *Conventional Commits* (`feat:`, `fix:`, `docs:`, `test:`, `refactor:`).
- **Estilo:** Python con `ruff` (lint + format) y `mypy`; TypeScript en modo `strict`.
- **Ramas:** `main` protegida; trabajo en `feat/<tema>`; PR con CI verde.
- **Configuración de experimentos:** un YAML por experimento en `configs/`.
- **Nombres de artefactos:** `artifacts/models/{run_id}/model.keras`, `scaler.joblib`, `config.yaml`.

## 5. Estructura del repositorio (objetivo)

```
xmr-forecast/
├── AGENTS.md · SKILLS.md · MEMORY.md · README.md · Makefile
├── .env.example · docker-compose.yml · docker-compose.dev.yml (solo desarrollo)
├── docs/                    # documentación (01-08) + diagramas
│   ├── fuentes/             # documentos originales del cliente
│   └── diagrams/            # diagramas drawio
├── backend/                 # Spring Boot 3.2.5 / Java 21 (monolito modular)
│   ├── pom.xml
│   ├── Dockerfile
│   └── src/
│       ├── main/java/com/aylzz/xmrforecast/
│       │   ├── auth/        # registro, login, refresh, OAuth Google, notificaciones
│       │   ├── user/        # users, roles, oauth_accounts, tokens, login_attempts, admin
│       │   ├── security/    # JWT, filtros, roles, hash de tokens, rate limiting
│       │   ├── market/ dataset/ experiment/ mlmodel/ metrics/ prediction/ job/
│       │   ├── ml/          # cliente tipado del servicio ML (R-32)
│       │   ├── audit/ common/ config/
│       │   └── XmrForecastApplication.java
│       ├── main/resources/
│       │   ├── application.yml
│       │   └── db/migration/  # Flyway V1..V5: ÚNICA fuente del esquema (R-34)
│       └── test/java/...      # JUnit 5
├── ml-service/              # FastAPI (Python)
│   ├── Dockerfile · requirements.txt (versiones fijadas, R-17)
│   └── app/
│       ├── main.py · config.py · services.py
│       ├── api/            # routes_health|models|predict|metrics, schemas.py
│       ├── clients/        # cliente de MLflow
│       └── ml/             # PAQUETE PURO, sin frameworks (R-13)
│           └── data/ models/ evaluation/ pipelines/
├── frontend/                # React 18 + TS + Vite + Tailwind (tema oscuro)
│   ├── Dockerfile · nginx.conf · vite.config.ts
│   └── src/{api,auth,components,config,pages,store,styles,test,types,utils}/
├── docker/                  # generate-dev-certs.sh · backup.sh · certs/ (ignorada)
│   └── postgres/init/
├── loadtests/api.js         # k6 (cifras solo tras ejecutar)
├── tools/                   # validate_mermaid.py · verify-stack.ps1
└── .github/workflows/ci.yml
```

## 6. Comandos estándar (planificados; crear con el andamiaje)

`make up` (levanta todo) · `make test` · `make lint` · `make migrate` · `make ingest` · `make experiment CONFIG=configs/lstm_base.yaml` · `make diagrams` (valida Mermaid)

## 7. Protocolo del agente en cada tarea

1. Leer `AGENTS.md` → `MEMORY.md` → `SKILLS.md` (solo la habilidad relevante).
2. Ejecutar la tarea respetando §2.
3. Verificar: tests/linters si hay código; coherencia con los diagramas si cambió el diseño.
4. **Actualizar `MEMORY.md`** (≤ 50 líneas).
5. Si aprendiste una regla crítica o tomaste una decisión permanente → **promoverla a este archivo**.
6. Añadir una fila al registro (§10).
7. Si surgió una habilidad nueva → añadirla a `SKILLS.md`.

## 8. Definición de Hecho (DoD)

- Cumple todas las reglas de §2.
- Con código: tests pasan, `ruff` y `mypy` limpios, tests de no-fuga incluidos si toca datos/ML.
- Con diseño: `docs/` y diagramas actualizados y consistentes entre sí.
- Los artefactos no incluyen secretos ni datos crudos pesados.
- `AGENTS.md`, `MEMORY.md` y el registro (§10) actualizados.

## 9. Decisiones abiertas y supuestos

| ID | Tema | Estado | Supuesto vigente |
|----|------|--------|------------------|
| D-00 | Dominio: los dos documentos difieren (retail Store Sales vs. Monero) | **Supuesto** | Proyecto activo = **Monero**. Retail queda como dataset alternativo vía la interfaz `DataSource` (no implementar salvo decisión del cliente). |
| D-01 | Fuente de datos XMR | Abierta | Candidata: Yahoo Finance (`yfinance`, XMR-USD); alternativas: CryptoDataDownload, CoinGecko. Guardar siempre snapshot CSV con checksum. |
| D-02 | Período exacto de datos | Abierta | Se fija al iniciar la recolección. |
| D-03 | Modelo recurrente final: LSTM vs. GRU | Abierta | LSTM principal, GRU alternativa. |
| D-04 | Salida principal | Abierta | Implementar **ambas** tareas: regresión (cierre t+1) y dirección; comparar. |
| D-05 | Proporciones de partición | Propuesta | 70 / 15 / 15 cronológico + `TimeSeriesSplit` (5) para ajuste. |
| D-06 | Cola de tareas | **Resuelto** | Tabla `jobs` en PostgreSQL con `idempotency_key` única, estados PENDING/RUNNING/COMPLETED/FAILED/CANCELLED y `heartbeat_at` para detectar trabajos colgados. El backend Java no usa Celery. |
| D-07 | Objetivos numéricos de calidad (cobertura ≥ 80 % en `ml/`, lecturas API p95 < 500 ms) | Propuesta | Ajustables por el cliente. |
| D-08 | Extensiones fuera de alcance inicial | Diferidas | Multi-horizonte, indicadores técnicos vs. solo precio, BTC/ETH, exógenas, alertas. |
| D-09 | Estado y `nonce` de OAuth: en memoria o en Redis | **Supuesto activo** | `GoogleOAuthService` los guarda en un `ConcurrentHashMap`. Valido con una sola instancia; con varias replicas o tras un reinicio la sesion se pierde. El propio codigo lo reconoce. Migrar a Redis (R-27) antes de escalar en horizontal. |
| D-10 | Entrenamiento de modelos: HTTP o CLI | **Resuelto (CLI)** | El entrenamiento NO se expone por HTTP. Un trabajo `TRAIN` termina siempre en `FAILED` con `TRAINING_NOT_EXPOSED`, a proposito: es una operacion larga y reproducible que se ejecuta por CLI sobre configuracion versionada. Decirlo es preferible a fingir una ejecucion que no ocurre (R-21). |
| D-11 | Identificadores en la API publica | **Resuelto (cadenas opacas)** | Todo `id` se publica como cadena. Evita depender del rango y de la precision de los enteros de JavaScript y hace que el cliente lo trate como opaco. El `@PathVariable` acepta ambas formas. Ver R-43. |
| D-12 | Puertos de PostgreSQL y Redis en desarrollo | **Resuelto (loopback alto)** | `docker-compose.yml` no publica nada (red `data` con `internal: true`). `docker-compose.dev.yml` publica 55432/56379 en loopback y conecta ambos a la red `backend` no interna. Motivo: una red interna no tiene ruta de vuelta al host, asi que el binding se acepta y no publica nada; y 5432 suele estar ocupado por una instalacion local, con el sintoma desconcertante de un "password authentication failed" que procede de OTRO servidor. |

## 10. Registro de tareas

| ID | Fecha | Tarea | Archivos | Resultado |
|----|-------|-------|----------|-----------|
| T-001 | 2026-09-29 | Lectura y análisis de los documentos fuente | — | Contexto extraído; se detecta discrepancia retail vs. Monero → D-00 |
| T-002 | 2026-09-29 | Creación de archivos de gobierno | AGENTS.md, SKILLS.md, MEMORY.md | Creados |
| T-003 | 2026-09-29 | Documentación general (visión, requisitos, casos de uso, arquitectura, BD, API, UI, pruebas, plan, riesgos) | docs/01_documentacion.md, docs/fuentes/ | Creada |
| T-004 | 2026-09-29 | Documento de stack tecnológico (backend, BD, ML, frontend, DevOps, seguridad, alternativas, Compose) | docs/02_stack_tecnologico.md | Creado |
| T-005 | 2026-09-29 | Diseño de Machine Learning (formulación, features, split, baselines, LSTM/GRU, métricas, fallos, experimentos E-01…E-09) | docs/03_machine_learning.md | Creado; se promueven R-23 y R-24 |
| T-006 | 2026-09-29 | Diagramas: casos de uso, flujo de usuario (+ 2 journeys), arquitectura, 3 de clases, 4 de flujo, 3 de secuencia, ER, 2 de estados | docs/04_diagramas.md | Creado; 19 diagramas validados (parse + render) |
| T-007 | 2026-09-29 | Herramienta de validación de Mermaid; alineación ER ↔ doc 01 (`data_split.dataset_version_id`); corrección de layout en casos de uso | tools/validate_mermaid.py, docs/01, docs/04 | Hecho; se promueve R-25 |
| T-008 | 2026-09-29 | Protocolo integral de seguridad: marcos, threat model, controles API/identidad/ML/supply chain, incidentes, matriz y checklist | docs/05_seguridad.md, docs/01, docs/02, AGENTS.md, SKILLS.md, MEMORY.md | Hecho; se promueven R-26…R-30 |
| T-009 | 2026-09-29 | Adaptación de documentación para cliente real (eliminar enfoque académico) | AGENTS.md, SKILLS.md, MEMORY.md, docs/01-05 | Hecho |
| T-013 | 2026-09-30 | **RETRACTADO (T-023)** — `backend/`, `ml-service/`, `docker-compose.yml` nunca existieron en git ni en disco | — | Registro falso corregido |
| T-014 | 2026-09-30 | **RETRACTADO (T-023)** — ver T-013 | — | Registro falso corregido |
| T-015 | 2026-09-30 | **RETRACTADO (T-023)** — ver T-013. `docs/06_auditoria.md` se conserva como documento, pero sus hallazgos no aplican al código real | docs/06_auditoria.md | Registro falso corregido |
| T-016 | 2026-09-30 | **RETRACTADO (T-023)** — ver T-013 | — | Registro falso corregido |
| T-017 | 2026-09-30 | **RETRACTADO (T-023)** — ver T-013 | — | Registro falso corregido |
| T-018 | 2026-09-30 | **RETRACTADO (T-023)** — ver T-013 | — | Registro falso corregido |
| T-019 | 2026-09-30 | **RETRACTADO (T-023)** — ver T-013 | — | Registro falso corregido |
| T-020 | 2026-09-30 | **RETRACTADO (T-023)** — ver T-013 | — | Registro falso corregido |
| T-021 | 2026-09-30 | **RETRACTADO (T-023)** — ver T-013 | — | Registro falso corregido |
| T-022 | 2026-09-30 | **RETRACTADO (T-023)** — ver T-013 | — | Registro falso corregido |
| T-023 | 2026-09-30 | Auditoría de continuidad: detectado registro falso, normalizada ruta `docs/`, recuperado el `frontend/` borrado del working tree | AGENTS.md, MEMORY.md, docs/, frontend/ | Hecho. Hallazgo: solo 3 commits en toda la historia, sin backend; `java` en PATH es JDK 8 (JDK 21 disponible en Adoptium); Python 3.12 (no 3.11); falta `tools/validate_mermaid.py`. La nota del JDK 8 quedo **desfasada en T-028**: el entorno ahora tiene Corretto 21.0.12 en `JAVA_HOME` y en el PATH |
| T-024 | 2026-09-30 | Construccion real del sistema: backend Spring Boot 3.2/Java 21, ml-service FastAPI, frontend oscuro, Docker Compose con HTTPS, CI/CD, k6, docs | backend/, ml-service/, frontend/, docker/, tools/, loadtests/, .github/workflows/ci.yml, README.md, docs/07, docs/08 | Hecho, pero **corregido en T-027**: las cifras de esta fila quedaron desfasadas. Se afirmaron entonces "40 tests" y "9 diagramas validados"; las cifras reales son 94 tests de backend y 7 diagramas. La construccion inicial tampoco arranco nunca contra una base de datos real: los siete defectos de arranque de T-027 estaban presentes desde aqui |
| T-025 | 2026-09-30 | Sincronizacion del contrato OAuth entre backend y frontend | GoogleOAuthController.java, GoogleCallbackPage.tsx, api/auth.ts, store/authStore.ts, test/google-oauth.test.tsx | Hecho. Google redirige con GET; el backend responde 302 con la sesion en el fragmento. Corregido: el frontend canjeaba el codigo por POST y el backend exigia GET |
| T-026 | 2026-09-30 | Correccion de un fallo de integracion detectado al cruzar los contratos | PredictionService.java, MlServiceClientContractTest.java | Hecho. El servicio ML usa `extra=forbid`: el backend enviaba `seed` y TODAS las predicciones fallarian con 422. Se elimino el campo y se fijo el contrato con un test |
| T-027 | 2026-09-30 | **Alineacion del contrato HTTP (33 -> 46 rutas) y verificacion de arranque de extremo a extremo** | backend: 9 controladores nuevos, `HttpContractTest`, `ControllerParameterTest`, `QueryParams`, `Ids`, `RefreshCookie`, `JobWorker`, `ExperimentRunCompletionLink`, V3/V4/V5, `pom.xml` (repackage), `application.yml`; frontend: `GoogleCallbackPage`, `types/domain.ts`; infra: `generate-dev-certs.sh`, `docker-compose.dev.yml`, `tools/verify-stack.ps1`, `backup.sh`; docs/01-05, 08 | Hecho. **Verificado**: backend 94 tests, frontend 147, ml-service 199 (+15 omitidos); el jar empaquetado **arranca de verdad** contra PostgreSQL 15.19, migra V1..V5 solo y responde por HTTPS con **43/43** comprobaciones de `tools/verify-stack.ps1`; backup y restauracion **ejecutados** (30 + 5 filas destruidas y recuperadas, 0 huerfanos); 7 diagramas Mermaid validan (parse + render). **Fuga IDOR corregida** en el listado de predicciones con filtro de simbolo. Siete defectos de arranque corregidos que ninguna prueba de unidades detectaba. Se promueven R-41, R-42 y R-43; R-15 deja de contradecir a R-34. **Corregido en T-028**: las 43 comprobaciones dejaban pasar un sistema con la cola de trabajos muerta |
| T-028 | 2026-09-30 | **Revision de documentacion oficial y correccion completa del backend** (Spring Data JPA, Spring Framework AOP, Spring Boot, Spring Security, JJWT, OpenID Connect y Google Identity, Jakarta Bean Validation, PostgreSQL, Docker) | backend: `JobQueue.java` (nuevo), `JobRepository`, `RevokedTokenRepository`, `ModelVersionRepository`, `JobWorker`, `ExperimentRunCompletionLink`, `AuditService`, `GoogleOAuthService`, `MlServiceClient`, `RestClientConfig`, `AppProperties`, `SecurityConfig` (sin cambios), `PredictionService`, `AuthService`, `UserAdminService`, `AdminUserController`, `ExperimentService`, `ExperimentController`, `DatasetService`, `MetricsService`, `MetricRepository`, `ModelService`, `ModelVersionRepository`, `MarketService`, `TokenHasher`, `RequestContext`, `CorsConfig`, `LoginAttempt`, `AppProperties`, `application.yml`, `Dockerfile`; pruebas: `JobQueueTest`, `GoogleOAuthServiceTest`, `BeanValidationConstraintTest` (nuevas), `AuditServiceSanitizationTest`, `ModelPromotionGateTest`; infra: `.dockerignore` (nuevo), `docker-compose.yml`, `.env.example`, `tools/verify-stack.ps1`; docs/01, 02, 04, 05, 08; `MEMORY.md` | Hecho. **Verificado**: `mvn clean verify` = **129 tests, BUILD SUCCESS**; el jar empaquetado (70 MB, `Main-Class` presente) **arranca contra PostgreSQL 15.19 y Redis 7 reales**, migra V1..V5 y responde por HTTPS; **`tools/verify-stack.ps1` = 48/48**, incluidas cuatro comprobaciones nuevas que obligan a que la cola AVANCE. Cuatro **BLOQUEANTES** corregidos: (1) **la cola estaba muerta** —`claimIfPending` era `@Modifying` sin `@Transactional` y el worker se auto-invocaba saltandose el proxy, con lo que *ningun* trabajo se procesaba desde T-027; (2) **OAuth Google nunca podia completarse** — la firma se verificaba con `parts[1]` (payload) en vez de `parts[2]`; (3) `@Valid` ausente en `POST /experiments/{id}/runs`, y al anadirlo `List<@Size Integer>` hacia que el endpoint devolviera 500 (`HV000030`); (4) `AuditService` capturaba el fallo dentro de su transaccion y el alta de usuario respondia 500 con `UnexpectedRollbackException`. Ademas: `model_key` numerico en error, `idempotency_key`/`job_key` mas largos que sus columnas, YAML de experimento invalido, `X-Request-Id` de >64 chars que destruia la auditoria, redaccion de auditoria por igualdad exacta, proteccion del ultimo ADMIN con carrera TOCTOU, `login_attempts.user_id` nunca persistido, 3 N+1, `RestClient.Builder` singleton, `ML_VERIFY_TLS` inerte, capa de cache del Dockerfile que no cacheaba nada y `.dockerignore` ausente. Se promueven R-44 a R-48 |
| T-029 | 2026-09-30 | Primer despliegue local completo con Docker | docker-compose.yml (mlflow v2.8.1, SQLite local, tracking http, healthchecks), ml-service/Dockerfile (base python 3.12, mkdir antes de USER), backend/Dockerfile (wget -O /dev/null), docker/generate-dev-certs.sh (truststore con keytool), truststore.p12 regenerado (ignorado por git) | Hecho. **Verificado**: 6/6 servicios `healthy` + `tools/verify-stack.ps1` 48/48. Siete fallos que ningun test detectaba: tag MLflow inexistente, pins 3.12 vs base 3.11, mkdir sin permiso, truststore vacio para Java, mlflow sin psycopg2, healthchecks wget, frontend https vs http. `assume-unchanged` desactivado en 4 ficheros que ocultaba cambios |
| T-030 | 2026-09-30 | Frontend apuntaba a `api.xmr-forecast.example` y CORS bloqueaba el registro local | docker-compose.yml (build-arg `FRONTEND_API_BASE_URL`, solo origen), .env (origen `http://localhost:3000` en CORS, no versionado) | Hecho. **Verificado**: el bundle sirve `VITE_API_BASE_URL:"https://localhost:8443"` sin restos del dominio de ejemplo; 6/6 healthy. Google sigue sin credenciales reales: limitacion documentada, no un bug |
| T-031 | 2026-09-30 | HTTPS en el frontend local (`https://localhost:3000`) | docker/generate-dev-certs.sh + cert `frontend` (ignorado por git), frontend/nginx.conf (8443 ssl, upstream `backend` para SNI, CA propia en proxy), docker-compose.yml (3000→8443, certs ro) | Hecho. **Verificado**: 200 en `/` y en `/api/v1/meta/disclaimer` via proxy con cadena valida; 6/6 healthy. El upstream `xmr_backend` abortaba el handshake (Tomcat `illegal_parameter`) porque el SNI no coincidia con ningun SAN |
| T-032 | 2026-09-30 | Pantallas de error de infraestructura (HTTP en claro + caidas) | frontend/nginx.conf (`error_page 497` a https, `50x.html` para 500/502/503/504), frontend/nginx-error/50x.html (nueva), frontend/Dockerfile (COPY de la pagina) | Hecho. **Verificado**: `http://...:3000/forgot-password` → 302 a https; con el backend detenido, `/api/...` devuelve 502 con la pagina propia; backend rearrancado, 6/6 healthy |
| T-033 | 2026-09-30 | Guia Vercel para el frontend + `vercel.json` (rewrites SPA, cabeceras como nginx) | frontend/vercel.json (nuevo) | Guia entregada; **no desplegado**: Vercel exige backend publico con TLS valido (localhost:8443 y la CA de desarrollo no sirven en navegadores ajenos), `VITE_REFRESH_TOKEN_MODE=body` (la cookie Strict no cruza dominios) y CORS/OAuth apuntando al dominio Vercel |
| T-034 | 2026-09-30 | Fusionar el trabajo en `main` y subirlo a origin para Vercel | (merge, sin archivos nuevos) | Hecho. **Verificado**: `main` ya contenia todo (`BackEnd/First` incluido, `FrontEnd/First` contenida sin nada nuevo); solo faltaba el `main` remoto (un `Initial commit` de 1 linea) → merge con `--allow-unrelated-histories`, conflicto `README.md` resuelto conservando el nuestro, `diff` vacio contra el arbol verificado, `push origin main` OK (`6a2e367..5ee2b62`) |
| T-035 | 2026-09-30 | Preparar el repo para Render (backend + ml-service) | application.yml (`SERVER_SSL_ENABLED`, def. true), ml-service/Dockerfile + compose (contexto raiz), render.yaml (Blueprint: backend web + ml-service privado, secretos como `sync:false`/Secret Files) | Hecho. **Verificado**: YAML OK, `compose config` OK, imagenes backend y ml-service **compiladas en local**. Decisiones: sin 5º servicio (MLflow tolera caidas en modo degradado); ml-service conserva HTTPS con los certs de desarrollo via Secret Files y nombre exacto `ml-service` (SNI); borde Render termina TLS, tramo interno HTTP. Commits en main y BackEnd/First, pusheados |
| T-036 | 2026-09-30 | Truststore binario a Render via `TRUSTSTORE_B64` (Secret Files solo texto) | backend/Dockerfile (ENTRYPOINT decodifica B64 a /tmp), render.yaml (var + JAVA_OPTS sin truststore) | Hecho. **Verificado**: build local OK + smoke test de decodificacion como uid 1001; push a rama y main. ml-service no necesito cambio (sus 3 PEM son texto) |
<!-- LOG:END -->
