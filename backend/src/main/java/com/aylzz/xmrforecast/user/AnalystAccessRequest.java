package com.aylzz.xmrforecast.user;

import com.aylzz.xmrforecast.common.Ids;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * Solicitud de acceso al rol ANALYST.
 * <p>
 * Estados: PENDING, APPROVED, REJECTED, REVOKED.
 * Un usuario solo puede tener una solicitud PENDING a la vez (constraint único).
 * La aprobación cambia el rol del usuario a ANALYST y revoca sus sesiones.
 */
@Entity
@Table(name = "analyst_access_requests",
        uniqueConstraints = @UniqueConstraint(name = "uq_analyst_access_requests_user_pending",
                columnNames = "user_id"))
@Getter
@Setter
public class AnalystAccessRequest {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false,
            foreignKey = @ForeignKey(name = "fk_analyst_access_requests_user"))
    private User user;

    /** Motivo de la solicitud (texto libre). */
    @Column(name = "motivo", nullable = false, columnDefinition = "TEXT")
    private String motivo;

    /** Uso previsto de la plataforma (texto libre). */
    @Column(name = "uso_previsto", nullable = false, columnDefinition = "TEXT")
    private String usoPrevisto;

    /** Versión del cuestionario aceptada (p.ej. "2026-10-01"). */
    @Column(name = "cuestionario_version", nullable = false, length = 32)
    private String cuestionarioVersion;

    /** Confirmaciones obligatorias del cuestionario. */
    @Column(name = "acepta_riesgos", nullable = false)
    private boolean aceptaRiesgos;

    @Column(name = "acepta_limitaciones", nullable = false)
    private boolean aceptaLimitaciones;

    @Column(name = "acepta_metricas", nullable = false)
    private boolean aceptaMetricas;

    @Column(name = "acepta_no_garantia", nullable = false)
    private boolean aceptaNoGarantia;

    @Column(name = "acepta_no_operaciones", nullable = false)
    private boolean aceptaNoOperaciones;

    @Column(name = "acepta_no_backtesting", nullable = false)
    private boolean aceptaNoBacktesting;

    @Column(name = "acepta_rol_analyst", nullable = false)
    private boolean aceptaRolAnalyst;

    @Column(name = "acepta_no_rentabilidad", nullable = false)
    private boolean aceptaNoRentabilidad;

    /** Estado de la solicitud: PENDING, APPROVED, REJECTED, REVOKED. */
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private AnalystAccessRequestStatus status = AnalystAccessRequestStatus.PENDING;

    /** ADMIN que decidió (aprobó/rechazó/revocó). */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "decided_by",
            foreignKey = @ForeignKey(name = "fk_analyst_access_requests_decided_by"))
    private User decidedBy;

    @Column(name = "decided_at")
    private Instant decidedAt;

    /** Motivo de rechazo o revocación (obligatorio si REJECTED/REVOKED). */
    @Column(name = "decision_reason", columnDefinition = "TEXT")
    private String decisionReason;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    @PreUpdate
    void onUpdate() {
        this.updatedAt = Instant.now();
    }

    public String getIdOpaque() {
        return Ids.of(id);
    }
}