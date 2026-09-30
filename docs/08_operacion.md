# 08 -- Operacion, recuperacion y retencion

> **Aviso legal.** XMR-Forecast muestra capacidad predictiva evaluada sobre datos
> historicos. No es asesoria financiera, no promete rentabilidad y no simula
> operaciones de trading ni backtesting (R-11, R-12).

Este documento cubre lo que hay que hacer **cuando algo falla**, y cuanto tiempo
se conservan los datos. Complementa a [`05_seguridad.md`](05_seguridad.md)
seccion 6 (Respuesta a incidentes).

---

## 1. Mapa de dependencias

Antes de diagnosticar, hay que saber de que depende cada servicio:

```
frontend --> backend --> PostgreSQL   (sin datos, no hay nada)
                 |    `-> Redis        (sin Redis, la API sigue pero sin limitador ni cache)
                 `----> ml-service --> MLflow --> PostgreSQL
```

Consecuencias practicas:

| Si falla... | Efecto | La API responde? |
|---|---|---|
| PostgreSQL | No hay datos ni esquema | **No.** Healthcheck en `DOWN` |
| Redis | Sin limitador ni cache | Si, degradada: `RateLimitFilter` deja pasar y avisa por log |
| ml-service | No hay predicciones nuevas | Si: `503 ML_SERVICE_UNAVAILABLE` en la ruta de prediccion |
| MLflow | Sin registro de experimentos | El camino de CLI de entrenamiento continua; solo se pierde el tracking |
| frontend | Ninguno | El backend sigue sirviendo la API |

Esta degradacion esta implementada a proposito: un fallo de cache o de ML no
debe convertir el sistema en una caida total.

---

## 2. Puesta en marcha reproducible

Este es el orden que funciona. Omitir el paso 1 produce errores de TLS
desconcertantes; omitir el paso 4 produce un sistema que parece sano y no lo es.

### Paso 1. Certificados TLS de desarrollo

```bash
bash docker/generate-dev-certs.sh
```

El script esta en **`docker/generate-dev-certs.sh`**. La ruta
`docker/certs/generate-dev-certs.sh` **no existe**: es un error de referencia
frecuente en documentacion antigua y no encuentra nada.

Genera una CA de desarrollo mas los certificados de `backend`, `ml-service` y
`mlflow`, y empaqueta `docker/certs/backend/keystore.p12` y
`docker/certs/ca/truststore.p12` con `openssl pkcs12`. Para regenerar la CA
(hace falta volver a importarla en el navegador):

```bash
FORCE=1 bash docker/generate-dev-certs.sh
```

### Paso 2. Secretos

```bash
cp .env.example .env
openssl rand -base64 64        # -> JWT_SECRET (minimo 64 bytes para HS512)
```

`POSTGRES_PASSWORD`, `REDIS_PASSWORD` y `JWT_SECRET` no tienen valor por defecto.
Si faltan, el compose o el backend **no arrancan**, y es intencionado (R-14).

### Paso 3. Levantar el sistema

```bash
docker compose up -d --build
docker compose ps
```

Servicios y puertos publicados (solo loopback):

| Servicio | Puerto en el host |
|----------|-------------------|
| `frontend` | `127.0.0.1:3000` |
| `backend` | `127.0.0.1:8443` (solo HTTPS; **no** existe listener en 8080) |
| `ml-service` | `127.0.0.1:8000` -> **8443 dentro del contenedor** |
| `mlflow` | `127.0.0.1:5000` |
| `postgres`, `redis` | **sin puerto publicado** (red `data` interna) |

### Paso 4 (solo desarrollo). Override para trabajar fuera de Docker

Para levantar el backend desde el IDE o consultar la base con `psql` desde el
host:

```bash
docker compose -f docker-compose.yml -f docker-compose.dev.yml up -d
```

Este override publica `postgres` en `127.0.0.1:55432` y `redis` en
`127.0.0.1:56379`, y ademas los conecta a la red `backend`, que **no** es
interna. Sin esa segunda conexion el binding no funciona: una red
`internal: true` no tiene ruta de vuelta al host, Docker acepta el puerto y no
publica nada, `docker port <contenedor>` sale vacio y el cliente acaba
conectandose a **otro** PostgreSQL del host, con el sintoma desconcertante de un
`password authentication failed` que no tiene nada que ver con esta
configuracion.

Los puertos altos son deliberados: `5432` y `6379` los ocupan con frecuencia
instalaciones locales, y ese conflicto tampoco avisa con claridad.

`DB_URL` correspondiente: `jdbc:postgresql://127.0.0.1:55432/xmr_forecast`.

**Este fichero no se usa en produccion.**

### Paso 5. Verificar de extremo a extremo

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File tools/verify-stack.ps1
```

`tools/verify-stack.ps1` arranca el **jar empaquetado** contra PostgreSQL y Redis
locales y ejecuta 43 comprobaciones HTTP reales: salud, aviso legal sin sesion,
`401` sin token, ausencia de listener HTTP plano, registro, puerta
`EMAIL_NOT_VERIFIED`, emision de JWT, cookie `HttpOnly`, rol efectivo,
identificadores opacos, extremos de lectura, familias de modelo, `403` por rol y
el camino de escritura de `ANALYST`.

Es la comprobacion que `mvn test` **no** puede dar: la suite en verde no
demuestra que la aplicacion arranque, ni que el esquema real coincida con las
entidades, ni que la configuracion permita levantar (R-42). El guion devuelve un
codigo de salida igual al numero de comprobaciones fallidas.

---

## 3. Comprobaciones iniciales

Ante una incidencia, comprobar en este orden:

```bash
# 1. Estado de los contenedores
docker compose ps

# 2. Logs del servicio afectado (ultimas 200 lineas)
docker compose logs --tail=200 backend
docker compose logs --tail=200 ml-service

# 3. Health del backend (incluye PostgreSQL y Redis)
curl --cacert docker/certs/ca/ca.crt https://localhost:8443/actuator/health

# 4. Las migraciones se aplicaron?
docker exec xmr-postgres psql -U xmr -d xmr_forecast \
  -c "SELECT version, description, success FROM flyway_schema_history ORDER BY installed_rank;"

# 5. Hay trabajos de ML en RUNNING?
docker exec xmr-postgres psql -U xmr -d xmr_forecast \
  -c "SELECT id, job_key, status, attempts, heartbeat_at FROM jobs WHERE status='RUNNING';"
```

Toda respuesta de error incluye `requestId`. Con ese valor se recuperan los logs
correlacionados. El backend envia `X-Request-Id` y `X-Trace-Id` al servicio ML, y
este devuelve ambas cabeceras en la respuesta:

```bash
docker compose logs backend | grep 'req-1a2b3c4d'
```

---

## 4. Recuperacion por escenario

### 4.1 El backend no arranca

1. **Falta un secreto.** Es intencionado: `JWT_SECRET`, `POSTGRES_PASSWORD` o
   `REDIS_PASSWORD` ausentes impiden el arranque. Revisar `.env`.
   ```
   JWT_SECRET no esta definido. Defina una clave de al menos 64 bytes
   ```
   Generar con `openssl rand -base64 64`.

2. **Clave JWT demasiado corta.** HS512 exige 64 bytes o mas. Un valor mas corto
   se rechaza **en el arranque**, no en la primera peticion.

3. **Fallo de conexion a PostgreSQL.** El contenedor no esta sano o las
   credenciales no coinciden:
   ```bash
   docker compose ps postgres
   docker exec xmr-postgres pg_isready -U xmr -d xmr_forecast
   ```

4. **El esquema no coincide con las entidades.** `spring.jpa.hibernate.ddl-auto`
   es `validate`: Hibernate compara el esquema real de PostgreSQL con el mapeo JPA
   y **aborta el arranque en la primera discrepancia**.

   El fallo mas frecuente, y el que ninguna prueba de unidades detecta, es una
   diferencia de tipo de columna de digest:

   ```
   SchemaManagementException: found [bpchar (Types#CHAR)],
   but expecting [varchar(64) (Types#VARCHAR)]
   ```

   `CHAR(64)` en PostgreSQL es `bpchar`, y ademas rellena con espacios; una
   entidad mapeada como `@Column(length = 64) String` se traduce a `VARCHAR(64)`.
   Para un SHA-256 hexadecimal de 64 caracteres `CHAR` no aporta nada y solo
   introduce sorpresas de relleno en comparaciones y `LIKE`.

   Lo grave del caso es **que `mvn test` y `mvn compile` pasan**: sin una base de
   datos real en las pruebas unitarias, la validacion del esquema no se ejecuta.
   El defecto solo aparece al levantar la aplicacion (R-42).

   La correccion **no** es cambiar `ddl-auto` a `update`, y tampoco es editar una
   migracion ya aplicada: su checksum esta registrado en `flyway_schema_history`
   y cambiarla haria que todas las instalaciones existentes dejaran de validar.
   El esquema se corrige **siempre** con una migracion nueva (R-34).

5. **Migraciones aplicadas.** El esquema lo crean estas cinco migraciones:

   | Version | Fichero | Contenido |
   |---------|---------|-----------|
   | V1 | `V1__init.sql` | Esquema base: usuarios, mercado, experimentos, predicciones, cola |
   | V2 | `V2__seed_roles_permissions.sql` | Roles y permisos base |
   | V3 | `V3__api_contract_columns.sql` | Columnas exigidas por el contrato de API |
   | V4 | `V4__seed_model_catalog.sql` | Catalogo de las cinco familias de modelo de R-06 |
   | V5 | `V5__align_digest_columns_with_jpa.sql` | Alinea los digest de `CHAR(64)` a `VARCHAR(64)` |

   En total, **22 tablas**.

### 4.2 PostgreSQL caido o corrupto

```bash
# Estado del contenedor y reinicio
docker compose restart postgres
docker compose logs --tail=100 postgres

# Si los datos se perdieron o estan corruptos, restaurar el ultimo volcado
bash docker/backup.sh list
bash docker/backup.sh verify  backups/xmr_forecast_<fecha>.dump
bash docker/backup.sh restore backups/xmr_forecast_<fecha>.dump
```

`verify` comprueba el SHA-256 y la estructura con `pg_restore --list` **antes** de
restaurar, y `restore` vuelve a ejecutar esa verificacion, de modo que no se puede
restaurar sobre un volcado corrupto por accidente.

Tras restaurar, `restore` ejecuta una **puerta de huerfanos** sobre cinco
relaciones y **aborta con codigo de salida distinto de cero** si queda alguna:

| Relacion comprobada |
|---------------------|
| `users` -> `user_roles` |
| `predictions` -> `model_versions` |
| `experiments` -> `dataset_versions` |
| `metrics` -> `experiment_runs` |
| `model_versions` -> `models` |

Comprobar solo que la base responde no basta: un `users` con el recuento correcto
no dice nada de las metricas de una corrida que ya no existe.

### 4.3 Redis caido

La API sigue respondiendo, pero **sin limitador ni cache**, lo que expone el
sistema a abuso. Al recuperarlo, el limitador arranca con contadores nuevos: los
clientes que superaban el limite temporalmente volveran a admitirse.

```bash
docker compose restart redis
docker compose logs --tail=50 redis
```

Redis **no** es la cola de trabajos: la cola es la tabla `jobs` de PostgreSQL, de
modo que perder la cache no pierde trabajos.

### 4.4 ml-service no responde

```bash
docker compose restart ml-service
docker compose logs --tail=200 ml-service
curl --cacert docker/certs/ca/ca.crt https://localhost:8000/health
```

El servicio escucha en **8443 dentro del contenedor**; `8000` es solo el mapeo de
loopback del host.

Si el certificado de desarrollo caduco (825 dias), regenerar:
`bash docker/generate-dev-certs.sh`.

El backend responde `503 ML_SERVICE_UNAVAILABLE` tras agotar los reintentos
configurados: timeout de conexion 3000 ms, de lectura 60000 ms y 2 reintentos
con espera incremental. **No hay circuit breaker**: es una limitacion
registrada, no una funcion pendiente de documentar.

Un `422` del servicio ML significa un problema de contrato, no de disponibilidad.
La causa mas frecuente: enviar un campo de mas en `POST /v1/predict`, que usa
`extra='forbid'`. **La semilla viaja en la version del modelo, nunca en el
cuerpo de la peticion**; enviarla devuelve `422` y rompe todas las predicciones.

### 4.5 Trabajos de ML

#### Deteccion

Un trabajo en `RUNNING` con `heartbeat_at` antiguo indica que el proceso que lo
ejecutaba murio:

```sql
SELECT id, job_key, status, attempts, heartbeat_at
FROM jobs
WHERE status = 'RUNNING' AND heartbeat_at < NOW() - INTERVAL '15 minutes';
```

#### La recuperacion la hace el sistema, no el operador

**No hay que escribir un `UPDATE` a mano.** `JobWorker` ejecuta
`recoverStalledJobs()` en cada pasada, **antes** de reclamar trabajo pendiente:
devuelve a `PENDING` los trabajos `RUNNING` cuyo `heartbeat_at` es anterior a
`app.jobs.heartbeat-timeout-seconds`, y los marca `FAILED` si ya agotaron los
intentos. Con la configuracion por defecto, un trabajo colgado se recupera solo en
la siguiente pasada, como maximo unos 15 segundos despues de vencer el umbral.

| Propiedad | Valor | Clave de configuracion |
|-----------|-------|-----------------------|
| Umbral de latido | 900 s (15 min) | `app.jobs.heartbeat-timeout-seconds` |
| Intentos maximos | 3 | `app.jobs.max-attempts` |
| Lote por pasada | 5 | Constante `BATCH_SIZE` |
| Retardo inicial | 5000 ms | `app.jobs.worker-initial-delay-ms` |
| Intervalo de sondeo | 15000 ms | `app.jobs.poll-interval-ms` |

**Advertencia sobre el `UPDATE` manual.** El worker reclama trabajo con un
`UPDATE ... WHERE status = PENDING`, que solo afecta a una fila. Un `UPDATE`
manual que ponga un trabajo en `PENDING` compite con ese reclamo y produce una de
dos cosas: el trabajo se ejecuta **dos veces**, o el operador cree que lo ha
reencolado y en realidad el worker lo Cerro como fallido por intentos agotados. Si
hay que forzar algo, primero se detiene el backend y se documenta el motivo.

La columna `idempotency_key` es unica: reencolar un trabajo no genera
predicciones duplicadas aunque se lance dos veces.

#### Politica de reintentos: 5xx si, 4xx no

| Codigo | Decision | Razon |
|--------|----------|-------|
| **502, 503, 504** | **Reintentar** | Fallo de transporte o indisponibilidad: el servicio puede volver |
| **4xx** | **Fallar de inmediato** | Error de negocio: reintentarlo solo repite el mismo fallo y consume intentos |
| **Runtime inesperado** | Reintentar | Se trata como transitorio hasta agotar `max-attempts` |

Al agotar los intentos, el trabajo pasa a `FAILED` con
`MAX_ATTEMPTS_EXCEEDED`.

#### Trabajos `TRAIN`: fallan por diseno

Un trabajo `TRAIN` **siempre** termina en `FAILED` con el codigo
`TRAINING_NOT_EXPOSED`. El entrenamiento es una operacion larga y reproducible que
se ejecuta por CLI sobre una configuracion versionada en `configs/*.yaml`; exponer
lo por HTTP abriria una via remota para disparar consumo de CPU sin control. El
API registra la corrida, sus semillas y su `configSha256`, y el worker marca el
trabajo como fallido explicando el motivo, en lugar de dejarlo en `RUNNING` para
siempre. **Un `TRAIN` en `FAILED` no es un incidente**: es el comportamiento
correcto.

Los trabajos `INGEST` y `BACKFILL` terminan con resultado explicito
`{"delegatedTo": "ml-service CLI", "executed": false}`. No se finge que se
ejecuto nada que no se ejecuto (R-21).

#### Estado del experimento y de la corrida

Al terminar un trabajo, `JobWorker` notifica a
`experiment/ExperimentRunCompletionLink`, que **recalcula el estado de la corrida
y del experimento** a partir del conjunto de sus corridas. El worker no conoce el
modulo de experimentos: la unica dependencia es la interfaz
`JobWorker.ExperimentRunLink`, declarada para que siga siendo testeable. Es el
mismo patron que la traduccion de estados en el servidor (R-43).

### 4.6 Credenciales de Google comprometidas

1. Revocar el secreto en Google Cloud Console.
2. Generar uno nuevo y actualizar `GOOGLE_CLIENT_SECRET` en `.env`.
3. `docker compose up -d backend`.
4. Revocar ademas los refresh tokens emitidos si se sospecha de uso indebido:
   ```sql
   UPDATE refresh_tokens SET revoked_at = NOW(), revoked_reason = 'SECRET_ROTATION'
   WHERE revoked_at IS NULL;
   ```

Procedimiento completo en [`05_seguridad.md`](05_seguridad.md) seccion 6 (R-30).

---

## 5. Copias de seguridad

### Politica

| Aspecto | Decision |
|---|---|
| Que se respalda | Base de datos completa (usuarios, mercado, experimentos, predicciones) |
| Formato | `pg_dump -Fc` (custom, comprimido), que permite restaurar parcialmente |
| Verificacion | SHA-256 + `pg_restore --list` obligatorios antes de restaurar |
| Integridad referencial | Puerta de huerfanos sobre 5 relaciones tras restaurar; aborta si queda alguna |
| Retencion | 30 dias por defecto (`RETENTION_DAYS`) |
| Cifrado | **No aplicado por defecto**: cifrar el volcado antes de almacenarlo fuera del host |
| Ubicacion | Fuera del host, en almacenamiento con control de acceso |

### Verificacion probada

**El ciclo completo se ejecuto de verdad** contra **PostgreSQL 15.19**. No es un
procedimiento teorico:

- Se genero el volcado con `pg_dump -Fc` y su fichero `.sha256`.
- Se destruyeron **30 filas de `market_data`** y **5 filas de `models`**.
- Se restauro el volcado con `bash docker/backup.sh restore`.
- **Las 30 filas de `market_data` y las 5 de `models` se recuperaron
  integro**, con sus relaciones.
- **Checksum verificado**: el `.sha256` coincide antes de restaurar.
- **0 filas huerfanas** en las cinco relaciones comprobadas.
- La comprobacion de huerfanos cubre hoy `users` -> `user_roles`,
  `predictions` -> `model_versions`, `experiments` -> `dataset_versions`,
  `metrics` -> `experiment_runs` y `model_versions` -> `models`. Antes solo se
  miraba que la base respondia, y eso no demuestra que los datos sean coherentes.

Evidencia reproducible: `bash docker/backup.sh backup` seguido de
`bash docker/backup.sh restore <fichero>`, cuyo codigo de salida es distinto de
cero si la restauracion deja huerfanos.

Advertencia: los volcados **contienen datos de usuarios**. No deben salir del
perimetro sin cifrar.

---

## 6. Retencion y eliminacion de datos

| Dato | Retencion | Como se elimina |
|---|---|---|
| Refresh tokens | Hasta su expiracion (30 dias por defecto) | `DELETE` de expirados; se revocan al cerrar sesion |
| Tokens revocados | Hasta `expires_at` del token | `RevokedTokenRepository.deleteExpiredBefore` |
| Tokens de reset y verificacion | 2 h (reset), 48 h (verificacion) | `consumed_at` al usarse; barrido periodico |
| Intentos de login | Retencion limitada | `login_attempts` se poda por periodo |
| Notificaciones | Hasta que el usuario las lee | `read_at`; sin borrado automatico |
| Eventos de auditoria | **No se eliminan automaticamente** | Append-only por diseno (R-27) |
| Datos de mercado | Permanentes | Son la entrada del modelado; se versionan por checksum |
| Artefactos ML | Permanente mientras el modelo sea campeon | Se conservan para reproducir resultados (R-28) |
| Backups | 30 dias | `find -mtime +N -delete` en `backup.sh` |

### Baja de una cuenta

`users.status = 'DELETED'` es una **baja logica**: el registro se conserva para no
romper la trazabilidad de auditoria ni las predicciones que el usuario solicito.
Las credenciales se invalidan revocando sus refresh tokens; ademas, la cookie
`xmr_refresh` se borra con `Max-Age=0` en la respuesta de logout.

---

## 7. Actualizaciones

```bash
# 1. Estado limpio antes de empezar
git status
git pull --ff-only

# 2. Reconstruir y reiniciar
docker compose build
docker compose up -d

# 3. Verificar
docker compose ps
curl --cacert docker/certs/ca/ca.crt https://localhost:8443/actuator/health

# 4. Confirmar que las migraciones se aplicaron
docker exec xmr-postgres psql -U xmr -d xmr_forecast \
  -c "SELECT version, description, success FROM flyway_schema_history ORDER BY installed_rank;"

# 5. Verificacion de extremo a extremo del artefacto recien construido
powershell -NoProfile -ExecutionPolicy Bypass -File tools/verify-stack.ps1
```

El paso 5 es el que detecta los fallos que la suite de pruebas no ve: un jar sin
`Main-Class` por `repackage` ausente, un esquema que no valida contra las
entidades, o una configuracion que impide arrancar (R-42).

**Antes de desplegar en un entorno real**: ejecuta `bash docker/backup.sh backup`.
Una migracion de esquema es irreversible sin un volcado previo.

---

## 8. Rotacion de secretos

| Secreto | Cadencia sugerida | Procedimiento |
|---|---|---|
| `JWT_SECRET` | 90 dias | Regenerar + reiniciar. **Invalida todos los access tokens**; las sesiones se renuevan con el refresh token |
| Cookie `xmr_refresh` | Con la rotacion de `JWT_SECRET` y ante cualquier sospecha | **Invalidarla no basta con borrar la cookie del navegador.** Revocar la familia de refresh tokens en la tabla, que es la unica fuente de verdad: `UPDATE refresh_tokens SET revoked_at = NOW(), revoked_reason = 'SECRET_ROTATION' WHERE revoked_at IS NULL;`. La cookie vive en el navegador del usuario y el servidor no puede limpiarla a distancia |
| `POSTGRES_PASSWORD` | 90 dias | `ALTER ROLE` + actualizar `.env` + reiniciar |
| `REDIS_PASSWORD` | 90 dias | Actualizar `.env` + reiniciar Redis y el backend |
| `GOOGLE_CLIENT_SECRET` | Segun Google Cloud | Seccion 4.6 |
| Contrasenas de keystore TLS | 365 dias | Regenerar certificados: `FORCE=1 bash docker/generate-dev-certs.sh` |

Al rotar `JWT_SECRET` conviene reiniciar tambien los servicios ML, que reciben
correlacion pero no validan JWT.

Detalle de la rotacion de refresh tokens: la rotacion es **por familia**.
Reutilizar un token ya rotado revoca la familia completa, porque la reutilizacion
es la senal de un token robado.

---

(c) AyLZz17 - AyLZz Software Solutions. Todos los derechos reservados.