package com.aylzz.xmrforecast.mail;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.mail.internet.MimeUtility;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;

/**
 * Envio de correo a traves de la Gmail API de Google, por HTTPS.
 *
 * <p><strong>Por que existe.</strong> Render bloquea el trafico saliente a los
 * puertos SMTP 25, 465 y 587 en los servicios gratuitos, de modo que
 * {@code smtp.gmail.com:587} responde con {@code Connection timed out} y todo
 * registro de usuario terminaba en un 503. La Gmail API se sirve en el puerto
 * 443, que no esta bloqueado, y el mensaje lo firma el propio Google, asi que
 * el correo llega con SPF/DKIM/DMARC alineados.
 *
 * <p><strong>Autenticacion.</strong> Flujo de refresh token: la aplicacion
 * canjea {@code GOOGLE_MAIL_REFRESH_TOKEN} por un access token de vida corta
 * ({@code expires_in}, normalmente 3600 s) y lo reutiliza hasta cinco minutos
 * antes de que venza. El refresh token se obtiene una sola vez (p. ej. con el
 * OAuth Playground) y se guarda como secreto de entorno (R-14).
 *
 * <p><strong>Limite conocido.</strong> Si la pantalla de consentimiento de
 * Google esta en modo <em>Testing</em>, los refresh tokens caducan a los 7 dias
 * y Google responde {@code invalid_grant}; este transporte traduce ese codigo
 * a un mensaje accionable en los logs en lugar de dejarlo como un fallo generico.
 */
@Component
public class GmailApiTransport implements MailTransport {

    private static final String TOKEN_ENDPOINT = "https://oauth2.googleapis.com/token";
    private static final String SEND_ENDPOINT = "https://gmail.googleapis.com/gmail/v1/users/me/messages/send";

    /** Renovar con margen: el access token dura una hora. */
    private static final Duration TOKEN_REFRESH_MARGIN = Duration.ofMinutes(5);

    private final RestClient client;
    private final String clientId;
    private final String clientSecret;
    private final String refreshToken;

    private final Object tokenLock = new Object();
    private volatile String accessToken;
    private volatile Instant accessTokenExpiresAt = Instant.EPOCH;

    public GmailApiTransport(
            @Value("${GOOGLE_MAIL_CLIENT_ID:${GOOGLE_CLIENT_ID:}}") String clientId,
            @Value("${GOOGLE_MAIL_CLIENT_SECRET:${GOOGLE_CLIENT_SECRET:}}") String clientSecret,
            @Value("${GOOGLE_MAIL_REFRESH_TOKEN:}") String refreshToken) {
        this.clientId = clientId == null ? "" : clientId.trim();
        this.clientSecret = clientSecret == null ? "" : clientSecret.trim();
        this.refreshToken = refreshToken == null ? "" : refreshToken.trim();

        // Tiempos propios y cortos: el envio ocurre dentro de la peticion de
        // registro y el cliente aborta a los 20 s. Con el cliente compartido
        // (lectura de 60 s) un fallo de Google convertia el alta en un timeout.
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(3));
        factory.setReadTimeout(Duration.ofSeconds(8));
        this.client = RestClient.builder().requestFactory(factory).build();
    }

    @Override
    public String name() {
        return "gmail";
    }

    @Override
    public void send(String from, String to, String subject, String body) throws MailTransportException {
        if (refreshToken.isEmpty()) {
            throw new MailTransportException(
                    "El canal gmail esta seleccionado (MAIL_TRANSPORT=gmail) pero falta "
                            + "GOOGLE_MAIL_REFRESH_TOKEN en el entorno.");
        }
        if (from == null || from.isBlank()) {
            throw new MailTransportException("Falta MAIL_FROM: la Gmail API necesita un remitente.");
        }

        String token = accessToken();
        String raw = mimeMessage(from, to, subject, body);
        try {
            client.post()
                    .uri(SEND_ENDPOINT)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("raw", raw))
                    .retrieve()
                    .body(String.class);
        } catch (RestClientException ex) {
            // Un 401 suele ser un access token caducado: se invalida para que la
            // proxima llamada lo renueve en lugar de reutilizarlo.
            invalidateAccessToken();
            throw new MailTransportException("Gmail API rechazo el envio: " + describe(ex), ex);
        }
    }

    // ------------------------------------------------------------ access token

    private String accessToken() throws MailTransportException {
        String cached = accessToken;
        if (cached != null && Instant.now().isBefore(accessTokenExpiresAt.minus(TOKEN_REFRESH_MARGIN))) {
            return cached;
        }
        synchronized (tokenLock) {
            cached = accessToken;
            if (cached != null && Instant.now().isBefore(accessTokenExpiresAt.minus(TOKEN_REFRESH_MARGIN))) {
                return cached;
            }
            try {
                TokenResponse response = client.post()
                        .uri(TOKEN_ENDPOINT)
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .body(tokenRequest())
                        .retrieve()
                        .body(TokenResponse.class);
                if (response == null || response.accessToken() == null || response.accessToken().isBlank()) {
                    throw new MailTransportException("Google devolvio un access token vacio.");
                }
                long ttl = response.expiresIn() > 0 ? response.expiresIn() : 3600L;
                accessToken = response.accessToken();
                accessTokenExpiresAt = Instant.now().plus(Duration.ofSeconds(ttl));
                return response.accessToken();
            } catch (RestClientException ex) {
                invalidateAccessToken();
                String detail = describe(ex);
                if (detail.contains("invalid_grant")) {
                    throw new MailTransportException(
                            "Google rechazo GOOGLE_MAIL_REFRESH_TOKEN (invalid_grant): caduco o fue revocado. "
                                    + "Con la pantalla de consentimiento en modo Testing caduca cada 7 dias; "
                                    + "genera uno nuevo con el OAuth Playground y actualiza la variable.",
                            ex);
                }
                throw new MailTransportException("No se pudo obtener el access token de Gmail: " + detail, ex);
            }
        }
    }

    private MultiValueMap<String, String> tokenRequest() {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "refresh_token");
        form.add("client_id", clientId);
        form.add("client_secret", clientSecret);
        form.add("refresh_token", refreshToken);
        return form;
    }

    private void invalidateAccessToken() {
        synchronized (tokenLock) {
            accessToken = null;
            accessTokenExpiresAt = Instant.EPOCH;
        }
    }

    // ------------------------------------------------------------------ MIME

    /**
     * Construye el mensaje RFC 5322 y lo codifica en base64url, que es el formato
     * que exige {@code users.messages.send}.
     *
     * <p>Los asuntos del proyecto son ASCII, pero se pasan por
     * {@link MimeUtility#encodeText} para que un acento futuro no rompa la cabecera.
     */
    private static String mimeMessage(String from, String to, String subject, String body) throws MailTransportException {
        try {
            String message = "From: " + MimeUtility.encodeText(from, "UTF-8", null) + "\r\n"
                    + "To: " + MimeUtility.encodeText(to, "UTF-8", null) + "\r\n"
                    + "Subject: " + MimeUtility.encodeText(subject, "UTF-8", null) + "\r\n"
                    + "MIME-Version: 1.0\r\n"
                    + "Content-Type: text/plain; charset=\"UTF-8\"\r\n"
                    + "\r\n"
                    + body;
            return Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(message.getBytes(StandardCharsets.UTF_8));
        } catch (Exception ex) {
            throw new MailTransportException("No se pudo construir el mensaje MIME: " + ex.getMessage(), ex);
        }
    }

    /**
     * Describe el fallo sin volcar secretos. Solo se toma el mensaje de la
     * excepcion (que contiene el estado y la respuesta de Google, no nuestras
     * credenciales) y se acota para que un error verboso no llene el log.
     */
    private static String describe(RestClientException ex) {
        String message = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
        String compact = message.replaceAll("\\s+", " ").trim();
        return compact.length() <= 300 ? compact : compact.substring(0, 300) + "...";
    }

    /** Respuesta de {@code oauth2.googleapis.com/token}. */
    record TokenResponse(
            @JsonProperty("access_token") String accessToken,
            @JsonProperty("expires_in") long expiresIn) {
    }
}
