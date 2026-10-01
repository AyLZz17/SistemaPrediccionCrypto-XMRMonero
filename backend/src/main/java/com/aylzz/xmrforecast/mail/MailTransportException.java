package com.aylzz.xmrforecast.mail;

/**
 * Fallo del canal fisico de correo (SMTP o Gmail API).
 *
 * <p><strong>Nunca lleva el cuerpo del mensaje.</strong> El cuerpo contiene el
 * enlace opaco de verificacion o de recuperacion, y R-14 prohibie registrar
 * tokens en claro: la excepcion solo describe que fallo y por que, no que se
 * intentaba enviar.
 */
public class MailTransportException extends Exception {

    public MailTransportException(String message) {
        super(message);
    }

    public MailTransportException(String message, Throwable cause) {
        super(message, cause);
    }
}
