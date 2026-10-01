package com.aylzz.xmrforecast.mail;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;

/**
 * Compone y entrega los correos de cuenta (verificacion y recuperacion).
 *
 * <p><strong>El correo nunca decide si una operacion de negocio sobrevive.</strong>
 * Antes este servicio lanzaba {@code ApiException} y se le llamaba dentro de la
 * transaccion de registro: un fallo de SMTP marcaba la transaccion rollback-only
 * y el alta de usuario se perdia con un 503. Hoy el envio se hace despues del
 * commit (ver {@code AuthService}) y aqui solo se devuelve {@code false}, para
 * que quien llama pueda reflejar el resultado sin romper nada.
 *
 * <p>El canal se elige con {@code MAIL_TRANSPORT} ({@code smtp} por defecto,
 * {@code gmail} en Render). Con {@code gmail} el remitente y el cliente OAuth se
 * reutilizan de {@code MAIL_FROM} y {@code GOOGLE_CLIENT_ID}/{@code GOOGLE_CLIENT_SECRET};
 * solo hace falta añadir {@code GOOGLE_MAIL_REFRESH_TOKEN}.
 *
 * <p>Nunca registra tokens ni enlaces en los mensajes de log (R-14): el cuerpo
 * del correo no sale de {@link MailTransport}.
 */
@Service
public class MailService {

    private static final Logger log = LoggerFactory.getLogger(MailService.class);

    private final MailTransport transport;
    private final String requestedTransport;
    private final String from;
    private final String fromName;
    private final String frontendBaseUrl;

    /**
     * @param fromName nombre visible del remitente ({@code MAIL_FROM_NAME}).
     *        Vacio por defecto: un {@code From} sin nombre es valido en ambos
     *        canales y no inventa una identidad que el buzon no verifico.
     * @param frontendBaseUrl URL publica del servicio usada para construir los
     *        enlaces. {@code APP_PUBLIC_URL} es el nombre generico y manda si
     *        esta presente; si no, se usa {@code FRONTEND_BASE_URL}. Son la
     *        misma pieza de estado con dos nombres de entorno, no dos piezas.
     */
    public MailService(List<MailTransport> transports,
                       @Value("${MAIL_TRANSPORT:smtp}") String requestedTransport,
                       @Value("${MAIL_FROM:}") String from,
                       @Value("${MAIL_FROM_NAME:}") String fromName,
                       @Value("${APP_PUBLIC_URL:${FRONTEND_BASE_URL:}}") String frontendBaseUrl) {
        this.requestedTransport = requestedTransport == null || requestedTransport.isBlank()
                ? "smtp" : requestedTransport.trim().toLowerCase(Locale.ROOT);
        this.from = from == null ? "" : from.trim();
        this.fromName = fromName == null ? "" : fromName.trim();
        this.frontendBaseUrl = frontendBaseUrl == null
                ? "" : frontendBaseUrl.trim().replaceAll("/$", "");
        this.transport = pick(transports, this.requestedTransport);
    }

    /**
     * Remitente efectivo: {@code Nombre <correo>} o solo el correo.
     *
     * <p>El nombre se limpia de saltos de linea antes de entrar en la cabecera
     * {@code From} (inyeccion de cabeceras) y se codifica como palabra RFC 2047
     * cuando no es ASCII puro. Eso deja la cadena final siempre en ASCII, que
     * es la condicion bajo la cual {@code MimeUtility.encodeText} de la Gmail API
     * y {@code setFrom} de JavaMail la dejan pasar sin re-codificarla entera y
     * romper la direccion.
     */
    private String sender() {
        if (fromName.isEmpty() || from.contains("<")) {
            return from;
        }
        String encoded = encodeDisplayName(fromName);
        return encoded.isEmpty() ? from : encoded + " <" + from + ">";
    }

    private static String encodeDisplayName(String name) {
        String safe = name.replaceAll("[\\r\\n\"<>]", " ").trim();
        if (safe.isEmpty()) {
            return "";
        }
        boolean ascii = true;
        for (int i = 0; i < safe.length(); i++) {
            if (safe.charAt(i) > 127) {
                ascii = false;
                break;
            }
        }
        if (ascii) {
            return safe;
        }
        return "=?UTF-8?B?" + java.util.Base64.getEncoder()
                .encodeToString(safe.getBytes(java.nio.charset.StandardCharsets.UTF_8)) + "?=";
    }

    private static MailTransport pick(List<MailTransport> transports, String wanted) {
        if (transports == null) {
            return null;
        }
        return transports.stream()
                .filter(candidate -> candidate.name().equalsIgnoreCase(wanted))
                .findFirst()
                .orElse(null);
    }

    /** Canal efectivamente configurado, con remitente y destino web completos. */
    public boolean isConfigured() {
        return transport != null && !from.isBlank() && !frontendBaseUrl.isBlank();
    }

    /**
     * Envia el enlace de verificacion.
     *
     * @return {@code true} si el mensaje se entrego; {@code false} si no estaba
     *         configurado o el canal fallo. Nunca lanza.
     */
    public boolean sendVerificationEmail(String recipient, String name, String token) {
        return send(recipient, "Confirma tu cuenta en XMR-Forecast",
                "Hola " + safeName(name) + ",\n\n"
                        + "Confirma tu correo para activar tu cuenta:\n"
                        + frontendBaseUrl + "/verify-email?token=" + token + "\n\n"
                        + "Este enlace caduca en 48 horas.\n\n"
                        + "Si no creaste esta cuenta, ignora este mensaje.");
    }

    /**
     * Envia el enlace de recuperacion.
     *
     * @return {@code true} si el mensaje se entrego. Nunca lanza: una respuesta
     *         de error solo cuando el correo existe permitiria enumerar cuentas
     *         registradas.
     */
    public boolean sendPasswordResetEmail(String recipient, String name, String token) {
        return send(recipient, "Restablece tu contrasena en XMR-Forecast",
                "Hola " + safeName(name) + ",\n\n"
                        + "Puedes crear una contrasena nueva desde este enlace:\n"
                        + frontendBaseUrl + "/reset-password?token=" + token + "\n\n"
                        + "Si no solicitaste este cambio, ignora este mensaje.");
    }

    private boolean send(String recipient, String subject, String body) {
        if (transport == null) {
            log.warn("Correo no configurado: MAIL_TRANSPORT='{}' no corresponde a ningun canal "
                    + "(disponibles: smtp, gmail). No se envia mensaje a {}", requestedTransport, recipient);
            return false;
        }
        if (from.isBlank() || frontendBaseUrl.isBlank()) {
            log.warn("Correo no configurado (MAIL_FROM{} y APP_PUBLIC_URL/FRONTEND_BASE_URL{}); "
                    + "no se envia mensaje a {}",
                    from.isBlank() ? " vacio" : " presente",
                    frontendBaseUrl.isBlank() ? " vacio" : " presente",
                    recipient);
            return false;
        }
        try {
            transport.send(sender(), recipient, subject, body);
            return true;
        } catch (MailTransportException | RuntimeException ex) {
            log.error("No se pudo enviar el correo de cuenta a {} por el canal {}",
                    recipient, transport.name(), ex);
            return false;
        }
    }

    private static String safeName(String name) {
        return name == null || name.isBlank() ? "usuario" : name.trim();
    }
}
