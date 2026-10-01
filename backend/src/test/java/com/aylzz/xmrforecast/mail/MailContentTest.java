package com.aylzz.xmrforecast.mail;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Contrato de contenido de los correos de cuenta.
 *
 * <p><strong>Lo que este test fija.</strong> El cliente exige que el mensaje de
 * verificacion traiga: identificacion de la aplicacion, el motivo por el que
 * llego, el enlace HTTPS {@code /verificar-email?token=...}, la fecha de
 * expiracion, un aviso de seguridad y los enlaces a terminos y privacidad; y
 * que ninguno de los dos mensajes incluya la contrasena, un JWT ni secretos.
 * Nada de eso se puede comprobar despues: el cuerpo se compone aqui y sale por
 * la red, de modo que la verificacion tiene que ser una prueba sobre el texto
 * realmente enviado al canal.
 *
 * <p>Tambien fija que un {@code APP_PUBLIC_URL}/{@code FRONTEND_BASE_URL} sin
 * HTTPS no genera envio (R-33): un enlace {@code http://} en el correo dejaria
 * el token de recuperacion expuesto a cualquier intermediario.
 */
class MailContentTest {

    private static final String BASE = "https://xmr-forecast.example";
    private static final Instant EXPIRY = Instant.parse("2026-10-03T15:42:00Z");
    private static final String TOKEN = "p8Zt5wK-abc123XYZ";

    /** Canal que solo registra lo que recibe, sin tocar la red. */
    private static final class CapturingTransport implements MailTransport {
        private int sent;
        private String lastTo;
        private String lastSubject;
        private String lastBody;

        @Override
        public String name() {
            return "smtp";
        }

        @Override
        public void send(String from, String to, String subject, String body) {
            sent++;
            lastTo = to;
            lastSubject = subject;
            lastBody = body;
        }
    }

    private static MailService service(CapturingTransport transport, String baseUrl) {
        return new MailService(List.of(transport), "smtp", "no-reply@example.com",
                "XMR-Forecast", baseUrl);
    }

    private static MailService serviceWith(CapturingTransport transport) {
        return service(transport, BASE);
    }

    private static CapturingTransport transport() {
        return new CapturingTransport();
    }

    // ----------------------------------------------------- verificacion

    @Test
    @DisplayName("el correo de verificacion identifica la aplicacion, el motivo, el enlace y la caducidad")
    void verificacionCumpleElContratoDeContenido() {
        CapturingTransport t = transport();

        boolean sent = serviceWith(t).sendVerificationEmail("daniel@example.com",
                "Daniel Prueba", TOKEN, EXPIRY);

        assertThat(sent).isTrue();
        assertThat(t.lastTo).isEqualTo("daniel@example.com");
        assertThat(t.lastSubject).contains("XMR-Forecast");
        // Identificacion de la aplicacion.
        assertThat(t.lastBody).contains("XMR-Forecast");
        // Motivo por el que se recibio.
        assertThat(t.lastBody).contains("se creo una cuenta");
        // Enlace HTTPS con la ruta que el cliente exige.
        assertThat(t.lastBody).contains(BASE + "/verificar-email?token=" + TOKEN);
        assertThat(t.lastBody).doesNotContain("http://");
        // Fecha de expiracion real (UTC etiquetada) mas la duracion.
        assertThat(t.lastBody).contains("El enlace caduca el 3 de octubre de 2026, 15:42 UTC");
        assertThat(t.lastBody).contains("48 horas despues del envio");
        // Aviso de seguridad.
        assertThat(t.lastBody).contains("Aviso de seguridad");
        assertThat(t.lastBody).contains("nunca te pedira tu contrasena");
        // Enlaces legales.
        assertThat(t.lastBody).contains(BASE + "/terms");
        assertThat(t.lastBody).contains(BASE + "/privacy");
        assertThat(t.lastBody).contains(BASE + "/data-policy");
    }

    @Test
    @DisplayName("el correo de verificacion no trae contrasena, JWT ni secretos")
    void verificacionNoExponeSecretos() {
        CapturingTransport t = transport();

        serviceWith(t).sendVerificationEmail("daniel@example.com", "Daniel Prueba", TOKEN, EXPIRY);

        String body = t.lastBody;
        // Un JWT en Josefin base64url empieza siempre por "eyJ".
        assertThat(body).doesNotContain("eyJ");
        assertThat(body.toLowerCase(java.util.Locale.ROOT)).doesNotContain("jwt");
        assertThat(body).doesNotContain("Bearer");
        assertThat(body).doesNotContain("passwordHash");
        assertThat(body).doesNotContain("app.jwt");
        assertThat(body).doesNotContain("client_secret");
    }

    @Test
    @DisplayName("la caducidad se escribe en UTC y en espanol aunque el servidor este en ingles")
    void laCaducidadSeEscribeEnUtc() {
        CapturingTransport t = transport();

        serviceWith(t).sendVerificationEmail("daniel@example.com", "Daniel Prueba", TOKEN,
                Instant.parse("2026-12-31T23:05:00Z"));

        assertThat(t.lastBody).contains("31 de diciembre de 2026, 23:05 UTC");
    }

    @Test
    @DisplayName("la fecha que promete el correo es la que recibe el metodo, sin calcularla de nuevo")
    void laCaducidadNoSeRecalcula() {
        CapturingTransport t = transport();
        Instant otra = Instant.parse("2030-01-02T03:04:05Z");

        serviceWith(t).sendVerificationEmail("daniel@example.com", "Daniel Prueba", TOKEN, otra);

        assertThat(t.lastBody).contains("2 de enero de 2030, 03:04 UTC");
    }

    // ----------------------------------------------------- recuperacion

    @Test
    @DisplayName("el correo de recuperacion trae enlace HTTPS, caducidad, uso unico y el aviso correspondiente")
    void recuperacionCumpleElContratoDeContenido() {
        CapturingTransport t = transport();

        boolean sent = serviceWith(t).sendPasswordResetEmail("daniel@example.com",
                "Daniel Prueba", TOKEN, EXPIRY);

        assertThat(sent).isTrue();
        assertThat(t.lastSubject).contains("XMR-Forecast");
        assertThat(t.lastBody).contains("se solicito restablecer la contrasena");
        assertThat(t.lastBody).contains(BASE + "/reset-password?token=" + TOKEN);
        assertThat(t.lastBody).doesNotContain("http://");
        assertThat(t.lastBody).contains("El enlace caduca el 3 de octubre de 2026, 15:42 UTC");
        assertThat(t.lastBody).contains("2 horas despues del envio");
        assertThat(t.lastBody).contains("solo puede usarse una vez");
        assertThat(t.lastBody).contains("Aviso de seguridad");
        assertThat(t.lastBody).contains(BASE + "/terms");
        assertThat(t.lastBody).contains(BASE + "/privacy");
        assertThat(t.lastBody).contains(BASE + "/data-policy");
    }

    @Test
    @DisplayName("si el usuario no pidio el cambio, el correo recomienda cambiar la contrasena")
    void recuperacionRecomiendaCambiarSiNoSolicito() {
        CapturingTransport t = transport();

        serviceWith(t).sendPasswordResetEmail("daniel@example.com", "Daniel Prueba", TOKEN, EXPIRY);

        assertThat(t.lastBody).contains("Si NO solicitaste este proceso");
        assertThat(t.lastBody).contains("cambiar tu contrasena cuanto antes");
    }

    @Test
    @DisplayName("el correo de recuperacion no trae la contrasena ni secretos del servidor")
    void recuperacionNoExponeSecretos() {
        CapturingTransport t = transport();

        serviceWith(t).sendPasswordResetEmail("daniel@example.com", "Daniel Prueba", TOKEN, EXPIRY);

        String body = t.lastBody;
        assertThat(body).doesNotContain("eyJ");
        assertThat(body).doesNotContain("Bearer");
        assertThat(body).doesNotContain("passwordHash");
        assertThat(body).doesNotContain("app.jwt");
        assertThat(body).doesNotContain("client_secret");
    }

    // ----------------------------------------------------- transporte

    @Test
    @DisplayName("sin HTTPS en la base publica no se envia ningun mensaje")
    void sinHttpsNoSeEnvia() {
        CapturingTransport t = transport();

        boolean sent = service(t, "http://xmr-forecast.example")
                .sendVerificationEmail("daniel@example.com", "Daniel Prueba", TOKEN, EXPIRY);

        assertThat(sent).isFalse();
        assertThat(t.sent).isZero();
        assertThat(service(t, "http://xmr-forecast.example").isConfigured()).isFalse();
    }

    @Test
    @DisplayName("con HTTPS la base publica esta configurada")
    void conHttpsEstaConfigurado() {
        assertThat(serviceWith(transport()).isConfigured()).isTrue();
    }

    @Test
    @DisplayName("una base vacia o un canal inexistente devuelven false sin lanzar")
    void baseVaciaOCanalDesconocidoNoLanzan() {
        CapturingTransport t = transport();

        assertThat(service(t, "").sendPasswordResetEmail("daniel@example.com",
                "Daniel Prueba", TOKEN, EXPIRY)).isFalse();

        MailService otro = new MailService(List.of(transport()), "carrier-pigeon",
                "no-reply@example.com", "XMR-Forecast", BASE);
        assertThat(otro.sendVerificationEmail("daniel@example.com", "Daniel Prueba",
                TOKEN, EXPIRY)).isFalse();
        assertThat(otro.isConfigured()).isFalse();
        assertThat(t.sent).isZero();
    }

    @Test
    @DisplayName("un fallo del canal se traduce en false, nunca en excepcion")
    void falloDelCanalNoLanza() {
        MailTransport caido = new MailTransport() {
            @Override
            public String name() {
                return "smtp";
            }

            @Override
            public void send(String from, String to, String subject, String body)
                    throws MailTransportException {
                throw new MailTransportException("caido", null);
            }
        };
        MailService service = new MailService(List.of(caido), "smtp", "no-reply@example.com",
                "XMR-Forecast", BASE);

        assertThat(service.sendVerificationEmail("daniel@example.com", "Daniel Prueba",
                TOKEN, EXPIRY)).isFalse();
    }
}
