package com.aylzz.xmrforecast.auth;

import com.aylzz.xmrforecast.audit.AuditService;
import com.aylzz.xmrforecast.auth.dto.AuthRequests;
import com.aylzz.xmrforecast.auth.dto.UserResponse;
import com.aylzz.xmrforecast.mail.MailService;
import com.aylzz.xmrforecast.security.JwtService;
import com.aylzz.xmrforecast.security.TokenHasher;
import com.aylzz.xmrforecast.user.LoginAttemptRepository;
import com.aylzz.xmrforecast.user.OAuthAccountRepository;
import com.aylzz.xmrforecast.user.PasswordResetToken;
import com.aylzz.xmrforecast.user.PasswordResetTokenRepository;
import com.aylzz.xmrforecast.user.RefreshTokenRepository;
import com.aylzz.xmrforecast.user.RevokedTokenRepository;
import com.aylzz.xmrforecast.user.User;
import com.aylzz.xmrforecast.user.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.core.task.TaskExecutor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * La entrega de correo no puede decidir si el registro sobrevive.
 *
 * <p><strong>El fallo que este test fija.</strong> El envio del correo de
 * verificacion ocurría dentro de {@code register()}, que es {@code @Transactional},
 * y {@code MailService} lanzaba {@code ApiException} cuando el canal fallaba.
 * En Render los puertos SMTP estan bloqueados, asi que cada intento de registro:
 * (1) dejaba la peticion colgada dos minutos sin timeout de conexion, y (2)
 * marcaba la transaccion rollback-only, de modo que el alta de usuario se
 * perdia y el cliente recibia un 503. El usuario veia "timeout al crear cuenta",
 * la tabla {@code users} quedaba vacia y ningun correo llegaba: tres sintomas,
 * una sola causa.
 *
 * <p>Verificado en produccion el 2026-10-01 con
 * {@code MailConnectException: Couldn't connect to host, port: smtp.gmail.com, 587}
 * seguido de {@code EMAIL_DELIVERY_FAILED status=503}.
 */
class RegisterMailDeliveryTest {

    private static final String EMAIL = "daniel@example.com";

    private final UserRepository userRepository = mock(UserRepository.class);
    private final RefreshTokenRepository refreshTokenRepository = mock(RefreshTokenRepository.class);
    private final OAuthAccountRepository oauthAccountRepository = mock(OAuthAccountRepository.class);
    private final LoginAttemptRepository loginAttemptRepository = mock(LoginAttemptRepository.class);
    private final PasswordResetTokenRepository passwordResetTokenRepository =
            mock(PasswordResetTokenRepository.class);
    private final RevokedTokenRepository revokedTokenRepository = mock(RevokedTokenRepository.class);
    private final PasswordEncoder passwordEncoder = mock(PasswordEncoder.class);
    private final JwtService jwtService = mock(JwtService.class);
    private final TokenHasher tokenHasher = mock(TokenHasher.class);
    private final AuditService auditService = mock(AuditService.class);
    private final NotificationService notificationService = mock(NotificationService.class);
    private final MailService mailService = mock(MailService.class);

    private AuthService service;

    @BeforeEach
    void setUp() {
        // AppProperties es un record, no se puede generar un mock: registrar no
        // lo consulta, asi que se inyecta null a proposito.
        service = new AuthService(userRepository, refreshTokenRepository, oauthAccountRepository,
                loginAttemptRepository, passwordResetTokenRepository, revokedTokenRepository,
                passwordEncoder, jwtService, tokenHasher, auditService, notificationService, null);
        ReflectionTestUtils.setField(service, "mailService", mailService);

        when(userRepository.saveAndFlush(any(User.class)))
                .thenAnswer(invocation -> {
                    User user = invocation.getArgument(0);
                    // La base asigna la clave en la escritura real.
                    if (user.getId() == null) {
                        user.setId(1L);
                    }
                    return user;
                });
        when(mailService.sendVerificationEmail(anyString(), anyString(), anyString()))
                .thenReturn(true);
    }

    @AfterEach
    void clearSynchronization() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    private static AuthRequests.RegisterRequest request() {
        return new AuthRequests.RegisterRequest(EMAIL, "Correcta-2026!", "Daniel Prueba");
    }

    @Test
    @DisplayName("un canal de correo caido no impide crear la cuenta")
    void registroSobreviveCuandoElCanalDeCorreoFalla() {
        when(mailService.sendVerificationEmail(anyString(), anyString(), anyString()))
                .thenThrow(new IllegalStateException("canal de correo caido"));

        UserResponse created = service.register(request(), "127.0.0.1", "junit");

        assertThat(created.email()).isEqualTo(EMAIL);
        verify(userRepository).saveAndFlush(any(User.class));
    }

    @Test
    @DisplayName("el correo se entrega despues de confirmar la transaccion, no dentro")
    void elCorreoSeEnviaDespuesDeConfirmarLaTransaccion() {
        TransactionSynchronizationManager.initSynchronization();

        service.register(request(), "127.0.0.1", "junit");

        // Dentro de la transaccion el canal no se toca: si lo hiciera, un fallo
        // posterior seguira revirtiendo el alta.
        verify(mailService, never()).sendVerificationEmail(anyString(), anyString(), anyString());

        TransactionSynchronizationManager.getSynchronizations()
                .forEach(TransactionSynchronization::afterCommit);

        verify(mailService).sendVerificationEmail(eq(EMAIL), anyString(), anyString());
    }

    @Test
    @DisplayName("la tarea pos-commit se despacha a un hilo propio, no al hilo de la peticion")
    void laTareaPosCommitSeDespachaAUnHiloPropio() {
        TaskExecutor executor = mock(TaskExecutor.class);
        ReflectionTestUtils.setField(service, "taskExecutor", executor);
        TransactionSynchronizationManager.initSynchronization();

        service.register(request(), "127.0.0.1", "junit");
        TransactionSynchronizationManager.getSynchronizations()
                .forEach(TransactionSynchronization::afterCommit);

        // En el hilo de la peticion la transaccion esta cerrada pero sus recursos
        // siguen atados: una escritura ahi entra en esa transaccion caducada, no
        // se confirma y la conexion la revierte al volver al pool. Por eso NO se
        // ejecuta ahi. Verificado en local: el usuario se creaba y la
        // notificacion no aparecia nunca en la tabla notifications.
        ArgumentCaptor<Runnable> pending = ArgumentCaptor.forClass(Runnable.class);
        verify(executor).execute(pending.capture());
        verify(mailService, never()).sendVerificationEmail(anyString(), anyString(), anyString());

        // Ya en el hilo propio (sin transaccion atada) el trabajo si se hace.
        pending.getValue().run();
        verify(mailService).sendVerificationEmail(eq(EMAIL), anyString(), anyString());
        verify(notificationService).notify(
                eq(1L),
                eq(NotificationService.NotificationType.ACCOUNT),
                eq("Confirme su correo"),
                contains("Enviamos un enlace de verificacion"),
                eq(NotificationService.Severity.INFO));
    }

    @Test
    @DisplayName("si el executor rechaza, la tarea se ejecuta igualmente en el hilo actual")
    void executorRechazadoNoPierdeLaTarea() {
        TaskExecutor executor = mock(TaskExecutor.class);
        doThrow(new IllegalStateException("pool lleno")).when(executor).execute(any());
        ReflectionTestUtils.setField(service, "taskExecutor", executor);
        TransactionSynchronizationManager.initSynchronization();

        service.register(request(), "127.0.0.1", "junit");
        TransactionSynchronizationManager.getSynchronizations()
                .forEach(TransactionSynchronization::afterCommit);

        verify(mailService).sendVerificationEmail(eq(EMAIL), anyString(), anyString());
    }

    @Test
    @DisplayName("sin transaccion activa el correo se envia de inmediato")
    void sinTransaccionSeEnviaDeInmediato() {
        service.register(request(), "127.0.0.1", "junit");

        verify(mailService).sendVerificationEmail(eq(EMAIL), anyString(), anyString());
    }

    @Test
    @DisplayName("cuando el correo sale, la bandeja lo anuncia como enviado")
    void envioExitosoSeNotificaComoEnviado() {
        service.register(request(), "127.0.0.1", "junit");

        verify(notificationService).notify(
                eq(1L),
                eq(NotificationService.NotificationType.ACCOUNT),
                eq("Confirme su correo"),
                contains("Enviamos un enlace de verificacion"),
                eq(NotificationService.Severity.INFO));
    }

    @Test
    @DisplayName("cuando el correo no sale, la bandeja lo dice en lugar de anunciar un enlace inexistente")
    void envioFallidoSeNotificaComoNoEnviado() {
        when(mailService.sendVerificationEmail(anyString(), anyString(), anyString()))
                .thenReturn(false);

        UserResponse created = service.register(request(), "127.0.0.1", "junit");

        assertThat(created.email()).isEqualTo(EMAIL);
        verify(notificationService).notify(
                eq(1L),
                eq(NotificationService.NotificationType.ACCOUNT),
                eq("Correo de verificacion no enviado"),
                contains("pendiente de confirmacion"),
                eq(NotificationService.Severity.WARNING));
    }

    @Test
    @DisplayName("un fallo de la bandeja de notificaciones tampoco rompe el registro")
    void falloDeLaBandejaNoRompeElRegistro() {
        doThrow(new IllegalStateException("bandeja caida"))
                .when(notificationService)
                .notify(any(), any(), anyString(), anyString(), any());

        assertThatNoException().isThrownBy(() -> service.register(request(), "127.0.0.1", "junit"));
        verify(userRepository).saveAndFlush(any(User.class));
    }

    @Test
    @DisplayName("la recuperacion de contrasena sobrevive a un canal de correo caido")
    void recuperacionDeContrasenaSobreviveSiElCorreoFalla() {
        User user = new User();
        user.setId(7L);
        user.setEmail(EMAIL);
        user.setFullName("Daniel Prueba");
        when(userRepository.findByEmailIgnoreCase(EMAIL)).thenReturn(Optional.of(user));
        when(mailService.sendPasswordResetEmail(anyString(), anyString(), anyString()))
                .thenThrow(new IllegalStateException("canal de correo caido"));

        assertThatNoException()
                .isThrownBy(() -> service.forgotPassword(new AuthRequests.ForgotPasswordRequest(EMAIL)));

        verify(passwordResetTokenRepository).save(any(PasswordResetToken.class));
    }
}
