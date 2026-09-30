package com.aylzz.xmrforecast.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.util.List;

/**
 * Configuracion tipada de la aplicacion. Todo secreto llega del entorno (R-14);
 * ninguna propiedad tiene un valor por defecto utilizable.
 */
@Validated
@ConfigurationProperties(prefix = "app")
public record AppProperties(
        @NotNull @Valid Jwt jwt,
        @NotNull @Valid OAuth2 oauth2,
        @NotNull @Valid Ml ml,
        @NotNull @Valid Security security,
        @NotNull @Valid Jobs jobs
) {

    public record Jwt(
            @NotBlank String secret,
            @NotBlank String issuer,
            @NotBlank String audience,
            @Positive long accessTokenTtlSeconds,
            @Positive long refreshTokenTtlSeconds,
            long clockSkewSeconds
    ) {}

    public record OAuth2(
            @NotNull @Valid Google google
    ) {
        public record Google(
                String clientId,
                String clientSecret,
                String redirectUri,
                String frontendCallback,
                List<String> allowedIssuers,
                String expectedAudience
        ) {
            /** OAuth Google no esta configurado si falta client id o secret. */
            public boolean isConfigured() {
                return clientId != null && !clientId.isBlank()
                        && clientSecret != null && !clientSecret.isBlank();
            }
        }
    }

    /**
     * Cliente del servicio ML.
     *
     * <p>No hay ninguna propiedad para desactivar la verificacion TLS. Existia
     * {@code verifyTls} y no hacia nada: el cliente HTTP usaba el almacen de
     * confianza por defecto, con lo que la propiedad era decorativa y su
     * documentacion afirmaba una garantia inexistente. Una bandera para no
     * verificar certificados acaba activada en algun entorno por descuido
     * (R-33), asi que se elimino en lugar de implementarla. Para el certificado de
     * desarrollo se anade la CA al truststore del proceso
     * ({@code -Djavax.net.ssl.trustStore}).
     */
    public record Ml(
            @NotBlank String baseUrl,
            @Positive int connectTimeoutMs,
            @Positive int readTimeoutMs,
            @Positive int maxRetries
    ) {}

    public record Security(
            boolean requireHttps,
            @Positive long hstsMaxAgeSeconds,
            List<String> allowedOrigins,
            @NotNull @Valid RateLimit rateLimit,
            @NotNull @Valid Account account
    ) {
        public record RateLimit(
                boolean enabled,
                @Positive int loginAttemptsPerMinute,
                @Positive int apiRequestsPerMinute
        ) {}

        public record Account(
                @Positive int maxFailedLogins,
                @Positive int lockDurationMinutes
        ) {}
    }

    public record Jobs(
            @Positive int maxAttempts,
            @Positive long heartbeatTimeoutSeconds
    ) {}
}