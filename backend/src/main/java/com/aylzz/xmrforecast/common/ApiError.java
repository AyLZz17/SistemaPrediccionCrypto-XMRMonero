package com.aylzz.xmrforecast.common;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Respuesta de error unificada de la API. Nunca incluye stack traces,
 * rutas de clase ni secretos (R-14, OWASP).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiError(
        Instant timestamp,
        int status,
        String error,
        String code,
        String message,
        String path,
        String requestId,
        List<FieldViolation> fieldErrors
) {

    /** Detalle de una violacion de Bean Validation. Nunca incluye el valor rechazado. */
    public record FieldViolation(String field, String message) {}

    public static ApiError of(int status, String error, String code, String message,
                              String path, String requestId) {
        return new ApiError(Instant.now(), status, error, code, message, path, requestId, null);
    }

    public static ApiError withFields(int status, String error, String code, String message,
                                      String path, String requestId, List<FieldViolation> fields) {
        return new ApiError(Instant.now(), status, error, code, message, path, requestId, fields);
    }

    public Map<String, Object> asLogAttributes() {
        return Map.of(
                "status", status,
                "code", code,
                "path", path,
                "request_id", requestId == null ? "" : requestId
        );
    }
}