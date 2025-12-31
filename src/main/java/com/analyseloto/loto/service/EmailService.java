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
        String subject = "🚀 Activez votre compte Loto Master AI";

        // On utilise StringBuilder pour construire le HTML proprement
        StringBuilder html = new StringBuilder();

        // --- DEBUT DU TEMPLATE ---
        html.append("<!DOCTYPE html>");
        html.append("<html><head>");
        html.append("<meta charset='UTF-8'>");
        html.append("<meta name='viewport' content='width=device-width, initial-scale=1.0'>");
        html.append("</head>");
        html.append("<body style='margin: 0; padding: 0; font-family: \"Segoe UI\", Helvetica, Arial, sans-serif; background-color: #f3f4f6; color: #333333;'>");

        // Conteneur Principal (Fond gris)
        html.append("<div style='width: 100%; padding: 40px 0; background-color: #f3f4f6;'>");

        // Carte Blanche Centrée
        html.append("<div style='max-width: 600px; margin: 0 auto; background-color: #ffffff; border-radius: 8px; overflow: hidden; box-shadow: 0 4px 6px rgba(0,0,0,0.1);'>");

        // En-tête (Header) avec couleur de marque
        html.append("<div style='background-color: #4F46E5; padding: 30px; text-align: center;'>");
        html.append("<h1 style='color: #ffffff; margin: 0; font-size: 24px; letter-spacing: 1px;'>🎱 Loto Master AI</h1>");
        html.append("</div>");

        // Corps du message
        html.append("<div style='padding: 40px 30px;'>");

        // Salutations
        html.append("<h2 style='color: #1f2937; margin-top: 0; font-size: 20px;'>Bonjour ").append(name).append(" ! 👋</h2>");
        html.append("<p style='font-size: 16px; line-height: 1.6; color: #4b5563; margin-bottom: 25px;'>");
        html.append("Merci de rejoindre la communauté. Votre assistant intelligent d'analyse de Loto est prêt.");
        html.append("<br>Pour commencer, veuillez confirmer votre adresse email en cliquant sur le bouton ci-dessous :");
        html.append("</p>");

        // Bouton d'action (CTA)
        html.append("<div style='text-align: center; margin: 35px 0;'>");
        html.append("<a href='").append(link).append("' style='background-color: #4F46E5; color: #ffffff; padding: 14px 30px; text-decoration: none; border-radius: 50px; font-weight: bold; font-size: 16px; display: inline-block; box-shadow: 0 4px 6px rgba(79, 70, 229, 0.3);'>");
        html.append("ACTIVER MON COMPTE");
        html.append("</a>");
        html.append("</div>");

        // Infos expiration
        html.append("<p style='font-size: 14px; color: #6b7280; text-align: center; margin-top: 20px;'>");
        html.append("⏳ Ce lien est valide pendant <strong>24 heures</strong>.");
        html.append("</p>");

        // Lien de secours (si le bouton ne marche pas)
        html.append("<div style='border-top: 1px solid #e5e7eb; margin-top: 30px; padding-top: 20px; font-size: 12px; color: #9ca3af; word-break: break-all;'>");
        html.append("Si le bouton ne fonctionne pas, copiez ce lien dans votre navigateur :<br>");
        html.append("<a href='").append(link).append("' style='color: #4F46E5; text-decoration: underline;'>").append(link).append("</a>");
        html.append("</div>");

        html.append("</div>"); // Fin Corps

        // Footer
        html.append("<div style='background-color: #f9fafb; padding: 20px; text-align: center; font-size: 12px; color: #9ca3af; border-top: 1px solid #e5e7eb;'>");
        html.append("<p style='margin: 0;'>&copy; 2025 Loto Master AI. Tous droits réservés.</p>");
        html.append("<p style='margin: 5px 0 0 0;'>Si vous n'êtes pas à l'origine de cette inscription, ignorez simplement cet email.</p>");
        html.append("</div>");

        html.append("</div>"); // Fin Carte
        html.append("</div>"); // Fin Conteneur Principal
        html.append("</body></html>");

        // Envoi
        sendHtmlEmail(to, subject, html.toString());
    }
}
