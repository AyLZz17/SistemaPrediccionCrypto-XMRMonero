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

    public static LoginAttempt failure(String email, Long userId, String reason, String ip,
                                        String userAgent, AuthProvider provider) {
        LoginAttempt attempt = new LoginAttempt();
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