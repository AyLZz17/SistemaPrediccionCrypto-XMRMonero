package com.aylzz.xmrforecast.auth;

import com.aylzz.xmrforecast.config.AppProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/**
 * Verificacion del ID Token de Google.
 *
 * <p><strong>El fallo que este test fija.</strong> La verificacion de la firma
 * comparaba el <em>payload</em> contra si mismo: se tomaba {@code parts[1]} (el
 * payload) como si fuera la firma y se INTENTABA leer en {@code parts[2]} (la
 * firma) como si fueran los claims. El resultado era que <strong>ningun login con
 * Google podia completarse</strong>: la firma nunca cuadraba y se respondia
 * INVALID_ID_TOKEN_SIGNATURE el 100 % de las veces. Compilaba, arrancaba, y
 * toda la cadena de pruebas pasaba porque el flujo OAuth nunca se ejecutaba
 * contra Google.
 *
 * <p>Un JWT es {@code base64url(header) . base64url(payload) . base64url(firma)}.
 * Este test firma un token de verdad con una clave RSA y comprueba que el
 * servicio lo acepta, y que rechaza las tres formas de manipularlo.
 */
class GoogleOAuthServiceTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final KeyPairGenerator generator = keyPair();

    // ------------------------------------------------------------- utilities

    private static KeyPairGenerator keyPair() {
        try {
            KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
            gen.initialize(2048);
            return gen;
        } catch (Exception ex) {
            throw new IllegalStateException("RSA no disponible en esta JVM", ex);
        }
    }

    private static String base64Url(byte[] raw) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
    }

    private static String sign(KeyPair keyPair, byte[] signingInput) {
        try {
            Signature signature = Signature.getInstance("SHA256withRSA");
            signature.initSign(keyPair.getPrivate());
            signature.update(signingInput);
            return base64Url(signature.sign());
        } catch (Exception ex) {
            throw new IllegalStateException("No se pudo firmar el token de prueba", ex);
        }
    }

    /** Construye un ID Token firmado de verdad, con la forma de Google. */
    private String signedToken(KeyPair keyPair, String kid, JsonNode claims) {
        try {
            String header = base64Url(("{\"alg\":\"RS256\",\"kid\":\"" + kid
                    + "\",\"typ\":\"JWT\"}").getBytes(StandardCharsets.UTF_8));
            String payload = base64Url(JSON.writeValueAsBytes(claims));
            String signingInput = header + "." + payload;
            return signingInput + "."
                    + sign(keyPair, signingInput.getBytes(StandardCharsets.US_ASCII));
        } catch (Exception ex) {
            throw new IllegalStateException("No se pudo construir el token de prueba", ex);
        }
    }

    private com.fasterxml.jackson.databind.node.ObjectNode claims(String nonce, String audience) {
        return JSON.createObjectNode()
                .put("iss", "https://accounts.google.com")
                .put("aud", audience)
                .put("sub", "1234567890")
                .put("email", "user@example.com")
                .put("email_verified", true)
                .put("nonce", nonce)
                .put("iat", System.currentTimeMillis() / 1000L)
                .put("exp", System.currentTimeMillis() / 1000L + 3600L);
    }

    /** Servicio con la clave publica de test inyectada en la cache del JWKS. */
    private GoogleOAuthService service(KeyPair keyPair, String kid, String expectedAudience) {
        AppProperties properties = new AppProperties(
                new AppProperties.Jwt("k".repeat(64), "https://localhost:8443",
                        "xmr-forecast-api", 900, 2592000, 30),
                new AppProperties.OAuth2(new AppProperties.OAuth2.Google(
                        "client-id", "client-secret",
                        "https://localhost:8443/api/v1/auth/google/callback",
                        "https://localhost:3000/auth/callback",
                        List.of("https://accounts.google.com"),
                        expectedAudience)),
                new AppProperties.Ml("https://ml-service:8443", 3000, 60000, 2, ""),
                new AppProperties.Security(true, 31536000L,
                        List.of("https://localhost:3000"),
                        new AppProperties.Security.RateLimit(true, 5, 300),
                        new AppProperties.Security.Account(5, 15)),
                new AppProperties.Jobs(3, 900));

        GoogleOAuthService service = new GoogleOAuthService(properties,
                org.springframework.web.client.RestClient.builder(),
                mock(AuthService.class), mock(com.aylzz.xmrforecast.audit.AuditService.class));

        // Se inyecta la clave publica directamente en la cache del JWKS: la firma
        // la produce el propio test, sin llamadas de red (R-18). El formato es el
        // que devuelve https://www.googleapis.com/oauth2/v3/certs: `n` y `e` en
        // base64url.
        Map<String, Object> jwk = new java.util.HashMap<>();
        jwk.put("n", rsaModulus(keyPair));
        jwk.put("e", "AQAB");
        @SuppressWarnings("unchecked")
        Map<String, Map<String, Map<String, Object>>> cache =
                (Map<String, Map<String, Map<String, Object>>>) ReflectionTestUtils
                        .getField(service, "jwksCache");
        cache.put("https://www.googleapis.com/oauth2/v3/certs", Map.of(kid, jwk));
        return service;
    }

    /** Modulo `n` del JWKS, en base64url sin relleno: el formato que espera el servicio. */
    private static String rsaModulus(KeyPair keyPair) {
        java.security.interfaces.RSAPublicKey key =
                (java.security.interfaces.RSAPublicKey) keyPair.getPublic();
        byte[] unsigned = key.getModulus().toByteArray();
        // BigInteger puede anteponer un 0x00 para que el numero sea positivo; el
        // base64url de un modulo no lo lleva.
        int offset = (unsigned.length > 1 && unsigned[0] == 0) ? 1 : 0;
        return base64Url(java.util.Arrays.copyOfRange(unsigned, offset, unsigned.length));
    }

    /** Invoca `verifyIdToken`, que es privada, por reflexion. */
    private JsonNode verify(GoogleOAuthService service, String token, String expectedNonce) {
        return (JsonNode) ReflectionTestUtils.invokeMethod(service, "verifyIdToken",
                token, expectedNonce);
    }

    // ------------------------------------------------------------------ tests

    @Test
    @DisplayName("Un ID Token bien formado y firmado se acepta")
    void acceptsAValidlySignedToken() {
        KeyPair keyPair = generator.generateKeyPair();
        String nonce = "nonce-de-prueba";
        String token = signedToken(keyPair, "kid-1", claims(nonce, "client-id"));
        GoogleOAuthService service = service(keyPair, "kid-1", "client-id");

        JsonNode verified = verify(service, token, nonce);

        assertThat(verified).isNotNull();
        assertThat(verified.get("sub").asText()).isEqualTo("1234567890");
        assertThat(verified.get("email").asText()).isEqualTo("user@example.com");
    }

    @Test
    @DisplayName("Un token cuya firma no corresponde al payload se rechaza")
    void rejectsATamperedToken() {
        KeyPair keyPair = generator.generateKeyPair();
        KeyPair otherKeyPair = generator.generateKeyPair();
        String nonce = "nonce-de-prueba";
        String token = signedToken(otherKeyPair, "kid-1", claims(nonce, "client-id"));
        GoogleOAuthService service = service(keyPair, "kid-1", "client-id");

        assertThatThrownBy(() -> verify(service, token, nonce))
                .isInstanceOf(com.aylzz.xmrforecast.common.ApiException.class)
                .hasMessageContaining("firma");
    }

    @Test
    @DisplayName("Un token con el payload alterado se rechaza aunque la firma sea valida")
    void rejectsModifiedClaims() {
        KeyPair keyPair = generator.generateKeyPair();
        String nonce = "nonce-de-prueba";
        String original = signedToken(keyPair, "kid-1", claims(nonce, "client-id"));
        // Se sustituye el payload por uno con otra audiencia, conservando cabecera y
        // firma. Un codigo que leyera los claims antes de verificar la firma
        // aceptaria esto; el orden correcto lo rechaza.
        String[] parts = original.split("\\.");
        String forged = base64Url(claims(nonce, "otra-aplicacion")
                .toString().getBytes(StandardCharsets.UTF_8));
        String tampered = parts[0] + "." + forged + "." + parts[2];
        GoogleOAuthService service = service(keyPair, "kid-1", "client-id");

        assertThatThrownBy(() -> verify(service, tampered, nonce))
                .isInstanceOf(com.aylzz.xmrforecast.common.ApiException.class);
    }

    @Test
    @DisplayName("Un token de otro emisor se rechaza")
    void rejectsForeignIssuer() {
        KeyPair keyPair = generator.generateKeyPair();
        String nonce = "n";
        JsonNode claims = claims(nonce, "client-id").put("iss", "https://evil.example.com");
        GoogleOAuthService service = service(keyPair, "kid-1", "client-id");

        assertThatThrownBy(() -> verify(service, signedToken(keyPair, "kid-1", claims), nonce))
                .isInstanceOf(com.aylzz.xmrforecast.common.ApiException.class);
    }

    @Test
    @DisplayName("Un token para otra audiencia se rechaza")
    void rejectsWrongAudience() {
        KeyPair keyPair = generator.generateKeyPair();
        String nonce = "n";
        GoogleOAuthService service = service(keyPair, "kid-1", "client-id");

        assertThatThrownBy(() -> verify(service,
                signedToken(keyPair, "kid-1", claims(nonce, "otra-app")), nonce))
                .isInstanceOf(com.aylzz.xmrforecast.common.ApiException.class);
    }

    @Test
    @DisplayName("Un nonce distinto se rechaza (defensa contra replay)")
    void rejectsWrongNonce() {
        KeyPair keyPair = generator.generateKeyPair();
        GoogleOAuthService service = service(keyPair, "kid-1", "client-id");

        assertThatThrownBy(() -> verify(service,
                signedToken(keyPair, "kid-1", claims("nonce-original", "client-id")),
                "nonce-esperado"))
                .isInstanceOf(com.aylzz.xmrforecast.common.ApiException.class)
                .hasMessageContaining("nonce");
    }

    @Test
    @DisplayName("Un token expirado se rechaza")
    void rejectsExpiredToken() {
        KeyPair keyPair = generator.generateKeyPair();
        String nonce = "n";
        JsonNode claims = claims(nonce, "client-id")
                .put("exp", System.currentTimeMillis() / 1000L - 60);
        GoogleOAuthService service = service(keyPair, "kid-1", "client-id");

        assertThatThrownBy(() -> verify(service, signedToken(keyPair, "kid-1", claims), nonce))
                .isInstanceOf(com.aylzz.xmrforecast.common.ApiException.class)
                .hasMessageContaining("expiro");
    }

    @Test
    @DisplayName("Un token con correo sin verificar se rechaza")
    void rejectsUnverifiedEmail() {
        KeyPair keyPair = generator.generateKeyPair();
        String nonce = "n";
        JsonNode claims = claims(nonce, "client-id").put("email_verified", false);
        GoogleOAuthService service = service(keyPair, "kid-1", "client-id");

        assertThatThrownBy(() -> verify(service, signedToken(keyPair, "kid-1", claims), nonce))
                .isInstanceOf(com.aylzz.xmrforecast.common.ApiException.class)
                .hasMessageContaining("verificado");
    }

    @Test
    @DisplayName("Sin audiencia esperada se rechaza cerrado, no se relaja el control")
    void rejectsWhenExpectedAudienceIsBlank() {
        KeyPair keyPair = generator.generateKeyPair();
        String nonce = "n";
        GoogleOAuthService service = service(keyPair, "kid-1", "");

        assertThatThrownBy(() -> verify(service,
                signedToken(keyPair, "kid-1", claims(nonce, "client-id")), nonce))
                .isInstanceOf(com.aylzz.xmrforecast.common.ApiException.class);
    }

    @Test
    @DisplayName("aud como arreglo se acepta si contiene la audiencia esperada")
    void acceptsAudienceArray() {
        // OpenID Connect Core 3.1.3.7 admite ambas formas: si el token es para
        // varias aplicaciones, `aud` es un arreglo. Aceptar solo la cadena
        // rechazaba tokens legitimos de Google.
        KeyPair keyPair = generator.generateKeyPair();
        String nonce = "n";
        com.fasterxml.jackson.databind.node.ObjectNode claims = JSON.createObjectNode()
                .put("iss", "https://accounts.google.com")
                .put("sub", "1234567890")
                .put("email", "user@example.com")
                .put("email_verified", true)
                .put("nonce", nonce)
                .put("iat", System.currentTimeMillis() / 1000L)
                .put("exp", System.currentTimeMillis() / 1000L + 3600);
        claims.putArray("aud").add("otra-app").add("client-id");
        GoogleOAuthService service = service(keyPair, "kid-1", "client-id");

        assertThat(verify(service, signedToken(keyPair, "kid-1", claims), nonce)).isNotNull();
    }

    @Test
    @DisplayName("HS256 se rechaza: la clave publica de Google no es un secreto")
    void rejectsHs256() {
        // Con HS256, un atacante que conozca la clave publica de Google podria
        // firmar su propio id_token. Google solo admite RS256.
        KeyPair keyPair = generator.generateKeyPair();
        String nonce = "n";
        String header = base64Url("{\"alg\":\"HS256\",\"kid\":\"kid-1\"}"
                .getBytes(StandardCharsets.UTF_8));
        String payload = signedToken(keyPair, "kid-1", claims(nonce, "client-id"))
                .split("\\.")[1];
        String token = header + "." + payload + "." + base64Url("firma-falsa"
                .getBytes(StandardCharsets.UTF_8));
        GoogleOAuthService service = service(keyPair, "kid-1", "client-id");

        assertThatThrownBy(() -> verify(service, token, nonce))
                .isInstanceOf(com.aylzz.xmrforecast.common.ApiException.class)
                .hasMessageContaining("algoritmo");
    }

    @Test
    @DisplayName("Un token con una clave desconocida se rechaza")
    void rejectsUnknownSigningKey() {
        KeyPair keyPair = generator.generateKeyPair();
        KeyPair other = generator.generateKeyPair();
        String nonce = "n";
        // Firmado con una clave cuya kid no esta en el JWKS cacheado.
        String token = signedToken(other, "kid-desconocida", claims(nonce, "client-id"));
        GoogleOAuthService service = service(keyPair, "kid-1", "client-id");

        assertThatThrownBy(() -> verify(service, token, nonce))
                .isInstanceOf(com.aylzz.xmrforecast.common.ApiException.class);
    }

    @Test
    @DisplayName("Un token mal formado se rechaza sin lanzar excepcion propia")
    void rejectsMalformedToken() {
        GoogleOAuthService service = service(generator.generateKeyPair(), "kid-1", "client-id");

        assertThatThrownBy(() -> verify(service, "no-es-un-jwt", "n"))
                .isInstanceOf(com.aylzz.xmrforecast.common.ApiException.class);
    }
}
