package com.aylzz.xmrforecast.job;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Map;

/**
 * Trabajo asincrono. Los trabajos largos de ML no bloquean la API (seccion 10).
 *
 * <p>{@code idempotencyKey} es unico en la base de datos: es la garantia de que
 * una reentrega, un doble clic o un reintento del cliente no generen dos
 * predicciones ni dos entrenamientos.
 */
@Entity
@Table(name = "jobs")
@Getter
@Setter
public class Job {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "job_key", nullable = false, unique = true, length = 80)
    private String jobKey;

    @Column(nullable = false, length = 48)
    private String type;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    private JobStatus status = JobStatus.PENDING;

    @Column(name = "idempotency_key", unique = true, length = 128)
    private String idempotencyKey;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private Map<String, Object> payload;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private Map<String, Object> result;

    @Column(name = "progress_percent", nullable = false)
    private int progressPercent = 0;

    @Column(nullable = false)
    private int attempts = 0;

    @Column(name = "max_attempts", nullable = false)
    private int maxAttempts = 3;

    @Column(name = "error_message", columnDefinition = "text")
    private String errorMessage;

    @Column(name = "created_by")
    private Long createdBy;

    @Column(name = "queued_at", nullable = false)
    private Instant queuedAt = Instant.now();

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    /** Ultimo latido del worker; permite detectar trabajos colgados tras un reinicio. */
    @Column(name = "heartbeat_at")
    private Instant heartbeatAt;

    public enum JobStatus {
        PENDING,
        RUNNING,
        COMPLETED,
        FAILED,
        CANCELLED
    }

    public boolean isTerminal() {
        return status == JobStatus.COMPLETED
                || status == JobStatus.FAILED
                || status == JobStatus.CANCELLED;
    }

    public boolean canRetry() {
        return !isTerminal() && attempts < maxAttempts;
    }
}