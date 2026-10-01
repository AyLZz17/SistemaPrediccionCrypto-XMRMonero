package com.aylzz.xmrforecast.auth;

import com.aylzz.xmrforecast.audit.AuditService;
import com.aylzz.xmrforecast.auth.dto.AuthRequests;
import com.aylzz.xmrforecast.auth.dto.TokenResponse;
import com.aylzz.xmrforecast.auth.dto.UserResponse;
import com.aylzz.xmrforecast.common.RequestContext;
import com.aylzz.xmrforecast.security.AuthenticatedUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Endpoints de autenticacion (seccion 4).
 *
 * <p>Documentacion de seguridad por ruta (R-35): las rutas publicas de este
 * controlador aplican rate limiting, validan la entrada y responden con mensajes
 * genericos para no permitir enumerar cuentas.
 */
@Tag(name = "Autenticacion")
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final AuthService authService;
    private final com.aylzz.xmrforecast.config.AppProperties properties;

    public AuthController(AuthService authService,
                          com.aylzz.xmrforecast.config.AppProperties properties) {
        this.authService = authService;
        this.properties = properties;
    }

    /** Anade la cookie HttpOnly del refresh token a una respuesta de sesion. */
    private ResponseEntity<TokenResponse> withRefreshCookie(TokenResponse tokens, long ttlSeconds) {
        return ResponseEntity.ok()
                .header(org.springframework.http.HttpHeaders.SET_COOKIE,
                        RefreshCookie.issue(tokens.refreshToken(), ttlSeconds).toString())
                .body(tokens);
    }

    @Operation(summary = "Perfil del usuario autenticado")
    @SecurityRequirement(name = "bearerAuth")
    @GetMapping("/me")
    public UserResponse me(@AuthenticationPrincipal AuthenticatedUser user) {
        return authService.toResponse(authService.loadUser(user.id()));
    }

    @Operation(summary = "Registra una cuenta local",
            description = "Publica. La cuenta nace PENDING_VERIFICATION y recibe el rol VIEWER.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Cuenta creada"),
            @ApiResponse(responseCode = "400", description = "Entrada invalida"),
            @ApiResponse(responseCode = "409", description = "No se puede completar el registro")
    })
    @PostMapping("/register")
    public ResponseEntity<UserResponse> register(
            @Valid @RequestBody AuthRequests.RegisterRequest request,
            HttpServletRequest httpRequest) {
        UserResponse created = authService.register(request,
                AuditService.clientIp(httpRequest), httpRequest.getHeader("User-Agent"));
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @Operation(summary = "Inicia sesion con correo y contrasena",
            description = "Publica. Bloqueo progresivo tras varios fallos consecutivos.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Sesion iniciada"),
            @ApiResponse(responseCode = "401", description = "Credenciales invalidas"),
            @ApiResponse(responseCode = "403", description = "Cuenta bloqueada, suspendida o sin confirmar"),
            @ApiResponse(responseCode = "429", description = "Rate limit excedido")
    })
    @PostMapping("/login")
    public ResponseEntity<TokenResponse> login(@Valid @RequestBody AuthRequests.LoginRequest request,
                                               HttpServletRequest httpRequest) {
        TokenResponse tokens = authService.login(request,
                AuditService.clientIp(httpRequest), httpRequest.getHeader("User-Agent"));
        return withRefreshCookie(tokens, properties.jwt().refreshTokenTtlSeconds());
    }

    @Operation(summary = "Renueva la sesion",
            description = "Publica. Rota el refresh token; reutilizar uno ya rotado revoca la familia. "
                    + "El token se toma del cuerpo o, si falta, de la cookie HttpOnly `xmr_refresh`.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Sesion renovada"),
            @ApiResponse(responseCode = "401", description = "Token ausente, invalido, expirado o reutilizado")
    })
    @PostMapping("/refresh")
    public ResponseEntity<TokenResponse> refresh(@Valid @RequestBody AuthRequests.RefreshRequest request,
                                                 HttpServletRequest httpRequest) {
        TokenResponse tokens = authService.refresh(request, RefreshCookie.read(httpRequest),
                AuditService.clientIp(httpRequest), httpRequest.getHeader("User-Agent"));
        return withRefreshCookie(tokens, properties.jwt().refreshTokenTtlSeconds());
    }

    @Operation(summary = "Cierra la sesion",
            description = "Revoca el refresh token del cuerpo o de la cookie y borra la cookie. "
                    + "Es idempotente: cerrar sesion sin token no es un error.")
    @ApiResponses(@ApiResponse(responseCode = "204", description = "Sesion cerrada"))
    @PostMapping("/logout")
    public ResponseEntity<Void> logout(@Valid @RequestBody(required = false) AuthRequests.LogoutRequest request,
                                       HttpServletRequest httpRequest,
                                       @AuthenticationPrincipal AuthenticatedUser user) {
        authService.logout(request == null
                        ? new AuthRequests.LogoutRequest(null) : request,
                RefreshCookie.read(httpRequest),
                user == null ? null : user.id());
        // La cookie se borra siempre, se haya revocado o no un token. Dejarla
        // puesta despues de cerrar sesion deja al navegador con una credencial
        // que solo fallara mas tarde, en el proximo intento de renovacion, y el
        // usuario ve un error de sesion expirada en lugar de "has cerrado sesion".
        return ResponseEntity.noContent()
                .header(org.springframework.http.HttpHeaders.SET_COOKIE,
                        RefreshCookie.clear().toString())
                .build();
    }

    @Operation(summary = "Solicita recuperacion de contrasena",
            description = "Publica. La respuesta es identica exista o no el correo.")
    @ApiResponses(@ApiResponse(responseCode = "204", description = "Solicitud registrada"))
    @PostMapping("/password/forgot")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void forgotPassword(@Valid @RequestBody AuthRequests.ForgotPasswordRequest request) {
        authService.forgotPassword(request);
    }

    @Operation(summary = "Restablece la contrasena con un token opaco")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Contrasena restablecida"),
            @ApiResponse(responseCode = "400", description = "Token invalido o expirado")
    })
    @PostMapping("/password/reset")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void resetPassword(@Valid @RequestBody AuthRequests.ResetPasswordRequest request) {
        authService.resetPassword(request);
    }

    @Operation(summary = "Cambia la contrasena del usuario autenticado")
    @SecurityRequirement(name = "bearerAuth")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Contrasena actualizada; sesiones revocadas"),
            @ApiResponse(responseCode = "400", description = "Contrasena actual incorrecta")
    })
    @PostMapping("/password/change")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void changePassword(@Valid @RequestBody AuthRequests.ChangePasswordRequest request,
                               @AuthenticationPrincipal AuthenticatedUser user) {
        authService.changePassword(request, user.id());
    }

    @Operation(summary = "Confirma el correo con el token recibido por email")
    @ApiResponses(@ApiResponse(responseCode = "204", description = "Correo confirmado"))
    @PostMapping("/verify-email")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void verifyEmail(@RequestParam String token) {
        authService.verifyEmail(token);
    }

    @Operation(summary = "Reenvia el correo de verificacion",
            description = "Publica. Devuelve 204 exista o no la cuenta, para no "
                    + "permitir enumerar correos registrados. Protegido con rate limit.")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Solicitud registrada"),
            @ApiResponse(responseCode = "429", description = "Rate limit excedido")
    })
    @PostMapping("/verify-email/resend")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void resendVerification(
            @Valid @RequestBody AuthRequests.ResendVerificationRequest request,
            HttpServletRequest httpRequest) {
        authService.resendVerification(request,
                AuditService.clientIp(httpRequest), httpRequest.getHeader("User-Agent"));
    }

    @Operation(summary = "Identificador de correlacion de la peticion actual",
            description = "Util para verificar la correlacion frontend -> backend -> ML.")
    @GetMapping("/request-id")
    public RequestIdResponse requestId() {
        return new RequestIdResponse(RequestContext.requestId(), RequestContext.traceId());
    }

    /** Identificadores de correlacion devueltos a la UI. */
    public record RequestIdResponse(String requestId, String traceId) {}
}