package com.aylzz.xmrforecast.experiment;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/** Experimento: una hipotesis, una configuracion YAML y un dataset versionado. */
@Entity
@Table(name = "experiments")
@Getter
@Setter
public class Experiment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 64)
    private String code;

    @Column(nullable = false, length = 160)
    private String name;

    @Column(columnDefinition = "text")
    private String description;

    @Column(columnDefinition = "text")
    private String hypothesis;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    private ExperimentStatus status = ExperimentStatus.DRAFT;

    /**
     * Tarea que evalua el experimento (D-04). Vive aqui y no solo en la corrida
     * porque la comparacion de metricas se filtra por tarea, y un filtro que
     * obligara a unir con las corridas no se podria aplicar sobre el catalogo.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "task_type", nullable = false, length = 16)
    private TaskType taskType = TaskType.REGRESSION;

    /** Configuracion del experimento tal cual se entrega al servicio ML. */
    @Column(name = "config_yaml", nullable = false, columnDefinition = "text")
    private String configYaml;

    @Column(name = "config_sha256", nullable = false, length = 64)
    private String configSha256;

    @Column(name = "dataset_version_id", nullable = false)
    private Long datasetVersionId;

    @Column(name = "created_by")
    private Long createdBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    public enum ExperimentStatus {
        DRAFT,
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