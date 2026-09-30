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

    public AuthController(AuthService authService) {
        this.authService = authService;
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
    public TokenResponse login(@Valid @RequestBody AuthRequests.LoginRequest request,
                               HttpServletRequest httpRequest) {
        return authService.login(request,
                AuditService.clientIp(httpRequest), httpRequest.getHeader("User-Agent"));
    }

    @Operation(summary = "Renueva la sesion",
            description = "Publica. Rota el refresh token; reutilizar uno ya rotado revoca la familia.")
    @PostMapping("/refresh")
    public TokenResponse refresh(@Valid @RequestBody AuthRequests.RefreshRequest request,
                                 HttpServletRequest httpRequest) {
        return authService.refresh(request,
                AuditService.clientIp(httpRequest), httpRequest.getHeader("User-Agent"));
    }

    @Operation(summary = "Cierra la sesion", description = "Revoca el refresh token indicado.")
    @ApiResponses(@ApiResponse(responseCode = "204", description = "Sesion cerrada"))
    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(@Valid @RequestBody AuthRequests.LogoutRequest request,
                       @AuthenticationPrincipal AuthenticatedUser user) {
        authService.logout(request, user == null ? null : user.id());
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

    @Operation(summary = "Identificador de correlacion de la peticion actual",
            description = "Util para verificar la correlacion frontend -> backend -> ML.")
    @GetMapping("/request-id")
    public RequestIdResponse requestId() {
        return new RequestIdResponse(RequestContext.requestId(), RequestContext.traceId());
    }

    /** Identificadores de correlacion devueltos a la UI. */
    public record RequestIdResponse(String requestId, String traceId) {}
}