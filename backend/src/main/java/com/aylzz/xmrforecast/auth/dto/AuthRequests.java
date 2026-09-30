package com.aylzz.xmrforecast.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Contratos de entrada del modulo de autenticacion. */
public final class AuthRequests {

    private AuthRequests() {
    }

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
            String fullName
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

    public record RefreshRequest(
            @NotBlank(message = "El refresh token es obligatorio.")
            @Size(max = PasswordPolicy.MAX_TOKEN_LENGTH)
            String refreshToken
    ) {}

    public record LogoutRequest(
            @NotBlank(message = "El refresh token es obligatorio.")
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