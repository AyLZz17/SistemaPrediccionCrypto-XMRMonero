package com.aylzz.xmrforecast.user.dto;

import com.aylzz.xmrforecast.user.AnalystAccessRequestStatus;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;

/**
 * Contratos de entrada/salida para solicitudes de acceso al rol ANALYST.
 */
public final class AnalystAccessRequests {

    private AnalystAccessRequests() {
    }

    /**
     * Solicitud de acceso al rol ANALYST.
     *
     * <p>Todos los campos del cuestionario son obligatorios y deben ser
     * confirmados (checkbox marcados). El servidor los valida dos veces:
     * aqui con {@code @AssertTrue} (que Spring ejecuta sobre una instancia real,
     * R-46) y de nuevo en el servicio, por si alguna ruta construye el objeto
     * sin {@code @Valid}.
     */
    public record CreateRequest(
            @NotBlank(message = "El motivo es obligatorio.")
            @Size(max = 2000, message = "El motivo no puede exceder 2000 caracteres.")
            String motivo,

            @NotBlank(message = "El uso previsto es obligatorio.")
            @Size(max = 2000, message = "El uso previsto no puede exceder 2000 caracteres.")
            String usoPrevisto,

            @NotNull(message = "Debe confirmar que comprende los riesgos.")
            @AssertTrue(message = "Debe confirmar que comprende los riesgos.")
            Boolean aceptaRiesgos,

            @NotNull(message = "Debe confirmar que comprende las limitaciones.")
            @AssertTrue(message = "Debe confirmar que comprende las limitaciones.")
            Boolean aceptaLimitaciones,

            @NotNull(message = "Debe confirmar que revisara las metricas.")
            @AssertTrue(message = "Debe confirmar que revisara las metricas.")
            Boolean aceptaMetricas,

            @NotNull(message = "Debe confirmar que no hay garantia de resultados.")
            @AssertTrue(message = "Debe confirmar que no hay garantia de resultados.")
            Boolean aceptaNoGarantia,

            @NotNull(message = "Debe confirmar que la plataforma no realiza operaciones.")
            @AssertTrue(message = "Debe confirmar que la plataforma no realiza operaciones.")
            Boolean aceptaNoOperaciones,

            @NotNull(message = "Debe confirmar que no hay backtesting de trading.")
            @AssertTrue(message = "Debe confirmar que no hay backtesting de trading.")
            Boolean aceptaNoBacktesting,

            @NotNull(message = "Debe confirmar que comprende el alcance del rol ANALYST.")
            @AssertTrue(message = "Debe confirmar que comprende el alcance del rol ANALYST.")
            Boolean aceptaRolAnalyst,

            @NotNull(message = "Debe confirmar que no hay garantia de rentabilidad.")
            @AssertTrue(message = "Debe confirmar que no hay garantia de rentabilidad.")
            Boolean aceptaNoRentabilidad
    ) {}

    /**
     * Respuesta de la solicitud creada.
     */
    public record CreateResponse(
            String id,
            AnalystAccessRequestStatus status,
            Instant createdAt
    ) {}

    /**
     * Respuesta con el estado de la solicitud del usuario actual.
     */
    public record MyRequestResponse(
            String id,
            String motivo,
            String usoPrevisto,
            String cuestionarioVersion,
            AnalystAccessRequestStatus status,
            Instant createdAt,
            Instant decidedAt,
            String decisionReason,
            String decidedBy
    ) {}

    /**
     * Respuesta para listado administrativo.
     */
    public record AdminListResponse(
            String id,
            String userId,
            String userEmail,
            String userFullName,
            String motivo,
            String usoPrevisto,
            String cuestionarioVersion,
            AnalystAccessRequestStatus status,
            Instant createdAt,
            Instant decidedAt,
            String decisionReason,
            String decidedByEmail
    ) {}

    /**
     * Decision del ADMIN: aprobar, rechazar o revocar.
     */
    public record DecisionRequest(
            @NotBlank(message = "El motivo de la decision es obligatorio.")
            @Size(max = 2000, message = "El motivo no puede exceder 2000 caracteres.")
            String reason
    ) {}

    /**
     * Respuesta tras decision del ADMIN.
     */
    public record DecisionResponse(
            String id,
            AnalystAccessRequestStatus status,
            Instant decidedAt
    ) {}

    /**
     * Version actual del cuestionario (se actualiza si cambia el texto).
     * Debe coincidir con {@code LegalDocuments.CURRENT_VERSION} y la version
     * que se muestra en frontend.
     */
    public static final String CUESTIONARIO_VERSION = "2026-10-01";
}