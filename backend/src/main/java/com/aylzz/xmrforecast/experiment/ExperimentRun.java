package com.aylzz.xmrforecast.experiment;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * Corrida de un experimento. Guarda las proporciones de la particion cronologica
 * y las semillas; la tabla impone que sumen exactamente 1 y que las semillas
 * esten fijadas (R-01, R-08).
 */
@Entity
@Table(name = "experiment_runs")
@Getter
@Setter
public class ExperimentRun {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "experiment_id", nullable = false)
    private Long experimentId;

    @Column(name = "run_key", nullable = false, length = 64)
    private String runKey;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    private RunStatus status = RunStatus.PENDING;

    @Enumerated(EnumType.STRING)
    @Column(name = "task_type", nullable = false, length = 16)
    private TaskType taskType = TaskType.REGRESSION;

    /** Semillas fijadas. Para modelos estocasticos, al menos 5 (R-08). */
    @Column(nullable = false, columnDefinition = "integer[]")
    @JdbcTypeCode(SqlTypes.ARRAY)
    private List<Integer> seeds = List.of(42);

    @Column(name = "train_ratio", nullable = false, precision = 4, scale = 3)
    private BigDecimal trainRatio = new BigDecimal("0.700");

    @Column(name = "val_ratio", nullable = false, precision = 4, scale = 3)
    private BigDecimal valRatio = new BigDecimal("0.150");

    @Column(name = "test_ratio", nullable = false, precision = 4, scale = 3)
    private BigDecimal testRatio = new BigDecimal("0.150");

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    @Column(name = "error_message", columnDefinition = "text")
    private String errorMessage;

    @Column(name = "created_by")
    private Long createdBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    public enum RunStatus {
        PENDING,
        RUNNING,
        COMPLETED,
        FAILED,
        CANCELLED
    }

    public enum TaskType {
        /** Estimacion del cierre t+1. */
        REGRESSION,
        /** Direccion sube/baja. */
        DIRECTION
    }
}