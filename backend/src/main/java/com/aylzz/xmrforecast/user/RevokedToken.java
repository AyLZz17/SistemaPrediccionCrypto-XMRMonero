package com.aylzz.xmrforecast.user;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * Access token revocado por su {@code jti}. Permite cerrar la sesion de forma
 * inmediata sin esperar a que expire el token (R: revocacion por jti).
 */
@Entity
@Table(name = "revoked_tokens")
@Getter
@Setter
public class RevokedToken {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 64)
    private String jti;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", foreignKey = @ForeignKey(name = "fk_revoked_token_user"))
    private User user;

    /** Se conserva hasta que el token habria expirado, para no guardar basura inutil. */
    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "revoked_at", nullable = false)
    private Instant revokedAt = Instant.now();

    @Column(nullable = false, length = 64)
    private String reason;
}