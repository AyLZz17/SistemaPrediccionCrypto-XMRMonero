# XMR-Forecast — Protocolo integral de seguridad

> **Estado:** baseline de diseño para desarrollo y preproducción  
> **Versión:** 3.0 · **Fecha:** 2026-09-30  
> **Propietario:** responsable técnico (`admin`) · **Revisión mínima:** trimestral y ante cada cambio de arquitectura, dependencia crítica o incidente.

---

## 1. Marcos y protocolos de referencia

| Área | Marco/protocolo | Uso en este proyecto | Nivel |
|---|---|---|---|
| Gobierno | NIST CSF 2.0 | Riesgos, responsables, métricas, revisión y mejora | Obligatorio |
| Aplicación web | OWASP ASVS 5.0.0 + OWASP Top 10 2025 | Requisitos, revisión de código y pruebas | Obligatorio |
| API REST | OWASP API Security Top 10 2023 | Autorización por objeto/función, límites, SSRF e inventario | Obligatorio |
| Identidad | NIST SP 800-63B-4 | Autenticadores, sesiones y recuperación | Obligatorio |
| Desarrollo | NIST SP 800-218 SSDF | Seguridad desde requisitos hasta publicación | Obligatorio |
| Incidentes | NIST SP 800-61 Rev. 3 | Preparación, triage, contención, recuperación y lecciones aprendidas | Obligatorio |
| Cadena de suministro | NIST SP 800-161 Rev. 1, SBOM y SLSA | Dependencias, imágenes, acciones CI y artefactos | Obligatorio |
| ML/IA | NIST AI RMF 1.0, MITRE ATLAS y OWASP ML Security Top 10 | Integridad de datos/modelos, poisoning, extracción y abuso de inferencia | Obligatorio para `ml/` |
| Contenedores | Docker Engine Security, modo rootless cuando sea posible | Aislamiento, privilegios, red y filesystem | Obligatorio en despliegue |

---

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
| A-01 | Contraseñas, JWT y secretos | Restringido | Toma de cuenta |
| A-02 | Roles, permisos y registro de auditoría | Confidencial | Escalada o pérdida de evidencia |
| A-03 | PostgreSQL y backups | Confidencial | Exfiltración o manipulación |
| A-04 | Redis y caché | Interno/confidencial | Ejecución, replay o indisponibilidad |
| A-05 | Snapshots OHLCV, manifests y checksums | Integridad crítica | Data poisoning |
| A-06 | Modelos, scalers, configuraciones y MLflow | Confidencial | Sustitución, extracción o carga de código no confiable |
| A-07 | Código, dependencias, imágenes y workflows CI | Integridad crítica | Compromiso de la cadena de suministro |
| A-08 | Exportaciones CSV/PDF y logs | Interno; confidencial si incluyen identificadores | Inyección, fuga o retención excesiva |
| A-09 | Documentación, métricas agregadas y OHLCV publicado | Público | Manipulación de confianza o reputación |

---

## 3. Modelo de amenazas y abuso previsto

| Amenaza | Superficie | Control principal |
|---|---|---|
| BOLA/IDOR: consultar o modificar otro objeto | `/experiments/{id}`, `/runs/{id}`, `/datasets` | Autorización server-side por objeto y rol; pruebas negativas |
| Escalada de función | Ingesta, ejecución, campeón, usuarios | RBAC explícito; `admin` solo para administración; deny-by-default |
| Credential stuffing/brute force | `/auth/login` | BCrypt, rate limit, MFA de admin, errores genéricos y alertas |
| SSRF | `data_source.base_url` e ingesta | Allowlist de hosts/protocolos, DNS/IP validation, egress limitado, timeout |
| Inyección | JSON, filtros, exportaciones | Validación estricta, SQL parametrizado, no shell con input de usuario |
| Consumo ilimitado | Exportaciones, predicción, training jobs | Cuotas, paginación, límites de tamaño, idempotencia y colas |
| XSS/CSRF/clickjacking | SPA, reportes y cookies | CSP, escape contextual, headers, SameSite y CSRF si hay cookies |
| Dependencia o imagen maliciosa | Python, npm, Docker, GitHub Actions | Lockfile, SBOM, SCA, firma/provenance, scanners y revisión |
| Alteración o poisoning del dataset | Fuente externa, CSV, snapshot | Validación de esquema, anomalías, snapshot inmutable, checksum, aprobación |
| Sustitución de modelo/scaler | MLflow, artifacts, worker | ACL, digest, solo artefactos aprobados, registro y rollback |
| Exposición de Redis/MLflow/DB | Red y puertos | Red privada, autenticación, TLS cuando aplique, sin puertos públicos |

En el despliegue local reproducible, Spring Boot atiende por HTTPS en `8443` y redirige `8080`; FastAPI-ML atiende por HTTPS en `8000` y MLflow por HTTPS en `5000`. PostgreSQL y Redis permanecen en la red privada de Docker. Los certificados autofirmados solo están permitidos para desarrollo local; nunca se debe desactivar la validación TLS en producción.
| Pérdida de disponibilidad | API, DB, worker o proveedor | Health checks, límites, retry con backoff, backup y recuperación |

---

## 4. Controles obligatorios por capa

### 4.1 Identidad, autenticación y sesiones

- Todos los accesos de producción se realizan por HTTPS; TLS 1.3 es la opción preferida.
- Usar JWT de acceso de corta duración (10 minutos) y, si se necesitan sesiones prolongadas, refresh token rotatorio y revocable.
- Validar firma, algoritmo permitido, `iss`, `aud`, `sub`, `iat`, `exp` y `jti`.
- Contraseñas con **BCrypt** (o Argon2id) y salt único por contraseña.
- Login con respuesta genérica para no revelar si existe un correo. Rate limit por IP y por cuenta.
- MFA obligatorio para `admin` en producción; WebAuthn/FIDO2 es preferible y TOTP es el mínimo aceptable.
- Aplicar autorización en cada request, nunca confiar en el rol enviado por el cliente.
- Revocar sesiones al desactivar usuario, cambiar contraseña o detectar compromiso.

### 4.2 API REST (Spring Boot)

- Mantener OpenAPI y rutas bajo `/api/v1`; retirar endpoints no usados.
- Definir esquemas de entrada y salida con allowlist, tipos estrictos, límites de longitud/rango.
- Implementar rate limiting diferenciado: login, exportación, ingesta, creación de experimentos, predicción.
- Aplicar CORS solo a orígenes conocidos; nunca `*` con credenciales.
- Añadir `Content-Security-Policy`, `Strict-Transport-Security`, `X-Content-Type-Options: nosniff`, `Referrer-Policy`.
- Usar consultas parametrizadas mediante JPA; no concatenar SQL.
- Cada endpoint debe declarar rol requerido y comprobar el objeto solicitado.
- Configurar timeouts, límites de concurrencia, tamaño máximo de body y respuesta.

### 4.3 Servicio FastAPI-ML

- **No es accesible públicamente** (solo red interna de Spring Boot).
- Validar todas las entradas con Pydantic.
- Verificar integridad de artefactos por digest antes de cargar modelos.
- No aceptar rutas arbitrarias ni URLs externas no autorizadas.
- Implementar health checks, `request_id`, timeouts y límites de tamaño.
- No realizar llamadas de red reales en tests.

### 4.4 Frontend React y navegador

- Usar escape contextual de React; prohibir `dangerouslySetInnerHTML`.
- No poner secretos ni JWT de larga duración en el bundle.
- CSP sin `unsafe-eval`.
- Mostrar aviso legal, origen/fecha de datos, estado de modelo y limitaciones.

### 4.5 PostgreSQL, Redis y MLflow

- PostgreSQL, Redis y MLflow se exponen solo en la red privada de servicios.
- Usar usuarios separados y privilegios mínimos.
- Cifrar conexiones y backups donde el despliegue lo permita.
- Migraciones solo por Flyway, revisadas y probadas contra PostgreSQL real.
- Redis debe exigir autenticación y no almacenar secretos en claro.

### 4.6 ML, modelos predictivos y MLOps

- Tratar dataset, feature set, scaler, modelo, configuración, métricas y código como una cadena de procedencia.
- Firmar o registrar el digest del snapshot, modelo y scaler; verificarlo antes de cargarlo.
- Solo cargar artefactos generados por el pipeline confiable y aprobados en MLflow.
- Detectar poisoning y manipulación mediante validación de esquema y controles estadísticos.
- Monitorizar deriva de datos, cambios de distribución y tasa de errores.

### 4.7 Dependencias, código, CI/CD e imágenes

- Fijar versiones en lockfiles y generar SBOM CycloneDX.
- Ejecutar en CI: secret scanning, SAST, lint/tipos, tests, tests de no fuga, SCA.
- Construir imágenes mínimas, reproducibles y con digest; ejecutar como usuario no root.

### 4.8 Observabilidad y detección

- Logs estructurados en JSON con `request_id`, servicio, ruta, resultado, latencia y actor pseudonimizado.
- Alertar sobre: ráfagas de login fallido, cambios de rol, alta de admin, fallos repetidos de autorización.

---

## 5. Protocolos operativos

### 5.1 Protocolo de desarrollo seguro

1. Registrar requisito, activo afectado, amenaza y control `SEC-*` antes de implementar.
2. Revisar autorización, validación, secretos, logs y errores en cada PR.
3. Ejecutar CI completo; ninguna vulnerabilidad crítica o alta conocida puede entrar en `main`.
4. Ejecutar DAST con OWASP ZAP o equivalente en staging.
5. Documentar evidencia: commit, workflow, SBOM, scan, test y aprobadores.

### 5.2 Protocolo de release

1. Confirmar lockfiles, SBOM, digest de imágenes, migraciones revisadas y backup reciente.
2. Verificar secretos de producción mediante el gestor autorizado.
3. Ejecutar smoke tests HTTPS, autenticación, autorización por rol, health check mínimo y rollback.
4. Publicar release con commit/tag inmutable, changelog de seguridad y ventana de observación.

### 5.3 Protocolo de ingesta y promoción ML

1. Descargar solo desde fuente aprobada y por HTTPS.
2. Crear snapshot y manifest inmutables.
3. Validar esquema, fechas, valores, duplicados, anomalías y checksum.
4. Entrenar solo con snapshot versionado; registrar configuración, semillas, código y artefactos.
5. Pasar gates de no fuga, reproducibilidad, integridad y calidad.
6. Promover campeón por validación, nunca por test; registrar aprobación y plan de rollback.

---

## 6. Respuesta a incidentes

El equipo sigue las fases de NIST SP 800-61 Rev. 3: preparar; detectar y analizar; responder; recuperar; y mejorar.

### 6.1 Severidad y objetivo de atención

| Severidad | Ejemplos | Acción inicial objetivo |
|---|---|---|
| P0 crítica | Secreto/JWT de producción expuesto, ejecución remota, alteración masiva | Inmediata; detener exposición y rotar credenciales |
| P1 alta | Escalada de privilegio, acceso a datos confidenciales, poisoning confirmado | ≤ 4 h |
| P2 media | Vulnerabilidad explotable con mitigación, abuso limitado | ≤ 1 día hábil |
| P3 baja | Hallazgo documental, hardening pendiente | Próximo ciclo |

### 6.2 Runbook

1. **Detectar y declarar:** registrar `incident_id`, hora UTC, alertas, personas y alcance.
2. **Contener:** revocar sesiones, rotar secretos, desactivar cuenta/endpoint, aislar worker.
3. **Analizar:** identificar vector, activos, periodo, usuarios afectados.
4. **Erradicar:** corregir código/configuración, retirar dependencia o imagen comprometida.
5. **Recuperar:** restaurar backup verificado, desplegar release limpio, comprobar checksums.
6. **Comunicar:** informar a responsables y afectados según contrato y legislación.
7. **Cerrar y mejorar:** informe de causa raíz, impacto, controles fallidos, evidencias, acciones.

---

## 7. Gestión continua y matriz de controles

| ID | Control verificable | Evidencia mínima | Frecuencia |
|---|---|---|---|
| SEC-001 | Threat model y activos actualizados | Registro de amenazas | Cada cambio mayor |
| SEC-002 | ASVS/API Top 10 trazados a tests | Matriz + tests | Cada release |
| SEC-003 | RBAC y autorización por objeto | Tests 401/403/IDOR | Cada PR |
| SEC-004 | BCrypt, MFA admin y sesiones revocables | Configuración + test | Cada release |
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

---

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

---

## 9. Registro de excepciones

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

---

## 10. Fuentes normativas y técnicas

- [NIST Cybersecurity Framework 2.0](https://www.nist.gov/publications/nist-cybersecurity-framework-csf-20)
- [OWASP Application Security Verification Standard 5.0.0](https://owasp.org/projects/asvs)
- [OWASP Top 10 2025](https://owasp.org/projects/top-ten)
- [OWASP API Security Top 10 2023](https://owasp.org/API-Security/)
- [NIST SP 800-63B-4 — Authentication and Authenticator Management](https://csrc.nist.gov/pubs/sp/800/63/B/4/final)
- [NIST SP 800-218 — Secure Software Development Framework](https://csrc.nist.gov/pubs/sp/800/218/final)
- [NIST SP 800-61 Rev. 3 — Incident Response](https://csrc.nist.gov/pubs/sp/800/61/r3/final)
- [NIST AI Risk Management Framework 1.0](https://www.nist.gov/itl/ai-risk-management-framework)
- [MITRE ATLAS](https://atlas.mitre.org/)
- [OWASP Machine Learning Security Top Ten](https://owasp.org/projects/machine-learning-security-top-ten)
- [Docker Engine security](https://docs.docker.com/engine/security/)
- [SLSA specification](https://slsa.dev/spec/v1.0/)
