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
 */
@Service
public class AuditService {

    private static final Logger log = LoggerFactory.getLogger(AuditService.class);

    /**
     * Claves que nunca deben terminar en la tabla de auditoria, aunque el
     * llamador intente incluirlas (defensa en profundidad frente a R-14).
     */
    private static final java.util.Set<String> REDACTED_KEYS = java.util.Set.of(
            "password", "newpassword", "currentpassword", "passwordhash", "token",
            "accesstoken", "refreshtoken", "secret", "clientsecret", "authorization",
            "jwt", "jwtsecret", "apikey", "credential", "credentials");

    private final AuditEventRepository repository;

    public AuditService(AuditEventRepository repository) {
        this.repository = repository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(Long actorUserId, String actorRole, String action, String resourceType,
                       String resourceId, AuditEvent.Outcome outcome, Map<String, Object> details) {
        try {
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
        } catch (RuntimeException ex) {
            // La auditoria nunca debe romper la peticion de negocio.
            log.error("No se pudo registrar el evento de auditoria action={}", action, ex);
        }
    }

    public void success(Long actorUserId, String actorRole, String action, String resourceType,
                        String resourceId, Map<String, Object> details) {
        record(actorUserId, actorRole, action, resourceType, resourceId,
                AuditEvent.Outcome.SUCCESS, details);
    }

    public void denied(Long actorUserId, String actorRole, String action, String resourceType,
                       String resourceId, Map<String, Object> details) {
        record(actorUserId, actorRole, action, resourceType, resourceId,
                AuditEvent.Outcome.DENIED, details);
    }

    public void failure(Long actorUserId, String actorRole, String action, String resourceType,
                        String resourceId, Map<String, Object> details) {
        record(actorUserId, actorRole, action, resourceType, resourceId,
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

    /** Sustituye por {@code [REDACTED]} cualquier clave sensible del detalle. */
    public static Map<String, Object> sanitize(Map<String, Object> details) {
        if (details == null || details.isEmpty()) {
            return null;
        }
        Map<String, Object> safe = new HashMap<>();
        details.forEach((key, value) -> {
            if (key == null) {
                return;
            }
            if (REDACTED_KEYS.contains(key.toLowerCase(java.util.Locale.ROOT))) {
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
}