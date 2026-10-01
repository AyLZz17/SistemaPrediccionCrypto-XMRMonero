package com.aylzz.xmrforecast.user;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * Una aceptacion (o revocatoria) de un documento legal, con su version.
 *
 * <p>Tabla {@code consent_records} (migracion V6). Es la prueba de que el
 * usuario vio y acepto el texto vigente: quien acepto, cual version, cuando y
 * desde donde. Se guarda el hash de la IP no: la IP en claro es suficiente para
 * el proposito y se limita a 45 caracteres (IPv6).
 */
@Entity
@Table(name = "consent_records")
@Getter
@Setter
public class ConsentRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Enumerated(EnumType.STRING)
    @Column(name = "consent_type", nullable = false, length = 24)
    private ConsentType consentType;

    /** Version del documento aceptado, p. ej. {@code 2026-10-01}. */
    @Column(nullable = false, length = 32)
    private String version;

    /** {@code false} en una revocatoria explicita de comunicaciones. */
    @Column(nullable = false)
    private boolean accepted;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    private ConsentSource source;

    @Column(name = "ip_address", length = 45)
    private String ipAddress;

    @Column(name = "user_agent", length = 255)
    private String userAgent;

    @Column(name = "accepted_at", nullable = false)
    private Instant acceptedAt = Instant.now();

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();
}
