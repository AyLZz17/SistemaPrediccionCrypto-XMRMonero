package com.aylzz.xmrforecast.auth;

import com.aylzz.xmrforecast.audit.AuditService;
import com.aylzz.xmrforecast.common.ApiException;
import com.aylzz.xmrforecast.mail.MailService;
import com.aylzz.xmrforecast.security.JwtService;
import com.aylzz.xmrforecast.security.TokenHasher;
import com.aylzz.xmrforecast.user.ConsentRecordRepository;
import com.aylzz.xmrforecast.user.LoginAttemptRepository;
import com.aylzz.xmrforecast.user.OAuthAccountRepository;
import com.aylzz.xmrforecast.user.PasswordResetToken;
import com.aylzz.xmrforecast.user.PasswordResetTokenRepository;
import com.aylzz.xmrforecast.user.RevokedTokenRepository;
import com.aylzz.xmrforecast.user.User;
import com.aylzz.xmrforecast.user.UserRepository;
import com.aylzz.xmrforecast.user.UserStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Verificacion de correo: token valido, caducado, ya usado y de otro proposito.
 *
 * <p>El encargo pide comprobar el ciclo completo del enlace que llega por
 * correo. El envio en si lo cubre {@code ConsentRegistrationTest} y
 * {@code RegisterMailDeliveryTest}; aqui se fija lo que ocurre cuando el
 * navegador abre el enlace, que es la parte que decide si la cuenta se activa.
 */
class EmailVerificationFlowTest {

    private static final String EMAIL = "daniel@example.com";
    private static final String RAW_TOKEN = "token-opaco-de-verificacion";
    private static final String HASH = "hmac-de-verificacion";

    private final UserRepository userRepository = mock(UserRepository.class);
    private final com.aylzz.xmrforecast.user.RefreshTokenRepository refreshTokenRepository =
            mock(com.aylzz.xmrforecast.user.RefreshTokenRepository.class);
    private final OAuthAccountRepository oauthAccountRepository = mock(OAuthAccountRepository.class);
    private final LoginAttemptRepository loginAttemptRepository = mock(LoginAttemptRepository.class);
    private final PasswordResetTokenRepository passwordResetTokenRepository =
            mock(PasswordResetTokenRepository.class);
    private final RevokedTokenRepository revokedTokenRepository = mock(RevokedTokenRepository.class);
    private final ConsentRecordRepository consentRecordRepository = mock(ConsentRecordRepository.class);
    private final PasswordEncoder passwordEncoder = mock(PasswordEncoder.class);
    private final JwtService jwtService = mock(JwtService.class);
    private final TokenHasher tokenHasher = mock(TokenHasher.class);
    private final AuditService auditService = mock(AuditService.class);
    private final NotificationService notificationService = mock(NotificationService.class);
    private final MailService mailService = mock(MailService.class);

    private AuthService service;

    @BeforeEach
    void setUp() {
        service = new AuthService(userRepository, refreshTokenRepository,
                oauthAccountRepository, loginAttemptRepository, passwordResetTokenRepository,
                revokedTokenRepository, consentRecordRepository, passwordEncoder, jwtService,
                tokenHasher, auditService, notificationService, null);
        ReflectionTestUtils.setField(service, "mailService", mailService);
        when(tokenHasher.hash(RAW_TOKEN)).thenReturn(HASH);
    }

    private static User pendiente() {
        User user = new User();
        user.setId(7L);
        user.setEmail(EMAIL);
        user.setFullName("Daniel Prueba");
        user.setStatus(UserStatus.PENDING_VERIFICATION);
        user.setEmailVerified(false);
        return user;
    }

    private static PasswordResetToken tokenDe(User user, Instant expiresAt,
                                              Instant consumedAt) {
        PasswordResetToken token = new PasswordResetToken();
        token.setUser(user);
        token.setPurpose(PasswordResetToken.Purpose.VERIFY_EMAIL);
        token.setExpiresAt(expiresAt);
        token.setConsumedAt(consumedAt);
        return token;
    }

    @Test
    @DisplayName("un token valido activa la cuenta, lo consume y lo anuncia en la bandeja")
    void tokenValidoActivaLaCuenta() {
        User user = pendiente();
        PasswordResetToken token = tokenDe(user, Instant.now().plus(Duration.ofHours(1)), null);
        when(passwordResetTokenRepository.findByTokenHash(HASH)).thenReturn(Optional.of(token));
        when(userRepository.save(user)).thenReturn(user);

        service.verifyEmail(RAW_TOKEN);

        assertThat(user.isEmailVerified()).isTrue();
        assertThat(user.getEmailVerifiedAt()).isNotNull();
        assertThat(user.getStatus()).isEqualTo(UserStatus.ACTIVE);
        assertThat(token.getConsumedAt()).isNotNull();

        verify(notificationService).notify(eq(7L), any(), eq("Correo confirmado"),
                anyString(), eq(NotificationService.Severity.SUCCESS));
        verify(auditService).record(eq(7L), any(), eq("AUTH_EMAIL_VERIFIED"), anyString(),
                anyString(), any(), any());
    }

    @Test
    @DisplayName("un token caducado se rechaza y la cuenta sigue pendiente")
    void tokenCaducadoSeRechaza() {
        User user = pendiente();
        PasswordResetToken token = tokenDe(user, Instant.now().minus(Duration.ofMinutes(1)), null);
        when(passwordResetTokenRepository.findByTokenHash(HASH)).thenReturn(Optional.of(token));

        assertThatThrownBy(() -> service.verifyEmail(RAW_TOKEN))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getCode())
                .isEqualTo("EXPIRED_VERIFICATION_TOKEN");

        assertThat(user.isEmailVerified()).isFalse();
        assertThat(user.getStatus()).isEqualTo(UserStatus.PENDING_VERIFICATION);
    }

    @Test
    @DisplayName("un token ya consumido no sirve una segunda vez")
    void tokenYaUsadoSeRechaza() {
        User user = pendiente();
        PasswordResetToken token = tokenDe(user,
                Instant.now().plus(Duration.ofHours(1)), Instant.now().minus(Duration.ofSeconds(3)));
        when(passwordResetTokenRepository.findByTokenHash(HASH)).thenReturn(Optional.of(token));

        assertThatThrownBy(() -> service.verifyEmail(RAW_TOKEN))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getCode())
                .isEqualTo("EXPIRED_VERIFICATION_TOKEN");

        assertThat(user.isEmailVerified()).isFalse();
    }

    @Test
    @DisplayName("un token desconocido se rechaza con un codigo estable")
    void tokenDesconocidoSeRechaza() {
        when(passwordResetTokenRepository.findByTokenHash(HASH)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.verifyEmail(RAW_TOKEN))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getCode())
                .isEqualTo("INVALID_VERIFICATION_TOKEN");
    }

    @Test
    @DisplayName("un token de recuperacion no sirve para verificar el correo")
    void tokenDeOtroPropositoSeRechaza() {
        User user = pendiente();
        PasswordResetToken token = new PasswordResetToken();
        token.setUser(user);
        token.setPurpose(PasswordResetToken.Purpose.RESET);
        token.setExpiresAt(Instant.now().plus(Duration.ofHours(1)));
        when(passwordResetTokenRepository.findByTokenHash(HASH)).thenReturn(Optional.of(token));

        assertThatThrownBy(() -> service.verifyEmail(RAW_TOKEN))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getCode())
                .isEqualTo("INVALID_VERIFICATION_TOKEN");

        assertThat(user.isEmailVerified()).isFalse();
    }

    @Test
    @DisplayName("verificar dos veces con el mismo enlace no duplica nada: el segundo intento falla")
    void segundoIntentoConElMismoEnlaceFalla() {
        User user = pendiente();
        PasswordResetToken token = tokenDe(user, Instant.now().plus(Duration.ofHours(1)), null);
        when(passwordResetTokenRepository.findByTokenHash(HASH)).thenReturn(Optional.of(token));
        when(userRepository.save(user)).thenReturn(user);

        service.verifyEmail(RAW_TOKEN);
        ArgumentCaptor<PasswordResetToken> captor = ArgumentCaptor.forClass(PasswordResetToken.class);
        verify(passwordResetTokenRepository).save(captor.capture());

        // El mismo registro en la base ya esta consumido: isUsable() lo rechaza.
        assertThat(captor.getValue().isUsable(Instant.now())).isFalse();
    }
}
