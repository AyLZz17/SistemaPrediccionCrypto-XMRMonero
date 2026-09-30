package com.aylzz.xmrforecast.user;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * Intento de autenticacion. Base del bloqueo progresivo y de la investigacion
 * de ataques de credenciales. Nunca almacena la contrasena intentada (R-14).
 */
@Entity
@Table(name = "login_attempts")
@Getter
@Setter
public class LoginAttempt {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(length = 254)
    private String email;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", foreignKey = @ForeignKey(name = "fk_login_attempt_user"))
    private User user;

    @Column(nullable = false)
    private boolean successful;

    @Column(name = "failure_reason", length = 64)
    private String failureReason;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    private AuthProvider provider = AuthProvider.LOCAL;

    @Column(name = "ip_address", length = 64)
    private String ipAddress;

    @Column(name = "user_agent", length = 255)
    private String userAgent;

    @Column(name = "attempted_at", nullable = false)
    private Instant attemptedAt = Instant.now();

    public static LoginAttempt success(User user, String ip, String userAgent, AuthProvider provider) {
        LoginAttempt attempt = new LoginAttempt();
        attempt.setUser(user);
        attempt.setEmail(user.getEmail());
        attempt.setSuccessful(true);
        attempt.setIpAddress(ip);
        attempt.setUserAgent(truncate(userAgent));
        attempt.setProvider(provider);
        return attempt;
    }

    /**
     * Intento fallido.
     *
     * <p>Se acepta la entidad {@code user} y no solo su id: la columna
     * {@code login_attempts.user_id} existe, con su indice, para correlocar los
     * ataques contra una cuenta concreta. Guardando solo el correo, toda consulta
     * por {@code user_id} devolvia cero filas y el indice no se usaba nunca.
     *
     * @param user usuario afectado, o {@code null} si el correo no existe (un
     *             intento contra una cuenta inexistente no tiene usuario al que
     *             associarse, y es precisamente el caso que se quiere detectar).
     */
    public static LoginAttempt failure(String email, User user, String reason, String ip,
                                       String userAgent, AuthProvider provider) {
        LoginAttempt attempt = new LoginAttempt();
        attempt.setUser(user);
        attempt.setEmail(email);
        attempt.setSuccessful(false);
        attempt.setFailureReason(reason);
        attempt.setIpAddress(ip);
        attempt.setUserAgent(truncate(userAgent));
        attempt.setProvider(provider);
        return attempt;
    }

    private static String truncate(String value) {
        if (value == null) {
            return null;
        }
        return value.length() > 255 ? value.substring(0, 255) : value;
    }
}