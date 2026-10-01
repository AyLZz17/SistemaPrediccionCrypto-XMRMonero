package com.aylzz.xmrforecast.security;

import com.aylzz.xmrforecast.config.AppProperties;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pruebas del servicio JWT. Cubren los requisitos de la seccion 4: claims
 * obligatorios, duracion corta, rechazo de algoritmos no permitidos y de claves
 * debiles.
 */
class JwtServiceTest {

    private static final String SECRET = "clave-de-prueba-de-64-bytes-minimo-para-hs512-xxxxxxxxxxxxxxxxxxxxxxx";
    private static final String ISSUER = "https://localhost:8443";
    private static final String AUDIENCE = "xmr-forecast-api";

    private AppProperties properties;
    private JwtService jwtService;

    @BeforeEach
    void setUp() {
        properties = new AppProperties(
                new AppProperties.Jwt(SECRET, ISSUER, AUDIENCE, 900, 2592000, 30),
                new AppProperties.OAuth2(new AppProperties.OAuth2.Google(
                        "", "", "", "", List.of(), "")),
                new AppProperties.Ml("https://ml-service:8443", 3000, 60000, 2, ""),
                new AppProperties.Security(true, 31536000, List.of("https://localhost:3000"),
                        new AppProperties.Security.RateLimit(false, 5, 300),
                        new AppProperties.Security.Account(5, 15)),
                new AppProperties.Jobs(3, 900));
        jwtService = new JwtService(properties);
    }

    @Test
    @DisplayName("Emite un token con todos los claims obligatorios")
    void emitsAllRequiredClaims() {
        var issued = jwtService.issueAccessToken(7L, "user@example.com", List.of(Role.VIEWER));

        var claims = jwtService.parse(issued.token());

        assertThat(claims).isNotNull();
        assertThat(claims.getSubject()).isEqualTo("7");
        assertThat(claims.getIssuer()).isEqualTo(ISSUER);
        assertThat(claims.getAudience()).contains(AUDIENCE);
        assertThat(claims.getIssuedAt()).isNotNull();
        assertThat(claims.getExpiration()).isNotNull();
        assertThat(claims.getId()).isNotBlank().isEqualTo(issued.jti());
        assertThat(String.valueOf(claims.get(Role.CLAIM))).contains("VIEWER");
    }

    @Test
    @DisplayName("El access token es de corta duracion")
    void accessTokenIsShortLived() {
        var issued = jwtService.issueAccessToken(1L, "a@b.com", List.of(Role.VIEWER));
        long ttlSeconds = issued.expiresAt().getEpochSecond() - Instant.now().getEpochSecond();

        assertThat(ttlSeconds).isLessThanOrEqualTo(900).isGreaterThan(0);
    }

    @Test
    @DisplayName("Cada emision usa un jti distinto, para poder revocar por separado")
    void jtiIsUniquePerToken() {
        var first = jwtService.issueAccessToken(1L, "a@b.com", List.of(Role.VIEWER));
        var second = jwtService.issueAccessToken(1L, "a@b.com", List.of(Role.VIEWER));

        assertThat(first.jti()).isNotEqualTo(second.jti());
    }

    @Test
    @DisplayName("Rechaza un token firmado con otra clave")
    void rejectsForeignSignature() {
        var otherKey = Keys.hmacShaKeyFor(
                "otra-clave-de-prueba-de-64-bytes-minimo-para-hs512-xxxxxxxxxxxxx".getBytes(StandardCharsets.UTF_8));
        String forged = Jwts.builder()
                .subject("1")
                .issuer(ISSUER)
                .audience().add(AUDIENCE).and()
                .issuedAt(new Date())
                .expiration(Date.from(Instant.now().plusSeconds(300)))
                .signWith(otherKey, Jwts.SIG.HS512)
                .compact();

        assertThat(jwtService.parse(forged)).isNull();
    }

    @Test
    @DisplayName("Rechaza un token con issuer distinto (evita reutilizarlo en otro entorno)")
    void rejectsWrongIssuer() {
        String token = Jwts.builder()
                .subject("1")
                .issuer("https://otro-servidor.example")
                .audience().add(AUDIENCE).and()
                .issuedAt(new Date())
                .expiration(Date.from(Instant.now().plusSeconds(300)))
                .signWith(testKey(), Jwts.SIG.HS512)
                .compact();

        assertThat(jwtService.parse(token)).isNull();
    }

    @Test
    @DisplayName("Rechaza un token con audiencia distinta")
    void rejectsWrongAudience() {
        String token = Jwts.builder()
                .subject("1")
                .issuer(ISSUER)
                .audience().add("otra-api").and()
                .issuedAt(new Date())
                .expiration(Date.from(Instant.now().plusSeconds(300)))
                .signWith(testKey(), Jwts.SIG.HS512)
                .compact();

        assertThat(jwtService.parse(token)).isNull();
    }

    @Test
    @DisplayName("Rechaza un token expirado")
    void rejectsExpiredToken() {
        String token = Jwts.builder()
                .subject("1")
                .issuer(ISSUER)
                .audience().add(AUDIENCE).and()
                .issuedAt(Date.from(Instant.now().minusSeconds(7200)))
                .expiration(Date.from(Instant.now().minusSeconds(3600)))
                .signWith(testKey(), Jwts.SIG.HS512)
                .compact();

        // clockSkew de 30 s no salva un token vencido hace una hora.
        assertThat(jwtService.parse(token)).isNull();
    }

    @Test
    @DisplayName("Rechaza un token sin firma (alg=none)")
    void rejectsUnsignedToken() {
        // Cabecera y payload en base64url, firma vacia: el ataque clasico.
        String header = base64Url("{\"alg\":\"none\",\"typ\":\"JWT\"}");
        String payload = base64Url("{\"sub\":\"1\",\"iss\":\"" + ISSUER
                + "\",\"aud\":[\"" + AUDIENCE + "\"],\"exp\":9999999999}");
        String unsigned = header + "." + payload + ".";

        assertThat(jwtService.parse(unsigned)).isNull();
    }

    @Test
    @DisplayName("Rechaza basura que no es un JWT")
    void rejectsGarbage() {
        assertThat(jwtService.parse("no-es-un-jwt")).isNull();
        assertThat(jwtService.parse("")).isNull();
        assertThat(jwtService.parse("a.b.c")).isNull();
    }

    @Test
    @DisplayName("Arranque falla si la clave es debil: nunca hay clave por defecto")
    void refusesWeakSecret() {
        assertThatThrownBy(() -> new JwtService(new AppProperties(
                new AppProperties.Jwt("corta", ISSUER, AUDIENCE, 900, 2592000, 30),
                properties.oauth2(), properties.ml(), properties.security(), properties.jobs())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("64");

        assertThatThrownBy(() -> new JwtService(new AppProperties(
                new AppProperties.Jwt("", ISSUER, AUDIENCE, 900, 2592000, 30),
                properties.oauth2(), properties.ml(), properties.security(), properties.jobs())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("JWT_SECRET");
    }

    @Test
    @DisplayName("Role.fromString normaliza sin depender del locale")
    void roleParsingIsLocaleIndependent() {
        assertThat(Role.fromString("admin")).isEqualTo(Role.ADMIN);
        assertThat(Role.fromString(" ADMIN ")).isEqualTo(Role.ADMIN);
        assertThat(Role.fromString("Viewer")).isEqualTo(Role.VIEWER);

        java.util.Locale previous = java.util.Locale.getDefault();
        try {
            // Locale tr-TR: "i".toUpperCase() produce "İ" y romperia la resolucion.
            java.util.Locale.setDefault(new java.util.Locale("tr", "TR"));
            assertThat(Role.fromString("admin")).isEqualTo(Role.ADMIN);
            assertThat(Role.fromString("viewer")).isEqualTo(Role.VIEWER);
        } finally {
            java.util.Locale.setDefault(previous);
        }
    }

    private SecretKey testKey() {
        return Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8));
    }

    private static String base64Url(String json) {
        return java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }
}