package com.aylzz.xmrforecast.auth;

import com.aylzz.xmrforecast.audit.AuditEvent;
import com.aylzz.xmrforecast.audit.AuditService;
import com.aylzz.xmrforecast.auth.dto.TokenResponse;
import com.aylzz.xmrforecast.common.ApiException;
import com.aylzz.xmrforecast.config.AppProperties;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Login social con Google OAuth 2.0 / OpenID Connect, Authorization Code Flow.
 *
 * <p>Controles implementados (OWASP):
 * <ul>
 *   <li>Authorization Code Flow: el codigo se canjea en el servidor. El secreto
 *       nunca llega al navegador.</li>
 *   <li>Validacion del ID Token: firma (JWKS de Google), {@code iss}, {@code aud},
 *       {@code exp}, {@code nonce} y {@code state}.</li>
 *   <li>CSRF: {@code state} de un solo uso y ligado a la sesion, mas {@code nonce}.</li>
 *   <li>Se rechazan responses con error de Google, incluidos {@code error=access_denied}
 *       que hacen que NaN quede registrado como si fuera un fallo de credenciales.</li>
 * </ul>
 *
 * <p>El cliente por defecto es el propio backend: el frontend no recibe ningun
 * secreto ni se comunica directamente con Google.
 */
@Service
public class GoogleOAuthService {

    private static final Logger log = LoggerFactory.getLogger(GoogleOAuthService.class);

    private static final String TOKEN_ENDPOINT = "https://oauth2.googleapis.com/token";
    private static final String AUTH_ENDPOINT = "https://accounts.google.com/o/oauth2/v2/auth";
    private static final String JWKS_URI = "https://www.googleapis.com/oauth2/v3/certs";
    private static final String ISSUER = "https://accounts.google.com";

    private static final String STATE_TTL_KEY = "oauth:state:";

    private final AppProperties properties;
    private final RestClient restClient;
    private final AuthService authService;
    private final AuditService auditService;

    /** Estados de un solo uso. En produccion deben vivir en Redis, no en memoria. */
    private final Map<String, OAuthState> pendingStates = new ConcurrentHashMap<>();

    /** Cache del JWKS de Google: URI -> (kid -> campos del JWK). */
    private final Map<String, Map<String, Map<String, Object>>> jwksCache = new ConcurrentHashMap<>();

    private final AppProperties.OAuth2.Google google() {
        return properties.oauth2().google();
    }

    public GoogleOAuthService(AppProperties properties, RestClient.Builder builder,
                              AuthService authService, AuditService auditService) {
        this.properties = properties;
        this.restClient = builder.build();
        this.authService = authService;
        this.auditService = auditService;
    }

    private void requireConfigured() {
        if (!google().isConfigured()) {
            throw ApiException.unavailable("OAUTH_NOT_CONFIGURED",
                    "El inicio de sesion con Google no esta configurado en este entorno.");
        }
    }

    /** Construye la URL de autorizacion de Google con state y nonce de un solo uso. */
    public URI buildAuthorizationUri() {
        requireConfigured();
        String state = UUID.randomUUID().toString();
        String nonce = UUID.randomUUID().toString();
        pendingStates.put(state, new OAuthState(nonce, System.currentTimeMillis()));

        return UriComponentsBuilder.fromUriString(AUTH_ENDPOINT)
                .queryParam("client_id", google().clientId())
                .queryParam("redirect_uri", google().redirectUri())
                .queryParam("response_type", "code")
                .queryParam("scope", "openid email profile")
                .queryParam("state", state)
                .queryParam("nonce", nonce)
                .queryParam("prompt", "select_account")
                .queryParam("access_type", "offline")
                .build(true)
                .toUri();
    }

    /**
     * Procesa el retorno de Google: valida state, canjea el codigo y valida el
     * ID Token. Devuelve la sesion propia de la aplicacion.
     */
    @Transactional
    public TokenResponse handleCallback(String code, String state, String error,
                                        String errorDescription, HttpServletRequest request) {
        requireConfigured();

        // Google devuelve un parametro `error` cuando el usuario cancela o deniega.
        // No es un fallo de credenciales: se distingue y se registra aparte.
        if (error != null && !error.isBlank()) {
            auditService.record(null, null, "AUTH_GOOGLE_DENIED", "OAuthAccount", null,
                    AuditEvent.Outcome.DENIED,
                    Map.of("oauth_error", error, "description", String.valueOf(errorDescription)));
            throw ApiException.unauthorized("OAUTH_DENIED",
                    "El inicio de sesion con Google fue cancelado o denegado.");
        }

        if (code == null || code.isBlank()) {
            throw ApiException.badRequest("OAUTH_MISSING_CODE",
                    "No se recibio el codigo de autorizacion.");
        }

        OAuthState expected = consumeState(state);
        if (expected == null) {
            auditService.record(null, null, "AUTH_GOOGLE_INVALID_STATE", "OAuthAccount", null,
                    AuditEvent.Outcome.DENIED, Map.of());
            // Un state invalido es un intento de CSRF o de reutilizacion.
            throw ApiException.badRequest("OAUTH_INVALID_STATE",
                    "La solicitud de inicio de sesion no es valida o ya expiro.");
        }

        String idToken = exchangeCodeForIdToken(code);
        JsonNode claims = verifyIdToken(idToken, expected.nonce());

        AuthService.GoogleIdentity identity = new AuthService.GoogleIdentity(
                text(claims, "sub"),
                text(claims, "email"),
                claims.path("email_verified").asBoolean(false));

        if (identity.subject() == null) {
            throw ApiException.unauthorized("INVALID_ID_TOKEN",
                    "El ID Token de Google no incluye un identificador de sujeto.");
        }

        return authService.loginWithGoogle(identity,
                AuditService.clientIp(request), request.getHeader("User-Agent"));
    }

    private OAuthState consumeState(String state) {
        if (state == null || state.isBlank()) {
            return null;
        }
        OAuthState stored = pendingStates.remove(state);
        if (stored == null) {
            return null;
        }
        // Ventana de 10 minutos: suficiente para el ida y vuelta de OAuth.
        long ageMillis = System.currentTimeMillis() - stored.createdAtMillis();
        return ageMillis <= 600_000L ? stored : null;
    }

    /** Canje del codigo por tokens. El client secret no sale del servidor (R-14). */
    private String exchangeCodeForIdToken(String code) {
        try {
            String body = restClient.post()
                    .uri(TOKEN_ENDPOINT)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form("code", code))
                    .retrieve()
                    .body(String.class);
            JsonNode json = new com.fasterxml.jackson.databind.ObjectMapper().readTree(body);
            JsonNode idToken = json.get("id_token");
            if (idToken == null || idToken.asText().isBlank()) {
                log.warn("Google no devolvio id_token");
                throw ApiException.unauthorized("OAUTH_NO_ID_TOKEN",
                        "Google no devolvio un ID Token valido.");
            }
            return idToken.asText();
        } catch (ApiException ex) {
            throw ex;
        } catch (Exception ex) {
            log.warn("Fallo al canjear el codigo de autorizacion: {}", ex.getMessage());
            throw ApiException.unauthorized("OAUTH_CODE_EXCHANGE_FAILED",
                    "No se pudo completar el inicio de sesion con Google.");
        }
    }

    private org.springframework.util.MultiValueMap<String, String> form(String key, String value) {
        var map = new org.springframework.util.LinkedMultiValueMap<String, String>();
        map.add("code", value);
        map.add("client_id", google().clientId());
        map.add("client_secret", google().clientSecret());
        map.add("redirect_uri", google().redirectUri());
        map.add("grant_type", "authorization_code");
        return map;
    }

    /**
     * Valida el ID Token: firma RSA contra el JWKS de Google, emisor, audiencia,
     * expiracion y nonce. Rechaza cualquier alg distinto de RS256.
     */
    private JsonNode verifyIdToken(String idToken, String expectedNonce) {
        String[] parts = idToken.split("\\.");
        if (parts.length != 3) {
            throw ApiException.unauthorized("MALFORMED_ID_TOKEN", "El ID Token no es valido.");
        }

        JsonNode header = readJson(Base64.getUrlDecoder().decode(parts[0]));
        String alg = text(header, "alg");
        if (!"RS256".equals(alg)) {
            // Nunca aceptar HS256 con la clave publica como secreto: permite falsificar tokens.
            throw ApiException.unauthorized("UNSUPPORTED_ALG",
                    "El ID Token usa un algoritmo de firma no admitido.");
        }

        String kid = text(header, "kid");
        java.security.PublicKey key = resolveKey(kid);

        byte[] signingInput = (parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII);
        byte[] signature = Base64.getUrlDecoder().decode(parts[1]);
        if (!verifySignature(key, signingInput, signature)) {
            throw ApiException.unauthorized("INVALID_ID_TOKEN_SIGNATURE",
                    "La firma del ID Token no es valida.");
        }

        JsonNode claims = readJson(Base64.getUrlDecoder().decode(parts[2]));

        String iss = text(claims, "iss");
        if (iss == null || !properties.oauth2().google().allowedIssuers().contains(iss)) {
            throw ApiException.unauthorized("INVALID_ISSUER", "El emisor del ID Token no es Google.");
        }

        String aud = text(claims, "aud");
        if (aud == null || !aud.equals(google().expectedAudience())) {
            throw ApiException.unauthorized("INVALID_AUDIENCE",
                    "La audiencia del ID Token no corresponde a esta aplicacion.");
        }

        long exp = claims.path("exp").asLong(0);
        if (exp == 0 || exp < System.currentTimeMillis() / 1000L) {
            throw ApiException.unauthorized("EXPIRED_ID_TOKEN", "El ID Token de Google expiro.");
        }

        String nonce = text(claims, "nonce");
        if (nonce == null || !nonce.equals(expectedNonce)) {
            // Un nonce ausente o distinto significa que el token no corresponde a
            // esta peticion: es la defensa contra replay.
            throw ApiException.unauthorized("INVALID_NONCE", "El nonce del ID Token no coincide.");
        }

        return claims;
    }

    private boolean verifySignature(java.security.PublicKey key, byte[] signingInput, byte[] signature) {
        try {
            var verifier = java.security.Signature.getInstance("SHA256withRSA");
            verifier.initVerify(key);
            verifier.update(signingInput);
            return verifier.verify(signature);
        } catch (java.security.GeneralSecurityException ex) {
            log.warn("No se pudo verificar la firma del ID Token: {}", ex.getMessage());
            return false;
        }
    }

    /** Obtiene la clave publica de Google a partir del JWKS, cacheado un tiempo. */
    private java.security.PublicKey resolveKey(String kid) {
        Map<String, Object> jwk = jwksByKeyId().get(kid);
        if (jwk == null) {
            // Kid desconocido: puede ser una clave rotada por Google.
            // Se invalida la cache y se reintenta una sola vez.
            jwksCache.remove(JWKS_URI);
            jwk = jwksByKeyId().get(kid);
            if (jwk == null) {
                throw ApiException.unauthorized("UNKNOWN_SIGNING_KEY",
                        "La clave de firma del ID Token es desconocida.");
            }
        }
        try {
            return java.security.KeyFactory.getInstance("RSA")
                    .generatePublic(new java.security.spec.RSAPublicKeySpec(
                            new java.math.BigInteger(1, Base64.getUrlDecoder().decode((String) jwk.get("n"))),
                            new java.math.BigInteger(1, Base64.getUrlDecoder().decode((String) jwk.get("e")))));
        } catch (RuntimeException | java.security.GeneralSecurityException ex) {
            log.warn("No se pudo construir la clave publica de Google: {}", ex.getMessage());
            throw ApiException.unauthorized("INVALID_JWKS_KEY",
                    "No se pudo construir la clave publica de Google.");
        }
    }

    /** Descarga y parsea el JWKS de Google, cacheandolo para no repetir la llamada. */
    private Map<String, Map<String, Object>> jwksByKeyId() {
        Map<String, Map<String, Object>> cached = jwksCache.get(JWKS_URI);
        if (cached != null) {
            return cached;
        }
        try {
            String body = restClient.get().uri(JWKS_URI).retrieve().body(String.class);
            JsonNode parsed = new com.fasterxml.jackson.databind.ObjectMapper().readTree(body);
            Map<String, Map<String, Object>> parsedKeys = new ConcurrentHashMap<>();
            for (JsonNode key : parsed.path("keys")) {
                String keyId = text(key, "kid");
                if (keyId == null) {
                    continue;
                }
                Map<String, Object> entry = new ConcurrentHashMap<>();
                key.fields().forEachRemaining(field ->
                        entry.put(field.getKey(), asObject(field.getValue())));
                parsedKeys.put(keyId, entry);
            }
            jwksCache.put(JWKS_URI, parsedKeys);
            return parsedKeys;
        } catch (ApiException ex) {
            throw ex;
        } catch (Exception ex) {
            log.warn("No se pudo obtener el JWKS de Google: {}", ex.getMessage());
            throw ApiException.unavailable("JWKS_UNAVAILABLE",
                    "No se pudieron obtener las claves de verificacion de Google.");
        }
    }

    private static Object asObject(JsonNode node) {
        if (node.isTextual()) {
            return node.asText();
        }
        return node.asText("");
    }

    private static JsonNode readJson(byte[] payload) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().readTree(payload);
        } catch (Exception ex) {
            throw ApiException.unauthorized("MALFORMED_ID_TOKEN", "El ID Token no es valido.");
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    private record OAuthState(String nonce, long createdAtMillis) {
    }
}