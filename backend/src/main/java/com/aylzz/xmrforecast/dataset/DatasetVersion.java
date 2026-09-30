package com.aylzz.xmrforecast.dataset;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;

/**
 * Version de dataset inmutable. El {@code checksumSha256} y el bloque
 * {@code provenance} son la garantia de procedencia exigida por R-28: un dataset
 * sin checksum verificable no puede usarse para entrenar ni promover un campeon.
 */
@Entity
@Table(name = "dataset_versions")
@Getter
@Setter
public class DatasetVersion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 16)
    private String symbol;

    @Column(nullable = false, length = 64)
    private String version;

    @Column(nullable = false, length = 32)
    private String source;

    @Column(name = "checksum_sha256", nullable = false, length = 64)
    private String checksumSha256;

    @Column(name = "rows_count", nullable = false)
    private long rowsCount;

    @Column(name = "first_date", nullable = false)
    private LocalDate firstDate;

    @Column(name = "last_date", nullable = false)
    private LocalDate lastDate;

    @Column(name = "storage_uri", columnDefinition = "text")
    private String storageUri;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private Map<String, Object> provenance;

    @Column(name = "created_by")
    private Long createdBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();
}