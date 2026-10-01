package com.aylzz.xmrforecast.security;

import com.aylzz.xmrforecast.common.RequestContext;
import com.aylzz.xmrforecast.config.AppProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Duration;

/**
 * Rate limiting distribuido sobre Redis (ventana fija).
 *
 * <p>Dos politicas distintas segun el riesgo: el login lleva un limite muy bajo
 * porque es el objetivo de un ataque de credenciales; el resto de la API lleva un
 * limite alto deThroughput normal. Al superar el limite se responde 429 con la
 * cabecera {@code Retry-After}.
 *
 * <p>Si Redis no esta disponible se deja pasar la peticion: un fallo de cache no
 * debe convertir la aplicacion en un denial of service total.
 */
@Component
public class RateLimitFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RateLimitFilter.class);
    private static final String KEY_PREFIX = "ratelimit:";
    private static final Duration WINDOW = Duration.ofMinutes(1);

    private final StringRedisTemplate redis;
    private final AppProperties properties;
    private final ObjectMapper objectMapper;

    public RateLimitFilter(StringRedisTemplate redis, AppProperties properties, ObjectMapper objectMapper) {
        this.redis = redis;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        if (!properties.security().rateLimit().enabled()) {
            chain.doFilter(request, response);
            return;
        }

        boolean sensitive = isSensitivePath(request);
        int limit = sensitive
                ? properties.security().rateLimit().loginAttemptsPerMinute()
                : properties.security().rateLimit().apiRequestsPerMinute();

        String identity = resolveIdentity(request);
        String bucket = KEY_PREFIX + (sensitive ? "auth:" : "api:") + identity;

        try {
            Long count = redis.opsForValue().increment(bucket);
            if (count != null && count == 1L) {
                redis.expire(bucket, WINDOW);
            }
            if (count != null && count > limit) {
                log.warn("Rate limit excedido identity={} path={} count={} limit={}",
                        identity, request.getRequestURI(), count, limit);
                writeTooManyRequests(response, limit);
                return;
            }
        } catch (RuntimeException ex) {
            // Redis caido: se degrada sin bloquear la API.
            log.warn("Rate limit no disponible, se permite la peticion: {}", ex.getMessage());
        }

        chain.doFilter(request, response);
    }

    /** Rutas sensibles al abuso de credenciales. */
    private boolean isSensitivePath(HttpServletRequest request) {
        String path = request.getRequestURI();
        return path.startsWith("/api/v1/auth/login")
                || path.startsWith("/api/v1/auth/register")
                || path.startsWith("/api/v1/auth/refresh")
                || path.startsWith("/api/v1/auth/password/forgot")
                || path.startsWith("/api/v1/auth/password/reset")
                // El reenvio del correo de verificacion es publico y genera un
                // token cada vez: sin limite, un script regenera tokens a voluntad
                // y dispara envios masivos contra una lista de correos.
                || path.startsWith("/api/v1/auth/verify-email");
    }

    /**
     * Identidad del cliente: el usuario autenticado si existe, si no la IP.
     * Se hashea con SHA-256 para no dejar IPs en claro como claves de Redis.
     */
    private String resolveIdentity(HttpServletRequest request) {
        var authentication = org.springframework.security.core.context.SecurityContextHolder
                .getContext().getAuthentication();
        String raw;
        if (authentication != null && authentication.isAuthenticated()
                && authentication.getPrincipal() instanceof AuthenticatedUser user) {
            raw = "user:" + user.id();
        } else {
            raw = "ip:" + com.aylzz.xmrforecast.audit.AuditService.clientIp(request);
        }
        return sha256Hex(raw);
    }

    static String sha256Hex(String value) {
        try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(hash);
        } catch (java.security.NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 no disponible", ex);
        }
    }

    private void writeTooManyRequests(HttpServletResponse response, int limit) throws IOException {
        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.setContentType("application/json");
        response.setHeader("Retry-After", String.valueOf(WINDOW.toSeconds()));
        var body = com.aylzz.xmrforecast.common.ApiError.of(
                HttpStatus.TOO_MANY_REQUESTS.value(),
                "Too Many Requests",
                "RATE_LIMIT_EXCEEDED",
                "Ha superado el limite de peticiones. Intentelo de nuevo en un minuto.",
                "/api/v1", RequestContext.requestId());
        response.getWriter().write(objectMapper.writeValueAsString(body));
    }
}