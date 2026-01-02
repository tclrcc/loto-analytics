package com.analyseloto.loto.service;

import com.analyseloto.loto.dto.PronosticResultDto;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class EmailService {
    private final JavaMailSender mailSender;

    @Value("${app.base-url:http://localhost:8080}")
    private String baseUrl;

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

    public String buildPersonalizedHtmlBody(List<PronosticResultDto> pronos, LocalDate date, String prenom) {
        StringBuilder sb = new StringBuilder();

        // Formatage date joli : "Lundi 29 Décembre"
        String dateJolie = date.format(DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.FRANCE));
        // Mettre la première lettre en majuscule
        dateJolie = dateJolie.substring(0, 1).toUpperCase() + dateJolie.substring(1);

        sb.append("<html><body style='font-family: 'Segoe UI', Tahoma, Geneva, Verdana, sans-serif; color: #333;'>");
        sb.append("<div style='max-width: 600px; margin: 0 auto; background-color: #ffffff; border: 1px solid #e0e0e0; border-radius: 8px; overflow: hidden;'>");

        // Header Bleu
        sb.append("<div style='background-color: #0d6efd; padding: 20px; text-align: center;'>");
        sb.append("<h2 style='color: white; margin: 0;'>🔮 Loto Master AI</h2>");
        sb.append("</div>");

        // Contenu
        sb.append("<div style='padding: 20px;'>");
        sb.append("<p style='font-size: 16px;'>Bonjour <strong>").append(prenom).append("</strong> 👋,</p>");
        sb.append("<p>Les astres et les statistiques se sont alignés pour vous. Voici vos 5 grilles optimisées pour le tirage du <strong>")
                .append(dateJolie)
                .append("</strong>.</p>");

        sb.append("<table style='width: 100%; border-collapse: collapse; margin-top: 15px; background-color: #f8f9fa; border-radius: 8px;'>");

        int count = 1;
        for (PronosticResultDto p : pronos) {
            String boules = p.getBoules().stream()
                    .map(b -> "<span style='display:inline-block; width:25px; height:25px; line-height:25px; text-align:center; background-color:#0d6efd; color:white; border-radius:50%; margin:0 2px; font-weight:bold; font-size:12px;'>" + b + "</span>")
                    .collect(Collectors.joining(" "));

            String chance = "<span style='display:inline-block; width:25px; height:25px; line-height:25px; text-align:center; background-color:#dc3545; color:white; border-radius:50%; margin-left:5px; font-weight:bold; font-size:12px;'>" + p.getNumeroChance() + "</span>";

            sb.append("<tr>");
            sb.append("<td style='padding: 12px; border-bottom: 1px solid #dee2e6;'><strong>Grille #").append(count++).append("</strong></td>");
            sb.append("<td style='padding: 12px; border-bottom: 1px solid #dee2e6;'>");
            sb.append(boules).append(chance);
            sb.append("</td>");
            sb.append("<td style='padding: 12px; border-bottom: 1px solid #dee2e6; color: #6c757d; font-size: 12px; text-align: right;'>");
            sb.append("Force: <strong>").append(p.getMaxRatioDuo()).append("x</strong>");
            sb.append("</td>");
            sb.append("</tr>");
        }
        sb.append("</table>");

        // Bouton d'action
        sb.append("<div style='text-align: center; margin-top: 30px; margin-bottom: 20px;'>");
        sb.append("<a href='https://www.fdj.fr/jeux-de-tirage/loto' style='background-color: #198754; color: white; padding: 12px 25px; text-decoration: none; border-radius: 50px; font-weight: bold; font-size: 16px;'>🍀 Jouer ces grilles</a>");
        sb.append("</div>");

        sb.append("<p style='font-size: 12px; color: #999; text-align: center; margin-top: 20px;'>");
        sb.append("Généré par votre algorithme hybride personnel.<br>Jouez de manière responsable.");
        sb.append("</p>");

        sb.append("</div></div></body></html>");
        return sb.toString();
    }

    /**
     * Envoi du Bilan Financier Hebdo (Dépenses, Gains, Bénéfice)
     */
    public void sendBudgetAlertEmail(String to, String name, double depenses, double gains, double benefice, String periode) {
        String subject = "💰 Votre bilan Loto de la semaine (Loto Master AI)";
        StringBuilder html = new StringBuilder();
        String link = baseUrl + "/profile/stats";

        // Détermination de la couleur et du signe pour le bénéfice
        boolean isPositif = benefice >= 0;
        String colorNet = isPositif ? "#10b981" : "#ef4444"; // Vert ou Rouge
        String bgNet = isPositif ? "#ecfdf5" : "#fef2f2"; // Fond Vert clair ou Rouge clair
        String sign = isPositif ? "+" : "";

        // --- DEBUT HTML ---
        html.append("<!DOCTYPE html><html><head><meta charset='UTF-8'></head>");
        html.append("<body style='margin: 0; padding: 0; font-family: \"Segoe UI\", Helvetica, Arial, sans-serif; background-color: #f3f4f6; color: #333;'>");

        // Container
        html.append("<div style='width: 100%; padding: 40px 0; background-color: #f3f4f6;'>");
        html.append("<div style='max-width: 600px; margin: 0 auto; background-color: #ffffff; border-radius: 12px; overflow: hidden; box-shadow: 0 4px 6px rgba(0,0,0,0.05);'>");

        // Header (Bleu professionnel)
        html.append("<div style='background-color: #1e1b4b; padding: 25px; text-align: center;'>");
        html.append("<h1 style='color: #ffffff; margin: 0; font-size: 20px;'>📊 Bilan Hebdomadaire</h1>");
        html.append("<p style='color: #a5b4fc; margin: 5px 0 0 0; font-size: 13px;'>").append(periode).append("</p>");
        html.append("</div>");

        // Corps
        html.append("<div style='padding: 30px;'>");
        html.append("<p style='font-size: 15px; color: #4b5563; margin-bottom: 25px;'>Bonjour <strong>").append(name).append("</strong>,<br>Voici les résultats de vos jeux pour la semaine écoulée :</p>");

        // --- BLOC STATS (Tableau pour alignement) ---
        html.append("<table style='width: 100%; border-collapse: separate; border-spacing: 0 10px;'>");

        // Ligne 1 : Dépenses (Mises)
        html.append("<tr><td style='padding: 15px; background-color: #f8fafc; border-radius: 8px; border: 1px solid #e2e8f0;'>");
        html.append("<div style='display: flex; justify-content: space-between; align-items: center;'>");
        html.append("<span style='color: #64748b; font-weight: 600; font-size: 14px;'>💸 Mises engagées : </span>");
        html.append("<span style='color: #334155; font-weight: bold; font-size: 16px;'>").append(String.format("%.2f", depenses)).append(" €</span>");
        html.append("</div></td></tr>");

        // Ligne 2 : Gains
        html.append("<tr><td style='padding: 15px; background-color: #f0fdf4; border-radius: 8px; border: 1px solid #dcfce7;'>");
        html.append("<div style='display: flex; justify-content: space-between; align-items: center;'>");
        html.append("<span style='color: #15803d; font-weight: 600; font-size: 14px;'>🏆 Gains remportés : </span>");
        html.append("<span style='color: #15803d; font-weight: bold; font-size: 16px;'>+").append(String.format("%.2f", gains)).append(" €</span>");
        html.append("</div></td></tr>");

        html.append("</table>");

        // --- GROS BLOC : BÉNÉFICE NET ---
        html.append("<div style='margin-top: 20px; padding: 20px; background-color: ").append(bgNet).append("; border: 2px dashed ").append(colorNet).append("; border-radius: 12px; text-align: center;'>");
        html.append("<p style='margin: 0; font-size: 12px; text-transform: uppercase; color: ").append(colorNet).append("; font-weight: bold; letter-spacing: 1px;'>RÉSULTAT NET</p>");
        html.append("<h2 style='margin: 5px 0 0 0; font-size: 36px; color: ").append(colorNet).append(";'>");
        html.append(sign).append(String.format("%.2f", benefice)).append(" €");
        html.append("</h2>");
        html.append("</div>");
        // --------------------------------

        // Petit commentaire contextuel
        html.append("<p style='text-align: center; font-size: 13px; color: #9ca3af; margin-top: 20px;'>");
        if (benefice > 0) {
            html.append("✨ Bravo ! Une semaine positive. Profitez-en bien !");
        } else if (benefice == 0 && depenses == 0) {
            html.append("😴 Une semaine calme sans aucun jeu.");
        } else {
            html.append("Le hasard est capricieux. Jouez prudemment la semaine prochaine.");
        }
        html.append("</p>");

        // Bouton
        html.append("<div style='text-align: center; margin-top: 30px;'>");
        html.append("<a href='").append(link).append("' style='background-color: #334155; color: #ffffff; padding: 12px 25px; text-decoration: none; border-radius: 50px; font-weight: 600; font-size: 14px;'>Voir détails complets</a>");
        html.append("</div>");

        html.append("</div></div></div></body></html>");

        sendHtmlEmail(to, subject, html.toString());
    }
}
