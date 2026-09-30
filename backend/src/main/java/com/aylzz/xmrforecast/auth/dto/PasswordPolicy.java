package com.aylzz.xmrforecast.auth.dto;

/**
 * Politica de contrasenas aplicada por igual en registro, reset y cambio.
 * Longitud minima de 12 caracteres (recomendacion OWASP ASVS V2), con mezcla de
 * mayusculas, minusculas, digitos y simbolos, y sin espacios en blanco.
 *
 * <p>Las anotaciones de validacion se repiten explicitamente en cada DTO con
 * contrasena para que la regla sea legible desde el propio DTO.
 */
public final class PasswordPolicy {

    public static final String MESSAGE = "La contrasena debe tener al menos 12 caracteres, "
            + "incluir mayuscula, minuscula, digito y simbolo, y no contener espacios.";

    /** Mayuscula + minuscula + digito + simbolo, 12..128 caracteres, sin espacios. */
    public static final String REGEX =
            "^(?=.*[a-z])(?=.*[A-Z])(?=.*\\d)(?=.*[^A-Za-z0-9\\s])\\S{12,128}$";

    /** Tope de longitud del token opaco de recuperacion. */
    public static final int MAX_TOKEN_LENGTH = 512;

    private PasswordPolicy() {
    }
}