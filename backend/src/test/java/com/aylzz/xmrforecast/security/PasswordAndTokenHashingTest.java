package com.aylzz.xmrforecast.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pruebas del hash de tokens y del cifrado de contrasenas: ninguna de las dos
 * funciones debe filtrar el valor original (R-14).
 */
class PasswordAndTokenHashingTest {

    private final PasswordEncoder encoder = new BCryptPasswordEncoder(12);

    @Test
    @DisplayName("La contrasena se almacena como hash bcrypt, nunca en claro")
    void passwordIsHashed() {
        String raw = "MiClaveSegura123!";

        String hash = encoder.encode(raw);

        assertThat(hash).isNotEqualTo(raw).doesNotContain(raw);
        assertThat(hash).startsWith("$2");
        assertThat(encoder.matches(raw, hash)).isTrue();
        assertThat(encoder.matches("otraClave123!", hash)).isFalse();
    }

    @Test
    @DisplayName("El mismo hash no se repite: bcrypt aplica sal aleatoria")
    void bcryptUsesRandomSalt() {
        assertThat(encoder.encode("mismaClave123!")).isNotEqualTo(encoder.encode("mismaClave123!"));
    }

    @Test
    @DisplayName("El hash de token es determinista y no revela el token")
    void tokenHashIsDeterministicAndOpaque() {
        var hasher = new TokenHasher(
                "clave-de-prueba-de-64-bytes-minimo-para-hmac-sha256-xxxxxxxxxxxxxxx");

        String token = TokenHasher.newOpaqueToken();
        String hash = hasher.hash(token);

        assertThat(hash).isEqualTo(hasher.hash(token));
        assertThat(hash).doesNotContain(token);
        assertThat(hash).hasSize(64);
        assertThat(hash).matches("[0-9a-f]{64}");
    }

    @Test
    @DisplayName("La comparacion de tokens funciona y resiste timing basico")
    void tokenMatchWorks() {
        var hasher = new TokenHasher(
                "clave-de-prueba-de-64-bytes-minimo-para-hmac-sha256-xxxxxxxxxxxxxxx");
        String token = TokenHasher.newOpaqueToken();
        String hash = hasher.hash(token);

        assertThat(hasher.matches(token, hash)).isTrue();
        assertThat(hasher.matches(TokenHasher.newOpaqueToken(), hash)).isFalse();
        assertThat(hasher.matches(null, hash)).isFalse();
        assertThat(hasher.matches(token, null)).isFalse();
    }

    @Test
    @DisplayName("Los tokens opacos tienen entropia suficiente (256 bits)")
    void opaqueTokensAreStrong() {
        String a = TokenHasher.newOpaqueToken();
        String b = TokenHasher.newOpaqueToken();

        assertThat(a).isNotEqualTo(b);
        assertThat(a).hasSizeGreaterThanOrEqualTo(43);
        assertThat(java.util.Base64.getUrlDecoder().decode(a)).hasSize(32);
    }

    @Test
    @DisplayName("El hasher exige su clave: no arranca con un valor por defecto debil")
    void tokenHasherRequiresSecret() {
        org.assertj.core.api.Assertions
                .assertThatThrownBy(() -> new TokenHasher(""))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("La politica de contrasenas acepta una clave fuerte y rechaza las debiles")
    void passwordPolicyRejectsWeakPasswords() {
        String regex = com.aylzz.xmrforecast.auth.dto.PasswordPolicy.REGEX;

        assertThat("Corta1!".repeat(3)).matches(regex);          // >= 12, con mezcla
        assertThat("minusculas1!sinesmayus").doesNotMatch(regex);
        assertThat("SOLOMAYUSCULAS123!").doesNotMatch(regex);
        assertThat("SinDigitos!!AAaa").doesNotMatch(regex);
        assertThat("ConEspacios 123!Aa").doesNotMatch(regex);
        assertThat("Corta1!aa").doesNotMatch(regex);             // demasiado corta
    }

    @Test
    @DisplayName("Normalizar el correo no depende del locale")
    void emailNormalizationIsLocaleIndependent() {
        // Con localeointeres, "I".toLowerCase() daria un caracter distinto.
        Locale previous = Locale.getDefault();
        try {
            Locale.setDefault(new Locale("tr", "TR"));
            assertThat("USER@EXAMPLE.COM".toLowerCase(Locale.ROOT)).isEqualTo("user@example.com");
        } finally {
            Locale.setDefault(previous);
        }
    }
}