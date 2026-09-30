package com.aylzz.xmrforecast.auth;

import com.aylzz.xmrforecast.auth.dto.TokenResponse;
import com.aylzz.xmrforecast.common.ApiException;
import com.aylzz.xmrforecast.config.AppProperties;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Endpoints del flujo OAuth 2.0 de Google. Publicos por necesidad: el navegador
 * llega aqui desde Google, sin cabeceras de autorizacion (R-35).
 */
@Tag(name = "Autenticacion")
@RestController
@RequestMapping("/api/v1/auth/google")
public class GoogleOAuthController {

    private final GoogleOAuthService oauthService;
    private final AppProperties properties;

    public GoogleOAuthController(GoogleOAuthService oauthService, AppProperties properties) {
        this.oauthService = oauthService;
        this.properties = properties;
    }

    /** Redirige al formulario de consentimiento de Google. */
    @Operation(summary = "Inicia el flujo de Google OAuth 2.0",
            description = "Publica. Devuelve 307 con la URL de autorizacion de Google.")
    @GetMapping("/authorize")
    public ResponseEntity<Void> authorize() {
        var uri = oauthService.buildAuthorizationUri();
        return ResponseEntity.status(HttpStatus.TEMPORARY_REDIRECT)
                .header(HttpHeaders.LOCATION, uri.toString())
                .build();
    }

    /**
     * Callback de Google. En lugar de exponer los tokens en la URL (quedarian en
     * el historial y en los logs del navegador), devuelve un HTML minimo que
     * entrega la sesion al frontend y Borra la URL del historial.
     */
    @Operation(summary = "Callback de Google OAuth 2.0",
            description = "Publica. Google redirige aqui con GET. Valida state y nonce, "
                    + "y responde 302 al frontend con la sesion en el fragmento de la URL.")
    @GetMapping("/callback")
    public ResponseEntity<Void> callback(@RequestParam(required = false) String code,
                                         @RequestParam(required = false) String state,
                                         @RequestParam(required = false) String error,
                                         @RequestParam(required = false) String error_description,
                                         HttpServletRequest request) {
        TokenResponse tokens;
        try {
            tokens = oauthService.handleCallback(code, state, error, error_description, request);
        } catch (ApiException ex) {
            return redirectToFrontend("error_code=" + urlEncode(ex.getCode())
                    + "&error_message=" + urlEncode(ex.getMessage()));
        }

        // Traspaso de la sesion en el fragmento de la URL (#...). El fragmento nunca
        // viaja al servidor, no aparece en los logs de acceso ni en la cabecera
        // Referer, y el frontend lo borra de inmediato con history.replaceState.
        return redirectToFrontend("access_token=" + urlEncode(tokens.accessToken())
                        + "&refresh_token=" + urlEncode(tokens.refreshToken())
                        + "&token_type=" + urlEncode(tokens.tokenType())
                        + "&expires_in=" + tokens.expiresIn()
                        + "&role=" + urlEncode(tokens.roles().stream()
                                .map(Enum::name).findFirst().orElse("VIEWER")),
                // Ademas del fragmento se emite la cookie HttpOnly: el cliente puede
                // descartar el fragmento y renovar por cookie sin volver a exponer
                // el refresh token a JavaScript.
                RefreshCookie.issue(tokens.refreshToken(),
                        properties.jwt().refreshTokenTtlSeconds()));
    }

    private ResponseEntity<Void> redirectToFrontend(String fragment) {
        return redirectToFrontend(fragment, null);
    }

    private ResponseEntity<Void> redirectToFrontend(String fragment,
                                                    org.springframework.http.ResponseCookie cookie) {
        String base = properties.oauth2().google().frontendCallback();
        String target = (base == null || base.isBlank() ? "https://localhost:3000" : base)
                + "#" + fragment;
        ResponseEntity.BodyBuilder builder = ResponseEntity.status(HttpStatus.FOUND)
                .header(HttpHeaders.LOCATION, target)
                // La sesion no debe quedar cacheada en el navegador.
                .header(HttpHeaders.CACHE_CONTROL, "no-store, no-cache, must-revalidate")
                .header(HttpHeaders.PRAGMA, "no-cache");
        if (cookie != null) {
            builder.header(HttpHeaders.SET_COOKIE, cookie.toString());
        }
        return builder.build();
    }

    /** Percent-encoding para construir el fragmento de forma segura. */
    private static String urlEncode(String value) {
        return java.net.URLEncoder.encode(value == null ? "" : value,
                java.nio.charset.StandardCharsets.UTF_8);
    }
}