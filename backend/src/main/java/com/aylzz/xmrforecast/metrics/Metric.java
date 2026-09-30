package com.aylzz.xmrforecast.metrics;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;

/**
 * Metricas de evaluacion de una corrida en una particion concreta.
 *
 * <p>Se guardan MAE, RMSE, MAPE y acierto de direccion (R-07), la matriz de
 * confusion y la desviacion tipica entre semillas. La particion {@code TEST} se
 * calcula una sola vez y no se usa para elegir modelos (R-04, R-24).
 */
@Entity
@Table(name = "metrics")
@Getter
@Setter
public class Metric {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "run_id", nullable = false)
    private Long runId;

    @Column(name = "model_version_id")
    private Long modelVersionId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Split split;

    /** Error absoluto medio, en USD (predicciones des-escaladas, R-02). */
    @Column(precision = 20, scale = 10)
    private BigDecimal mae;

    @Column(precision = 20, scale = 10)
    private BigDecimal rmse;

    @Column(precision = 20, scale = 10)
    private BigDecimal mape;

    /** Proporcion de aciertos en la tarea de direccion. */
    @Column(name = "direction_accuracy", precision = 6, scale = 5)
    private BigDecimal directionAccuracy;

    @Column(name = "precision_up", precision = 6, scale = 5)
    private BigDecimal precisionUp;

    @Column(name = "recall_up", precision = 6, scale = 5)
    private BigDecimal recallUp;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "confusion_matrix", columnDefinition = "jsonb")
    private Map<String, Object> confusionMatrix;

    @Column(name = "n_samples")
    private Integer samples;

    /** Numero de semillas promediadas. Para deterministas, 1. */
    @Column(name = "n_seeds", nullable = false)
    private Integer seedCount = 1;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private Map<String, Object> stddev;

    @Column(name = "computed_at", nullable = false)
    private Instant computedAt = Instant.now();

    public enum Split {
        TRAIN,
        VALIDATION,
        TEST
    }
}