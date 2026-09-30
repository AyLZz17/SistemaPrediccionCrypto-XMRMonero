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
     * Longitud maxima admitida. Coincide con el ancho de las columnas que guardan
     * estos identificadores: {@code audit_events.request_id},
     * {@code audit_events.trace_id} y {@code predictions.request_id} son
     * {@code VARCHAR(64)}. Aceptar mas aqui hacia que un cliente que mandara
     * 100 caracteres provocara un {@code value too long for type character
     * varying(64)} al insertar el registro de auditoria: la peticion se
     * atendia, pero la traza de auditoria se perdia en silencio.
     */
    public static final int MAX_LENGTH = 64;

    /**
     * Sanea un identificador entrante: si el cliente manda algo absurdo se descarta
     * y se genera uno nuevo, evitando inyeccion en los logs (CRLF injection).
     */
    public static String sanitize(String candidate) {
        if (candidate == null || candidate.isBlank()) {
            return newId();
        }
        String trimmed = candidate.trim();
        boolean safe = trimmed.length() <= MAX_LENGTH
                && trimmed.chars().allMatch(c -> Character.isLetterOrDigit(c) || c == '-' || c == '_' || c == '.');
        return safe ? trimmed : newId();
    }
}