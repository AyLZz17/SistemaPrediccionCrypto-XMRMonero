package com.aylzz.xmrforecast.audit;

import com.aylzz.xmrforecast.security.Role;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pruebas de la sanitizacion de la auditoria. Es la barrera que impide que una
 * contrasena o un token lleguen a la tabla de auditoria (R-14).
 */
class AuditServiceSanitizationTest {

    @Test
    @DisplayName("Las claves sensibles se redactan en la auditoria")
    void redactsSensitiveKeys() {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("email", "user@example.com");
        details.put("password", "MiClaveMuySecreta123!");
        details.put("accessToken", "eyJhbGciOiJIUzI1NiJ9...");

        var sanitized = AuditService.sanitize(details);

        assertThat(sanitized).containsEntry("email", "user@example.com");
        assertThat(sanitized).containsEntry("password", "[REDACTED]");
        assertThat(sanitized).containsEntry("accessToken", "[REDACTED]");
        assertThat(sanitized.toString()).doesNotContain("MiClaveMuySecreta123!");
        assertThat(sanitized.toString()).doesNotContain("eyJhbGciOiJIUzI1NiJ9");
    }

    @Test
    @DisplayName("La redaccion cubre todas las variantes de nombre de clave")
    void redactsAllKeyVariants() {
        Map<String, Object> details = new LinkedHashMap<>();
        for (String key : new String[]{"password", "PASSWORD", "newPassword", "currentPassword",
                "token", "refreshToken", "clientSecret", "jwtSecret", "apiKey",
                "credentials", "authorization"}) {
            details.put(key, "valor-sensible");
        }

        var sanitized = AuditService.sanitize(details);

        assertThat(sanitized.values()).allMatch("[REDACTED]"::equals);
    }

    @Test
    @DisplayName("Los mapas anidados tambien se sanitizan")
    void redactsNestedMaps() {
        Map<String, Object> inner = new LinkedHashMap<>();
        inner.put("password", "otra-clave");
        inner.put("modelKey", "lstm_base");
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("request", inner);

        var sanitized = AuditService.sanitize(details);

        @SuppressWarnings("unchecked")
        Map<String, Object> nested = (Map<String, Object>) sanitized.get("request");
        assertThat(nested).containsEntry("password", "[REDACTED]");
        assertThat(nested).containsEntry("modelKey", "lstm_base");
    }

    @Test
    @DisplayName("Detalle nulo o vacio produce null, no un mapa vacio en la base")
    void emptyDetailsBecomeNull() {
        assertThat(AuditService.sanitize(null)).isNull();
        assertThat(AuditService.sanitize(Map.of())).isNull();
    }

    @Test
    @DisplayName("Las claves nulas se descartan")
    void dropsNullKeys() {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put(null, "valor");
        details.put("ok", 1);

        assertThat(AuditService.sanitize(details)).containsOnlyKeys("ok");
    }

    @Test
    @DisplayName("El enum de resultado cubre los tres desenlaces de auditoria")
    void outcomeValues() {
        assertThat(AuditEvent.Outcome.values())
                .containsExactly(AuditEvent.Outcome.SUCCESS,
                        AuditEvent.Outcome.DENIED,
                        AuditEvent.Outcome.FAILURE);
    }

    @Test
    @DisplayName("El rol ADMIN queda registrado en el evento")
    void roleIsRecorded() {
        var event = new AuditEvent();
        event.setAction("MODEL_PROMOTE");
        event.setActorRole(Role.ADMIN.name());

        assertThat(event.getActorRole()).isEqualTo("ADMIN");
        assertThat(event.getOutcome()).isEqualTo(AuditEvent.Outcome.SUCCESS);
    }
}