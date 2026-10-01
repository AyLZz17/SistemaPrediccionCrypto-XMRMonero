package com.aylzz.xmrforecast.mail;

/**
 * Canal fisico por el que se entrega un mensaje de correo.
 *
 * <p>Existe porque Render bloquea el trafico saliente a los puertos SMTP 25,
 * 465 y 587 en los servicios gratuitos (cambio de plataforma vigente desde el
 * 26 de septiembre de 2025), de modo que en produccion el correo sale por la
 * Gmail API (HTTPS) mientras que en desarrollo sigue saliendo por SMTP contra
 * un servidor local. {@link MailService} elige el canal con {@code MAIL_TRANSPORT}
 * y el resto del codigo no sabe cual esta en uso.
 *
 * <p>Las dos implementaciones son stateless respecto al mensaje: reciben todo lo
 * que necesitan como parametros y no guardan nada del contenido.
 */
public interface MailTransport {

    /** Identificador corto del canal ({@code smtp}, {@code gmail}). */
    String name();

    /**
     * Entrega el mensaje.
     *
     * @param from    remitente en formato RFC 5322
     * @param to      destinatario
     * @param subject asunto (ASCII; ver {@link MailService})
     * @param body    cuerpo en texto plano UTF-8
     * @throws MailTransportException si el canal no pudo entregar el mensaje
     */
    void send(String from, String to, String subject, String body) throws MailTransportException;
}
