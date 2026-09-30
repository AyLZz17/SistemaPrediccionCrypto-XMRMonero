package com.aylzz.xmrforecast.common;

import java.util.UUID;

/**
 * Correlacion de extremo a extremo: frontend -> Spring Boot -> FastAPI-ML -> MLflow.
 * El identificador viaja en la cabecera {@code X-Request-Id} y en el MDC para que
 * los logs JSON de cada servicio puedan correlacionarse (seccion 13).
 */
public final class RequestContext {

    public static final String HEADER = "X-Request-Id";
    public static final String TRACE_HEADER = "X-Trace-Id";

    private static final ThreadLocal<String> CURRENT = new ThreadLocal<>();
    private static final ThreadLocal<String> TRACE = new ThreadLocal<>();

    private RequestContext() {
    }

    public static void set(String requestId, String traceId) {
        CURRENT.set(requestId);
        TRACE.set(traceId);
    }

    public static void clear() {
        CURRENT.remove();
        TRACE.remove();
    }

    public static String requestId() {
        return CURRENT.get();
    }

    public static String traceId() {
        return TRACE.get();
    }

    /** Identificador nuevo para cuando el cliente no envia ninguno. */
    public static String newId() {
        return UUID.randomUUID().toString();
    }

    /**
     * Sanea un identificador entrante: si el cliente manda algo absurdo se descarta
     * y se genera uno nuevo, evitando inyeccion en los logs (CRLF injection).
     */
    public static String sanitize(String candidate) {
        if (candidate == null || candidate.isBlank()) {
            return newId();
        }
        String trimmed = candidate.trim();
        boolean safe = trimmed.length() <= 128
                && trimmed.chars().allMatch(c -> Character.isLetterOrDigit(c) || c == '-' || c == '_' || c == '.');
        return safe ? trimmed : newId();
    }
}