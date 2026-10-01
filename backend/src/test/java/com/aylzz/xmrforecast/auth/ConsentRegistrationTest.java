package com.aylzz.xmrforecast.auth;

import com.aylzz.xmrforecast.audit.AuditService;
import com.aylzz.xmrforecast.auth.dto.AuthRequests;
import com.aylzz.xmrforecast.auth.dto.UserResponse;
import com.aylzz.xmrforecast.common.ApiException;
import com.aylzz.xmrforecast.common.LegalDocuments;
import com.aylzz.xmrforecast.mail.MailService;
import com.aylzz.xmrforecast.security.JwtService;
import com.aylzz.xmrforecast.security.TokenHasher;
import com.aylzz.xmrforecast.user.ConsentRecord;
import com.aylzz.xmrforecast.user.ConsentRecordRepository;
import com.aylzz.xmrforecast.user.ConsentSource;
import com.aylzz.xmrforecast.user.ConsentType;
import com.aylzz.xmrforecast.user.LoginAttemptRepository;
import com.aylzz.xmrforecast.user.OAuthAccountRepository;
import com.aylzz.xmrforecast.user.PasswordResetToken;
import com.aylzz.xmrforecast.user.PasswordResetTokenRepository;
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

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * El registro no puede completarse sin una aceptacion registrada.
 *
 * <p><strong>Lo que este test fija.</strong> El prompt exige checkboxes
 * obligatorios de terminos y de politica de datos, con version, fecha, usuario
 * e IP demostrables. Un checkbox solo en el frontend no es eso: si el servidor
 * no lo comprueba, cualquier cliente que se salte la UI crea cuentas sin
 * autorizacion, y la tabla de consentimiento queda vacia para siempre.
 *
 * <p>Por eso hay dos capas con sus propias pruebas: la anotacion, verificada
 * sobre instancias reales en {@code BeanValidationConstraintTest} (R-46), y esta
 * que comprueba la logica de {@code AuthService}, que es la que corre aunque
 * alguna ruta llegue a construir el objeto sin {@code @Valid}.
 */
class ConsentRegistrationTest {

    private static final String EMAIL = "consentimiento@example.com";

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

        when(userRepository.saveAndFlush(any(User.class))).thenAnswer(invocation -> {
            User user = invocation.getArgument(0);
            if (user.getId() == null) {
                user.setId(42L);
            }
            return user;
        });
        when(passwordEncoder.encode(anyString())).thenReturn("hash-bcrypt");
    }

    private static AuthRequests.RegisterRequest registro(Boolean terms, Boolean policy,
                                                         Boolean marketing) {
        return new AuthRequests.RegisterRequest(EMAIL, "Correcta-2026!", "Consent Prueba",
                terms, policy, marketing);
    }

    @Test
    @DisplayName("sin aceptar los terminos no se crea la cuenta ni se escribe nada")
    void sinTerminosNoSeCreaNada() {
        assertThatThrownBy(() -> service.register(registro(false, true, false),
                "203.0.113.9", "junit"))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getCode())
                .isEqualTo("CONSENT_REQUIRED");

        verify(userRepository, never()).saveAndFlush(any(User.class));
        verify(consentRecordRepository, never()).save(any(ConsentRecord.class));
    }

    @Test
    @DisplayName("sin aceptar la politica de datos no se crea la cuenta")
    void sinPoliticaDeDatosNoSeCreaNada() {
        assertThatThrownBy(() -> service.register(registro(true, false, false),
                "203.0.113.9", "junit"))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getCode())
                .isEqualTo("CONSENT_REQUIRED");

        verify(userRepository, never()).saveAndFlush(any(User.class));
    }

    @Test
    @DisplayName("aceptes nulos se rechazan igual que false")
    void aceptesNulosSeRechazan() {
        assertThatThrownBy(() -> service.register(registro(null, null, null),
                "203.0.113.9", "junit"))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getCode())
                .isEqualTo("CONSENT_REQUIRED");
    }

    @Test
    @DisplayName("con los dos aceptes se registran las dos aceptaciones con su version, IP y agente")
    void seRegistranLasDosAceptaciones() {
        UserResponse created = service.register(registro(true, true, false),
                "203.0.113.9", "junit/1.0");

        assertThat(created.email()).isEqualTo(EMAIL);

        ArgumentCaptor<ConsentRecord> captor = ArgumentCaptor.forClass(ConsentRecord.class);
        verify(consentRecordRepository, org.mockito.Mockito.times(2)).save(captor.capture());

        List<ConsentRecord> records = captor.getAllValues();
        assertThat(records)
                .extracting(ConsentRecord::getConsentType)
                .containsExactly(ConsentType.TERMS, ConsentType.DATA_POLICY);
        assertThat(records)
                .allSatisfy(record -> {
                    assertThat(record.getVersion())
                            .as("la version es la del texto publicado, no una libre")
                            .isEqualTo(LegalDocuments.CURRENT_VERSION);
                    assertThat(record.isAccepted()).isTrue();
                    assertThat(record.getSource()).isEqualTo(ConsentSource.REGISTER);
                    assertThat(record.getIpAddress()).isEqualTo("203.0.113.9");
                    assertThat(record.getUserAgent()).isEqualTo("junit/1.0");
                    assertThat(record.getAcceptedAt()).isNotNull();
                    assertThat(record.getUser().getId()).isEqualTo(42L);
                });
    }

    @Test
    @DisplayName("las comunicaciones comerciales opcionales solo generan fila si se aceptan")
    void marketingSoloSiSeAcepta() {
        service.register(registro(true, true, true), "203.0.113.9", "junit");

        ArgumentCaptor<ConsentRecord> captor = ArgumentCaptor.forClass(ConsentRecord.class);
        verify(consentRecordRepository, org.mockito.Mockito.times(3)).save(captor.capture());
        assertThat(captor.getAllValues())
                .extracting(ConsentRecord::getConsentType)
                .contains(ConsentType.MARKETING);
    }

    @Test
    @DisplayName("sin marketing la tabla queda sin fila de marketing: ausencia = no aceptado")
    void sinMarketingNoHayFilaDeMarketing() {
        service.register(registro(true, true, false), "203.0.113.9", "junit");

        ArgumentCaptor<ConsentRecord> captor = ArgumentCaptor.forClass(ConsentRecord.class);
        verify(consentRecordRepository, org.mockito.Mockito.times(2)).save(captor.capture());
        assertThat(captor.getAllValues())
                .extracting(ConsentRecord::getConsentType)
                .doesNotContain(ConsentType.MARKETING);
    }

    @Test
    @DisplayName("el reenvio de verificacion no revela si la cuenta existe")
    void reenvioAntiEnumeracion() {
        when(userRepository.findByEmailIgnoreCase("nadie@example.com"))
                .thenReturn(java.util.Optional.empty());

        // Ni excepcion ni envio: la respuesta es la misma que para una cuenta real.
        service.resendVerification(new AuthRequests.ResendVerificationRequest("nadie@example.com"),
                "203.0.113.9", "junit");

        verify(mailService, never()).sendVerificationEmail(anyString(), anyString(), anyString());
        verify(notificationService, never()).notify(any(), any(), anyString(), anyString(), any());
        verify(passwordResetTokenRepository, never()).save(any(PasswordResetToken.class));
    }

    @Test
    @DisplayName("el reenvio emite un token nuevo y caduca el anterior")
    void reenvioInvalidaElTokenAnterior() {
        User user = new User();
        user.setId(9L);
        user.setEmail(EMAIL);
        user.setFullName("Consent Prueba");
        when(userRepository.findByEmailIgnoreCase(EMAIL)).thenReturn(java.util.Optional.of(user));

        PasswordResetToken anterior = new PasswordResetToken();
        anterior.setConsumedAt(null);
        when(passwordResetTokenRepository.findAllByUserIdAndPurposeAndConsumedAtIsNull(
                9L, PasswordResetToken.Purpose.VERIFY_EMAIL))
                .thenReturn(List.of(anterior));

        service.resendVerification(new AuthRequests.ResendVerificationRequest(EMAIL),
                "203.0.113.9", "junit");

        assertThat(anterior.getConsumedAt())
                .as("el ultimo enlace emitido es el unico que sirve")
                .isNotNull();
        verify(passwordResetTokenRepository, org.mockito.Mockito.times(2))
                .save(any(PasswordResetToken.class));
        verify(mailService).sendVerificationEmail(eq(EMAIL), anyString(), anyString());
        verify(auditService).record(eq(9L), any(), eq("AUTH_EMAIL_RESENT"), anyString(),
                anyString(), any(), any());
    }

    @Test
    @DisplayName("el reenvio a una cuenta ya verificada no vuelve a enviar nada")
    void reenvioAContaVerificadaNoEnvia() {
        User user = new User();
        user.setId(10L);
        user.setEmail(EMAIL);
        user.setFullName("Consent Prueba");
        user.setEmailVerified(true);
        user.setStatus(com.aylzz.xmrforecast.user.UserStatus.ACTIVE);
        when(userRepository.findByEmailIgnoreCase(EMAIL)).thenReturn(java.util.Optional.of(user));

        service.resendVerification(new AuthRequests.ResendVerificationRequest(EMAIL),
                "203.0.113.9", "junit");

        verify(mailService, never()).sendVerificationEmail(anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("un canal de correo caido en el reenvio no lanza excepcion")
    void reenvioSobreviveSiElCorreoFalla() {
        User user = new User();
        user.setId(11L);
        user.setEmail(EMAIL);
        user.setFullName("Consent Prueba");
        when(userRepository.findByEmailIgnoreCase(EMAIL)).thenReturn(java.util.Optional.of(user));
        when(mailService.sendVerificationEmail(anyString(), anyString(), anyString()))
                .thenThrow(new IllegalStateException("canal caido"));

        org.assertj.core.api.Assertions.assertThatNoException().isThrownBy(
                () -> service.resendVerification(
                        new AuthRequests.ResendVerificationRequest(EMAIL), "203.0.113.9", "junit"));
    }
}
