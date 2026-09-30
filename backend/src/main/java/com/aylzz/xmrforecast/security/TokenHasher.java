package com.aylzz.xmrforecast.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.HexFormat;

/**
 * Derivacion de hash para tokens opacos (refresh, reset de contrasena, confirmacion).
 *
 * <p>Usa HMAC-SHA256 con una clave de servidor, de modo que un dump de la base de
 * datos no permita reconstruir los tokens. Nunca se persiste el token en claro
 * (seccion 9: "no guardar tokens sin hash cuando sea posible").
 */
@Component
public class TokenHasher {

    private static final String ALGORITHM = "HmacSHA256";

    private final byte[] key;

    public TokenHasher(@Value("${app.jwt.secret}") String secret) {
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException(
                    "La clave de hash de tokens depende de app.jwt.secret; no se puede arrancar sin ella.");
        }
        this.key = secret.getBytes(StandardCharsets.UTF_8);
    }

    /** Hash determinista y con sal implicita (la clave del servidor). */
    public String hash(String token) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(key, ALGORITHM));
            return HexFormat.of().formatHex(mac.doFinal(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException | java.security.InvalidKeyException ex) {
            throw new IllegalStateException("No se pudo calcular el hash del token", ex);
        }
    }

    /** Comparacion en tiempo constante para evitar temporizacion attacks. */
    public boolean matches(String token, String expectedHash) {
        if (token == null || expectedHash == null) {
            return false;
        }
        return MessageDigest.isEqual(
                hash(token).getBytes(StandardCharsets.UTF_8),
                expectedHash.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Genera un token opaco de alta entropia. 32 bytes = 256 bits, suficiente
     * contra fuerza bruta (OWASP).
     */
    public static String newOpaqueToken() {
        byte[] bytes = new byte[32];
        new java.security.SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}