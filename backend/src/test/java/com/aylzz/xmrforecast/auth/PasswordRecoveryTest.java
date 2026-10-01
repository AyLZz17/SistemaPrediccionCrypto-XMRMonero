package com.aylzz.xmrforecast.auth;

import com.aylzz.xmrforecast.audit.AuditService;
import com.aylzz.xmrforecast.auth.dto.AuthRequests;
import com.aylzz.xmrforecast.common.ApiException;
import com.aylzz.xmrforecast.mail.MailService;
import com.aylzz.xmrforecast.security.JwtService;
import com.aylzz.xmrforecast.security.TokenHasher;
import com.aylzz.xmrforecast.user.ConsentRecordRepository;
import com.aylzz.xmrforecast.user.LoginAttemptRepository;
import com.aylzz.xmrforecast.user.OAuthAccountRepository;
import com.aylzz.xmrforecast.user.PasswordResetToken;
import com.aylzz.xmrforecast.user.PasswordResetTokenRepository;
import com.aylzz.xmrforecast.user.RefreshToken;
import com.aylzz.xmrforecast.user.RefreshTokenRepository;
import com.aylzz.xmrforecast.user.RevokedTokenRepository;
import com.aylzz.xmrforecast.user.User;
import com.aylzz.xmrforecast.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Recuperacion de contrasena: solicitud, token, uso unico y cierre de sesiones.
 *
 * <p>Fija los puntos que el encargo exige verificar: respuesta generica ante un
 * correo desconocido (no enumeracion), token opaco con hash y expiracion corta,
 * uso unico, invalidacion de las sesiones anteriores, ventana de gracia contra
 * el abuso del buzon y rechazo de tokens caducados o ya consumidos.
 */
class PasswordRecoveryTest {

    private static final String EMAIL = "daniel@example.com";
    private static final String RAW_TOKEN = "token-opaco-de-recuperacion";
    private static final String HASH = "hmac-del-token";

    private final UserRepository userRepository = mock(UserRepository.class);
    private final RefreshTokenRepository refreshTokenRepository = mock(RefreshTokenRepository.class);
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
        service = new AuthService(userRepository, refreshTokenRepository, oauthAccountRepository,
                loginAttemptRepository, passwordResetTokenRepository, revokedTokenRepository,
                consentRecordRepository, passwordEncoder, jwtService, tokenHasher, auditService,
                notificationService, null);
        ReflectionTestUtils.setField(service, "mailService", mailService);
        // El token en claro es aleatorio por solicitud: se hash-ea cualquiera.
        when(tokenHasher.hash(anyString())).thenReturn(HASH);
        when(mailService.sendPasswordResetEmail(anyString(), anyString(), anyString(),
                any(Instant.class))).thenReturn(true);
    }

    private static User usuario() {
        User user = new User();
        user.setId(7L);
        user.setEmail(EMAIL);
        user.setFullName("Daniel Prueba");
        user.setPasswordHash("hash-viejo");
        return user;
    }

    // -------------------------------------------------------- solicitud

    @Test
    @DisplayName("la solicitud valida emite un token de dos horas, avisa y pide el correo")
    void solicitudValidaEmiteTokenDeDosHoras() {
        User user = usuario();
        when(userRepository.findByEmailIgnoreCase(EMAIL)).thenReturn(Optional.of(user));

        Instant before = Instant.now();
        service.forgotPassword(new AuthRequests.ForgotPasswordRequest(EMAIL));
        Instant after = Instant.now();

        ArgumentCaptor<PasswordResetToken> captor = ArgumentCaptor.forClass(PasswordResetToken.class);
        verify(passwordResetTokenRepository).save(captor.capture());
        PasswordResetToken token = captor.getValue();
        assertThat(token.getPurpose()).isEqualTo(PasswordResetToken.Purpose.RESET);
        assertThat(token.getConsumedAt()).isNull();
        assertThat(token.getTokenHash()).isEqualTo(HASH);
        // Expiracion corta: 2 horas, medida desde la solicitud.
        assertThat(token.getExpiresAt())
                .isBetween(before.plus(Duration.ofHours(2)), after.plus(Duration.ofHours(2)));

        verify(mailService).sendPasswordResetEmail(eq(EMAIL), anyString(), anyString(),
                eq(token.getExpiresAt()));
        verify(notificationService).notify(eq(7L), any(), eq("Recuperacion de contrasena"),
                anyString(), any());
        verify(auditService).record(eq(7L), any(), eq("AUTH_PASSWORD_FORGOT"), anyString(),
                anyString(), any(), any());
    }

    @Test
    @DisplayName("un correo desconocido no genera token, correo ni notificacion (anti-enumeracion)")
    void usuarioInexistenteNoGeneraNada() {
        when(userRepository.findByEmailIgnoreCase(EMAIL)).thenReturn(Optional.empty());

        assertThatNoException()
                .isThrownBy(() -> service.forgotPassword(new AuthRequests.ForgotPasswordRequest(EMAIL)));

        verify(passwordResetTokenRepository, never()).save(any(PasswordResetToken.class));
        verify(mailService, never()).sendPasswordResetEmail(anyString(), anyString(), anyString(),
                any(Instant.class));
        verify(notificationService, never()).notify(any(), any(), anyString(), anyString(), any());
        verify(auditService, never()).record(any(), any(), eq("AUTH_PASSWORD_FORGOT"),
                anyString(), anyString(), any(), any());
    }

    @Test
    @DisplayName("una segunda solicitud dentro de la ventana de gracia no rota el enlace ni envia otro correo")
    void ventanaDeGraciaContraElAbusoDelBuzon() {
        User user = usuario();
        when(userRepository.findByEmailIgnoreCase(EMAIL)).thenReturn(Optional.of(user));

        service.forgotPassword(new AuthRequests.ForgotPasswordRequest(EMAIL));
        service.forgotPassword(new AuthRequests.ForgotPasswordRequest(EMAIL));
        service.forgotPassword(new AuthRequests.ForgotPasswordRequest(EMAIL));

        // Si el token se rotara en cada intento, el enlace que el usuario ya abrio
        // dejaria de servir y el buzon se llenaria de mensajes.
        verify(passwordResetTokenRepository, times(1)).save(any(PasswordResetToken.class));
        verify(mailService, times(1)).sendPasswordResetEmail(anyString(), anyString(), anyString(),
                any(Instant.class));
        verify(notificationService, times(1)).notify(eq(7L), any(), anyString(), anyString(), any());
    }

    @Test
    @DisplayName("una solicitud fallida de correo no lanza ni cambia la respuesta")
    void solicitudSobreviveSiElCorreoFalla() {
        User user = usuario();
        when(userRepository.findByEmailIgnoreCase(EMAIL)).thenReturn(Optional.of(user));
        when(mailService.sendPasswordResetEmail(anyString(), anyString(), anyString(),
                any(Instant.class))).thenThrow(new IllegalStateException("canal caido"));

        assertThatNoException()
                .isThrownBy(() -> service.forgotPassword(new AuthRequests.ForgotPasswordRequest(EMAIL)));

        verify(passwordResetTokenRepository).save(any(PasswordResetToken.class));
    }

    // ----------------------------------------------------------- reset

    @Test
    @DisplayName("con token valido se cambia la contrasena, se consume el token y se cierran las sesiones")
    void resetValidoCambiaContrasenaYCierraSesiones() {
        User user = usuario();
        PasswordResetToken token = new PasswordResetToken();
        token.setUser(user);
        token.setPurpose(PasswordResetToken.Purpose.RESET);
        token.setExpiresAt(Instant.now().plus(Duration.ofHours(1)));
        when(passwordResetTokenRepository.findByTokenHash(HASH)).thenReturn(Optional.of(token));
        when(userRepository.findById(7L)).thenReturn(Optional.of(user));
        when(passwordEncoder.encode("Nueva-Clave-2026!")).thenReturn("hash-nuevo");

        RefreshToken sesion1 = new RefreshToken();
        RefreshToken sesion2 = new RefreshToken();
        when(refreshTokenRepository.findAllByUserIdAndRevokedAtIsNull(7L))
                .thenReturn(List.of(sesion1, sesion2));

        service.resetPassword(new AuthRequests.ResetPasswordRequest(RAW_TOKEN, "Nueva-Clave-2026!"));

        assertThat(user.getPasswordHash()).isEqualTo("hash-nuevo");
        assertThat(token.getConsumedAt())
                .as("el enlace no puede volver a usarse")
                .isNotNull();
        assertThat(sesion1.getRevokedAt()).isNotNull();
        assertThat(sesion1.getRevokedReason()).isEqualTo("PASSWORD_RESET");
        assertThat(sesion2.getRevokedAt()).isNotNull();
        assertThat(sesion2.getRevokedReason()).isEqualTo("PASSWORD_RESET");
        verify(refreshTokenRepository, times(2)).save(any(RefreshToken.class));
        verify(auditService).record(eq(7L), any(), eq("AUTH_PASSWORD_RESET"), anyString(),
                anyString(), any(), any());
        verify(auditService).record(eq(7L), any(), eq("AUTH_LOGOUT_ALL"), anyString(),
                anyString(), any(), org.mockito.ArgumentMatchers.argThat(
                        details -> "PASSWORD_RESET".equals(details.get("reason"))));
    }

    @Test
    @DisplayName("un token caducado se rechaza y no cambia nada")
    void tokenCaducadoSeRechaza() {
        User user = usuario();
        PasswordResetToken token = new PasswordResetToken();
        token.setUser(user);
        token.setExpiresAt(Instant.now().minus(Duration.ofMinutes(1)));
        when(passwordResetTokenRepository.findByTokenHash(HASH)).thenReturn(Optional.of(token));

        assertThatThrownBy(() -> service.resetPassword(
                new AuthRequests.ResetPasswordRequest(RAW_TOKEN, "Nueva-Clave-2026!")))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getCode())
                .isEqualTo("EXPIRED_RESET_TOKEN");

        assertThat(user.getPasswordHash()).isEqualTo("hash-viejo");
        verify(refreshTokenRepository, never()).findAllByUserIdAndRevokedAtIsNull(any());
    }

    @Test
    @DisplayName("un token ya consumido no vuelve a servir (uso unico)")
    void tokenReutilizadoSeRechaza() {
        User user = usuario();
        PasswordResetToken token = new PasswordResetToken();
        token.setUser(user);
        token.setExpiresAt(Instant.now().plus(Duration.ofHours(1)));
        token.setConsumedAt(Instant.now().minus(Duration.ofSeconds(5)));
        when(passwordResetTokenRepository.findByTokenHash(HASH)).thenReturn(Optional.of(token));

        assertThatThrownBy(() -> service.resetPassword(
                new AuthRequests.ResetPasswordRequest(RAW_TOKEN, "Nueva-Clave-2026!")))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getCode())
                .isEqualTo("EXPIRED_RESET_TOKEN");

        assertThat(user.getPasswordHash()).isEqualTo("hash-viejo");
    }

    @Test
    @DisplayName("un token desconocido se rechaza sin decir si existe la cuenta")
    void tokenDesconocidoSeRechaza() {
        when(passwordResetTokenRepository.findByTokenHash(HASH)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.resetPassword(
                new AuthRequests.ResetPasswordRequest(RAW_TOKEN, "Nueva-Clave-2026!")))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getCode())
                .isEqualTo("INVALID_RESET_TOKEN");

        verify(passwordEncoder, never()).encode(anyString());
        verify(refreshTokenRepository, never()).findAllByUserIdAndRevokedAtIsNull(any());
    }
}
