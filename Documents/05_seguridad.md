# XMR-Forecast — Protocolo integral de seguridad

> **Estado:** baseline de diseño para desarrollo y preproducción  
> **Versión:** 1.0 · **Fecha:** 2026-09-29  
> **Propietario:** responsable técnico (`admin`) · **Revisión mínima:** trimestral y ante cada cambio de arquitectura, dependencia crítica o incidente.

Este documento define cómo se **previene, detecta, responde y mejora** la seguridad de XMR-Forecast. Cubre la SPA React, la API FastAPI, PostgreSQL, Redis/Celery, MLflow, los snapshots OHLCV, los modelos y artefactos de ML, Docker, CI/CD y las fuentes externas.

No es una certificación ni sustituye una auditoría independiente. El sistema no custodia XMR, no contiene claves privadas, no procesa pagos y no ejecuta operaciones de trading. Si se añade cualquiera de esas capacidades, se debe abrir una nueva evaluación de riesgos y revisar las obligaciones legales antes de desplegarla.

## 1. Marcos y protocolos de referencia

Se adopta un enfoque de defensa en profundidad, con **NIST CSF 2.0** como ciclo de gobierno (`Govern`, `Identify`, `Protect`, `Detect`, `Respond`, `Recover`), **OWASP ASVS 5.0.0** como catálogo verificable de controles web, **OWASP Top 10 2025** como guía de riesgos de aplicación y **OWASP API Security Top 10 2023** como guía específica de la API.

| Área | Marco/protocolo | Uso en este proyecto | Nivel |
|---|---|---|---|
| Gobierno | NIST CSF 2.0 | Riesgos, responsables, métricas, revisión y mejora | Obligatorio |
| Aplicación web | OWASP ASVS 5.0.0 + OWASP Top 10 2025 | Requisitos, revisión de código y pruebas | Obligatorio |
| API REST | OWASP API Security Top 10 2023 | Autorización por objeto/función, límites, SSRF e inventario | Obligatorio |
| Identidad | NIST SP 800-63B-4 | Autenticadores, sesiones y recuperación | Obligatorio |
| Desarrollo | NIST SP 800-218 SSDF | Seguridad desde requisitos hasta publicación | Obligatorio |
| Incidentes | NIST SP 800-61 Rev. 3 | Preparación, triage, contención, recuperación y lecciones aprendidas | Obligatorio |
| Cadena de suministro | NIST SP 800-161 Rev. 1 (actualización 2024), SBOM y SLSA | Dependencias, imágenes, acciones CI y artefactos | Obligatorio |
| ML/IA | NIST AI RMF 1.0, MITRE ATLAS y OWASP ML Security Top 10 | Integridad de datos/modelos, poisoning, extracción y abuso de inferencia | Obligatorio para `ml/` |
| Contenedores | Docker Engine Security, modo rootless cuando sea posible | Aislamiento, privilegios, red y filesystem | Obligatorio en despliegue |
| Privacidad | Principio de minimización y normativa aplicable al lugar de operación | Cuenta, auditoría, IP, retención y derechos | Según jurisdicción |

El **OWASP ML Security Top 10** se mantiene como referencia de trabajo porque su propia página lo marca como borrador vivo; para amenazas de modelos predictivos se priorizan además MITRE ATLAS y las pruebas internas definidas en este documento.

### 1.1 Perfiles de cumplimiento

Estos marcos no significan que el proyecto esté certificado. ISO/IEC 27001, SOC 2, PCI DSS, normativa de proveedor de servicios de activos virtuales o requisitos de infraestructura crítica solo se incorporan mediante una decisión explícita si el alcance, los datos o la operación los hacen aplicables. PCI DSS no aplica al diseño actual porque no hay pagos ni tarjetas; tampoco se deben anunciar cumplimiento o certificaciones inexistentes.

## 2. Objetivos, límites y clasificación

### 2.1 Objetivos de seguridad

1. **Confidencialidad:** proteger credenciales, tokens, configuraciones, logs sensibles y artefactos no publicados.
2. **Integridad:** impedir que se alteren datos, snapshots, configuraciones, modelos, métricas, predicciones o registros de auditoría sin autorización.
3. **Disponibilidad:** evitar que login, API, workers, Redis o endpoints de entrenamiento sean agotados.
4. **Trazabilidad:** poder asociar cada acción sensible con identidad, momento, objeto, resultado y solicitud.
5. **Reproducibilidad confiable:** cada resultado ML debe poder vincularse a un snapshot, checksum, configuración, código y modelo verificables.
6. **Uso responsable:** las salidas no se presentan como asesoría financiera ni desencadenan operaciones.

### 2.2 Activos y clasificación

| ID | Activo | Clasificación | Riesgo principal |
|---|---|---|---|
| A-01 | Contraseñas, JWT, refresh tokens y secretos | Restringido | Toma de cuenta |
| A-02 | Roles, permisos y registro de auditoría | Confidencial | Escalada o pérdida de evidencia |
| A-03 | PostgreSQL y backups | Confidencial | Exfiltración o manipulación |
| A-04 | Redis, mensajes Celery y caché | Interno/confidencial | Ejecución, replay o indisponibilidad |
| A-05 | Snapshots OHLCV, manifests y checksums | Integridad crítica | Data poisoning o resultados no reproducibles |
| A-06 | Modelos, scalers, configuraciones y MLflow | Confidencial | Sustitución, extracción o carga de código no confiable |
| A-07 | Código, dependencias, imágenes y workflows CI | Integridad crítica | Compromiso de la cadena de suministro |
| A-08 | Exportaciones CSV/PDF y logs | Interno; confidencial si incluyen identificadores | Inyección, fuga o retención excesiva |
| A-09 | Documentación, métricas agregadas y OHLCV publicado | Público | Manipulación de confianza o reputación |

## 3. Modelo de amenazas y abuso previsto

Los actores considerados son: visitante no autenticado, usuario `viewer`, analista comprometido, administrador comprometido, proveedor de datos malicioso o defectuoso, dependencia comprometida, insider, atacante de la infraestructura y proceso/worker abusado.

| Amenaza | Superficie | Control principal |
|---|---|---|
| BOLA/IDOR: consultar o modificar otro objeto | `/experiments/{id}`, `/runs/{id}`, `/datasets` | Autorización server-side por objeto y rol; pruebas negativas |
| Escalada de función | Ingesta, ejecución, campeón, usuarios | RBAC explícito; `admin` solo para administración; deny-by-default |
| Credential stuffing/brute force | `/auth/login` y OAuth | Argon2id, rate limit, MFA de admin, errores genéricos y alertas |
| SSRF | `data_source.base_url` e ingesta | Allowlist de hosts/protocolos, DNS/IP validation, egress limitado, timeout |
| Inyección | JSON, filtros, exportaciones, comandos | Pydantic allowlist, SQL parametrizado, no shell con input de usuario |
| Consumo ilimitado | Exportaciones, predicción, training jobs | Cuotas, paginación, límites de tamaño, idempotencia y colas |
| XSS/CSRF/clickjacking | SPA, reportes y cookies | CSP, escape contextual, headers, SameSite y CSRF si hay cookies |
| Dependencia o imagen maliciosa | Python, npm, Docker, GitHub Actions | Lockfile, SBOM, SCA, firma/provenance, scanners y revisión |
| Alteración o poisoning del dataset | Fuente externa, CSV, snapshot | Validación de esquema, anomalías, snapshot inmutable, checksum, aprobación |
| Sustitución de modelo/scaler | MLflow, artifacts, worker | ACL, digest, solo artefactos aprobados, registro y rollback |
| Exposición de Redis/MLflow/DB | Red y puertos | Red privada, autenticación, TLS cuando aplique, sin puertos públicos |
| Pérdida de disponibilidad | API, DB, worker o proveedor | Health checks, límites, retry con backoff, backup y recuperación |

## 4. Controles obligatorios por capa

### 4.1 Identidad, autenticación y sesiones

- Todos los accesos de producción se realizan por HTTPS; TLS 1.3 es la opción preferida y TLS 1.2 solo se mantiene por compatibilidad justificada. Activar HSTS después de verificar que todos los subdominios sirven HTTPS.
- Usar JWT de acceso de corta duración (objetivo inicial: 10 minutos) y, si se necesitan sesiones prolongadas, refresh token rotatorio, revocable y almacenado únicamente en cookie `HttpOnly; Secure; SameSite=Strict` o equivalente seguro. El access token no se guarda en `localStorage`.
- Validar firma, algoritmo permitido, `iss`, `aud`, `sub`, `iat`, `exp` y `jti`; rechazar algoritmos inesperados. Separar secretos de firma entre desarrollo, CI y producción.
- Contraseñas con **Argon2id** y salt único por contraseña. No usar MD5, SHA-1, SHA-256 directo ni cifrado reversible. Ajustar el coste mediante benchmark del entorno y rehash al iniciar sesión cuando el coste quede obsoleto.
- Política de contraseña: mínimo 12 caracteres, permitir gestores de contraseñas, bloquear contraseñas comprometidas cuando sea posible y no imponer reglas de composición que favorezcan patrones predecibles.
- Login con respuesta genérica para no revelar si existe un correo. Aplicar rate limit por IP y por cuenta, backoff progresivo, alerta ante patrones anómalos y bloqueo temporal reversible; nunca bloqueo permanente sin recuperación segura.
- MFA obligatorio para `admin` en producción; WebAuthn/FIDO2 es preferible y TOTP es el mínimo aceptable. El acceso OAuth debe usar OIDC, Authorization Code + PKCE, `state`, `nonce`, validación estricta de issuer/audience y cuentas permitidas.
- Aplicar autorización en cada request, nunca confiar en el rol enviado por el cliente. Verificar además el acceso al objeto, experimento, corrida o dataset concreto.
- Revocar sesiones al desactivar usuario, cambiar contraseña, perder MFA o detectar compromiso. Registrar creación, renovación, revocación y fallos de autenticación sin registrar tokens.
- El alta, baja, cambio de rol, reset de contraseña, activación de MFA y designación de campeón requieren auditoría y, para `admin`, doble revisión cuando exista más de una persona operadora.

### 4.2 API REST y FastAPI

- Mantener OpenAPI y rutas bajo `/api/v1`; retirar endpoints no usados y proteger o desactivar Swagger/ReDoc en producción.
- Definir esquemas Pydantic de entrada y salida con allowlist, tipos estrictos, límites de longitud/rango, `limit` máximo, fechas válidas y paginación obligatoria en colecciones.
- Implementar rate limiting diferenciado: login, exportación, ingesta, creación de experimentos, predicción e administración. El límite y la respuesta `429` deben estar documentados.
- Aplicar CORS solo a orígenes conocidos; nunca `*` con credenciales. Si la autenticación usa cookies, proteger mutaciones con token CSRF y comprobar `Origin`/`Referer`.
- Añadir `Content-Security-Policy`, `Strict-Transport-Security`, `X-Content-Type-Options: nosniff`, `Referrer-Policy`, `Permissions-Policy` y `frame-ancestors`/`X-Frame-Options`.
- Usar consultas parametrizadas mediante SQLAlchemy; no concatenar SQL, rutas, shell ni expresiones de filtro con entrada del usuario. No devolver trazas, SQL, secretos ni rutas internas en errores.
- Cada endpoint debe declarar rol requerido y comprobar el objeto solicitado. El cliente nunca decide `is_admin`, `is_champion`, `is_live` ni campos de propiedad.
- Para fuentes externas, aceptar únicamente HTTPS y hosts aprobados; resolver y validar IP evitando loopback, link-local, metadata endpoints y redes privadas; limitar redirecciones, tamaño, tiempo, MIME y número de filas.
- Configurar timeouts, límites de concurrencia, tamaño máximo de body y respuesta. Los trabajos largos responden `202` con identificador opaco y no ejecutan lógica pesada dentro de la petición.
- Exportar con nombre generado por el servidor, ruta fuera del árbol de código, permisos mínimos, `Content-Disposition` seguro y respuesta sin datos que el usuario no pueda leer. Escapar celdas CSV que comiencen con `=`, `+`, `-` o `@`.
- Health checks no revelan versiones, variables, conectividad detallada ni credenciales. El endpoint público solo comunica estado agregado.

### 4.3 Frontend React y navegador

- Usar escape contextual de React; prohibir `dangerouslySetInnerHTML` salvo revisión de seguridad y sanitización explícita.
- No poner secretos, JWT de larga duración, credenciales ni URLs internas en el bundle. Validar también en backend cualquier dato validado en cliente.
- CSP sin `unsafe-eval`; limitar `script-src`, `connect-src`, imágenes, frames y fuentes a orígenes necesarios. Fijar dependencias y revisar paquetes de UI.
- Mostrar aviso legal, origen/fecha de datos, estado de modelo y limitaciones; no convertir una predicción en una acción de compra o venta.
- Probar navegación y permisos con cada rol, almacenamiento/cookies, expiración de sesión, logout, errores, accesibilidad básica y resistencia a XSS reflejado/almacenado.

### 4.4 PostgreSQL, Redis, Celery y MLflow

- PostgreSQL, Redis y MLflow se exponen solo en la red privada de servicios. En producción no se publican sus puertos en Internet.
- Usar usuarios separados y privilegios mínimos para API, migraciones, worker, MLflow y backups. El usuario de runtime no puede modificar el esquema.
- Cifrar conexiones y backups donde el despliegue lo permita; cifrar volúmenes y restringir permisos del filesystem. Probar restauraciones, no solo la creación de backups.
- Migraciones solo por Alembic, revisadas y probadas contra PostgreSQL real. Proteger contra consultas costosas mediante índices, límites y cancelación/timeouts.
- Redis debe exigir autenticación, estar en red interna y no almacenar secretos o tokens en claro. Celery debe usar serialización JSON; queda prohibido `pickle` o deserialización de tareas no confiables.
- Las tareas `ingest_daily`, `run_experiment` y `evaluate_run` deben ser idempotentes, con reintentos acotados, backoff, timeout, límites de memoria/CPU y deduplicación. Validar que un usuario no pueda inyectar nombre de tarea, módulo, comando o ruta de artefacto.
- MLflow requiere autenticación/authorization delante del servicio; separar tracking, artifacts y credenciales. Solo publicar como campeón un modelo que pase los gates de validación, integridad y revisión.
- Backups: cifrados, con acceso separado, retención documentada y prueba de restauración al menos trimestral. Definir RPO/RTO antes del primer despliegue público.

### 4.5 Datos, snapshots y privacidad

- Clasificar datos antes de ingerirlos. La serie OHLCV pública no elimina el riesgo de manipulación ni de trazabilidad del proveedor.
- Guardar snapshot crudo inmutable, fecha/hora UTC, proveedor, URL autorizada, parámetros, esquema, número de filas, periodo, versión del código y SHA-256. No sobrescribir snapshots; crear una nueva versión.
- Validar orden, duplicados, huecos, tipos, rangos, relaciones OHLC, volumen no negativo, zona horaria y valores extremos. Rechazar o poner en cuarentena datos que no pasen validación.
- Separar datos crudos, procesados, train/validation/test y artefactos. Un experimento registra el `dataset_version` exacto y no lee una fuente mutable durante la evaluación.
- `audit_log` es append-only desde la aplicación; un actor normal no puede editarlo ni borrarlo. Incluir `event_id`, `created_at` UTC, actor, rol, acción, entidad, `entity_id`, resultado, `request_id`, origen y metadatos mínimos redactados. No incluir contraseñas, tokens, payloads completos ni datos personales innecesarios.
- Definir retención y borrado por categoría: credenciales/sesiones según necesidad operativa; auditoría el mínimo requerido por la organización; datos de prueba y dumps temporales al terminar. Atender derechos de acceso/supresión cuando la jurisdicción lo exija, sin destruir evidencia de un incidente sin autorización.

### 4.6 ML, modelos predictivos y MLOps

- Tratar dataset, feature set, scaler, modelo, configuración, métricas y código como una cadena de procedencia. Cada artefacto tiene digest, propietario, origen, versión y relación con la corrida.
- Firmar o registrar el digest del snapshot, modelo y scaler; verificarlo antes de cargarlo. Solo cargar artefactos generados por el pipeline confiable y aprobados en MLflow. No cargar modelos recibidos de usuarios ni formatos que permitan ejecución arbitraria.
- Ejecutar entrenamiento e inferencia con usuario no privilegiado, filesystem de solo lectura salvo directorios de trabajo, red de salida allowlist y límites de recursos. Aislar notebooks y no usarlos como mecanismo de despliegue.
- Detectar poisoning y manipulación mediante validación de esquema, controles estadísticos, comparación con fuente independiente cuando sea posible, revisión de cambios, tests de distribución y registro de anomalías.
- El pipeline no puede modificar directamente el snapshot ni promover automáticamente un campeón sin pasar pruebas de calidad, integridad, no fuga temporal, reproducibilidad y revisión.
- Monitorizar deriva de datos, cambios de distribución, tasa de errores, valores fuera de rango, latencia, fallos de carga y discrepancia entre predicción y dato posterior. Ante anomalía: congelar promoción, marcar modelo, conservar evidencia y hacer rollback al último artefacto aprobado.
- Limitar consultas de predicción y exportaciones para reducir extracción de modelo. No publicar pesos, scalers, rutas internas ni hiperparámetros sensibles innecesarios.
- Amenazas específicas a verificar: data poisoning, model tampering, model extraction, adversarial inputs, supply-chain compromise y denegación de servicio. No se implementan agentes autónomos ni LLM; por tanto, los controles de prompt injection/agent tooling no aplican al alcance actual.

### 4.7 Dependencias, código, CI/CD e imágenes

- Fijar versiones en lockfiles y generar SBOM CycloneDX para backend, frontend e imágenes. Actualizar dependencias mediante PR revisada, no con cambios directos en producción.
- Ejecutar en CI: secret scanning, SAST, lint/tipos, tests, tests de no fuga, SCA (`pip-audit`/equivalente y `npm audit` o scanner superior), análisis de imagen y validación de SBOM.
- Fijar GitHub Actions por commit SHA cuando se use GitHub; restringir permisos del `GITHUB_TOKEN`, proteger ramas, exigir CI verde y revisión antes de fusionar.
- No permitir secretos en commits, imágenes, logs, artefactos, notebooks ni configuraciones de ejemplo. Si se detecta uno, revocar/rotar primero y después limpiar el historial con procedimiento aprobado.
- Construir imágenes mínimas, reproducibles y con digest; ejecutar como usuario no root, sin `--privileged`, sin capabilities innecesarias, sin montar `/var/run/docker.sock`, con filesystem de solo lectura cuando sea posible, `seccomp` y límites de recursos.
- Preferir Docker rootless o `userns-remap`; separar redes de frontend, backend, worker y datos. Aplicar egress mínimo desde el worker y no usar el daemon Docker desde la API.
- Publicar solo imágenes y artifacts que pasen escaneo y provenance. Conservar SBOM, digest, commit y pipeline que produjo cada release.

### 4.8 Observabilidad y detección

- Logs estructurados en JSON con `request_id`, servicio, ruta, resultado, latencia, actor pseudonimizado y severidad. Sin contraseñas, JWT, refresh tokens, claves, cookies, payloads completos ni datos sensibles.
- Alertar sobre: ráfagas de login fallido, cambios de rol, alta de admin, uso de endpoints administrativos, fallos repetidos de autorización, SSRF bloqueado, cambios de checksum, promoción/rollback de modelo, error de integridad, crecimiento anómalo de jobs y acceso a backups.
- Sincronizar relojes en UTC. Proteger el almacenamiento de logs contra edición por el servicio observado y restringir su lectura.
- Revisar alertas al menos diariamente en preproducción/producción; probar una alerta y una restauración de evidencia en cada ciclo trimestral.

## 5. Protocolos operativos

### 5.1 Protocolo de desarrollo seguro

1. Registrar requisito, activo afectado, amenaza y control `SEC-*` antes de implementar.
2. Revisar autorización, validación, secretos, logs y errores en cada PR.
3. Ejecutar CI completo; ninguna vulnerabilidad crítica o alta conocida puede entrar en `main` sin excepción aprobada, justificación, mitigación y fecha de caducidad.
4. Ejecutar DAST con OWASP ZAP o equivalente en staging y pruebas negativas de RBAC/BOLA antes de exponer la API.
5. Documentar evidencia: commit, workflow, SBOM, scan, test y aprobadores.

### 5.2 Protocolo de release

1. Confirmar lockfiles, SBOM, digest de imágenes, migraciones revisadas y backup reciente.
2. Verificar secretos de producción mediante el gestor autorizado; nunca copiar `.env` del desarrollador.
3. Ejecutar smoke tests HTTPS, autenticación, autorización por rol, health check mínimo y rollback.
4. Publicar release con commit/tag inmutable, changelog de seguridad y ventana de observación.
5. Si falla integridad, seguridad, disponibilidad o aviso legal, detener la promoción y volver al último release aprobado.

### 5.3 Protocolo de ingesta y promoción ML

1. Descargar solo desde fuente aprobada y por HTTPS.
2. Crear snapshot y manifest inmutables.
3. Validar esquema, fechas, valores, duplicados, anomalías y checksum.
4. Poner datos sospechosos en cuarentena; no "corregir" silenciosamente.
5. Entrenar solo con snapshot versionado; registrar configuración, semillas, código y artefactos.
6. Pasar gates de no fuga, reproducibilidad, integridad y calidad.
7. Promover campeón por validación, nunca por test; registrar aprobación y plan de rollback.

### 5.4 Protocolo de accesos y offboarding

- Revisar usuarios, roles, MFA y sesiones mensualmente y al cambiar de equipo.
- Dar acceso por tarea y por tiempo limitado; separar cuenta personal de cuenta de servicio.
- Revocar inmediatamente accesos, tokens, SSH, OAuth y claves ante baja o sospecha de compromiso.
- Revisar cuentas de servicio, secretos, jobs programados y permisos de artifacts después de cada cambio de infraestructura.

## 6. Respuesta a incidentes

El equipo sigue las fases de NIST SP 800-61 Rev. 3: preparar; detectar y analizar; responder; recuperar; y mejorar con lecciones aprendidas.

### 6.1 Severidad y objetivo de atención

| Severidad | Ejemplos | Acción inicial objetivo |
|---|---|---|
| P0 crítica | Secreto/JWT de producción expuesto, ejecución remota, alteración masiva de modelos/datos | Inmediata; detener exposición y rotar credenciales |
| P1 alta | Escalada de privilegio, acceso a datos confidenciales, poisoning confirmado, API gravemente indisponible | ≤ 4 h |
| P2 media | Vulnerabilidad explotable con mitigación, abuso limitado, fallo de control no explotado | ≤ 1 día hábil |
| P3 baja | Hallazgo documental, hardening pendiente o defecto sin impacto demostrable | Próximo ciclo |

### 6.2 Runbook

1. **Detectar y declarar:** registrar `incident_id`, hora UTC, alertas, personas y alcance; no borrar ni alterar evidencia.
2. **Contener:** revocar sesiones, rotar secretos, desactivar cuenta/endpoint, aislar worker o bloquear egress; preservar una copia forense autorizada.
3. **Analizar:** identificar vector, activos, periodo, usuarios afectados, cambios en DB/artifacts y si el ataque continúa.
4. **Erradicar:** corregir código/configuración, retirar dependencia o imagen comprometida, limpiar persistencia y regenerar artefactos desde fuente confiable.
5. **Recuperar:** restaurar backup verificado, desplegar release limpio, comprobar checksums, permisos, logs y monitoreo reforzado.
6. **Comunicar:** informar a responsables y afectados según contrato y legislación; no publicar detalles explotables antes de tener mitigación.
7. **Cerrar y mejorar:** informe de causa raíz, impacto, controles fallidos, evidencias, acciones, responsables, fecha límite y prueba de eficacia.

### 6.3 Casos especiales

- **Secreto expuesto:** revocar primero, rotar dependencias, buscar uso en logs, revisar commits/artifacts y registrar alcance.
- **Modelo o dataset alterado:** congelar promociones, marcar resultados no confiables, comparar digests, reconstruir desde snapshot/proveedor aprobado y reevaluar experimentos afectados.
- **Cuenta admin comprometida:** invalidar sesiones, bloquear acceso administrativo, exigir MFA/recovery, auditar cambios de usuarios, roles, fuentes y campeones.
- **Ransomware o pérdida de DB:** aislar, no pagar ni borrar evidencia, validar backup offline/seguro, restaurar en entorno limpio y hacer análisis de integridad.

## 7. Gestión continua y matriz de controles

El estado permitido es: `Pendiente`, `En progreso`, `Implementado`, `Verificado` o `Excepción`. Cada control requiere evidencia y responsable; una excepción debe contener riesgo residual, mitigación, aprobador y fecha de expiración.

| ID | Control verificable | Evidencia mínima | Frecuencia |
|---|---|---|---|
| SEC-001 | Threat model y activos actualizados | Registro de amenazas | Cada cambio mayor |
| SEC-002 | ASVS/API Top 10 trazados a tests | Matriz + tests | Cada release |
| SEC-003 | RBAC y autorización por objeto | Tests 401/403/IDOR | Cada PR |
| SEC-004 | Argon2id, MFA admin y sesiones revocables | Configuración + test | Cada release |
| SEC-005 | HTTPS, headers, CORS y CSRF | Scan staging | Cada release |
| SEC-006 | Rate limit y límites de recursos | Test 429/benchmark | Cada release |
| SEC-007 | SSRF/egress de fuentes externas | Allowlist + tests | Cada cambio de fuente |
| SEC-008 | SQL parametrizado y migraciones revisadas | SAST + PR | Cada PR |
| SEC-009 | Auditoría append-only y logs sin secretos | Test + muestra redactada | Mensual |
| SEC-010 | DB/Redis/MLflow privados y con mínimo privilegio | Revisión de red/roles | Mensual |
| SEC-011 | Backups cifrados y restaurables | Acta de restore | Trimestral |
| SEC-012 | Snapshots y artefactos con digest/procedencia | Manifest + hash | Cada ingesta/corrida |
| SEC-013 | Gates de data quality, no fuga y poisoning | Reporte pipeline | Cada corrida |
| SEC-014 | Modelo solo desde registry aprobado | ACL + aprobación | Cada promoción |
| SEC-015 | Dependencias, SBOM y secretos escaneados | CI artifacts | Cada PR/release |
| SEC-016 | Imágenes non-root, sin privileged y escaneadas | Scan + config | Cada build |
| SEC-017 | DAST y pruebas negativas de API | Informe staging | Cada release |
| SEC-018 | Alertas de seguridad probadas | Evento/alarma | Trimestral |
| SEC-019 | Runbook de incidente ejercitado | Simulacro | Semestral |
| SEC-020 | Revisión de accesos y excepciones | Acta firmada | Mensual/trimestral |

### 7.1 Métricas de gestión

Medir por ciclo: porcentaje de controles `Verificado`, vulnerabilidades abiertas por severidad y antigüedad, tiempo de rotación de secreto, cobertura de endpoints con pruebas 401/403, porcentaje de artefactos con manifest/digest, éxito de restauración, MTTD/MTTR y experimentos con procedencia completa. Las metas se fijan al comenzar la operación; no inventar resultados antes de medirlos.

## 8. Checklist de definición de hecho

- [ ] No hay secretos reales en repositorio, imagen, notebook, logs ni artifact.
- [ ] Todos los endpoints tienen autenticación/autorización o justificación pública.
- [ ] Existen pruebas de BOLA, escalada de rol, rate limit, SSRF, inyección, XSS y CSRF cuando corresponda.
- [ ] HTTPS, headers, CORS, límites, timeouts y errores seguros están activos.
- [ ] DB, Redis, MLflow y puertos de administración no están expuestos públicamente.
- [ ] CI genera SBOM y ejecuta SAST, SCA, secret scanning, tests y scan de imagen.
- [ ] Snapshots, modelos y scalers tienen procedencia, digest y rollback.
- [ ] Backup y restauración fueron probados; los runbooks tienen dueño.
- [ ] Logs/auditoría no contienen secretos y las alertas críticas llegan al responsable.
- [ ] El aviso legal sigue visible en UI, API, reportes y documentación.

## 9. Registro de excepciones

Usar esta plantilla en un issue o documento controlado; nunca aprobar una excepción verbalmente:

```text
ID: SEC-EX-YYYY-NNN
Control afectado:
Descripción y motivo:
Activo/amenaza:
Riesgo residual:
Mitigación temporal:
Responsable:
Aprobador:
Fecha de aprobación:
Fecha de expiración:
Evidencia y plan de cierre:
Estado: abierta | cerrada
```

## 10. Fuentes normativas y técnicas

- [NIST Cybersecurity Framework 2.0](https://www.nist.gov/publications/nist-cybersecurity-framework-csf-20)
- [OWASP Application Security Verification Standard 5.0.0](https://owasp.org/projects/asvs)
- [OWASP Top 10 2025](https://owasp.org/projects/top-ten)
- [OWASP API Security Top 10 2023](https://owasp.org/API-Security/)
- [NIST SP 800-63B-4 — Authentication and Authenticator Management](https://csrc.nist.gov/pubs/sp/800/63/B/4/final)
- [NIST SP 800-218 — Secure Software Development Framework](https://csrc.nist.gov/pubs/sp/800/218/final)
- [NIST SP 800-61 Rev. 3 — Incident Response](https://csrc.nist.gov/pubs/sp/800/61/r3/final)
- [NIST SP 800-161 Rev. 1 — Supply Chain Risk Management](https://csrc.nist.gov/pubs/sp/800/161/r1/upd1/final)
- [NIST AI Risk Management Framework 1.0](https://www.nist.gov/itl/ai-risk-management-framework)
- [MITRE ATLAS](https://atlas.mitre.org/)
- [OWASP Machine Learning Security Top Ten](https://owasp.org/projects/machine-learning-security-top-ten)
- [OWASP Password Storage Cheat Sheet](https://cheatsheetseries.owasp.org/cheatsheets/Password_Storage_Cheat_Sheet.html)
- [Docker Engine security](https://docs.docker.com/engine/security/)
- [SLSA specification](https://slsa.dev/spec/v1.0/)

Las versiones de los marcos se revisan cada trimestre. Cuando una fuente publique una nueva versión, se actualizan esta matriz, los requisitos, los tests y el registro de cambios; no se cambia silenciosamente la versión de referencia.
