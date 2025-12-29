package com.analyseloto.loto.service;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import lombok.RequiredArgsConstructor;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class EmailService {
    private final JavaMailSender mailSender;

    /**
     * Envoi email (format HTML)
     * @param to destinataire
     * @param subject objet
     * @param htmlBody contenu
     */
    public void sendHtmlEmail(String to, String subject, String htmlBody) {
        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");

            helper.setFrom("Loto Master AI <no-reply@lotomaster.com>");
            helper.setTo(to);
            helper.setSubject(subject);
            helper.setText(htmlBody, true); // true = HTML

            mailSender.send(message);
        } catch (MessagingException e) {
            e.printStackTrace();
            throw new RuntimeException("Erreur envoi mail");
        }
    }

    /**
     * Envoi email confirmation après inscription utilisateur
     * @param to destinataire
     * @param name nom user
     * @param link lien
     */
    public void sendConfirmationEmail(String to, String name, String link) {
        String subject = "Validez votre compte Loto Master AI";
        String htmlBody = "<html><body>"
                + "<h3>Bonjour " + name + ",</h3>"
                + "<p>Merci de vous être inscrit. Veuillez cliquer sur le lien ci-dessous pour activer votre compte :</p>"
                + "<p><a href=\"" + link + "\">ACTIVER MON COMPTE</a></p>"
                + "<p>Ce lien expirera dans 24 heures.</p>"
                + "</body></html>";

        sendHtmlEmail(to, subject, htmlBody);
    }
}
