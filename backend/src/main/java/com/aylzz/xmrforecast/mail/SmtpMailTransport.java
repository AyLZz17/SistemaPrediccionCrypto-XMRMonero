package com.aylzz.xmrforecast.mail;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import org.springframework.mail.MailException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

/**
 * Envio por SMTP. Es el canal de desarrollo local y de cualquier entorno cuyo
 * proveedor no bloquee los puertos de correo.
 *
 * <p>Los timeouts de conexion y de escritura se configuran en
 * {@code spring.mail.properties.mail.smtp.*} (application.yml): sin ellos, un
 * servidor que no responde deja la peticion colgada hasta el timeout del SO
 * (mas de dos minutos), que es exactamente el sintoma que escondia el bug de
 * registro en Render.
 */
@Component
public class SmtpMailTransport implements MailTransport {

    private final JavaMailSender sender;

    public SmtpMailTransport(JavaMailSender sender) {
        this.sender = sender;
    }

    @Override
    public String name() {
        return "smtp";
    }

    @Override
    public void send(String from, String to, String subject, String body) throws MailTransportException {
        try {
            MimeMessage message = sender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, false, StandardCharsets.UTF_8.name());
            helper.setFrom(from);
            helper.setTo(to);
            helper.setSubject(subject);
            helper.setText(body, false);
            sender.send(message);
        } catch (MessagingException | MailException ex) {
            // ex.getMessage() puede contener la respuesta del servidor, nunca el
            // cuerpo del mensaje: el cuerpo no se incluye aqui a proposito (R-14).
            throw new MailTransportException("SMTP no pudo entregar el mensaje: " + ex.getMessage(), ex);
        }
    }
}
