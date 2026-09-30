package com.aylzz.xmrforecast.auth;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseCookie;

import java.time.Duration;

/**
 * Cookie del refresh token.
 *
 * <p>Existe una segunda via de entrega del refresh token, deliberadamente
 * restringida a la recomendada: la cookie es {@code HttpOnly} (inaccesible desde
 * JavaScript, de modo que un XSS no puede exfiltrarla), {@code Secure} (solo
 * viaja por HTTPS) y {@code SameSite=Strict} (no se envia en navegaciones
 *-automaticas de terceros, lo que cierra el CSRF sobre el endpoint de refresh).
 *
 * <p>El token tambien se devuelve en el cuerpo porque hay clientes no navegador
 * (k6, CLI, integraciones) que no tienen almacén de cookies. El servidor acepta
 * ambas vias: cuerpo primero, cookie despues.
 */
public final class RefreshCookie {

    /** Nombre de la cookie. No aparece en ningun log ni en ninguna respuesta JSON. */
    public static final String NAME = "xmr_refresh";

    private RefreshCookie() {
    }

    /** Lee la cookie de la peticion; {@code null} si no viaja. */
    public static String read(HttpServletRequest request) {
        if (request == null) {
            return null;
        }
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        for (Cookie cookie : cookies) {
            if (NAME.equals(cookie.getName()) && cookie.getValue() != null
                    && !cookie.getValue().isBlank()) {
                return cookie.getValue();
            }
        }
        return null;
    }

    /**
     * Construye la cookie de entrega. El {@code path} se limita a la ruta del
     * endpoint de refresh: la cookie no debe viajar en cada peticion de la API.
     */
    public static ResponseCookie issue(String token, long ttlSeconds) {
        return ResponseCookie.from(NAME, token)
                .httpOnly(true)
                .secure(true)
                .sameSite("Strict")
                .path("/api/v1/auth")
                .maxAge(Duration.ofSeconds(Math.max(ttlSeconds, 1)))
                .build();
    }

    /** Cookie de borrado, con los mismos atributos que {@link #issue}. */
    public static ResponseCookie clear() {
        return ResponseCookie.from(NAME, "")
                .httpOnly(true)
                .secure(true)
                .sameSite("Strict")
                .path("/api/v1/auth")
                .maxAge(Duration.ZERO)
                .build();
    }
}
