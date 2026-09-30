package com.aylzz.xmrforecast.mlmodel;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * Version concreta y verificable de un modelo trained.
 *
 * <p>{@code selectedOn} documenta en que particion se decidio: la regla del
 * proyecto es {@code VALIDATION} y la base de datos admite {@code TEST} solo
 * para dejar constancia de una consulta puntual, nunca para la eleccion (R-24).
 */
@Entity
@Table(name = "model_versions")
@Getter
@Setter
public class ModelVersion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "model_id", nullable = false)
    private Long modelId;

    @Column(nullable = false, length = 64)
    private String version;

    @Column(name = "run_id")
    private Long runId;

    @Column(name = "dataset_version_id", nullable = false)
    private Long datasetVersionId;

    @Column(name = "artifact_uri", nullable = false, columnDefinition = "text")
    private String artifactUri;

    @Column(name = "artifact_sha256", nullable = false, length = 64)
    private String artifactSha256;

    @Column(name = "scaler_uri", columnDefinition = "text")
    private String scalerUri;

    @Column(name = "scaler_sha256", length = 64)
    private String scalerSha256;

    @Column(name = "config_sha256", nullable = false, length = 64)
    private String configSha256;

    @Column(nullable = false)
    private Integer seed = 42;

    @Enumerated(EnumType.STRING)
    @Column(name = "selected_on", nullable = false, length = 16)
    private SelectedOn selectedOn = SelectedOn.VALIDATION;

    @Column(name = "is_champion", nullable = false)
    private boolean champion;

    @Column(name = "promoted_by")
    private Long promotedBy;

    @Column(name = "promoted_at")
    private Instant promotedAt;

    /** Gate de integridad de R-28: artefacto y scaler verificados antes de servir. */
    @Column(name = "integrity_verified", nullable = false)
    private boolean integrityVerified;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    public enum SelectedOn {
        VALIDATION,
        MANUAL,
        TEST
    }
}