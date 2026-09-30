package com.aylzz.xmrforecast.user;

import com.aylzz.xmrforecast.security.Role;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * Vinculo entre una cuenta de Google y un usuario local.
 *
 * <p>La identidad federada se guarda por {@code (provider, providerSubject)},
 * nunca por email: asi un atacante no puede reclamar una cuenta existente
 * únicamente por conocer una direccion de correo (OWASP).
 */
@Entity
@Table(name = "oauth_accounts")
@Getter
@Setter
public class OAuthAccount {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false,
            foreignKey = @ForeignKey(name = "fk_oauth_user"))
    private User user;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    private AuthProvider provider = AuthProvider.GOOGLE;

    /** Identificador estable del sujeto en el proveedor (campo {@code sub} del ID Token). */
    @Column(name = "provider_subject", nullable = false, length = 255)
    private String providerSubject;

    @Column(name = "provider_email", length = 254)
    private String providerEmail;

    @Column(name = "linked_at", nullable = false)
    private Instant linkedAt = Instant.now();

    /** Rol heredado en el momento del enlace, para no recalcular en cada login. */
    @Transient
    private Role linkedRole;
}