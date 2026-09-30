package com.aylzz.xmrforecast.auth.dto;

import com.aylzz.xmrforecast.security.Role;

import java.util.Set;

/**
 * Respuesta de emision de sesion. El frontend guarda el access token en memoria
 * y el refresh token segun la politica de cookies del despliegue.
 */
public record TokenResponse(
        String accessToken,
        String refreshToken,
        String tokenType,
        long expiresIn,
        Set<Role> roles,
        UserResponse user
) {
    public static final String BEARER = "Bearer";

    public static TokenResponse of(String accessToken, String refreshToken, long expiresIn,
                                   Set<Role> roles, UserResponse user) {
        return new TokenResponse(accessToken, refreshToken, BEARER, expiresIn, roles, user);
    }
}