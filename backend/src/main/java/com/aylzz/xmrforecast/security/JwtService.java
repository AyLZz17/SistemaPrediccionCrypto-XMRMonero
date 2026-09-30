package com.aylzz.xmrforecast.security;

import com.aylzz.xmrforecast.config.AppProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.UUID;

/**
 * Emision y validacion de los JWT propios de la aplicacion, despues de un login
 * tradicional o de un OAuth 2.0 con Google (seccion 4).
 *
 * <p>Claims obligatorios: {@code sub}, {@code iss}, {@code aud}, {@code iat},
 * {@code exp}, {@code jti} y los roles. La validacion es estricta del algoritmo:
 * se fija HS512 y se rechazan {@code alg=none} y el cambio de clave sin rotar.
 */
@Service
public class JwtService {

    private static final Logger log = LoggerFactory.getLogger(JwtService.class);

    /** HS512 necesita >= 512 bits. Toda clave mas corta se rechaza al arrancar. */
    private static final int MIN_KEY_BYTES = 64;

    private final AppProperties properties;
    private final SecretKey key;

    public JwtService(AppProperties properties) {
        this.properties = properties;
        this.key = buildKey(properties.jwt().secret());
    }

    private static SecretKey buildKey(String secret) {
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException(
                    "JWT_SECRET no esta definido. Defina una clave de al menos "
                            + MIN_KEY_BYTES + " bytes en el entorno (R-14).");
        }
        byte[] raw = secret.getBytes(StandardCharsets.UTF_8);
        if (raw.length < MIN_KEY_BYTES) {
            throw new IllegalStateException(
                    "JWT_SECRET es demasiado corta (" + raw.length + " bytes); se requieren al menos "
                            + MIN_KEY_BYTES + " bytes para HS512. Generela con: openssl rand -base64 64");
        }
        return Keys.hmacShaKeyFor(raw);
    }

    /** Emite un access token de corta duracion con su {@code jti}. */
    public IssuedToken issueAccessToken(Long userId, String email, List<Role> roles) {
        Instant now = Instant.now();
        Instant expiry = now.plusSeconds(properties.jwt().accessTokenTtlSeconds());
        String jti = UUID.randomUUID().toString();

        String token = Jwts.builder()
                .id(jti)
                .subject(String.valueOf(userId))
                .issuer(properties.jwt().issuer())
                .audience().add(properties.jwt().audience()).and()
                .claim("email", email)
                .claim(Role.CLAIM, roles.stream().map(Enum::name).toList())
                .claim("typ", "access")
                .issuedAt(Date.from(now))
                .expiration(Date.from(expiry))
                .signWith(key, Jwts.SIG.HS512)
                .compact();

        return new IssuedToken(token, jti, expiry);
    }

    /** Valida firma, algoritmo, iss, aud y expiracion. Devuelve null si no es valido. */
    public Claims parse(String token) {
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(key)
                    .requireIssuer(properties.jwt().issuer())
                    .requireAudience(properties.jwt().audience())
                    .clockSkewSeconds(properties.jwt().clockSkewSeconds())
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();

            // Un refresh token no debe poder usarse como access token (confusion de tipo).
            Object typ = claims.get("typ");
            if (typ != null && !"access".equals(typ)) {
                return null;
            }
            return claims;
        } catch (ExpiredJwtException ex) {
            log.debug("JWT expirado");
            return null;
        } catch (JwtException | IllegalArgumentException ex) {
            // Sin detalle de la excepcion: podria filtrar informacion del token.
            log.debug("JWT rechazado");
            return null;
        }
    }

    public String newJti() {
        return UUID.randomUUID().toString();
    }

    public long accessTokenTtlSeconds() {
        return properties.jwt().accessTokenTtlSeconds();
    }

    public long refreshTokenTtlSeconds() {
        return properties.jwt().refreshTokenTtlSeconds();
    }

    public String issuer() {
        return properties.jwt().issuer();
    }

    public String audience() {
        return properties.jwt().audience();
    }

    public record IssuedToken(String token, String jti, Instant expiresAt) {}
}