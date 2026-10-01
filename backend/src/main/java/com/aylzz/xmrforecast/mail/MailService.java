package com.aylzz.xmrforecast.mail;

import com.aylzz.xmrforecast.common.ApiException;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.MailException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;

/** Envia los enlaces de cuenta por SMTP; nunca registra tokens ni enlaces. */
@Service
public class MailService {

    private static final Logger log = LoggerFactory.getLogger(MailService.class);

    private final JavaMailSender sender;
    private final String from;
    private final String frontendBaseUrl;

    public MailService(JavaMailSender sender,
                       @Value("${MAIL_FROM:}") String from,
                       @Value("${FRONTEND_BASE_URL:}") String frontendBaseUrl) {
        this.sender = sender;
        this.from = from == null ? "" : from.trim();
        this.frontendBaseUrl = frontendBaseUrl == null
                ? "" : frontendBaseUrl.trim().replaceAll("/$", "");
    }

    public void sendVerificationEmail(String recipient, String name, String token) {
        send(recipient, "Confirma tu cuenta en XMR-Forecast",
                "Hola " + safeName(name) + ",\n\n"
                        + "Confirma tu correo para activar tu cuenta:\n"
                        + frontendBaseUrl + "/verify-email?token=" + token + "\n\n"
                        + "Este enlace caduca en 48 horas.\n\n"
                        + "Si no creaste esta cuenta, ignora este mensaje.");
    }

    public void sendPasswordResetEmail(String recipient, String name, String token) {
        send(recipient, "Restablece tu contrasena en XMR-Forecast",
                "Hola " + safeName(name) + ",\n\n"
                        + "Puedes crear una contrasena nueva desde este enlace:\n"
                        + frontendBaseUrl + "/reset-password?token=" + token + "\n\n"
                        + "Si no solicitaste este cambio, ignora este mensaje.");
    }

    private void send(String recipient, String subject, String body) {
        if (from.isBlank() || frontendBaseUrl.isBlank()) {
            log.warn("Correo no configurado; no se envia mensaje a {}", recipient);
            return;
        }
        try {
            MimeMessage message = sender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, false, StandardCharsets.UTF_8.name());
            helper.setFrom(from);
            helper.setTo(recipient);
            helper.setSubject(subject);
            helper.setText(body, false);
            sender.send(message);
        } catch (MessagingException | MailException ex) {
            log.error("No se pudo enviar el correo de cuenta a {}", recipient, ex);
                throw ApiException.unavailable("EMAIL_DELIVERY_FAILED",
                    "No se pudo enviar el correo. Intentalo de nuevo mas tarde.");
        }
    }

    private static String safeName(String name) {
        return name == null || name.isBlank() ? "usuario" : name.trim();
    }
}