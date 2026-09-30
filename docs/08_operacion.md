# 08 · Operación, recuperación y retención

> **Aviso legal.** XMR-Forecast muestra capacidad predictiva evaluada sobre datos
> históricos. No es asesoría financiera, no promete rentabilidad y no simula
> operaciones de trading ni backtesting (R-11).

Este documento cubre lo que hay que hacer **cuando algo falla**, y cuánto tiempo se
conservan los datos. Complementa a [`05_seguridad.md`](05_seguridad.md) §Incidentes.

---

## 1. Mapa de dependencias

Antes de diagnosticar, hay que saber de qué depende cada servicio:

```
frontend ──▶ backend ──▶ PostgreSQL   (sin datos, no hay nada)
                 │    └─▶ Redis        (sin Redis, la API sigue pero sin rate limit ni caché)
                 └────▶ ml-service ──▶ MLflow ──▶ PostgreSQL
```

Consecuencias prácticas:

| Si falla… | Efecto | ¿La API responde? |
|---|---|---|
| PostgreSQL | No hay datos ni esquema | **No.** Healthcheck en `DOWN` |
| Redis | Sin rate limit ni caché | Sí, degradada: `RateLimitFilter` deja pasar y avisa por log |
| ml-service | No hay predicciones nuevas | Sí: `503 ML_SERVICE_UNAVAILABLE` en la ruta de predicción |
| MLflow | Sin registro de experimentos | El entrenamiento continúa; solo se pierde el tracking |
| frontend | Ninguno | El backend sigue sirviendo la API |

Esta degradación está implementada a propósito: un fallo de caché o de ML no debe
convertir el sistema en una caída total.

---

## 2. Comprobaciones iniciales

 Ante una incidencia, comprobar en este orden:

```bash
# 1. Estado de los contenedores
docker compose ps

# 2. Logs del servicio afectado (últimas 200 líneas)
docker compose logs --tail=200 backend
docker compose logs --tail=200 ml-service

# 3. Health del backend (incluye PostgreSQL y Redis)
curl --cacert docker/certs/ca/ca.crt https://localhost:8443/actuator/health

# 4. ¿La migración se aplicó?
docker exec xmr-postgres psql -U xmr -d xmr_forecast \
  -c "SELECT version, description, success FROM flyway_schema_history ORDER BY installed_rank;"

# 5. ¿Quedó algún trabajo de ML colgado?
docker exec xmr-postgres psql -U xmr -d xmr_forecast \
  -c "SELECT job_key, status, attempts, heartbeat_at FROM jobs WHERE status='RUNNING';"
```

Toda respuesta de error incluye `requestId`. Con ese valor se recuperan los logs
correlacionados:

```bash
docker compose logs backend | grep 'req-1a2b3c4d'
```

---

## 3. Recuperación por escenario

### 3.1 El backend no arranca

1. **Falta un secreto.** Es intencionado: `JWT_SECRET`, `POSTGRES_PASSWORD` o
   `REDIS_PASSWORD` ausentes impiden el arranque. Revisar `.env`.
   ```
   JWT_SECRET no esta definido. Defina una clave de al menos 64 bytes
   ```
   Generar con `openssl rand -base64 64`.

2. **Clave JWT demasiado corta.** HS512 exige ≥ 64 bytes. Un valor más corto se rechaza
   en el arranque, no en la primera petición.

3. **Fallo de conexión a PostgreSQL.** El contenedor no está sano o las credenciales no
   coinciden:
   ```bash
   docker compose ps postgres
   docker exec xmr-postgres pg_isready -U xmr -d xmr_forecast
   ```

4. **La migración falla.** `ddl-auto: validate` detecta cualquier diferencia entre el
   esquema y las entidades. El log indica la tabla y la columna concretas.
   **No se corrige con `ddl-auto: update`**: el esquema solo cambia por migración (R-34).

### 3.2 PostgreSQL caído o corrupto

```bash
# Estado del contenedor y reinicio
docker compose restart postgres
docker compose logs --tail=100 postgres

# Si los datos se perdieron o están corruptos, restaurar el último volcado
bash docker/backup.sh list
bash docker/backup.sh verify  backups/xmr_forecast_<fecha>.dump
bash docker/backup.sh restore backups/xmr_forecast_<fecha>.dump
```

`verify` comprueba el SHA-256 y la estructura con `pg_restore --list` **antes** de
restaurar. `restore` vuelve a ejecutar la verificación, de modo que no se puede
restaurar sobre un volcado corrupto por accidente.

### 3.3 Redis caído

La API sigue respondiendo, pero **sin rate limit ni caché**, lo que expone el sistema
a abuso. Al recuperarlo, el rate limit arranca con contadores nuevos: los clientes que
superaban el límite temporalmente volverán a admitirse.

```bash
docker compose restart redis
docker compose logs --tail=50 redis
```

### 3.4 ml-service no responde

```bash
docker compose restart ml-service
docker compose logs --tail=200 ml-service
curl --cacert docker/certs/ca/ca.crt https://localhost:8000/health
```

Si el certificado de desarrollo caducó (825 días), regenerar:
`bash docker/certs/generate-dev-certs.sh`.

El backend no expone credenciales del servicio ML ni acepta un silencio degradado: responde
`503 ML_SERVICE_UNAVAILABLE` tras agotar los reintentos configurados.

### 3.5 Trabajos de ML colgados

Un trabajo en `RUNNING` con `heartbeat_at` antiguo indica que el worker murió:

```sql
-- Detectar
SELECT id, job_key, status, attempts, heartbeat_at
FROM jobs
WHERE status = 'RUNNING' AND heartbeat_at < NOW() - INTERVAL '15 minutes';

-- Reencolar hasta el máximo de intentos
UPDATE jobs SET status = 'PENDING', heartbeat_at = NULL
WHERE id = <id> AND attempts < max_attempts;

-- Si se agotaron los intentos, marcar como fallido
UPDATE jobs SET status = 'FAILED', error_message = 'Worker perdido; intentos agotados',
       finished_at = NOW()
WHERE id = <id>;
```

La columna `idempotency_key` es única: reencolar un trabajo no genera predicciones
duplicadas aunque se lance dos veces.

### 3.6 Credenciales de Google comprometidas

1. Revocar el secreto en Google Cloud Console.
2. Generar uno nuevo y actualizar `GOOGLE_CLIENT_SECRET` en `.env`.
3. `docker compose up -d backend`.
4. Revocar además los refresh tokens emitidos si se sospecha de uso indebido:
   ```sql
   UPDATE refresh_tokens SET revoked_at = NOW(), revoked_reason = 'SECRET_ROTATION'
   WHERE revoked_at IS NULL;
   ```

Procedimiento completo en [`05_seguridad.md`](05_seguridad.md) §Incidentes (R-30).

---

## 4. Copias de seguridad

### Política

| Aspecto | Decisión |
|---|---|
| Qué se respalda | Base de datos completa (usuarios, mercado, experimentos, predicciones) |
| Formato | `pg_dump -Fc` (custom, comprimido), que permite restaurar parcialmente |
| Verificación | SHA-256 + `pg_restore --list` obligatorios antes de restaurar |
| Retención | 30 días por defecto (`RETENTION_DAYS`) |
| Cifrado | **No aplicado por defecto**: cifrar el volcado antes de almacenarlo fuera del host |
| Ubicación | Fuera del host, en almacenamiento con control de acceso |

### Verificación probada

El ciclo completo se ejecutó contra PostgreSQL 15.19: con 2 usuarios, 2 velas
diarias, 3 roles y 16 permisos se generó el volcado, se borró todo el contenido y se
restauró. Se recuperaron los usuarios **con sus roles correctos**, las velas y el
catálogo de permisos, con **0 filas huérfanas**.

Advertencia: los volcados **contienen datos de usuarios**. No deben salir del perímetro
sin cifrar.

---

## 5. Retención y eliminación de datos

| Dato | Retención | Cómo se elimina |
|---|---|---|
| Refresh tokens | Hasta su expiración (30 días por defecto) | `DELETE` de expirados; se revocan al cerrar sesión |
| Tokens revocados | Hasta `expires_at` del token | `RevokedTokenRepository.deleteExpiredBefore` |
| Tokens de reset/verificación | 2 h (reset) · 48 h (verificación) | `consumed_at` al usarse; barrido periódico |
| Intentos de login | Retención limitada | `login_attempts` se poda por periodo |
| Notificaciones | Hasta que el usuario las lee | `read_at`; sin borrado automático |
| Eventos de auditoría | **No se eliminan automáticamente** | Append-only por diseño (R-27) |
| Datos de mercado | Permanentes | Son la entrada del modelado; se versionan por checksum |
| Artefactos ML | Permanente mientras el modelo sea campeón | Se conservan para reproducir resultados (R-28) |
| Backups | 30 días | `find -mtime +N -delete` en `backup.sh` |

### Baja de una cuenta

`users.status = 'DELETED'` es una **baja lógica**: el registro se conserva para no
romper la trazabilidad de auditoría ni las predicciones que el usuario solicitó.
Las credenciales se invalidan revocando sus refresh tokens.

---

## 6. Actualizaciones

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
  -c "SELECT version, description, success FROM flyway_schema_history ORDER BY installed_rank DESC LIMIT 5;"
```

**Antes de desplegar en un entorno real**: ejecuta `bash docker/backup.sh backup`.
Una migración de esquema es irreversible sin un volcado previo.

---

## 7. Rotación de secretos

| Secreto | Cadencia sugerida | Procedimiento |
|---|---|---|
| `JWT_SECRET` | 90 días | Regenerar + reiniciar. **Invalida todos los access tokens**; las sesiones se renuevan con el refresh token |
| `POSTGRES_PASSWORD` | 90 días | `ALTER ROLE` + actualizar `.env` + reiniciar |
| `REDIS_PASSWORD` | 90 días | Actualizar `.env` + reiniciar Redis y el backend |
| `GOOGLE_CLIENT_SECRET` | Según Google Cloud | §3.6 |
| Contraseñas de keystore TLS | 365 días | Regenerar certificados |

Al rotar `JWT_SECRET` conviene reiniciar también los servicios ML, que reciben
correlación pero no validan JWT.

---

© AyLZz17 - AyLZz Software Solutions. Todos los derechos reservados.