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

    /**
     * Longitud minima de la clave, en bytes. Se exige la misma que para firmar
     * los JWT (HS512 necesita 512 bits) porque la clave de una y otra es la misma.
     */
    private static final int MIN_KEY_BYTES = 64;

    /**
     * Un unico generador para todo el proceso.
     *
     * <p>{@code new SecureRandom()} en cada token es una peticion al sistema
     * criptografico por cada refresh, reset y verificacion. En Linux, JDK 17+ usa
     * por defecto DRBG con reseado manual, y crear una instancia por llamada deja
     * el generador en un estado peor del que garantiza su documentacion.
     */
    private static final java.security.SecureRandom RANDOM = new java.security.SecureRandom();

    private final byte[] key;

    public TokenHasher(@Value("${app.jwt.secret}") String secret) {
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException(
                    "La clave de hash de tokens depende de app.jwt.secret; no se puede arrancar sin ella.");
        }
        byte[] raw = secret.getBytes(StandardCharsets.UTF_8);
        if (raw.length < MIN_KEY_BYTES) {
            throw new IllegalStateException(
                    "app.jwt.secret es demasiado corta (" + raw.length + " bytes); se requieren al menos "
                            + MIN_KEY_BYTES + " bytes.");
        }
        this.key = raw;
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

    /**
     * Comparacion en tiempo constante para evitar temporizacion attacks.
     *
     * <p>Con {@link MessageDigest#isEqual} la comparacion es constante en el tiempo
     * y ademas independiente de la longitud, que es justamente lo que se quiere.
     */
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
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}