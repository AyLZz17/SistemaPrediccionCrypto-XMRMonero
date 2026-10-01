package com.aylzz.xmrforecast.auth;

import com.aylzz.xmrforecast.audit.AuditEvent;
import com.aylzz.xmrforecast.audit.AuditService;
import com.aylzz.xmrforecast.auth.dto.AuthRequests;
import com.aylzz.xmrforecast.auth.dto.TokenResponse;
import com.aylzz.xmrforecast.auth.dto.UserResponse;
import com.aylzz.xmrforecast.common.ApiException;
import com.aylzz.xmrforecast.common.LegalDocuments;
import com.aylzz.xmrforecast.config.AppProperties;
import com.aylzz.xmrforecast.mail.MailService;
import com.aylzz.xmrforecast.security.JwtService;
import com.aylzz.xmrforecast.security.Role;
import com.aylzz.xmrforecast.security.TokenHasher;
import com.aylzz.xmrforecast.user.AuthProvider;
import com.aylzz.xmrforecast.user.ConsentRecord;
import com.aylzz.xmrforecast.user.ConsentRecordRepository;
import com.aylzz.xmrforecast.user.ConsentSource;
import com.aylzz.xmrforecast.user.ConsentType;
import com.aylzz.xmrforecast.user.LoginAttempt;
import com.aylzz.xmrforecast.user.LoginAttemptRepository;
import com.aylzz.xmrforecast.user.OAuthAccount;
import com.aylzz.xmrforecast.user.OAuthAccountRepository;
import com.aylzz.xmrforecast.user.PasswordResetToken;
import com.aylzz.xmrforecast.user.PasswordResetTokenRepository;
import com.aylzz.xmrforecast.user.RefreshToken;
import com.aylzz.xmrforecast.user.RefreshTokenRepository;
import com.aylzz.xmrforecast.user.RevokedTokenRepository;
import com.aylzz.xmrforecast.user.User;
import com.aylzz.xmrforecast.user.UserRepository;
import com.aylzz.xmrforecast.user.UserStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Servicio de autenticacion: registro, login tradicional, renovacion con rotacion
 * y revocacion, recuperacion de contrasena y vinculacion de cuentas Google.
 *
 * <p>Ningun camino de este servicio persiste ni registra una contrasena o un token
 * en claro (R-14). Los refresh tokens se almacenan como HMAC-SHA256.
 */
@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    private static final Duration FAILED_LOGIN_WINDOW = Duration.ofMinutes(15);

    /**
     * Executor de la aplicacion (bean {@code applicationTaskExecutor} de Spring
     * Boot). Es opcional: sin el —solo en pruebas unitarias— las tareas
     * pos-commit corren en el mismo hilo, que es correcto cuando no hay
     * transaccion real que haya dejado recursos atados.
     */
    @Autowired(required = false)
    @Qualifier("applicationTaskExecutor")
    private TaskExecutor taskExecutor;
    private static final Duration RESET_TOKEN_TTL = Duration.ofHours(2);
    private static final Duration VERIFY_TOKEN_TTL = Duration.ofHours(48);

    /**
     * Ventana de gracia entre dos correos de recuperacion de la misma cuenta.
     * Solo frena el envio: no invalida el enlace ya emitido.
     */
    private static final Duration RESET_MAIL_COOLDOWN = Duration.ofMinutes(2);

    private final UserRepository userRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final OAuthAccountRepository oauthAccountRepository;
    private final LoginAttemptRepository loginAttemptRepository;
    private final PasswordResetTokenRepository passwordResetTokenRepository;
    private final RevokedTokenRepository revokedTokenRepository;
    private final ConsentRecordRepository consentRecordRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final TokenHasher tokenHasher;
    private final AuditService auditService;
    private final NotificationService notificationService;
    private final AppProperties properties;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private MailService mailService;

    /**
     * Tokens opacos pendientes de entrega por correo. Solo viven en memoria del
     * proceso: nunca se escriben en disco ni en la base de datos en claro (R-14).
     */
    private final Map<Long, String> pendingResetTokens = new ConcurrentHashMap<>();
    private final Map<Long, String> pendingVerificationTokens = new ConcurrentHashMap<>();

    /**
     * Ultimo envio de correo de recuperacion por usuario (ventana de gracia).
     * En memoria: al igual que los propios tokens pendientes, es estado de un
     * solo proceso; con varias replicas haria falta Redis (ver D-09).
     */
    private final Map<Long, Instant> lastResetMailAt = new ConcurrentHashMap<>();

    public AuthService(UserRepository userRepository,
                       RefreshTokenRepository refreshTokenRepository,
                       OAuthAccountRepository oauthAccountRepository,
                       LoginAttemptRepository loginAttemptRepository,
                       PasswordResetTokenRepository passwordResetTokenRepository,
                       RevokedTokenRepository revokedTokenRepository,
                       ConsentRecordRepository consentRecordRepository,
                       PasswordEncoder passwordEncoder,
                       JwtService jwtService,
                       TokenHasher tokenHasher,
                       AuditService auditService,
                       NotificationService notificationService,
                       AppProperties properties) {
        this.userRepository = userRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.oauthAccountRepository = oauthAccountRepository;
        this.loginAttemptRepository = loginAttemptRepository;
        this.passwordResetTokenRepository = passwordResetTokenRepository;
        this.revokedTokenRepository = revokedTokenRepository;
        this.consentRecordRepository = consentRecordRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.tokenHasher = tokenHasher;
        this.auditService = auditService;
        this.notificationService = notificationService;
        this.properties = properties;
    }

    // ------------------------------------------------------------- registro

    @Transactional
    public UserResponse register(AuthRequests.RegisterRequest request, String ip, String userAgent) {
        // El consentimiento se comprueba AQUI, no solo en el controlador: una
        // ruta que construya el objeto sin @Valid no debe poder crear una cuenta
        // sin registro de aceptacion (R-16 y R-46).
        requireConsent(request.acceptTerms(), request.acceptDataPolicy());

        String email = normalizeEmail(request.email());
        if (userRepository.existsByEmailIgnoreCase(email)) {
            // Mensaje generico: no se revela que el correo ya existe (anti-enumeracion).
            throw ApiException.conflict("REGISTRATION_REJECTED",
                    "No se puede completar el registro con esos datos.");
        }

        User user = new User();
        user.setEmail(email);
        user.setFullName(request.fullName().trim());
        user.setPasswordHash(passwordEncoder.encode(request.password()));
        user.setProvider(AuthProvider.LOCAL);
        user.setStatus(UserStatus.PENDING_VERIFICATION);
        user.setRoles(new LinkedHashSet<>(Set.of(Role.VIEWER)));

        User saved;
        try {
            saved = userRepository.saveAndFlush(user);
        } catch (DataIntegrityViolationException ex) {
            // Carrera entre dos registros simultaneos con el mismo correo.
            throw ApiException.conflict("REGISTRATION_REJECTED",
                    "No se puede completar el registro con esos datos.");
        }

        // Se registran en la MISMA transaccion que el usuario: sin aceptacion no
        // hay cuenta, y con ella la prueba queda escrita antes de responder 201.
        recordConsent(saved, ConsentType.TERMS, true, ConsentSource.REGISTER, ip, userAgent);
        recordConsent(saved, ConsentType.DATA_POLICY, true, ConsentSource.REGISTER, ip, userAgent);
        if (Boolean.TRUE.equals(request.acceptMarketing())) {
            // Solo la aceptacion genera fila: la ausencia ya significa
            // "no quiere comunicaciones comerciales".
            recordConsent(saved, ConsentType.MARKETING, true, ConsentSource.REGISTER, ip, userAgent);
        }

        IssuedToken verification = issueVerificationToken(saved);
        // El correo se entrega DESPUES de confirmar la transaccion, nunca dentro.
        // Dentro, un fallo del canal (SMTP bloqueado en Render, Gmail API caida)
        // marcaba la transaccion rollback-only: el alta de usuario se perdia con
        // un 503 y la peticion se quedaba colgada hasta el timeout del SO.
        User created = saved;
        afterCommit(() -> deliverVerificationMail(created, verification.plain(), verification.expiresAt()));

        auditService.success(saved.getId(), Role.VIEWER.name(), "AUTH_REGISTER", "User",
                String.valueOf(saved.getId()), Map.of("provider", "LOCAL"));

        return toResponse(saved);
    }

    /**
     * Entrega el enlace de verificacion y refleja el resultado en la bandeja.
     *
     * <p>Nunca lanza: el usuario ya esta confirmado en base de datos y el correo
     * no debe poder deshacerlo. Si el envio falla, la notificacion lo dice
     * claramente en lugar de anunciar un enlace que no salio (R-21: no se afirma
     * lo que no ocurrio).
     */
    private void deliverVerificationMail(User user, String token, Instant expiresAt) {
        boolean delivered = false;
        try {
            delivered = mailService != null
                    && mailService.sendVerificationEmail(user.getEmail(), user.getFullName(),
                            token, expiresAt);
        } catch (RuntimeException ex) {
            log.error("La entrega del correo de verificacion fallo para el usuario {}", user.getId(), ex);
        }
        if (delivered) {
            notify(user.getId(), NotificationService.NotificationType.ACCOUNT,
                    "Confirme su correo",
                    "Enviamos un enlace de verificacion a " + user.getEmail(),
                    NotificationService.Severity.INFO);
        } else {
            notify(user.getId(), NotificationService.NotificationType.ACCOUNT,
                    "Correo de verificacion no enviado",
                    "No pudimos enviarte el enlace de verificacion a " + user.getEmail()
                            + ". Tu cuenta queda pendiente de confirmacion.",
                    NotificationService.Severity.WARNING);
        }
    }

    /**
     * Ejecuta {@code task} justo despues de confirmar la transaccion actual, o de
     * inmediato si no hay transaccion activa (caso de las pruebas unitarias).
     *
     * <p>La tarea corre en un hilo propio (ver {@link #dispatch}). Un fallo
     * dentro de la fase pos-commit no puede propagarse: la transaccion ya esta
     * confirmada y la excepcion se convertiria en un 500 que oculta que el
     * registro si se guardo. Por eso todo lo que corre aqui pasa por
     * {@link #runSafely}.
     */
    private void afterCommit(Runnable task) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    dispatch(task);
                }
            });
        } else {
            dispatch(task);
        }
    }

    /**
     * Pone la tarea en un hilo aparte; sin executor, la ejecuta donde esta.
     *
     * <p><strong>Por que no en el propio hilo.</strong> Los callbacks
     * {@code afterCommit} corren con los recursos de la transaccion todavia
     * atados al hilo de la peticion (se desatan en {@code cleanupAfterCompletion},
     * que va despues). Una escritura hecha ahi —la notificacion— se enlaza con la
     * transaccion que acabo de cerrarse: se queda en el {@code EntityManager} sin
     * llegar a flush ni a commit, y la conexion la revierte al volver al pool.
     * El resultado es un INSERT que no falla nunca y que tampoco existe nunca,
     * verificado con {@code REGISTER} en local: la cuenta se creaba y la
     * notificacion no aparecia. En un hilo distinto no hay transaccion atada, asi
     * que {@code NotificationService.notify} abre la suya, la confirma y el
     * enlace sigue llegando.
     *
     * <p>Ademas libera la peticion: el envio por la Gmail API (o el intento SMTP
     * con sus timeouts) ya no ocupa la conexion de base de datos ni el tiempo de
     * respuesta del registro.
     */
    private void dispatch(Runnable task) {
        if (taskExecutor == null) {
            runSafely(task);
            return;
        }
        try {
            taskExecutor.execute(() -> runSafely(task));
        } catch (RuntimeException ex) {
            // El executor rechaza bajo carga o en apagado: mejor intentarlo aqui
            // que perder el correo sin mas.
            log.error("No se pudo despachar la tarea pos-commit; se ejecuta en el hilo actual", ex);
            runSafely(task);
        }
    }

    private void runSafely(Runnable task) {
        try {
            task.run();
        } catch (RuntimeException ex) {
            log.error("Tarea pos-commit fallida; la operacion principal ya esta confirmada", ex);
        }
    }

    /** Notifica sin dejar que el fallo de la bandeja rompa el flujo que la origino. */
    private void notify(Long userId, NotificationService.NotificationType type,
                        String title, String body, NotificationService.Severity severity) {
        try {
            notificationService.notify(userId, type, title, body, severity);
        } catch (RuntimeException ex) {
            log.error("No se pudo registrar la notificacion '{}' para el usuario {}", title, userId, ex);
        }
    }

    // ---------------------------------------------------------------- login

    @Transactional
    public TokenResponse login(AuthRequests.LoginRequest request, String ip, String userAgent) {
        String email = normalizeEmail(request.email());
        Instant now = Instant.now();

        User user = userRepository.findByEmailIgnoreCase(email).orElse(null);
        if (user == null) {
            registerFailure(null, email, "UNKNOWN_EMAIL", ip, userAgent);
            throw ApiException.unauthorized("INVALID_CREDENTIALS", "Credenciales invalidas.");
        }

        if (user.isLocked(now)) {
            recordDeniedLogin(user, "ACCOUNT_LOCKED", ip, userAgent);
            throw ApiException.forbidden("ACCOUNT_LOCKED",
                    "La cuenta esta bloqueada temporalmente por intentos fallidos.");
        }

        if (user.getStatus() == UserStatus.SUSPENDED || user.getStatus() == UserStatus.DELETED) {
            recordDeniedLogin(user, "ACCOUNT_" + user.getStatus(), ip, userAgent);
            throw ApiException.forbidden("ACCOUNT_UNAVAILABLE", "La cuenta no esta disponible.");
        }

        if (user.getPasswordHash() == null) {
            registerFailure(user, email, "NO_LOCAL_CREDENTIAL", ip, userAgent);
            throw ApiException.unauthorized("USE_GOOGLE_LOGIN",
                    "Esta cuenta se autentica con Google.");
        }

        if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            registerFailure(user, email, "BAD_PASSWORD", ip, userAgent);
            throw ApiException.unauthorized("INVALID_CREDENTIALS", "Credenciales invalidas.");
        }

        if (user.getStatus() == UserStatus.PENDING_VERIFICATION) {
            recordDeniedLogin(user, "EMAIL_UNVERIFIED", ip, userAgent);
            throw ApiException.forbidden("EMAIL_NOT_VERIFIED",
                    "Debe confirmar su correo antes de iniciar sesion.");
        }

        user.setFailedLoginCount(0);
        user.setLockedUntil(null);
        user.setLastLoginAt(now);
        user.setUpdatedAt(now);
        userRepository.save(user);

        loginAttemptRepository.save(LoginAttempt.success(user, ip, userAgent, AuthProvider.LOCAL));
        auditService.success(user.getId(), primaryRole(user), "AUTH_LOGIN", "User",
                String.valueOf(user.getId()), Map.of("provider", "LOCAL"));

        return issueTokens(user, UUID.randomUUID().toString(), ip, userAgent);
    }

    // -------------------------------------------------------------- refresh

    @Transactional
    public TokenResponse refresh(AuthRequests.RefreshRequest request, String cookieToken,
                                 String ip, String userAgent) {
        String presented = resolveRefreshToken(request.refreshToken(), cookieToken);
        if (presented == null) {
            // Sin token no hay nada que renovar. Se responde 401 y no 400: la
            // peticion es correcta en forma pero no aporta credencial alguna.
            throw ApiException.unauthorized("MISSING_REFRESH_TOKEN",
                    "No se presento ningun refresh token.");
        }
        RefreshToken stored = refreshTokenRepository
                .findByTokenHash(tokenHasher.hash(presented))
                .orElseThrow(() -> ApiException.unauthorized("INVALID_REFRESH_TOKEN",
                        "El refresh token no es valido."));

        Instant now = Instant.now();
        if (stored.getRevokedAt() != null) {
            // Reutilizacion de un token ya rotado: indicio de robo. Se cae la familia.
            revokeFamily(stored.getFamilyId(), "REUSE_DETECTED", stored.getUser());
            auditService.record(stored.getUser().getId(), primaryRole(stored.getUser()),
                    "AUTH_REFRESH", "RefreshToken", stored.getJti(), AuditEvent.Outcome.DENIED,
                    Map.of("reason", "REUSE_DETECTED"));
            throw ApiException.unauthorized("REFRESH_TOKEN_REUSED",
                    "El refresh token fue revocado por seguridad. Inicie sesion de nuevo.");
        }

        if (!stored.isActive(now)) {
            throw ApiException.unauthorized("REFRESH_TOKEN_EXPIRED",
                    "El refresh token expiro. Inicie sesion de nuevo.");
        }

        User user = stored.getUser();
        if (!user.isActive()) {
            throw ApiException.forbidden("ACCOUNT_UNAVAILABLE", "La cuenta no esta disponible.");
        }

        // Rotacion: el token usado se revoca y se emite otro de la misma familia.
        stored.setRevokedAt(now);
        stored.setRevokedReason("ROTATED");
        refreshTokenRepository.save(stored);

        return issueTokens(user, stored.getFamilyId(), ip, userAgent);
    }

    // --------------------------------------------------------------- logout

    @Transactional
    public void logout(AuthRequests.LogoutRequest request, String cookieToken, Long userId) {
        String presented = resolveRefreshToken(request.refreshToken(), cookieToken);
        if (presented == null) {
            // Idempotente: cerrar sesion sin token no es un error, es un no-op.
            // Devolver 400 aqui dejaba al cliente sin forma de limpiar su estado.
            return;
        }
        refreshTokenRepository.findByTokenHash(tokenHasher.hash(presented))
                .ifPresent(token -> {
                    if (token.getRevokedAt() == null) {
                        token.setRevokedAt(Instant.now());
                        token.setRevokedReason("LOGOUT");
                        refreshTokenRepository.save(token);
                    }
                    auditService.success(userId, primaryRole(token.getUser()), "AUTH_LOGOUT",
                            "RefreshToken", token.getJti(), Map.of());
                });
    }

    /**
     * Resuelve el refresh token presentado: primero el cuerpo, despues la cookie.
     * Devuelve {@code null} si no llego ninguno.
     */
    private String resolveRefreshToken(String fromBody, String fromCookie) {
        if (fromBody != null && !fromBody.isBlank()) {
            return fromBody.trim();
        }
        if (fromCookie != null && !fromCookie.isBlank()) {
            return fromCookie.trim();
        }
        return null;
    }

    /** Cierra todas las sesiones del usuario. */
    @Transactional
    public int logoutAll(Long userId, String reason) {
        int revoked = 0;
        User actor = userRepository.findById(userId).orElse(null);
        for (RefreshToken token : refreshTokenRepository.findAllByUserIdAndRevokedAtIsNull(userId)) {
            token.setRevokedAt(Instant.now());
            token.setRevokedReason(reason);
            refreshTokenRepository.save(token);
            revoked++;
        }
        auditService.record(userId, actor == null ? null : primaryRole(actor), "AUTH_LOGOUT_ALL",
                "User", String.valueOf(userId), AuditEvent.Outcome.SUCCESS,
                Map.of("reason", reason, "sessions", revoked));
        return revoked;
    }

    /**
     * Revoca por {@code jti} los access tokens emitidos en una sesion.
     * Permite invalidar de inmediato un token ya entregado al cliente.
     */
    @Transactional
    public void revokeAccessToken(String jti, Long userId, Instant expiresAt, String reason) {
        revokedTokenRepository.insertIfAbsent(jti, userId, expiresAt, reason);
    }

    // ------------------------------------------------------- contrasenas

    /**
     * Inicio de recuperacion. La respuesta es identica exista o no el correo, para no
     * permitir enumerar cuentas registradas.
     *
     * <p><strong>Ventana de gracia por cuenta.</strong> El rate limit del filtro
     * acota por IP, pero un atacante con muchas IPs podria inundar el buzon de
     * una sola victima con enlaces de recuperacion. Por eso, si ya se envio un
     * correo de recuperacion a esta cuenta hace menos de
     * {@link #RESET_MAIL_COOLDOWN}, la solicitud se acepta igual (mismo 204, sin
     * revelar nada) pero no se emite otro token ni otro mensaje: el enlace que
     * el usuario ya tiene sigue siendo valido.
     */
    @Transactional
    public void forgotPassword(AuthRequests.ForgotPasswordRequest request) {
        String email = normalizeEmail(request.email());
        userRepository.findByEmailIgnoreCase(email).ifPresent(user -> {
            Instant now = Instant.now();
            Instant lastSent = lastResetMailAt.get(user.getId());
            if (lastSent != null && now.isBefore(lastSent.plus(RESET_MAIL_COOLDOWN))) {
                log.debug("Recuperacion solicitada dentro de la ventana de gracia "
                        + "para el usuario {}; no se emite otro correo", user.getId());
                return;
            }
            lastResetMailAt.put(user.getId(), now);

            invalidatePending(user.getId(), PasswordResetToken.Purpose.RESET);
            String plain = TokenHasher.newOpaqueToken();
            Instant expiresAt = now.plus(RESET_TOKEN_TTL);
            PasswordResetToken token = new PasswordResetToken();
            token.setUser(user);
            token.setTokenHash(tokenHasher.hash(plain));
            token.setPurpose(PasswordResetToken.Purpose.RESET);
            token.setExpiresAt(expiresAt);
            passwordResetTokenRepository.save(token);
            pendingResetTokens.put(user.getId(), plain);

            if (mailService != null) {
                // Fuera de la transaccion, por la misma razon que en el registro:
                // ademas, un error de correo aqui respondria 503 SOLO cuando la
                // cuenta existe, que es exactamente la fuga de enumeracion que el
                // comentario de este metodo dice evitar.
                afterCommit(() -> {
                    if (!mailService.sendPasswordResetEmail(user.getEmail(), user.getFullName(),
                            plain, expiresAt)) {
                        log.warn("No se entrego el correo de recuperacion al usuario {}", user.getId());
                    }
                });
            }

            notificationService.notify(user.getId(), NotificationService.NotificationType.ACCOUNT,
                    "Recuperacion de contrasena",
                    "Si la cuenta existe, recibira un enlace para restablecerla.",
                    NotificationService.Severity.WARNING);
            auditService.record(user.getId(), primaryRole(user), "AUTH_PASSWORD_FORGOT",
                    "User", String.valueOf(user.getId()), AuditEvent.Outcome.SUCCESS, Map.of());
        });
    }

    @Transactional
    public void resetPassword(AuthRequests.ResetPasswordRequest request) {
        PasswordResetToken stored = passwordResetTokenRepository
                .findByTokenHash(tokenHasher.hash(request.token()))
                .orElseThrow(() -> ApiException.badRequest("INVALID_RESET_TOKEN",
                        "El enlace de recuperacion no es valido."));

        if (!stored.isUsable(Instant.now())) {
            throw ApiException.badRequest("EXPIRED_RESET_TOKEN",
                    "El enlace de recuperacion expiro o ya fue utilizado.");
        }

        User user = stored.getUser();
        user.setPasswordHash(passwordEncoder.encode(request.newPassword()));
        user.setUpdatedAt(Instant.now());
        userRepository.save(user);

        // Tras un reset, todas las sesiones existentes deben morir.
        logoutAll(user.getId(), "PASSWORD_RESET");

        stored.setConsumedAt(Instant.now());
        passwordResetTokenRepository.save(stored);
        pendingResetTokens.remove(user.getId());

        auditService.record(user.getId(), primaryRole(user), "AUTH_PASSWORD_RESET", "User",
                String.valueOf(user.getId()), AuditEvent.Outcome.SUCCESS, Map.of());
    }

    @Transactional
    public void changePassword(AuthRequests.ChangePasswordRequest request, Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> ApiException.notFound("USER_NOT_FOUND", "Usuario no encontrado."));

        if (user.getPasswordHash() == null) {
            throw ApiException.badRequest("NO_LOCAL_CREDENTIAL",
                    "Esta cuenta se autentica con Google y no tiene contrasena local.");
        }
        if (!passwordEncoder.matches(request.currentPassword(), user.getPasswordHash())) {
            auditService.record(userId, primaryRole(user), "AUTH_PASSWORD_CHANGE", "User",
                    String.valueOf(userId), AuditEvent.Outcome.FAILURE,
                    Map.of("reason", "BAD_CURRENT_PASSWORD"));
            throw ApiException.badRequest("INVALID_CURRENT_PASSWORD",
                    "La contrasena actual no es correcta.");
        }
        if (passwordEncoder.matches(request.newPassword(), user.getPasswordHash())) {
            throw ApiException.badRequest("PASSWORD_REUSED",
                    "La contrasena nueva debe ser distinta de la actual.");
        }

        user.setPasswordHash(passwordEncoder.encode(request.newPassword()));
        user.setUpdatedAt(Instant.now());
        userRepository.save(user);
        logoutAll(userId, "PASSWORD_CHANGED");

        auditService.success(userId, primaryRole(user), "AUTH_PASSWORD_CHANGE", "User",
                String.valueOf(userId), Map.of());
    }

    /** Confirma el correo a partir del token opaco recibido por email. */
    @Transactional
    public void verifyEmail(String rawToken) {
        PasswordResetToken stored = passwordResetTokenRepository
                .findByTokenHash(tokenHasher.hash(rawToken))
                .filter(t -> t.getPurpose() == PasswordResetToken.Purpose.VERIFY_EMAIL)
                .orElseThrow(() -> ApiException.badRequest("INVALID_VERIFICATION_TOKEN",
                        "El enlace de verificacion no es valido."));

        if (!stored.isUsable(Instant.now())) {
            throw ApiException.badRequest("EXPIRED_VERIFICATION_TOKEN",
                    "El enlace de verificacion expiro.");
        }

        User user = stored.getUser();
        user.setEmailVerified(true);
        user.setEmailVerifiedAt(Instant.now());
        if (user.getStatus() == UserStatus.PENDING_VERIFICATION) {
            user.setStatus(UserStatus.ACTIVE);
        }
        user.setUpdatedAt(Instant.now());
        userRepository.save(user);

        stored.setConsumedAt(Instant.now());
        passwordResetTokenRepository.save(stored);
        pendingVerificationTokens.remove(user.getId());

        notificationService.notify(user.getId(), NotificationService.NotificationType.ACCOUNT,
                "Correo confirmado", "Su cuenta ya esta activa.", NotificationService.Severity.SUCCESS);

        auditService.record(user.getId(), primaryRole(user), "AUTH_EMAIL_VERIFIED", "User",
                String.valueOf(user.getId()), AuditEvent.Outcome.SUCCESS, Map.of());
    }

    /**
     * Reenvia el correo de verificacion.
     *
     * <p><strong>La respuesta es identica exista o no la cuenta, y tambien si
     * esta ya esta verificada.</strong> Cualquier otra cosa permitiria averiguar
     * que correos estan registrados simplemente preguntando por ellos. El
     * rate limit del filtro (ruta sensible) es lo que impide usar este endpoint
     * como generador de envios masivos.
     *
     * <p>El token anterior queda revocado: el reenvio emite uno nuevo y
     * caduca los anteriores, de modo que solo el ultimo enlace recibido sirve.
     */
    @Transactional
    public void resendVerification(AuthRequests.ResendVerificationRequest request,
                                   String ip, String userAgent) {
        String email = normalizeEmail(request.email());
        userRepository.findByEmailIgnoreCase(email).ifPresent(user -> {
            if (user.getStatus() != UserStatus.PENDING_VERIFICATION || user.isEmailVerified()) {
                // Nada que verificar: se ignora en silencio.
                return;
            }
            IssuedToken issued = issueVerificationToken(user);
            User target = user;
            afterCommit(() -> deliverVerificationMail(target, issued.plain(), issued.expiresAt()));
            auditService.record(user.getId(), primaryRole(user), "AUTH_EMAIL_RESENT", "User",
                    String.valueOf(user.getId()), AuditEvent.Outcome.SUCCESS,
                    Map.of("ip", truncateIp(ip), "user_agent", String.valueOf(truncate(userAgent))));
        });
    }

    /** Exige los dos aceptes obligatorios. Lanza 400 con codigo estable. */
    private static void requireConsent(Boolean acceptTerms, Boolean acceptDataPolicy) {
        if (!Boolean.TRUE.equals(acceptTerms)) {
            throw ApiException.badRequest("CONSENT_REQUIRED",
                    "Debe aceptar los terminos y condiciones para crear la cuenta.");
        }
        if (!Boolean.TRUE.equals(acceptDataPolicy)) {
            throw ApiException.badRequest("CONSENT_REQUIRED",
                    "Debe aceptar la politica de tratamiento de datos para crear la cuenta.");
        }
    }

    /**
     * Escribe la aceptacion de un documento con su version vigente (V6).
     *
     * <p>Se hace dentro de la transaccion que crea la cuenta: si la escritura
     * fallara, no debe quedar una cuenta sin prueba de su consentimiento.
     */
    private void recordConsent(User user, ConsentType type, boolean accepted,
                               ConsentSource source, String ip, String userAgent) {
        ConsentRecord record = new ConsentRecord();
        record.setUser(user);
        record.setConsentType(type);
        record.setVersion(LegalDocuments.CURRENT_VERSION);
        record.setAccepted(accepted);
        record.setSource(source);
        record.setIpAddress(truncateIp(ip));
        record.setUserAgent(truncate(userAgent));
        consentRecordRepository.save(record);
    }

    // ----------------------------------------------------- google oauth 2.0

    /**
     * Resuelve la identidad federada de Google a un usuario local.
     *
     * <p>La identidad es el {@code sub} del ID Token, que es estable y no reutilizable
     * entre aplicaciones. El correo solo permite enlazar una cuenta local ya
     * existente: nunca concede acceso por si solo, y solo si Google afirma
     * {@code email_verified}.
     *
     * <p><strong>Los tres caminos posibles.</strong>
     * <ol>
     *   <li>Ya existe la vinculacion {@code (GOOGLE, sub)}: se usa esa cuenta.</li>
     *   <li>No existe, pero hay una cuenta local con ese correo <em>y Google
     *       afirma que esta verificado</em>: se vincula, sin duplicar.</li>
     *   <li>No existe nada: se crea la cuenta con rol {@code VIEWER}, siempre
     *       que el navegador haya traido el consentimiento de los terminos y de
     *       la politica de datos en el {@code state} del flujo. Sin eso no se
     *       crea nada y se devuelve {@code CONSENT_REQUIRED}.</li>
     * </ol>
     */
    @Transactional
    public TokenResponse loginWithGoogle(GoogleIdentity identity, GoogleConsent consent,
                                         String ip, String userAgent) {
        if (identity == null || identity.subject() == null || identity.subject().isBlank()) {
            throw ApiException.unauthorized("INVALID_ID_TOKEN", "El ID Token de Google no es valido.");
        }

        Optional<OAuthAccount> linked = oauthAccountRepository
                .findByProviderAndProviderSubject(AuthProvider.GOOGLE, identity.subject());
        User user;

        if (linked.isPresent()) {
            user = linked.get().getUser();
        } else {
            String email = identity.email() == null ? null : normalizeEmail(identity.email());
            if (email == null || !identity.emailVerified()) {
                throw ApiException.unauthorized("UNVERIFIED_GOOGLE_ACCOUNT",
                        "La cuenta de Google no tiene un correo verificado.");
            }

            Optional<User> existing = userRepository.findByEmailIgnoreCase(email);
            if (existing.isPresent()) {
                // Vinculacion de una cuenta local previa: su consentimiento ya
                // quedo registrado cuando se creo, asi que no se vuelve a pedir.
                user = existing.get();
                linkGoogleAccount(user, identity, email);
                auditService.record(user.getId(), primaryRole(user), "AUTH_GOOGLE_LINK",
                        "OAuthAccount", identity.subject(), AuditEvent.Outcome.SUCCESS, Map.of());
            } else {
                // Cuenta nueva: sin aceptacion registrada no se crea nada (R-11
                // y Ley 1581 art. 8: la autorizacion debe ser previa y expresa).
                requireGoogleConsent(consent);
                user = createGoogleUser(identity, email, consent, ip, userAgent);
                linkGoogleAccount(user, identity, email);
            }
        }

        if (user.getStatus() == UserStatus.SUSPENDED || user.getStatus() == UserStatus.DELETED) {
            auditService.record(user.getId(), primaryRole(user), "AUTH_GOOGLE_LOGIN", "User",
                    String.valueOf(user.getId()), AuditEvent.Outcome.DENIED,
                    Map.of("reason", "ACCOUNT_" + user.getStatus()));
            throw ApiException.forbidden("ACCOUNT_UNAVAILABLE", "La cuenta no esta disponible.");
        }

        if (!user.isEmailVerified()) {
            // Google afirma haber verificado el correo (exigido por
            // verifyIdToken): se acepta como confirmacion ANTES de mirar si la
            // cuenta esta activa. Antes de este cambio el orden era al reves y
            // una cuenta local sin confirmar recibia ACCOUNT_UNAVAILABLE aunque
            // Google acabara de demostrar su identidad.
            user.setEmailVerified(true);
            user.setEmailVerifiedAt(Instant.now());
            if (user.getStatus() == UserStatus.PENDING_VERIFICATION) {
                user.setStatus(UserStatus.ACTIVE);
            }
        }

        if (!user.isActive()) {
            auditService.record(user.getId(), primaryRole(user), "AUTH_GOOGLE_LOGIN", "User",
                    String.valueOf(user.getId()), AuditEvent.Outcome.DENIED,
                    Map.of("reason", "ACCOUNT_" + user.getStatus()));
            throw ApiException.forbidden("ACCOUNT_UNAVAILABLE", "La cuenta no esta disponible.");
        }

        user.setFailedLoginCount(0);
        user.setLockedUntil(null);
        user.setLastLoginAt(Instant.now());
        userRepository.save(user);

        loginAttemptRepository.save(LoginAttempt.success(user, ip, userAgent, AuthProvider.GOOGLE));
        auditService.success(user.getId(), primaryRole(user), "AUTH_GOOGLE_LOGIN", "User",
                String.valueOf(user.getId()), Map.of("provider", "GOOGLE"));

        return issueTokens(user, UUID.randomUUID().toString(), ip, userAgent);
    }

    /** Crea la cuenta local de un usuario que llega por Google por primera vez. */
    private User createGoogleUser(GoogleIdentity identity, String email, GoogleConsent consent,
                                  String ip, String userAgent) {
        User user = new User();
        user.setEmail(email);
        user.setFullName(displayName(identity, email));
        // Sin contrasena local: esta cuenta solo se autentica con Google, y
        // exigir una clave aqui seria pedir un secreto que nadie usara.
        user.setPasswordHash(null);
        user.setProvider(AuthProvider.GOOGLE);
        // Google ya verifico el correo (verifyIdToken lo exige), asi que la
        // cuenta nace activa: no tiene sentido pedirle que confirme otra vez.
        user.setStatus(UserStatus.ACTIVE);
        user.setEmailVerified(true);
        user.setEmailVerifiedAt(Instant.now());
        user.setRoles(new LinkedHashSet<>(Set.of(Role.VIEWER)));

        User saved = userRepository.saveAndFlush(user);
        recordConsent(saved, ConsentType.TERMS, true, ConsentSource.GOOGLE_OAUTH, ip, userAgent);
        recordConsent(saved, ConsentType.DATA_POLICY, true, ConsentSource.GOOGLE_OAUTH, ip, userAgent);
        if (consent.marketing()) {
            recordConsent(saved, ConsentType.MARKETING, true, ConsentSource.GOOGLE_OAUTH, ip, userAgent);
        }
        auditService.success(saved.getId(), Role.VIEWER.name(), "AUTH_GOOGLE_REGISTER", "User",
                String.valueOf(saved.getId()), Map.of("provider", "GOOGLE"));
        return saved;
    }

    private void linkGoogleAccount(User user, GoogleIdentity identity, String email) {
        OAuthAccount account = new OAuthAccount();
        account.setUser(user);
        account.setProvider(AuthProvider.GOOGLE);
        account.setProviderSubject(identity.subject());
        account.setProviderEmail(email);
        oauthAccountRepository.save(account);
    }

    private static void requireGoogleConsent(GoogleConsent consent) {
        if (consent == null || !consent.terms() || !consent.dataPolicy()) {
            throw ApiException.forbidden("CONSENT_REQUIRED",
                    "Debe aceptar los terminos y condiciones y la politica de "
                            + "tratamiento de datos para crear la cuenta con Google.");
        }
    }

    /** Nombre visible: el claim {@code name} de Google, o la parte local del correo. */
    private static String displayName(GoogleIdentity identity, String email) {
        if (identity.name() != null && !identity.name().isBlank()) {
            String trimmed = identity.name().trim();
            return trimmed.length() > 120 ? trimmed.substring(0, 120) : trimmed;
        }
        int at = email.indexOf('@');
        return at > 0 ? email.substring(0, at) : "usuario";
    }

    // ------------------------------------------------------------- helpers

    /** Identidad ya validada extraida del ID Token de Google. */
    public record GoogleIdentity(String subject, String email, String name, boolean emailVerified) {
    }

    /**
     * Consentimiento traido desde el {@code state} del flujo OAuth.
     *
     * <p>No lo aporta Google: lo aporta el navegador cuando el usuario marco los
     * checkboxes en nuestra pagina <em>antes</em> de ser redirigido, y viaja en
     * el mismo state de un solo uso que ya protege el flujo contra CSRF.
     */
    public record GoogleConsent(boolean terms, boolean dataPolicy, boolean marketing) {
    }

    /** Carga el usuario para el perfil. Lanza 404 si ya no existe. */
    @Transactional(readOnly = true)
    public User loadUser(Long userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> ApiException.notFound("USER_NOT_FOUND", "Usuario no encontrado."));
    }

    private TokenResponse issueTokens(User user, String familyId, String ip, String userAgent) {
        Set<Role> roles = user.getRoles().isEmpty() ? EnumSet.of(Role.VIEWER) : user.getRoles();
        var issued = jwtService.issueAccessToken(user.getId(), user.getEmail(), List.copyOf(roles));

        String refreshPlain = TokenHasher.newOpaqueToken();
        RefreshToken refreshToken = new RefreshToken();
        refreshToken.setUser(user);
        refreshToken.setTokenHash(tokenHasher.hash(refreshPlain));
        refreshToken.setJti(UUID.randomUUID().toString());
        refreshToken.setFamilyId(familyId);
        refreshToken.setExpiresAt(Instant.now().plusSeconds(jwtService.refreshTokenTtlSeconds()));
        refreshToken.setIpAddress(ip);
        refreshToken.setUserAgent(truncate(userAgent));
        refreshTokenRepository.save(refreshToken);

        return TokenResponse.of(issued.token(), refreshPlain, jwtService.accessTokenTtlSeconds(),
                roles, toResponse(user));
    }

    private void revokeFamily(String familyId, String reason, User user) {
        for (RefreshToken token : refreshTokenRepository.findAllByUserIdAndRevokedAtIsNull(user.getId())) {
            if (familyId.equals(token.getFamilyId())) {
                token.setRevokedAt(Instant.now());
                token.setRevokedReason(reason);
                refreshTokenRepository.save(token);
            }
        }
    }

    private void invalidatePending(Long userId, PasswordResetToken.Purpose purpose) {
        for (PasswordResetToken token
                : passwordResetTokenRepository.findAllByUserIdAndPurposeAndConsumedAtIsNull(userId, purpose)) {
            token.setConsumedAt(Instant.now());
            passwordResetTokenRepository.save(token);
        }
    }

    /** Bloqueo progresivo: cuenta intentos y bloquea al superar el limite. */
    private void registerFailure(User user, String email, String reason, String ip, String userAgent) {
        loginAttemptRepository.save(LoginAttempt.failure(email, user, reason, ip, userAgent,
                AuthProvider.LOCAL));

        auditService.record(user == null ? null : user.getId(),
                user == null ? null : primaryRole(user), "AUTH_LOGIN", "User",
                user == null ? null : String.valueOf(user.getId()),
                AuditEvent.Outcome.FAILURE, Map.of("reason", reason));

        if (user == null) {
            return;
        }

        int maxFailed = properties.security().account().maxFailedLogins();
        long recent = loginAttemptRepository.countRecentFailures(
                email, Instant.now().minus(FAILED_LOGIN_WINDOW));

        user.setFailedLoginCount((int) Math.min(recent, maxFailed));
        if (recent >= maxFailed) {
            user.setLockedUntil(Instant.now().plus(Duration.ofMinutes(
                    properties.security().account().lockDurationMinutes())));
            auditService.record(user.getId(), primaryRole(user), "ACCOUNT_LOCKED", "User",
                    String.valueOf(user.getId()), AuditEvent.Outcome.DENIED,
                    Map.of("failed_attempts", recent));
            notificationService.notify(user.getId(), NotificationService.NotificationType.SECURITY,
                    "Cuenta bloqueada temporalmente",
                    "Se detectaron intentos fallidos consecutivos. Se desbloquea automaticamente.",
                    NotificationService.Severity.ERROR);
        }
        user.setUpdatedAt(Instant.now());
        userRepository.save(user);
    }

    private void recordDeniedLogin(User user, String reason, String ip, String userAgent) {
        loginAttemptRepository.save(LoginAttempt.failure(user.getEmail(), user, reason, ip,
                userAgent, AuthProvider.LOCAL));
        auditService.record(user.getId(), primaryRole(user), "AUTH_LOGIN", "User",
                String.valueOf(user.getId()), AuditEvent.Outcome.DENIED, Map.of("reason", reason));
    }

    /**
     * Emite el token de verificacion con su fecha de caducidad exacta.
     *
     * <p>Se devuelve la fecha junto con el token en claro porque es la misma que
     * se promete en el correo: si el mensaje dijera una hora y la base de datos
     * guardara otra, el enlace dejaria de funcionar antes o despues de lo
     * anunciado.
     */
    private IssuedToken issueVerificationToken(User user) {
        invalidatePending(user.getId(), PasswordResetToken.Purpose.VERIFY_EMAIL);
        String plain = TokenHasher.newOpaqueToken();
        Instant expiresAt = Instant.now().plus(VERIFY_TOKEN_TTL);
        PasswordResetToken token = new PasswordResetToken();
        token.setUser(user);
        token.setTokenHash(tokenHasher.hash(plain));
        token.setPurpose(PasswordResetToken.Purpose.VERIFY_EMAIL);
        token.setExpiresAt(expiresAt);
        passwordResetTokenRepository.save(token);
        pendingVerificationTokens.put(user.getId(), plain);
        return new IssuedToken(plain, expiresAt);
    }

    /** Token opaco emitido y el instante en que caduca. */
    private record IssuedToken(String plain, Instant expiresAt) {
    }

    public UserResponse toResponse(User user) {
        return UserResponse.of(
                user.getId(),
                user.getEmail(),
                user.getFullName(),
                user.getRoles(),
                user.getProvider(),
                user.isEmailVerified(),
                UserResponse.UserStatusView.valueOf(user.getStatus().name()),
                user.getLastLoginAt(),
                user.getCreatedAt());
    }

    /** Token opaco de verificacion pendiente de entrega, solo para las pruebas. */
    public Optional<String> peekPendingVerificationToken(Long userId) {
        return Optional.ofNullable(pendingVerificationTokens.get(userId));
    }

    /** Token opaco de recuperacion pendiente de entrega, solo para las pruebas. */
    public Optional<String> peekPendingResetToken(Long userId) {
        return Optional.ofNullable(pendingResetTokens.get(userId));
    }

    private static String normalizeEmail(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }

    private static String truncate(String value) {
        if (value == null) {
            return null;
        }
        return value.length() > 255 ? value.substring(0, 255) : value;
    }

    /** {@code X-Forwarded-For} puede traer toda la cadena: la columna admite 45. */
    private static String truncateIp(String ip) {
        if (ip == null) {
            return null;
        }
        return ip.length() > 45 ? ip.substring(0, 45) : ip;
    }

    private static String primaryRole(User user) {
        return user.getRoles().stream()
                .map(Enum::name)
                .min(java.util.Comparator.naturalOrder())
                .orElse(Role.VIEWER.name());
    }
}