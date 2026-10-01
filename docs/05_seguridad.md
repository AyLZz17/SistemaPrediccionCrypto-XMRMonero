# XMR-Forecast -- Protocolo integral de seguridad

> **Estado:** baseline de diseno para desarrollo y preproduccion
> **Version:** 4.0 - **Fecha:** 2026-09-30
> **Propietario:** responsable tecnico (`ADMIN`) - **Revision minima:** trimestral y ante cada cambio de arquitectura, dependencia critica o incidente.

---

## 1. Marcos y protocolos de referencia

| Area | Marco/protocolo | Uso en este proyecto | Nivel |
|---|---|---|---|
| Gobierno | NIST CSF 2.0 | Riesgos, responsables, metricas, revision y mejora | Obligatorio |
| Aplicacion web | OWASP ASVS 5.0.0 + OWASP Top 10 2025 | Requisitos, revision de codigo y pruebas | Obligatorio |
| API REST | OWASP API Security Top 10 2023 | Autorizacion por objeto/funcion, limites, SSRF e inventario | Obligatorio |
| Identidad | NIST SP 800-63B-4 | Autenticadores, sesiones y recuperacion | Obligatorio |
| Desarrollo | NIST SP 800-218 SSDF | Seguridad desde requisitos hasta publicacion | Obligatorio |
| Incidentes | NIST SP 800-61 Rev. 3 | Preparacion, triage, contencion, recuperacion y lecciones aprendidas | Obligatorio |
| Cadena de suministro | NIST SP 800-161 Rev. 1, SBOM y SLSA | Dependencias, imagenes, acciones CI y artefactos | Obligatorio |
| ML/IA | NIST AI RMF 1.0, MITRE ATLAS y OWASP ML Security Top 10 | Integridad de datos/modelos, poisoning, extraccion y abuso de inferencia | Obligatorio para `ml/` |
| Contenedores | Docker Engine Security, modo rootless cuando sea posible | Aislamiento, privilegios, red y filesystem | Obligatorio en despliegue |

---

## 2. Objetivos, limites y clasificacion

### 2.1 Objetivos de seguridad

1. **Confidencialidad:** proteger credenciales, tokens, configuraciones, logs
   sensibles y artefactos no publicados.
2. **Integridad:** impedir que se alteren datos, snapshots, configuraciones,
   modelos, metricas, predicciones o registros de auditoria sin autorizacion.
3. **Disponibilidad:** evitar que login, API, workers, Redis o inferencia sean
   agotados.
4. **Trazabilidad:** poder asociar cada accion sensible con identidad, momento,
   objeto, resultado y solicitud.
5. **Reproducibilidad confiable:** cada resultado ML debe poder vincularse a un
   snapshot, checksum, configuracion, codigo y modelo verificables.
6. **Uso responsable:** las salidas **no** se presentan como asesoria financiera
   ni desencadenan operaciones. El aviso legal es visible en la UI, en la
   documentacion de la API, en el README y en los reportes exportados, y el
   lenguaje es de *capacidad predictiva evaluada*, nunca de "prediccion del
   mercado" (R-11, R-12).

### 2.2 Activos y clasificacion

| ID | Activo | Clasificacion | Riesgo principal |
|---|---|---|---|
| A-01 | Contrasenas, JWT y secretos | Restringido | Toma de cuenta |
| A-02 | Roles, permisos y registro de auditoria | Confidencial | Escalada o perdida de evidencia |
| A-03 | PostgreSQL y backups | Confidencial | Exfiltracion o manipulacion |
| A-04 | Redis y cache | Interno/confidencial | Ejecucion, replay o indisponibilidad |
| A-05 | Snapshots OHLCV, manifests y checksums | Integridad critica | Data poisoning |
| A-06 | Modelos, scalers, configuraciones y MLflow | Confidencial | Sustitucion, extraccion o carga de codigo no confiable |
| A-07 | Codigo, dependencias, imagenes y workflows CI | Integridad critica | Compromiso de la cadena de suministro |
| A-08 | Exportaciones CSV/PDF y logs | Interno; confidencial si incluyen identificadores | Inyeccion, fuga o retencion excesiva |
| A-09 | Documentacion, metricas agregadas y OHLCV publicado | Publico | Manipulacion de confianza o reputacion |

---

## 3. Modelo de amenazas y abuso previsto

| Amenaza | Superficie | Control principal |
|---|---|---|
| BOLA/IDOR: consultar o modificar otro objeto | `/api/v1/experiments/{id}`, `/api/v1/runs/{id}`, `/api/v1/datasets` | Autorizacion server-side por objeto y rol; pruebas negativas |
| Escalada de funcion | Ingesta, ejecucion, campeon, usuarios | RBAC explicito; `ADMIN` solo para administracion; deny-by-default |
| Escalada de rol por cambio en la sesion | `/api/v1/auth/me`, emision de JWT | El rol efectivo se resuelve en el servidor en cada peticion; el cliente no decide ni envia roles |
| Credential stuffing / brute force | `/api/v1/auth/login` | BCrypt coste 12, limitador por IP y por cuenta, bloqueo de cuenta, errores genericos y alertas |
| SSRF | `data_source.base_url` e ingesta | Allowlist de hosts y protocolos, validacion DNS/IP, egress limitado, timeout |
| Inyeccion | JSON, filtros, exportaciones | Validacion estricta, SQL parametrizado por JPA, nunca shell con entrada de usuario |
| Consumo ilimitado | Exportaciones, prediccion, trabajos de la cola | Cuotas, paginacion, limites de tamano, `idempotency_key` y lote del worker |
| XSS / CSRF / clickjacking | SPA, reportes y cookies | CSP, escape contextual, HSTS, `X-Content-Type-Options`, `X-Frame-Options: deny`, `SameSite` y justificacion explicita del CSRF (ver 4.4) |
| CSRF de OAuth | `/api/v1/auth/google/**` | `state` de un solo uso mas `nonce` validado contra el `id_token` |
| Dependencia o imagen maliciosa | Python, npm, Docker, GitHub Actions | Lockfile, SBOM, SCA, procedencia, scanners y revision |
| Alteracion o poisoning del dataset | Fuente externa, CSV, snapshot | Validacion de esquema, anomalias, snapshot inmutable, checksum, aprobacion |
| Sustitucion de modelo o scaler | MLflow, artefactos, worker | ACL, digest, solo artefactos aprobados, registro y rollback |
| Exposicion de Redis, MLflow o PostgreSQL | Red y puertos | Red interna, autenticacion, TLS cuando aplique, sin puertos publicados en el Compose base |
| Perdida de disponibilidad | API, DB, worker o proveedor | Health checks, limites, reintento con backoff, backup y recuperacion |

### 3.1 Topologia real de red y puertos

En el despliegue local reproducible:

| Servicio | Donde escucha | Puerto en el host |
|----------|----------------|-------------------|
| `backend` (Spring Boot) | **Solo HTTPS en 8443** | `127.0.0.1:8443` |
| `frontend` (React + nginx) | HTTPS en 8080 del contenedor | `127.0.0.1:3000` |
| `ml-service` (FastAPI) | **Solo HTTPS en 8443** del contenedor | `127.0.0.1:8000` |
| `mlflow` | HTTPS en 5000 | `127.0.0.1:5000` |
| `postgres` | 5432, red `data` **interna** | **sin puerto publicado** |
| `redis` | 6379, red `data` **interna** | **sin puerto publicado** |

Dos precisiones que evitan diagnosticos falsos:

- **El backend no redirige `8080`.** Con `server.ssl.enabled: true`, Tomcat abre
  **unicamente** el conector HTTPS: no existe un listener de HTTP plano. No hay
  redireccion porque no hay nada que redireccionar, y por eso `verify-stack.ps1`
  comprueba que el puerto 80 esta cerrado y que una peticion HTTP contra el 8443
  se rechaza.

  Nota: la propiedad `server.http.enabled` que aparecia en versiones anteriores de
  esta documentacion **no existe en Spring Boot** y no tenia efecto alguno. La
  garantia de "solo HTTPS" la da `server.ssl.enabled: true`, que es lo que esta
  verificado. Eliminar una propiedad que suena a garantia y no lo es es peor que
  documentar la que si la sostiene.
- **`8000` no es el puerto del servicio ML.** El proceso de FastAPI escucha en
  **8443 dentro del contenedor**; `8000` es unicamente el mapeo de loopback del
  host. El backend lo llama por `https://ml-service:8443`.

Todos los puertos publicados son de loopback. Los certificados autofirmados solo
estan permitidos para desarrollo local; **nunca** se debe desactivar la validacion
TLS en produccion (R-33).

---

## 4. Controles obligatorios por capa

### 4.1 Identidad, autenticacion y sesiones

- Todos los accesos se realizan por HTTPS; TLS 1.3 es la opcion preferida.
- **JWT de acceso de 15 minutos** (`JWT_ACCESS_TTL=900`, HS512). La sesion
  prolongada se sostiene con un **refresh token rotatorio de 30 dias**
  (`JWT_REFRESH_TTL=2592000`).
- El refresh token se almacena **solo como hash HMAC-SHA256**, nunca en claro, y
  la rotacion es **por familia**: reutilizar un token ya rotado revoca la familia
  completa, porque la reutilizacion es la senal de un token robado.
- Validar firma, algoritmo permitido, `iss`, `aud`, `sub`, `iat`, `exp` y `jti`,
  con una desviacion de reloj de 30 s.
- Contrasenas con **BCrypt** coste 12 y salt unico por contrasena.
- Login con respuesta generica: no revela si existe un correo. Limitador por IP y
  por cuenta, y bloqueo tras `MAX_FAILED_LOGINS` intentos durante
  `LOCK_DURATION_MINUTES`.
- Aplicar autorizacion en cada peticion; nunca confiar en el rol enviado por el
  cliente.
- Revocar sesiones al desactivar el usuario, cambiar la contrasena o detectar un
  compromiso. Al rotar `JWT_SECRET` **todos** los access tokens quedan invalidos.
- **Un unico administrador no puede perder su rol.** La comprobacion de "no es el
  ultimo ADMIN" se hace con aislamiento `SERIALIZABLE`: con el aislamiento por
  defecto (READ COMMITTED), dos degradaciones concurrentes observan ambas dos
  administradores y las dos pasan el control, dejando el sistema sin nadie capaz de
  restaurarlo.
- **Los intentos fallidos se asocian a la cuenta**, no solo al correo:
  `login_attempts.user_id` existe con su indice para correlacionar ataques contra
  una cuenta concreta. Guardar solo el correo dejaba ese indice sin usar.
- **Consentimiento previo y demostrable.** El registro exige los dos aceptes
  (terminos y politica de datos) y cada aceptacion se escribe en
  `consent_records` con documento, version, fecha, cuenta, canal e IP dentro de
  la misma transaccion que crea la cuenta: sin fila no hay cuenta. Si la
  escritura fallara, no queda un usuario sin prueba de su autorizacion.
  `acceptMarketing` es opcional y solo genera fila si se acepta.
- **El reenvio del correo de verificacion es publico y responde igual exista o
  no la cuenta** (`POST /api/v1/auth/verify-email/resend` -> `204`): cualquier
  otra respuesta permite enumerar correos registrados. Va en la lista de rutas
  sensibles del limitador, porque ademas genera un token nuevo cada vez, y
  caduca los anteriores.
- **Google OAuth: sin consentimiento registrado no se crea la cuenta.** Los
  aceptes se recogen en nuestra pagina y viajan dentro del `state` de un solo
  uso; el callback devuelve `error_code=CONSENT_REQUIRED` y el frontend presenta
  los documentos antes de reintentar. La identidad se resuelve por `sub`; el
  correo solo enlaza cuentas existentes y solo si Google afirma
  `email_verified`. La verificacion aportada por Google se acepta **antes** de
  comprobar el estado de la cuenta, de modo que una cuenta local sin confirmar
  no se bloquea a si misma.

### 4.2 API REST (Spring Boot)

- Mantener OpenAPI y rutas bajo `/api/v1`; retirar endpoints no usados. El
  inventario vivo son **14 controladores y 54 rutas**, y
  **`docs/01_documentacion.md` seccion 3.1 debe listar esas 54 rutas reales**
  (R-35). Un inventario desalineado es un inventario inservible: es lo que
  permite que rutas ausentes convivan con la suite en verde.
- **La superficie anonima es un conjunto cerrado y separado** (seccion 3.1.12
  de `docs/01`): las seis rutas de `/api/v1/public/**`, `meta/disclaimer`,
  `meta/legal` y los flujos de cuenta (`register`, `login`, `refresh`,
  `verify-email`, `password/forgot`, `password/reset`, `google/*`). `permitAll`
  esta declarado ANTES del matcher autenticado, no dentro de una excepcion:
  cualquiera que se añada a ese bloque es anonimo por decision, no por olvido.
  Comprobaciones negativas correspondientes: sin sesion, `/experiments`,
  `/jobs`, `/predictions`, `/notifications`, `/audit`, `/users` y
  `/metrics/compare` devuelven 401 o 403 (`tools/verify-stack.ps1`), y
  `PublicRoutesContractTest` revisa el fuente para que el panel no llegue a
  leer tablas de usuario. Las rutas publicas son **solo GET** (un POST devuelve
  405), validan sus parametros (`symbol` hasta 32, `limit` 1..365) y **no
  contienen** correos, contrasenas ni identificadores de usuario: esa
  comprobacion se hace sobre la respuesta real, no sobre la intencion.
- **El panel publico tiene cubo de limitacion propio** (`RATE_LIMIT_PUBLIC`,
  por defecto 120/min por IP), separado de `RATE_LIMIT_API` y de
  `RATE_LIMIT_LOGIN`, de modo que una consulta masiva al panel no puede
  agotar el limite del login ni al reves.
- Definir esquemas de entrada y salida con allowlist y tipos estrictos: el
  servicio ML usa `extra='forbid'`, de modo que un campo de mas devuelve `422`
  sin fallo de compilacion (R-38).
- **Toda restriccion de bean debe ser ejecutable, no solo plausible.** Una
  anotacion que no aplica a su tipo (`@Size` sobre un `Integer`) no falla al
  compilar: Hibernate Validator lanza `HV000030` en la primera peticion que la
  alcanza. Y si el controlador no lleva `@Valid`, el validador no se ejecuta
  nunca, con lo que la anotacion imposible permanece oculta. Ambas condiciones se
  vigilan en `BeanValidationConstraintTest`, que valida **instancias reales** de
  cada cuerpo de peticion: es lo que hace Spring, y es lo unico que detecta la
  clase de fallo que un analisis estatico pasa por alto.
- Todo `id` publicado es una **cadena opaca**, y la traduccion de estados entre
  lo persistido y lo publicado ocurre **en el servidor** (R-43).
- Implementar limitacion diferenciada: login, exportacion, ingesta, creacion de
  experimentos y prediccion (`RATE_LIMIT_LOGIN`, `RATE_LIMIT_API`).
- Aplicar CORS solo a origenes conocidos; nunca `*` con credenciales.
- Anadir `Content-Security-Policy`, `Strict-Transport-Security`,
  `X-Content-Type-Options: nosniff`, `Referrer-Policy` y `X-Frame-Options: deny`.
- Usar consultas parametrizadas mediante JPA; no concatenar SQL.
- Cada endpoint declara el rol requerido y comprueba el objeto solicitado.
- Configurar timeouts, limites de concurrencia y tamano maximo de body y de
  respuesta. Los errores no incluyen mensaje, traza ni errores de enlace
  (`include-message: never`), y la pantalla de error de Spring esta
  deshabilitada.

### 4.3 Servicio FastAPI-ML

- **No es accesible publicamente**: solo la red interna del backend.
- Validar todas las entradas con Pydantic v2.
- Verificar la integridad de los artefactos por digest antes de cargar modelos.
- No aceptar rutas arbitrarias ni URLs externas no autorizadas.
- Implementar health checks, `request_id` y limites de tamano; el middleware
  rechaza con `400 TLS_REQUIRED` cualquier peticion que no llegue por TLS, y la
  verificacion de certificados nunca se desactiva.
- Los errores tienen formato uniforme `{detail, code, request_id}` y no filtran
  detalles internos.
- No realizar llamadas de red reales en tests (R-18).

### 4.4 Frontend React y navegador

- Usar escape contextual de React; **prohibir** `dangerouslySetInnerHTML`.
- No poner secretos ni tokens de larga duracion en el bundle.
- CSP sin `unsafe-eval`.
- Mostrar aviso legal, origen y fecha de los datos, estado del modelo y
  limitaciones.

### 4.5 Cookies, CSRF y XSS: justificacion explicita

La decision debe quedar escrita, porque "CSRF desactivado" sin explicar por que
es exactamente el tipo de control que un revisor no puede evaluar.

| Elemento | Realidad del sistema | Consecuencia |
|----------|-----------------------|--------------|
| **Token de acceso** | Viaja **unicamente** en la cabecera `Authorization`. Un navegador no la envia solo | **El CSRF de Spring esta desactivado** porque el token de acceso nunca viaja en una cookie: una peticion forzada desde otro origen llega sin credencial y responde `401` |
| **Cookie `xmr_refresh`** | **Si existe.** Transporta el refresh token, no el de acceso | Acotada a cuatro atributos: `HttpOnly`, `Secure`, `SameSite=Strict` y `Path=/api/v1/auth`. No se envia en peticiones de terceros ni fuera de la ruta de autenticacion |
| **Emision de `xmr_refresh`** | Login, refresh y callback de Google | Se emite tambien en el cuerpo de la respuesta para clientes sin almacen de cookies (k6, CLI) |
| **Borrado** | Logout | `Max-Age=0` con los mismos atributos |
| **CSRF de OAuth** | Flujo de Google | `state` de un solo uso mas `nonce`, ambos verificados en el callback |
| **XSS** | SPA y reportes | Escape contextual, CSP, `nosniff`, `frameOptions deny`. Como `HttpOnly` impide leer la cookie desde JavaScript, un XSS no puede exfiltrar el refresh token |

Precisiones que no conviene resumir: el CSRF de Spring esta desactivado **para
el token de acceso**, y la cookie de refresh queda por tanto fuera de la
proteccion de Spring. Ese hueco se cierra por construccion de la cookie
(`HttpOnly` + `Secure` + `SameSite=Strict` + `Path` restringido), no por un filtro.

### 4.6 PostgreSQL, Redis y MLflow

- En el Compose base, PostgreSQL y Redis viven en la red `data`, marcada
  `internal: true` y **sin puertos publicados**. MLflow publica solo
  `127.0.0.1:5000`.
- **Excepcion documentada y de uso restringido:** `docker-compose.dev.yml` publica
  PostgreSQL en `127.0.0.1:55432` y Redis en `127.0.0.1:56379`, y los conecta
  ademas a la red `backend`, que no es interna. Sin esa segunda red el binding no
  funciona, porque una red `internal: true` no tiene ruta de vuelta al host:
  Docker acepta el puerto, no publica nada, y el cliente acaba conectandose a
  **otro** PostgreSQL del host con el sintoma desconcertante de un
  "password authentication failed" ajeno a esta configuracion. Se usan puertos
  altos porque `5432` y `6379` los ocupan con frecuencia instalaciones locales.
  **Este fichero no se usa en produccion.**
- Usar usuarios separados y privilegios minimos.
- Cifrar conexiones y backups donde el despliegue lo permita.
- Migraciones **solo** por Flyway, revisadas y probadas contra PostgreSQL real.
  `ddl-auto: validate` es deliberado: valida, nunca crea ni actualiza (R-34).
- Redis exige autenticacion (`requirepass`) y no almacena secretos en claro.
- Redis **no** es la cola de trabajos: la cola es la tabla `jobs` de PostgreSQL.
- **Todo payload que pasa por `@Cacheable` es `Serializable`.** Con
  `spring.cache.type: redis`, Spring escribe los resultados con
  `JdkSerializationRedisSerializer`, y el fallo no ocurre al leer sino al
  **escribir**: dentro del propio metodo cacheado. Un tipo que no lo sea
  devuelve `500 DefaultSerializer requires a Serializable payload` en la ruta
  entera, y solo cuando la cache llega a persistir, es decir, solo con datos.
  Es el defecto que en T-043 dejo tres de las seis rutas del panel publico en
  500 con la suite de unidades en verde. Lo cubren `CachedPayloadSerializationTest`
  (la misma operacion que Redis, sin Redis) y `tools/verify-stack.ps1`, que
  siembra datos sinteticos para que la cache llegue a escribirse.

### 4.7 ML, modelos predictivos y MLOps

- Tratar dataset, feature set, scaler, modelo, configuracion, metricas y codigo
  como una cadena de procedencia.
- Firmar o registrar el digest del snapshot, del modelo y del scaler, y
  verificarlo antes de cargarlo.
- Solo cargar artefactos generados por el pipeline confiable y aprobados.
- Detectar poisoning y manipulacion mediante validacion de esquema y controles
  estadisticos.
- Monitorizar deriva de datos, cambios de distribucion y tasa de errores.
- El backend aplica el gate `MODEL_NOT_VERIFIED` antes de permitir una
  prediccion sobre una version sin integridad probada.

### 4.8 Dependencias, codigo, CI/CD e imagenes

- Fijar versiones en lockfiles y generar SBOM CycloneDX.
- Ejecutar en CI: secret scanning, SAST, lint y tipos, tests, tests de no fuga,
  SCA, y el **contrato HTTP verificado en runtime** (`HttpContractTest`).
- Construir imagenes minimas, reproducibles y con digest; ejecutar como usuario
  no root.
- Ninguna vulnerabilidad critica o alta conocida entra en `main`. Las excepciones
  requieren vencimiento y aprobacion registrada.

### 4.9 Observabilidad y deteccion

- Logs estructurados en JSON con `request_id`, `trace_id`, servicio, ruta,
  resultado, latencia y actor pseudonimizado. El servicio ML registra solo
  metadatos de cabeceras, nunca sus valores sensibles.
- Alertar sobre: rafagas de login fallido, cambios de rol, alta de `ADMIN`,
  fallos repetidos de autorizacion y trabajos que se reencolan por latido
  vencido.

---

## 5. Protocolos operativos

### 5.1 Protocolo de desarrollo seguro

1. Registrar requisito, activo afectado, amenaza y control `SEC-*` antes de
   implementar.
2. Revisar autorizacion, validacion, secretos, logs y errores en cada PR.
3. Ejecutar CI completo; ninguna vulnerabilidad critica o alta conocida puede
   entrar en `main`.
4. Ejecutar DAST con OWASP ZAP o equivalente en staging.
5. Documentar evidencia: commit, workflow, SBOM, scan, test y aprobadores.
6. Toda ruta nueva requiere documentacion de seguridad: autenticacion,
   autorizacion, validacion, limites, datos tratados, amenazas, controles y
   pruebas (R-35).

### 5.2 Protocolo de release

1. Confirmar lockfiles, SBOM, digest de imagenes, migraciones revisadas y backup
   reciente.
2. Verificar secretos de produccion mediante el gestor autorizado.
3. Ejecutar smoke tests HTTPS, autenticacion, autorizacion por rol, health check
   minimo y rollback.
4. Publicar release con commit o tag inmutable, changelog de seguridad y ventana
   de observacion.

La verificacion de release incluye **`tools/verify-stack.ps1`**, que levanta el
**jar empaquetado** contra PostgreSQL y Redis locales y ejecuta 43 comprobaciones
HTTP reales: salud `UP`, aviso legal accesible sin sesion, `401` sin token,
ausencia de listener HTTP plano, registro, puerta `403 EMAIL_NOT_VERIFIED`,
emision de JWT, cookie `HttpOnly`, rol efectivo en `/auth/me`, identificadores
opacos, extremos de lectura, las cinco familias de modelo de R-06, `403` para
`VIEWER` en rutas de `ADMIN` y, ya como `ANALYST`, creacion de dataset con
checksum calculado en el servidor, `409` en duplicados, creacion de experimento,
`400 INSUFFICIENT_SEEDS`, alta de corrida, `409` por `runKey` repetido, metricas
y presencia del trabajo `TRAIN` en la cola.

Su valor no es la cobertura de casos, sino que **levanta el artefacto de
produccion**: `mvn test` en verde no demuestra que la aplicacion arranque, ni que
el esquema real coincida con las entidades, ni que la configuracion permita
levantar (R-42).

### 5.3 Protocolo de ingesta y promocion ML

1. Descargar solo desde fuente aprobada y por HTTPS.
2. Crear snapshot y manifest inmutables.
3. Validar esquema, fechas, valores, duplicados, anomalias y checksum.
4. Entrenar solo con snapshot versionado; registrar configuracion, semillas, al
   menos 5 semillas para modelos estocasticos, codigo y artefactos (R-08).
5. Pasar gates de no fuga, reproducibilidad, integridad y calidad.
6. Promover campeon **por validacion, nunca por test**; registrar aprobacion y
   plan de rollback (R-24).

### 5.4 Gestion de secretos

- Secretos solo por variables de entorno; nada de credenciales en git (R-14).
- En produccion, fuera de git, artefactos y logs; minimo privilegio; red
  privada para DB, Redis y MLflow; rotacion y revocacion ante compromiso
  (R-27).
- Cadencias y procedimientos de rotacion en
  [`08_operacion.md`](08_operacion.md) seccion 8.

---

## 6. Respuesta a incidentes

El equipo sigue las fases de NIST SP 800-61 Rev. 3: preparar; detectar y
analizar; responder; recuperar; y mejorar.

### 6.1 Severidad y objetivo de atencion

| Severidad | Ejemplos | Accion inicial objetivo |
|---|---|---|
| P0 critica | Secreto o JWT de produccion expuesto, ejecucion remota, alteracion masiva | Inmediata; detener exposicion y rotar credenciales |
| P1 alta | Escalada de privilegio, acceso a datos confidenciales, poisoning confirmado | <= 4 h |
| P2 media | Vulnerabilidad explotable con mitigacion, abuso limitado | <= 1 dia habil |
| P3 baja | Hallazgo documental, hardening pendiente | Proximo ciclo |

### 6.2 Runbook

1. **Detectar y declarar:** registrar `incident_id`, hora UTC, alertas, personas
   y alcance.
2. **Contener:** revocar sesiones y familias de refresh tokens, rotar secretos,
   desactivar cuenta o endpoint, aislar el worker.
3. **Analizar:** identificar vector, activos, periodo y usuarios afectados.
4. **Erradicar:** corregir codigo o configuracion, retirar la dependencia o la
   imagen comprometida.
5. **Recuperar:** restaurar un backup verificado, desplegar un release limpio y
   comprobar checksums.
6. **Comunicar:** informar a responsables y afectados segun contrato y
   legislacion.
7. **Cerrar y mejorar:** informe de causa raiz, impacto, controles fallidos,
   evidencias y acciones.

Preservar la evidencia, rotar las credenciales comprometidas y documentar causa
raiz, impacto y acciones correctivas es parte del runbook, no un extra (R-30).

---

## 7. Gestion continua y matriz de controles

| ID | Control verificable | Evidencia minima | Frecuencia |
|---|---|---|---|
| SEC-001 | Threat model y activos actualizados | Registro de amenazas | Cada cambio mayor |
| SEC-002 | ASVS y API Top 10 trazados a tests | Matriz + tests | Cada release |
| SEC-003 | RBAC y autorizacion por objeto | Tests 401/403/IDOR | Cada PR |
| SEC-004 | BCrypt, MFA de `ADMIN` y sesiones revocables | Configuracion + test | Cada release |
| SEC-005 | HTTPS, headers, CORS y justificacion de CSRF | Scan staging + este documento 4.5 | Cada release |
| SEC-006 | Limitador y limites de recursos | Test 429 y benchmark | Cada release |
| SEC-007 | SSRF y egress de fuentes externas | Allowlist + tests | Cada cambio de fuente |
| SEC-008 | SQL parametrizado y migraciones revisadas | SAST + PR | Cada PR |
| SEC-009 | Auditoria append-only y logs sin secretos | Test + muestra redactada | Mensual |
| SEC-010 | DB, Redis y MLflow privados y con minimo privilegio | Revision de red y de roles; incluido el override de desarrollo | Mensual |
| SEC-011 | Backups cifrados y restaurables | Acta de restore con recuento de huerfanos | Trimestral |
| SEC-012 | Snapshots y artefactos con digest y procedencia | Manifest + hash | Cada ingesta o corrida |
| SEC-013 | Gates de calidad de datos, no fuga y poisoning | Reporte del pipeline | Cada corrida |
| SEC-014 | Modelo solo desde el registry aprobado | ACL + aprobacion | Cada promocion |
| SEC-015 | Dependencias, SBOM y secretos escaneados | Artefactos de CI | Cada PR y release |
| SEC-016 | Imagenes non-root, sin `privileged` y escaneadas | Scan + configuracion | Cada build |
| SEC-017 | DAST y pruebas negativas de API | Informe de staging | Cada release |
| SEC-018 | Alertas de seguridad probadas | Evento o alarma | Trimestral |
| SEC-019 | Runbook de incidente ejercitado | Simulacro | Semestral |
| SEC-020 | Revision de accesos y excepciones | Acta firmada | Mensual y trimestral |
| SEC-021 | Verificacion de extremo a extremo del artefacto | `tools/verify-stack.ps1` en verde | Cada release |
| SEC-022 | Consentimiento demostrable y sincronizado | `consent_records` con version + `LegalVersionsContractTest` y `RegisterPayloadContractTest` en verde | Cada cambio legal o de registro |
| SEC-023 | Reenvio de verificacion sin enumeracion y limitado | Pruebas `ConsentRegistrationTest` (anti-enumeracion, token anterior caducado, 429) | Cada release |

---

## 8. Checklist de definicion de hecho

- [ ] No hay secretos reales en repositorio, imagen, notebook, logs ni artefacto.
- [ ] Todos los endpoints tienen autenticacion y autorizacion, o justificacion
      publica.
- [ ] `docs/01_documentacion.md` seccion 3.1 lista las **46 rutas reales** y
      ninguna ruta nueva quedo sin documentar (R-35).
- [ ] Existen pruebas de BOLA, escalada de rol, limitador, SSRF, inyeccion, XSS y
      CSRF cuando corresponda.
- [ ] HTTPS, headers, CORS, limites, timeouts y errores seguros estan activos, y
      no hay listener de HTTP plano.
- [ ] DB, Redis, MLflow y puertos de administracion no estan expuestos
      publicamente; si se usa el override de desarrollo, es consciente y no llega
      a produccion.
- [ ] `docs/02_stack_tecnologico.md` y `docs/03_machine_learning.md` describen el
      contrato real: rutas, campos admitidos y ausencias.
- [ ] CI genera SBOM y ejecuta SAST, SCA, secret scanning, tests y scan de
      imagen.
- [ ] Snapshots, modelos y scalers tienen procedencia, digest y rollback.
- [ ] Backup y restauracion fueron probados; los runbooks tienen dueno.
- [ ] Logs y auditoria no contienen secretos, y las alertas criticas llegan al
      responsable.
- [ ] El aviso legal sigue visible en UI, API, reportes y documentacion, y el
      lenguaje sigue siendo de capacidad predictiva evaluada (R-11, R-12).

---

## 9. Limitaciones de seguridad registradas

Estas limitaciones **son reales y estan registradas**, no ocultas. Cada una es una
brecha conocida con su mitigacion parcial y su criterio de cierre.

| ID | Limitacion | Impacto | Mitigacion actual | Cierre |
|----|------------|---------|-------------------|--------|
| LIM-01 | **El entrenamiento es solo por CLI.** Un trabajo `TRAIN` termina siempre en `FAILED` con `TRAINING_NOT_EXPOSED` | No hay entrenamiento remoto | Decision de diseno: expone menos superficie; la corrida y su configuracion si quedan registradas | Mantener, o exponer un worker aislado con cuota y autenticacion |
| LIM-02 | **El `state` y el `nonce` de OAuth viven en la memoria del proceso** (`GoogleOAuthService.pendingStates`, un `ConcurrentHashMap`); el propio codigo indica que deberian vivir en Redis | Con varias replicas un callback puede invalidarse porque aterrizo en otra instancia, y un reinicio pierde todos los `state` pendientes | `state` de un solo uso, `nonce` verificado contra el `id_token`, emisores y audiencia restringidos | Mover el mapa a Redis con TTL |
| LIM-03 | **Sin circuit breaker hacia el servicio ML** | Un fallo sostenido genera reintentos y despues `503`, sin degradacion progresiva | Timeout de conexion y lectura, 2 reintentos con backoff incremental, health check | resilience4j con corte de circuito y recuperacion half-open |
| LIM-04 | **Sin MFA para administradores** | Un `ADMIN` queda protegido solo por contrasena | Bloqueo de cuenta, limitador, roles minimos y auditoria de cambios de rol | TOTP como minimo aceptable, WebAuthn preferible |
| LIM-05 | **El servicio ML no se autentica de forma interna** | Cualquiera que alcance su red puede pedir inferencia | Solo es alcanzable desde la red interna; se exige `https://` y validacion de TLS | API key o mTLS entre backend y servicio ML |
| LIM-06 | **El override de desarrollo publica DB y Redis en loopback** | Un `docker compose -f docker-compose.dev.yml up` en un host compartido expone datos | Solo loopback, puertos altos, y el fichero esta marcado como no productivo | Ninguna: es una herramienta de desarrollo, no un despliegue |
| LIM-07 | **El evento `AUTH_REGISTER` se guarda sin actor.** `AuditService` corre en su propia transaccion y aun no ve la fila del usuario que se acaba de crear: el insert falla por FK (`audit_events_actor_user_id_fkey`) y el propio servicio degrada, registra el evento **sin** `actor_user_id` y lo avisa en el log (`El evento AUTH_REGISTER se registra sin actor`) | La auditoria del alta no dice quien se dio de alta, aunque la cuenta, el correo y la hora si quedan en la fila | La degradacion esta implementada y es visible en el log: el evento **siempre** se registra, nunca se pierde; `AuthService` no puede retrasar el alta hasta despues del commit sin romper la respuesta | Ejecutar el registro de auditoria pos-commit en un hilo propio (mismo patron que las notificaciones de R-52) y comprobarlo con una prueba de integracion |

Ninguna de estas limitaciones se compensa con una afirmacion de seguridad que no
se pueda demostrar. El criterio de cierre de cada una es explicito.

---

## 10. Registro de excepciones

```text
ID: SEC-EX-YYYY-NNN
Control afectado:
Descripcion y motivo:
Activo/amenaza:
Riesgo residual:
Mitigacion temporal:
Responsable:
Aprobador:
Fecha de aprobacion:
Fecha de expiracion:
Evidencia y plan de cierre:
Estado: abierta | cerrada
```

Toda excepcion tiene fecha de expiracion. Una excepcion sin vencimiento no es
una excepcion: es un control retirado (R-29).

---

## 11. Fuentes normativas y tecnicas

- [NIST Cybersecurity Framework 2.0](https://www.nist.gov/publications/nist-cybersecurity-framework-csf-20)
- [OWASP Application Security Verification Standard 5.0.0](https://owasp.org/projects/asvs)
- [OWASP Top 10 2025](https://owasp.org/projects/top-ten)
- [OWASP API Security Top 10 2023](https://owasp.org/API-Security/)
- [NIST SP 800-63B-4 -- Authentication and Authenticator Management](https://csrc.nist.gov/pubs/sp/800/63/B/4/final)
- [NIST SP 800-218 -- Secure Software Development Framework](https://csrc.nist.gov/pubs/sp/800/218/final)
- [NIST SP 800-61 Rev. 3 -- Incident Response](https://csrc.nist.gov/pubs/sp/800/61/r3/final)
- [NIST AI Risk Management Framework 1.0](https://www.nist.gov/itl/ai-risk-management-framework)
- [MITRE ATLAS](https://atlas.mitre.org/)
- [OWASP Machine Learning Security Top Ten](https://owasp.org/projects/machine-learning-security-top-ten)
- [Docker Engine security](https://docs.docker.com/engine/security/)
- [SLSA specification](https://slsa.dev/spec/v1.0/)