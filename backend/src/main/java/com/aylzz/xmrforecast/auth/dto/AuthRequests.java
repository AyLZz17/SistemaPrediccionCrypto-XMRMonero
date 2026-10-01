package com.aylzz.xmrforecast.auth.dto;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Contratos de entrada del modulo de autenticacion. */
public final class AuthRequests {

    private AuthRequests() {
    }

    /**
     * Registro de cuenta con consentimiento.
     *
     * <p>Los dos aceptes son obligatorios y el servidor los comprueba dos veces:
     * aqui con {@code @AssertTrue} (que el validador de Spring ejecuta sobre una
     * instancia real, R-46) y de nuevo en {@code AuthService}, por si alguna
     * ruta llega a construir el objeto sin pasar por {@code @Valid}. Un checkbox
     * sin servidor detras es una promesa, no un registro.
     *
     * <p>{@code acceptMarketing} es opcional: {@code null} y {@code false}
     * significan lo mismo, "no quiero comunicaciones comerciales".
     */
    public record RegisterRequest(
            @NotBlank(message = "El correo es obligatorio.")
            @Email(message = "El correo no tiene un formato valido.")
            @Size(max = 254, message = "El correo es demasiado largo.")
            String email,

            @NotBlank(message = "La contrasena es obligatoria.")
            @Size(min = 12, max = 128)
            @Pattern(regexp = PasswordPolicy.REGEX, message = PasswordPolicy.MESSAGE)
            String password,

            @NotBlank(message = "El nombre es obligatorio.")
            @Size(min = 2, max = 120, message = "El nombre debe tener entre 2 y 120 caracteres.")
            String fullName,

            @NotNull(message = "Debe aceptar los terminos y condiciones.")
            @AssertTrue(message = "Debe aceptar los terminos y condiciones.")
            Boolean acceptTerms,

            @NotNull(message = "Debe aceptar la politica de tratamiento de datos.")
            @AssertTrue(message = "Debe aceptar la politica de tratamiento de datos.")
            Boolean acceptDataPolicy,

            Boolean acceptMarketing
    ) {}

    /** Reenvio del correo de verificacion. Respuesta identica exista o no la cuenta. */
    public record ResendVerificationRequest(
            @NotBlank(message = "El correo es obligatorio.")
            @Email(message = "El correo no tiene un formato valido.")
            @Size(max = 254)
            String email
    ) {}

    public record LoginRequest(
            @NotBlank(message = "El correo es obligatorio.")
            @Email(message = "El correo no tiene un formato valido.")
            @Size(max = 254)
            String email,

            @NotBlank(message = "La contrasena es obligatoria.")
            @Size(max = 128, message = "La contrasena es demasiado larga.")
            String password
    ) {}

    /**
     * Renovacion de sesion.
     *
     * <p>El token es opcional en el cuerpo porque puede llegar en la cookie
     * {@code xmr_refresh} ({@code HttpOnly}, {@code Secure}, {@code SameSite=Strict}),
     * que es la forma recomendada: un refresh token accesible desde JavaScript es
     * un refresh token que un XSS puede robar. El servidor resuelve cuerpo primero
     * y cookie despues, de modo que ambos clientes funcionan.
     */
    public record RefreshRequest(
            @Size(max = PasswordPolicy.MAX_TOKEN_LENGTH)
            String refreshToken
    ) {}

    /**
     * Cierre de sesion. Token opcional por la misma razon que en
     * {@link RefreshRequest}; ademas, cerrar sesion sin token debe ser una
     * operacion idempotente y sin error, no un 400 que deje al usuario encerrado
     * en una sesion que ya no controla.
     */
    public record LogoutRequest(
            @Size(max = PasswordPolicy.MAX_TOKEN_LENGTH)
            String refreshToken
    ) {}

    public record ForgotPasswordRequest(
            @NotBlank(message = "El correo es obligatorio.")
            @Email(message = "El correo no tiene un formato valido.")
            @Size(max = 254)
            String email
    ) {}

    public record ResetPasswordRequest(
            @NotBlank(message = "El token es obligatorio.")
            @Size(max = PasswordPolicy.MAX_TOKEN_LENGTH)
            String token,

            @NotBlank(message = "La contrasena nueva es obligatoria.")
            @Size(min = 12, max = 128)
            @Pattern(regexp = PasswordPolicy.REGEX, message = PasswordPolicy.MESSAGE)
            String newPassword
    ) {}

    public record ChangePasswordRequest(
            @NotBlank(message = "La contrasena actual es obligatoria.")
            @Size(max = 128)
            String currentPassword,

            @NotBlank(message = "La contrasena nueva es obligatoria.")
            @Size(min = 12, max = 128)
            @Pattern(regexp = PasswordPolicy.REGEX, message = PasswordPolicy.MESSAGE)
            String newPassword
    ) {}
}