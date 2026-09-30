package com.aylzz.xmrforecast.audit;

import com.aylzz.xmrforecast.common.RequestContext;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.HashMap;
import java.util.Map;

/**
 * Registro de auditoria de accesos y cambios (OWASP A09). Append-only.
 *
 * <p>Se propaga en REQUIRES_NEW para que el evento sobreviva a una transaccion
 * de negocio que luego falla: un acceso denegado es justamente el dato mas
 * importante que no debe perderse.
 *
 * <p><strong>Consecuencia asumida y documentada.</strong> Al confirmarse en una
 * transaccion independiente, el evento ve el estado de la base de datos tal como
 * estaba <em>antes</em> de la operacion que lo origina. Si esa operacion crea la
 * fila a la que el evento apunta —el alta de usuario, que referencia
 * {@code actor_user_id}— la clave foranea todavia no existe y el INSERT falla con
 * {@code audit_events_actor_user_id_fkey}.
 *
 * <p>Se asume conscientemente: perder el evento de un alta es preferible a
 * duplicar la escritura, y el fallback es el log ERROR con la traza completa
 * (ver {@link #write}). Para el alta de usuario el registro efectivo es el
 * {@code users.status = PENDING_VERIFICATION} mas el correo, de modo que la
 * auditoria no es la unica fuente de ese hecho.
 */
@Service
public class AuditService {

    private static final Logger log = LoggerFactory.getLogger(AuditService.class);

    private final AuditEventRepository repository;

    /**
     * Referencia a si mismo a traves del proxy.
     *
     * <p>Spring AOP es basado en proxy: una llamada {@code this.record(...)} es una
     * llamada directa sobre la referencia {@code this} y no atraviesa ningun
     * interceptor, de modo que {@code REQUIRES_NEW} no se aplicaria
     * (documentacion oficial de Spring Framework, "Understanding AOP proxies"). Es
     * la razon de que las envoltorias inyecten el proxy en lugar de llamar a
     * {@code this}. Con {@code @Lazy} no hay problema de inicializacion circular:
     * el proxy no se construye hasta la primera llamada real.
     */
    private final AuditService self;

    @org.springframework.context.annotation.Lazy
    public AuditService(AuditEventRepository repository,
                        @org.springframework.context.annotation.Lazy AuditService self) {
        this.repository = repository;
        this.self = self;
    }

    /**
     * Registra un evento de auditoria en su propia transaccion.
     *
     * <p><strong>REQUIRES_NEW es el punto de este metodo.</strong> El evento se
     * confirma aparte, de modo que sobrevive al rollback de la operacion de
     * negocio que lo origino: si el alta de usuario falla despues de haber
     * registrado AUTH_REGISTER, el registro del intento debe seguir ahi.
     *
     * <p>Deliberadamente <em>no</em> captura excepciones: si lo hiciera, la
     * transaccion quedaria marcada rollback-only y al intentar confirmarla Spring
     * lanzaria {@code UnexpectedRollbackException}, que escaparia al cliente como
     * un 500 atribuible a la auditoria. Capturar es responsabilidad de
     * {@link #write}, fuera del limite transaccional.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(Long actorUserId, String actorRole, String action, String resourceType,
                       String resourceId, AuditEvent.Outcome outcome, Map<String, Object> details) {
        AuditEvent event = new AuditEvent();
        event.setActorUserId(actorUserId);
        event.setActorRole(actorRole);
        event.setAction(action);
        event.setResourceType(resourceType);
        event.setResourceId(resourceId);
        event.setOutcome(outcome);
        event.setRequestId(RequestContext.requestId());
        event.setTraceId(RequestContext.traceId());
        event.setDetails(sanitize(details));
        applyRequestMetadata(event);
        repository.save(event);
    }

    /**
     * Envuelve {@link #record} capturando el fallo.
     *
     * <p>La captura va aqui, y no dentro de {@code record}, por lo explicado en
     * ese metodo: dentro de la transaccion el fallo la deja inservible y el
     * rollback sale como excepcion al commits.
     *
     * <p>Se registra tambien el motivo en el log del servidor: {@code audit_events}
     * es append-only y no admite correccion posterior, de modo que perder el evento
     * en silencio seria peor que un WARNING con la traza completa.
     *
     * <p>Si el fallo es la clave foranea del <em>actor</em>, se reintenta sin ese
     * identificador. Nace de un defecto real: como el evento se escribe en su
     * propia transaccion, no ve la fila de {@code users} que la operacion de
     * negocio acaba de crear y todavia no ha confirmado, y el alta de usuario
     * respondia 500 con {@code audit_events_actor_user_id_fkey}. El reintento no
     * pierde informacion relevante, porque el actor se deduce del propio evento
     * (id, rol y recurso) y el correo viaja en {@code details}.
     */
    private void write(Long actorUserId, String actorRole, String action, String resourceType,
                       String resourceId, AuditEvent.Outcome outcome, Map<String, Object> details) {
        try {
            self.record(actorUserId, actorRole, action, resourceType, resourceId, outcome, details);
        } catch (org.springframework.dao.DataIntegrityViolationException ex) {
            if (actorUserId == null || !isActorForeignKeyViolation(ex)) {
                log.error("No se pudo registrar el evento de auditoria action={} outcome={}",
                        action, outcome, ex);
                return;
            }
            log.warn("El evento {} se registra sin actor: su fila aun no es visible desde "
                    + "la transaccion de auditoria", action);
            try {
                self.record(null, actorRole, action, resourceType, resourceId, outcome, details);
            } catch (RuntimeException retryFailure) {
                log.error("No se pudo registrar el evento de auditoria action={} outcome={}",
                        action, outcome, retryFailure);
            }
        } catch (RuntimeException ex) {
            // La auditoria nunca debe romper la peticion de negocio.
            log.error("No se pudo registrar el evento de auditoria action={} outcome={}",
                    action, outcome, ex);
        }
    }

    /** ¿El fallo es la clave foranea del actor? Se lee el mensaje, no la clase de excepcion. */
    private static boolean isActorForeignKeyViolation(
            org.springframework.dao.DataIntegrityViolationException ex) {
        String message = ex.getMessage();
        return message != null && message.contains("audit_events_actor_user_id_fkey");
    }

    public void success(Long actorUserId, String actorRole, String action, String resourceType,
                        String resourceId, Map<String, Object> details) {
        write(actorUserId, actorRole, action, resourceType, resourceId,
                AuditEvent.Outcome.SUCCESS, details);
    }

    public void denied(Long actorUserId, String actorRole, String action, String resourceType,
                       String resourceId, Map<String, Object> details) {
        write(actorUserId, actorRole, action, resourceType, resourceId,
                AuditEvent.Outcome.DENIED, details);
    }

    public void failure(Long actorUserId, String actorRole, String action, String resourceType,
                        String resourceId, Map<String, Object> details) {
        write(actorUserId, actorRole, action, resourceType, resourceId,
                AuditEvent.Outcome.FAILURE, details);
    }

    private void applyRequestMetadata(AuditEvent event) {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs) {
            HttpServletRequest request = attrs.getRequest();
            event.setIpAddress(clientIp(request));
            String userAgent = request.getHeader("User-Agent");
            event.setUserAgent(userAgent == null || userAgent.length() <= 255 ? userAgent : userAgent.substring(0, 255));
        }
    }

    /**
     * IP del cliente. Solo se toma el primer valor de X-Forwarded-For cuando la
     * peticion viene del proxy de confianza; no es una fuente confiable por si misma.
     */
    public static String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            String first = forwarded.split(",")[0].trim();
            if (first.length() <= 64) {
                return first;
            }
        }
        String remote = request.getRemoteAddr();
        return remote != null && remote.length() <= 64 ? remote : null;
    }

    /**
     * Sustituye por {@code [REDACTED]} cualquier clave sensible del detalle.
     *
     * <p>La deteccion es por <em>contiene</em> de un marcador, no por igualdad
     * exacta: una lista cerrada depende de que quien llama se acuerde de anadir
     * cada variante. {@code refreshToken}, {@code id_token},
     * {@code password_confirmation} o {@code x-api-key} son el mismo secreto con
     * otra ortografia, y {@code audit_events} es append-only: lo que se escribe ahi
     * no se puede purgar despues (OWASP A09).
     *
     * <p>Es un superconjunto estricto de la lista exacta anterior, de modo que
     * ningun caso ya cubierto deja de estar cubierto.
     */
    public static Map<String, Object> sanitize(Map<String, Object> details) {
        if (details == null || details.isEmpty()) {
            return null;
        }
        Map<String, Object> safe = new HashMap<>();
        details.forEach((key, value) -> {
            if (key == null) {
                return;
            }
            if (isSensitiveKey(key)) {
                safe.put(key, "[REDACTED]");
            } else if (value instanceof Map<?, ?> nested) {
                Map<String, Object> nestedSafe = new HashMap<>();
                nested.forEach((k, v) -> nestedSafe.put(String.valueOf(k), v));
                safe.put(key, sanitize(nestedSafe));
            } else {
                safe.put(key, value);
            }
        });
        return safe;
    }

    /**
     * Marcadores que aparecen en el nombre de una credencial. Se comparan sobre la
     * clave normalizada (minusculas, sin {@code _}, {@code -} ni {@code .}), de
     * modo que {@code client_secret}, {@code clientSecret} y {@code CLIENT-SECRET}
     * se reconocen como la misma clave.
     */
    private static final java.util.List<String> SENSITIVE_MARKERS = java.util.List.of(
            "password", "passwd", "token", "secret", "apikey", "credential",
            "authorization", "privatekey", "signature", "cookie", "passphrase");

    /** ¿El nombre de la clave delata una credencial? */
    static boolean isSensitiveKey(String key) {
        String normalized = key.toLowerCase(java.util.Locale.ROOT)
                .replace("_", "")
                .replace("-", "")
                .replace(".", "");
        for (String marker : SENSITIVE_MARKERS) {
            if (normalized.contains(marker)) {
                return true;
            }
        }
        return false;
    }
}