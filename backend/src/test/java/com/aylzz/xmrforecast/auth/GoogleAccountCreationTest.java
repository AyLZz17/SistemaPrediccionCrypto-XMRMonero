package com.aylzz.xmrforecast.auth;

import com.aylzz.xmrforecast.audit.AuditService;
import com.aylzz.xmrforecast.auth.dto.TokenResponse;
import com.aylzz.xmrforecast.common.ApiException;
import com.aylzz.xmrforecast.common.LegalDocuments;
import com.aylzz.xmrforecast.mail.MailService;
import com.aylzz.xmrforecast.security.JwtService;
import com.aylzz.xmrforecast.security.Role;
import com.aylzz.xmrforecast.security.TokenHasher;
import com.aylzz.xmrforecast.user.AuthProvider;
import com.aylzz.xmrforecast.user.ConsentRecord;
import com.aylzz.xmrforecast.user.ConsentRecordRepository;
import com.aylzz.xmrforecast.user.ConsentSource;
import com.aylzz.xmrforecast.user.ConsentType;
import com.aylzz.xmrforecast.user.LoginAttemptRepository;
import com.aylzz.xmrforecast.user.OAuthAccount;
import com.aylzz.xmrforecast.user.OAuthAccountRepository;
import com.aylzz.xmrforecast.user.PasswordResetTokenRepository;
import com.aylzz.xmrforecast.user.RefreshTokenRepository;
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

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;

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
 * Los tres caminos del login con Google, con sus pruebas negativas.
 *
 * <p><strong>Defectos que este test fija (auditoria T-042):</strong>
 * <ul>
 *   <li><em>H-1</em>: un usuario nuevo llegado por Google se rechazaba con
 *       {@code NO_LOCAL_ACCOUNT} y se le exigia registrarse primero con
 *       contrasena. Hoy se crea la cuenta con rol {@code VIEWER}, pero solo si
 *       el navegador trajo el consentimiento de los documentos legales.</li>
 *   <li><em>H-2</em>: la comprobacion {@code isActive()} se hacia ANTES de
 *       promover la cuenta a verificada, de modo que una cuenta local sin
 *       confirmar recibia {@code ACCOUNT_UNAVAILABLE} aunque Google acabara de
 *       demostrar su identidad. El orden ahora es: denegar cuentas
 *       suspendidas, aceptar la verificacion de Google, y solo despues mirar
 *       si la cuenta esta activa.</li>
 * </ul>
 */
class GoogleAccountCreationTest {

    private static final String SUBJECT = "1038592254376-google-sub";
    private static final String EMAIL = "nuevo@example.com";

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
                user.setId(77L);
            }
            return user;
        });
        when(jwtService.issueAccessToken(any(), any(), any()))
                .thenReturn(new JwtService.IssuedToken("jwt-de-prueba", "jti-de-prueba",
                        Instant.now().plusSeconds(900)));
        when(oauthAccountRepository.findByProviderAndProviderSubject(eq(AuthProvider.GOOGLE),
                anyString())).thenReturn(Optional.empty());
    }

    private static AuthService.GoogleIdentity identity(boolean emailVerified) {
        return new AuthService.GoogleIdentity(SUBJECT, EMAIL, "Persona Nueva", emailVerified);
    }

    private static AuthService.GoogleConsent consent() {
        return new AuthService.GoogleConsent(true, true, false);
    }

    private static User localAccount(UserStatus status, boolean verified) {
        User user = new User();
        user.setId(5L);
        user.setEmail(EMAIL);
        user.setFullName("Cuenta Local");
        user.setPasswordHash("$2a$12$hash");
        user.setProvider(AuthProvider.LOCAL);
        user.setStatus(status);
        user.setEmailVerified(verified);
        user.setRoles(new LinkedHashSet<>(Set.of(Role.VIEWER)));
        return user;
    }

    @Test
    @DisplayName("un usuario nuevo con Google y consentimiento crea la cuenta con rol VIEWER")
    void cuentaNuevaSeCreaConRolViewer() {
        when(userRepository.findByEmailIgnoreCase(EMAIL)).thenReturn(Optional.empty());

        TokenResponse tokens = service.loginWithGoogle(identity(true), consent(),
                "203.0.113.9", "junit");

        assertThat(tokens.accessToken()).isEqualTo("jwt-de-prueba");

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).saveAndFlush(captor.capture());
        User creado = captor.getValue();
        assertThat(creado.getProvider()).isEqualTo(AuthProvider.GOOGLE);
        assertThat(creado.getStatus()).isEqualTo(UserStatus.ACTIVE);
        assertThat(creado.isEmailVerified())
                .as("Google ya verifico el correo: no se vuelve a pedir")
                .isTrue();
        assertThat(creado.getPasswordHash())
                .as("una cuenta Google no tiene contrasena local que recordar")
                .isNull();
        assertThat(creado.getRoles()).containsExactly(Role.VIEWER);

        // Vinculacion y consentimiento, los dos escritos en la misma transaccion.
        verify(oauthAccountRepository).save(any(OAuthAccount.class));
        ArgumentCaptor<ConsentRecord> consent = ArgumentCaptor.forClass(ConsentRecord.class);
        verify(consentRecordRepository, org.mockito.Mockito.times(2)).save(consent.capture());
        assertThat(consent.getAllValues())
                .extracting(ConsentRecord::getConsentType)
                .containsExactly(ConsentType.TERMS, ConsentType.DATA_POLICY);
        assertThat(consent.getAllValues())
                .allSatisfy(record -> {
                    assertThat(record.getSource()).isEqualTo(ConsentSource.GOOGLE_OAUTH);
                    assertThat(record.getVersion()).isEqualTo(LegalDocuments.CURRENT_VERSION);
                    assertThat(record.isAccepted()).isTrue();
                });
        verify(auditService).success(eq(77L), anyString(), eq("AUTH_GOOGLE_REGISTER"),
                anyString(), anyString(), any());
    }

    @Test
    @DisplayName("sin consentimiento no se crea nada: se responde CONSENT_REQUIRED")
    void sinConsentimientoNoSeCreaNada() {
        when(userRepository.findByEmailIgnoreCase(EMAIL)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.loginWithGoogle(identity(true), null,
                "203.0.113.9", "junit"))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getCode())
                .isEqualTo("CONSENT_REQUIRED");

        verify(userRepository, never()).saveAndFlush(any(User.class));
        verify(oauthAccountRepository, never()).save(any(OAuthAccount.class));
        verify(consentRecordRepository, never()).save(any(ConsentRecord.class));
    }

    @Test
    @DisplayName("un consentimiento incompleto (solo terminos) tampoco crea la cuenta")
    void consentimientoIncompletoNoCreaNada() {
        when(userRepository.findByEmailIgnoreCase(EMAIL)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.loginWithGoogle(identity(true),
                new AuthService.GoogleConsent(true, false, false), "203.0.113.9", "junit"))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getCode())
                .isEqualTo("CONSENT_REQUIRED");

        verify(userRepository, never()).saveAndFlush(any(User.class));
    }

    @Test
    @DisplayName("H-2: una cuenta local sin confirmar se activa con Google, no se rechaza")
    void cuentaLocalNoVerificadaSeActivaConGoogle() {
        User local = localAccount(UserStatus.PENDING_VERIFICATION, false);
        when(userRepository.findByEmailIgnoreCase(EMAIL)).thenReturn(Optional.of(local));
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

        // Sin consentimiento: no hace falta, la cuenta ya existe y acepto antes.
        TokenResponse tokens = service.loginWithGoogle(identity(true), null,
                "203.0.113.9", "junit");

        assertThat(tokens.accessToken()).isEqualTo("jwt-de-prueba");
        assertThat(local.getStatus())
                .as("Google acaba de demostrar la identidad: la cuenta pasa a activa")
                .isEqualTo(UserStatus.ACTIVE);
        assertThat(local.isEmailVerified()).isTrue();
        assertThat(local.getEmailVerifiedAt()).isNotNull();

        verify(oauthAccountRepository).save(any(OAuthAccount.class));
        verify(userRepository, never()).saveAndFlush(any(User.class));
        verify(consentRecordRepository, never()).save(any(ConsentRecord.class));
    }

    @Test
    @DisplayName("una cuenta vinculada entra sin volver a buscarla por correo")
    void cuentaVinculadaEntraDirectamente() {
        User local = localAccount(UserStatus.ACTIVE, true);
        OAuthAccount account = new OAuthAccount();
        account.setUser(local);
        account.setProvider(AuthProvider.GOOGLE);
        account.setProviderSubject(SUBJECT);
        when(oauthAccountRepository.findByProviderAndProviderSubject(AuthProvider.GOOGLE, SUBJECT))
                .thenReturn(Optional.of(account));

        TokenResponse tokens = service.loginWithGoogle(identity(true), null,
                "203.0.113.9", "junit");

        assertThat(tokens.accessToken()).isEqualTo("jwt-de-prueba");
        // La identidad la resuelve el sub: no se consulta el correo, que podria
        // haber cambiado de propietario.
        verify(userRepository, never()).findByEmailIgnoreCase(anyString());
        verify(oauthAccountRepository, never()).save(any(OAuthAccount.class));
    }

    @Test
    @DisplayName("una cuenta suspendida no entra por Google")
    void cuentaSuspendidaNoEntra() {
        User suspendida = localAccount(UserStatus.SUSPENDED, true);
        OAuthAccount account = new OAuthAccount();
        account.setUser(suspendida);
        when(oauthAccountRepository.findByProviderAndProviderSubject(AuthProvider.GOOGLE, SUBJECT))
                .thenReturn(Optional.of(account));

        assertThatThrownBy(() -> service.loginWithGoogle(identity(true), null,
                "203.0.113.9", "junit"))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getCode())
                .isEqualTo("ACCOUNT_UNAVAILABLE");
    }

    @Test
    @DisplayName("un correo de Google sin verificar no toma control de nada")
    void correoDeGoogleNoVerificadoSeRechaza() {
        assertThatThrownBy(() -> service.loginWithGoogle(identity(false), consent(),
                "203.0.113.9", "junit"))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getCode())
                .isEqualTo("UNVERIFIED_GOOGLE_ACCOUNT");

        verify(userRepository, never()).saveAndFlush(any(User.class));
        verify(userRepository, never()).findByEmailIgnoreCase(anyString());
    }

    @Test
    @DisplayName("una identidad sin sub se rechaza antes de tocar la base")
    void identidadSinSubjectSeRechaza() {
        AuthService.GoogleIdentity sinSubject =
                new AuthService.GoogleIdentity("  ", EMAIL, "Persona", true);

        assertThatThrownBy(() -> service.loginWithGoogle(sinSubject, consent(),
                "203.0.113.9", "junit"))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getCode())
                .isEqualTo("INVALID_ID_TOKEN");

        verify(userRepository, never()).saveAndFlush(any(User.class));
    }

    @Test
    @DisplayName("el nombre viene del claim name de Google, con el correo de respaldo")
    void elNombreDelUsuarioSaleDelClaimName() {
        when(userRepository.findByEmailIgnoreCase(EMAIL)).thenReturn(Optional.empty());

        service.loginWithGoogle(
                new AuthService.GoogleIdentity(SUBJECT, EMAIL, null, true),
                consent(), "203.0.113.9", "junit");

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).saveAndFlush(captor.capture());
        assertThat(captor.getValue().getFullName())
                .as("sin claim name se usa la parte local del correo, nunca vacio")
                .isEqualTo("nuevo");
    }
}
