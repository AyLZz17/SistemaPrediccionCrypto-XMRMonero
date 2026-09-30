package com.aylzz.xmrforecast.prediction;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;

/**
 * Prediccion persistida con su trazabilidad completa: version de modelo, dataset,
 * checksum del artefacto, checksum de la configuracion, semilla y bloque de
 * traza. Cada cifra publicada debe poder reconstruirse desde aqui (R-21).
 */
@Entity
@Table(name = "predictions")
@Getter
@Setter
public class Prediction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "model_version_id", nullable = false)
    private Long modelVersionId;

    @Column(nullable = false, length = 16)
    private String symbol;

    @Column(name = "target_date", nullable = false)
    private LocalDate targetDate;

    @Column(name = "predicted_close", nullable = false, precision = 20, scale = 8)
    private BigDecimal predictedClose;

    @Enumerated(EnumType.STRING)
    @Column(name = "predicted_direction", nullable = false, length = 8)
    private Direction predictedDirection;

    /** Precio real observado, disponible solo cuando la fecha ya paso. */
    @Column(name = "actual_close", precision = 20, scale = 8)
    private BigDecimal actualClose;

    @Column(precision = 6, scale = 5)
    private BigDecimal confidence;

    @Column(name = "dataset_version_id", nullable = false)
    private Long datasetVersionId;

    @Column(name = "artifact_sha256", nullable = false, length = 64)
    private String artifactSha256;

    @Column(name = "config_sha256", nullable = false, length = 64)
    private String configSha256;

    @Column(nullable = false)
    private Integer seed = 42;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private Map<String, Object> trace;

    @Column(name = "requested_by")
    private Long requestedBy;

    @Column(name = "request_id", length = 64)
    private String requestId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    /**
     * Estado del calculo. Una prediccion se persiste solo cuando el servicio ML
     * ya respondio, asi que lo normal es {@code READY}; {@code FAILED} queda
     * registrado para el caso de que una inferencia se complete a posteriori sin
     * precio utilizable, y {@code PENDING} para trabajo encolado.
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 12)
    private Status status = Status.PENDING;

    public enum Direction {
        UP,
        DOWN,
        FLAT
    }

    public enum Status {
        PENDING,
        READY,
        FAILED
    }
}