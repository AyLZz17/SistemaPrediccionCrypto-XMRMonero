package com.aylzz.xmrforecast.mlmodel;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * Modelo logico (LSTM, GRU, media movil, regresion lineal, ARIMA). La version
 * concreta vive en {@link ModelVersion}; asi los baselines pueden acumular
 * metricas historicas sin duplicar la definicion del modelo.
 */
@Entity
@Table(name = "models")
@Getter
@Setter
public class MlModel {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "model_key", nullable = false, unique = true, length = 64)
    private String modelKey;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private Family family;

    @Enumerated(EnumType.STRING)
    @Column(name = "task_type", nullable = false, length = 16)
    private TaskType taskType = TaskType.REGRESSION;

    @Column(columnDefinition = "text")
    private String description;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    public enum Family {
        LSTM,
        GRU,
        MOVING_AVERAGE,
        LINEAR_REGRESSION,
        ARIMA
    }

    public enum TaskType {
        REGRESSION,
        DIRECTION
    }
}