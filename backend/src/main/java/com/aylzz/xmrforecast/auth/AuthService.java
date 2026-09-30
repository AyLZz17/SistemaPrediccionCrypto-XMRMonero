package com.aylzz.xmrforecast.auth;

import com.aylzz.xmrforecast.audit.AuditEvent;
import com.aylzz.xmrforecast.audit.AuditService;
import com.aylzz.xmrforecast.auth.dto.AuthRequests;
import com.aylzz.xmrforecast.auth.dto.TokenResponse;
import com.aylzz.xmrforecast.auth.dto.UserResponse;
import com.aylzz.xmrforecast.common.ApiException;
import com.aylzz.xmrforecast.config.AppProperties;
import com.aylzz.xmrforecast.security.JwtService;
import com.aylzz.xmrforecast.security.Role;
import com.aylzz.xmrforecast.security.TokenHasher;
import com.aylzz.xmrforecast.user.AuthProvider;
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
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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

    private static final Duration FAILED_LOGIN_WINDOW = Duration.ofMinutes(15);
    private static final Duration RESET_TOKEN_TTL = Duration.ofHours(2);
    private static final Duration VERIFY_TOKEN_TTL = Duration.ofHours(48);

    private final UserRepository userRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final OAuthAccountRepository oauthAccountRepository;
    private final LoginAttemptRepository loginAttemptRepository;
    private final PasswordResetTokenRepository passwordResetTokenRepository;
    private final RevokedTokenRepository revokedTokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final TokenHasher tokenHasher;
    private final AuditService auditService;
    private final NotificationService notificationService;
    private final AppProperties properties;

    /**
     * Tokens opacos pendientes de entrega por correo. Solo viven en memoria del
     * proceso: nunca se escriben en disco ni en la base de datos en claro (R-14).
     */
    private final Map<Long, String> pendingResetTokens = new ConcurrentHashMap<>();
    private final Map<Long, String> pendingVerificationTokens = new ConcurrentHashMap<>();

    public AuthService(UserRepository userRepository,
                       RefreshTokenRepository refreshTokenRepository,
                       OAuthAccountRepository oauthAccountRepository,
                       LoginAttemptRepository loginAttemptRepository,
                       PasswordResetTokenRepository passwordResetTokenRepository,
                       RevokedTokenRepository revokedTokenRepository,
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

        issueVerificationToken(saved);
        notificationService.notify(saved.getId(), NotificationService.NotificationType.ACCOUNT,
                "Confirme su correo",
                "Enviamos un enlace de verificacion a " + saved.getEmail(),
                NotificationService.Severity.INFO);

        auditService.success(saved.getId(), Role.VIEWER.name(), "AUTH_REGISTER", "User",
                String.valueOf(saved.getId()), Map.of("provider", "LOCAL"));

        return toResponse(saved);
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
     */
    @Transactional
    public void forgotPassword(AuthRequests.ForgotPasswordRequest request) {
        String email = normalizeEmail(request.email());
        userRepository.findByEmailIgnoreCase(email).ifPresent(user -> {
            invalidatePending(user.getId(), PasswordResetToken.Purpose.RESET);
            String plain = TokenHasher.newOpaqueToken();
            PasswordResetToken token = new PasswordResetToken();
            token.setUser(user);
            token.setTokenHash(tokenHasher.hash(plain));
            token.setPurpose(PasswordResetToken.Purpose.RESET);
            token.setExpiresAt(Instant.now().plus(RESET_TOKEN_TTL));
            passwordResetTokenRepository.save(token);
            pendingResetTokens.put(user.getId(), plain);

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

    // ----------------------------------------------------- google oauth 2.0

    /**
     * Resuelve la identidad federada de Google a un usuario local.
     *
     * <p>La identidad es el {@code sub} del ID Token, que es estable y no reutilizable
     * entre aplicaciones. El correo solo permite enlazar una cuenta local ya
     * existente: nunca concede acceso por si solo, y solo si Google afirma
     * {@code email_verified}.
     */
    @Transactional
    public TokenResponse loginWithGoogle(GoogleIdentity identity, String ip, String userAgent) {
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

            // Solo vinculacion: se exige una cuenta local previa con ese correo.
            user = userRepository.findByEmailIgnoreCase(email).orElseThrow(() ->
                    ApiException.forbidden("NO_LOCAL_ACCOUNT",
                            "No existe una cuenta asociada a este correo de Google. "
                                    + "Registrese primero con correo y contrasena."));

            OAuthAccount account = new OAuthAccount();
            account.setUser(user);
            account.setProvider(AuthProvider.GOOGLE);
            account.setProviderSubject(identity.subject());
            account.setProviderEmail(email);
            oauthAccountRepository.save(account);

            auditService.record(user.getId(), primaryRole(user), "AUTH_GOOGLE_LINK",
                    "OAuthAccount", identity.subject(), AuditEvent.Outcome.SUCCESS, Map.of());
        }

        if (!user.isActive()) {
            auditService.record(user.getId(), primaryRole(user), "AUTH_GOOGLE_LOGIN", "User",
                    String.valueOf(user.getId()), AuditEvent.Outcome.DENIED,
                    Map.of("reason", "ACCOUNT_" + user.getStatus()));
            throw ApiException.forbidden("ACCOUNT_UNAVAILABLE", "La cuenta no esta disponible.");
        }

        if (!user.isEmailVerified()) {
            // Google afirma haber verificado el correo: se acepta como confirmacion.
            user.setEmailVerified(true);
            user.setEmailVerifiedAt(Instant.now());
            if (user.getStatus() == UserStatus.PENDING_VERIFICATION) {
                user.setStatus(UserStatus.ACTIVE);
            }
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

    // ------------------------------------------------------------- helpers

    /** Identidad ya validada extraida del ID Token de Google. */
    public record GoogleIdentity(String subject, String email, boolean emailVerified) {
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

    private void issueVerificationToken(User user) {
        invalidatePending(user.getId(), PasswordResetToken.Purpose.VERIFY_EMAIL);
        String plain = TokenHasher.newOpaqueToken();
        PasswordResetToken token = new PasswordResetToken();
        token.setUser(user);
        token.setTokenHash(tokenHasher.hash(plain));
        token.setPurpose(PasswordResetToken.Purpose.VERIFY_EMAIL);
        token.setExpiresAt(Instant.now().plus(VERIFY_TOKEN_TTL));
        passwordResetTokenRepository.save(token);
        pendingVerificationTokens.put(user.getId(), plain);
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

    private static String primaryRole(User user) {
        return user.getRoles().stream()
                .map(Enum::name)
                .min(java.util.Comparator.naturalOrder())
                .orElse(Role.VIEWER.name());
    }
}