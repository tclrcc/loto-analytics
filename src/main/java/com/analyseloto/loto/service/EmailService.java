package com.analyseloto.loto.service;

import com.analyseloto.loto.dto.PronosticResultDto;
import com.analyseloto.loto.entity.LotoTirage;
import com.analyseloto.loto.entity.User;
import com.analyseloto.loto.entity.UserBet;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
@Slf4j
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
            helper.setText(htmlBody, true);

            mailSender.send(message);
        } catch (MessagingException e) {
            log.error("Impossible d'envoyer l'email", e);
            throw new RuntimeException("Erreur envoi mail");
        }
    }

    /**
     * Envoi du récapitulatif des résultats d'un tirage spécifique
     * @param user L'utilisateur
     * @param tirage Le tirage officiel
     * @param bets La liste des paris de l'utilisateur pour ce tirage
     */
    public void sendDrawResultNotification(User user, LotoTirage tirage, List<UserBet> bets) {
        if (bets.isEmpty()) return;

        // 1. Calculs des totaux
        double totalMise = bets.stream().mapToDouble(UserBet::getMise).sum();
        double totalGain = bets.stream()
                .filter(b -> b.getGain() != null)
                .mapToDouble(UserBet::getGain)
                .sum();
        double benefice = totalGain - totalMise;

        // 2. Formatage Date
        String dateJolie = tirage.getDateTirage().format(DateTimeFormatter.ofPattern("EEEE d MMMM yyyy", Locale.FRANCE));
        dateJolie = dateJolie.substring(0, 1).toUpperCase() + dateJolie.substring(1);

        // 3. Définition du ton (Positif ou Neutre)
        boolean isGagnant = totalGain > 0;
        String subject = isGagnant
                ? "🏆 Bravo ! Vous avez gagné au Loto du " + dateJolie
                : "🎱 Résultats du Loto du " + dateJolie;

        String colorHeader = isGagnant ? "#059669" : "#4F46E5"; // Vert si gagné, Bleu sinon
        String emoji = isGagnant ? "🎉" : "📊";

        // --- CONSTRUCTION HTML ---
        StringBuilder html = new StringBuilder();
        html.append("<!DOCTYPE html><html><body style='font-family: \"Segoe UI\", Helvetica, Arial, sans-serif; background-color: #f3f4f6; color: #333; margin:0; padding:0;'>");

        // Container
        html.append("<div style='width: 100%; padding: 40px 0; background-color: #f3f4f6;'>");
        html.append("<div style='max-width: 600px; margin: 0 auto; background-color: #ffffff; border-radius: 12px; overflow: hidden; box-shadow: 0 4px 6px rgba(0,0,0,0.05);'>");

        // Header
        html.append("<div style='background-color: ").append(colorHeader).append("; padding: 25px; text-align: center;'>");
        html.append("<h1 style='color: #ffffff; margin: 0; font-size: 22px;'>").append(emoji).append(" Résultats du Tirage</h1>");
        html.append("<p style='color: rgba(255,255,255,0.8); margin: 5px 0 0 0;'>").append(dateJolie).append("</p>");
        html.append("</div>");

        // Body
        html.append("<div style='padding: 30px;'>");
        html.append("<p>Bonjour <strong>").append(user.getFirstName()).append("</strong>,</p>");
        html.append("<p>Le tirage a eu lieu. Voici le verdict pour vos <strong>").append(bets.size()).append(" grilles</strong> jouées.</p>");

        // --- SECTION 1 : RÉSULTAT OFFICIEL ---
        html.append("<div style='background-color: #f8fafc; border: 1px solid #e2e8f0; border-radius: 8px; padding: 15px; text-align: center; margin-bottom: 25px;'>");
        html.append("<p style='margin: 0 0 10px 0; font-size: 12px; text-transform: uppercase; color: #64748b; font-weight: bold;'>Combinaison Gagnante</p>");

        // Boules Officielles
        html.append("<div style='display: inline-block;'>");
        String bouleStyle = "display:inline-block; width:30px; height:30px; line-height:30px; background-color:#334155; color:white; border-radius:50%; margin:0 3px; font-weight:bold; text-align:center;";
        String chanceStyle = "display:inline-block; width:30px; height:30px; line-height:30px; background-color:#ef4444; color:white; border-radius:50%; margin:0 3px; font-weight:bold; text-align:center;";

        html.append("<span style='").append(bouleStyle).append("'>").append(tirage.getBoule1()).append("</span>");
        html.append("<span style='").append(bouleStyle).append("'>").append(tirage.getBoule2()).append("</span>");
        html.append("<span style='").append(bouleStyle).append("'>").append(tirage.getBoule3()).append("</span>");
        html.append("<span style='").append(bouleStyle).append("'>").append(tirage.getBoule4()).append("</span>");
        html.append("<span style='").append(bouleStyle).append("'>").append(tirage.getBoule5()).append("</span>");
        html.append("<span style='").append(chanceStyle).append("'>").append(tirage.getNumeroChance()).append("</span>");
        html.append("</div>");
        html.append("</div>");

        // --- SECTION 2 : BILAN FINANCIER ---
        html.append("<div style='display: flex; justify-content: space-between; margin-bottom: 25px; gap: 10px;'>");

        // Carte Dépenses
        html.append("<div style='flex: 1; background: #fff1f2; border: 1px solid #fecdd3; border-radius: 8px; padding: 15px; text-align: center;'>");
        html.append("<span style='display:block; font-size:12px; color:#9f1239; margin-bottom:5px;'>MISES</span>");
        html.append("<strong style='font-size:18px; color:#be123c;'>-").append(String.format("%.2f", totalMise)).append("€</strong>");
        html.append("</div>");

        // Carte Gains
        String bgGain = totalGain > 0 ? "#ecfdf5" : "#f3f4f6";
        String borderGain = totalGain > 0 ? "#a7f3d0" : "#e5e7eb";
        String textGainColor = totalGain > 0 ? "#047857" : "#6b7280";

        html.append("<div style='flex: 1; background: ").append(bgGain).append("; border: 1px solid ").append(borderGain).append("; border-radius: 8px; padding: 15px; text-align: center;'>");
        html.append("<span style='display:block; font-size:12px; color:").append(textGainColor).append("; margin-bottom:5px;'>GAINS</span>");
        html.append("<strong style='font-size:18px; color:").append(textGainColor).append(";'>+").append(String.format("%.2f", totalGain)).append("€</strong>");
        html.append("</div>");
        html.append("</div>");

        // --- SECTION 3 : LISTE DES GRILLES ---
        html.append("<table style='width: 100%; border-collapse: collapse; font-size: 14px;'>");
        html.append("<tr style='background-color: #f8fafc; text-align: left;'><th style='padding: 10px; border-bottom: 1px solid #e2e8f0;'>Vos Numéros</th><th style='padding: 10px; border-bottom: 1px solid #e2e8f0; text-align:right;'>Résultat</th></tr>");

        for (UserBet bet : bets) {
            boolean gridWin = bet.getGain() != null && bet.getGain() > 0;
            String rowBg = gridWin ? "background-color: #f0fdf4;" : ""; // Vert très clair si gagnant

            html.append("<tr style='border-bottom: 1px solid #f1f5f9; ").append(rowBg).append("'>");

            // Colonne Numéros
            html.append("<td style='padding: 12px 10px;'>");
            html.append("<span style='color: #475569;'>")
                    .append(bet.getB1()).append(" - ")
                    .append(bet.getB2()).append(" - ")
                    .append(bet.getB3()).append(" - ")
                    .append(bet.getB4()).append(" - ")
                    .append(bet.getB5())
                    .append("</span>");
            html.append(" <strong style='color: #dc2626; margin-left:5px;'>C").append(bet.getChance()).append("</strong>");
            html.append("</td>");

            // Colonne Gain
            html.append("<td style='padding: 12px 10px; text-align: right;'>");
            if (gridWin) {
                html.append("<strong style='color: #059669;'>+").append(String.format("%.2f", bet.getGain())).append("€</strong>");
            } else {
                html.append("<span style='color: #94a3b8; font-size: 12px;'>Perdu</span>");
            }
            html.append("</td>");

            html.append("</tr>");
        }
        html.append("</table>");

        // Message de fin
        html.append("<div style='text-align: center; margin-top: 30px;'>");
        if (benefice > 0) {
            html.append("<p style='color: #059669; font-weight: bold;'>✨ Quelle chance ! Vous avez fait un bénéfice net de ").append(String.format("%.2f", benefice)).append("€ !</p>");
        } else if (totalGain > 0) {
            html.append("<p style='color: #334155;'>Vous avez gagné un peu, mais pas encore le jackpot. Persévérez !</p>");
        } else {
            html.append("<p style='color: #64748b;'>Le hasard n'était pas de votre côté ce soir. La prochaine fois sera la bonne ! 🍀</p>");
        }

        html.append("<a href='").append(baseUrl).append("/profile/stats' style='display: inline-block; background-color: #334155; color: #ffffff; padding: 12px 25px; text-decoration: none; border-radius: 50px; font-weight: bold; font-size: 14px; margin-top: 10px;'>Voir mes stats complètes</a>");
        html.append("</div>");

        // Footer standard
        html.append("</div></div></div></body></html>");

        sendHtmlEmail(user.getEmail(), subject, html.toString());
    }

    /**
     * Envoi email confirmation après inscription utilisateur
     * @param to destinataire
     * @param name nom user
     * @param link lien
     */
    public void sendConfirmationEmail(String to, String name, String link) {
        // Objet du mail
        String subject = "🚀 Activez votre compte Loto Master AI";

        // Construction du contenu
        String html = "<!DOCTYPE html>" +
                "<html><head>" +
                "<meta charset='UTF-8'>" +
                "<meta name='viewport' content='width=device-width, initial-scale=1.0'>" +
                "</head>" +
                "<body style='margin: 0; padding: 0; font-family: \"Segoe UI\", Helvetica, Arial, sans-serif; background-color: #f3f4f6; color: #333333;'>" +

                // Conteneur Principal (Fond gris)
                "<div style='width: 100%; padding: 40px 0; background-color: #f3f4f6;'>" +

                // Carte Blanche Centrée
                "<div style='max-width: 600px; margin: 0 auto; background-color: #ffffff; border-radius: 8px; overflow: hidden; box-shadow: 0 4px 6px rgba(0,0,0,0.1);'>" +

                // En-tête (Header) avec couleur de marque
                "<div style='background-color: #4F46E5; padding: 30px; text-align: center;'>" +
                "<h1 style='color: #ffffff; margin: 0; font-size: 24px; letter-spacing: 1px;'>🎱 Loto Master AI</h1>" +
                "</div>" +

                // Corps du message
                "<div style='padding: 40px 30px;'>" +

                // Salutations
                "<h2 style='color: #1f2937; margin-top: 0; font-size: 20px;'>Bonjour " + name + " ! 👋</h2>" +
                "<p style='font-size: 16px; line-height: 1.6; color: #4b5563; margin-bottom: 25px;'>" +
                "Merci de rejoindre la communauté. Votre assistant intelligent d'analyse de Loto est prêt." +
                "<br>Pour commencer, veuillez confirmer votre adresse email en cliquant sur le bouton ci-dessous :" +
                "</p>" +

                // Bouton d'action (CTA)
                "<div style='text-align: center; margin: 35px 0;'>" +
                "<a href='" + link + "' style='background-color: #4F46E5; color: #ffffff; padding: 14px 30px; text-decoration: none; border-radius: 50px; font-weight: bold; font-size: 16px; display: inline-block; box-shadow: 0 4px 6px rgba(79, 70, 229, 0.3);'>" +
                "ACTIVER MON COMPTE" +
                "</a>" +
                "</div>" +

                // Infos expiration
                "<p style='font-size: 14px; color: #6b7280; text-align: center; margin-top: 20px;'>" +
                "⏳ Ce lien est valide pendant <strong>24 heures</strong>." +
                "</p>" +

                // Lien de secours (si le bouton ne marche pas)
                "<div style='border-top: 1px solid #e5e7eb; margin-top: 30px; padding-top: 20px; font-size: 12px; color: #9ca3af; word-break: break-all;'>" +
                "Si le bouton ne fonctionne pas, copiez ce lien dans votre navigateur :<br>" +
                "<a href='" + link + "' style='color: #4F46E5; text-decoration: underline;'>" + link + "</a>" +
                "</div>" +
                "</div>" + // Fin Corps

                // Footer
                "<div style='background-color: #f9fafb; padding: 20px; text-align: center; font-size: 12px; color: #9ca3af; border-top: 1px solid #e5e7eb;'>" +
                "<p style='margin: 0;'>&copy; 2025 Loto Master AI. Tous droits réservés.</p>" +
                "<p style='margin: 5px 0 0 0;'>Si vous n'êtes pas à l'origine de cette inscription, ignorez simplement cet email.</p>" +
                "</div>" +
                "</div>" + // Fin Carte
                "</div>" + // Fin Conteneur Principal
                "</body></html>";

        // Envoi
        sendHtmlEmail(to, subject, html);
    }

    /**
     * Construction du contenu du mail des pronostics générés
     * @param pronos liste pronos
     * @param date date jeu
     * @param prenom prénom
     * @return contenu
     */
    public String buildPersonalizedHtmlBody(List<PronosticResultDto> pronos, LocalDate date, String prenom) {
        StringBuilder sb = new StringBuilder();

        // Formatage date type : "Lundi 29 Décembre"
        String dateJolie = date.format(DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.FRANCE));
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
        // Objet du mail
        String subject = "💰 Votre bilan Loto de la semaine (Loto Master AI)";
        StringBuilder html = new StringBuilder();
        // Construction du lien vers les stats du joueur
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

        // Envoi mail
        sendHtmlEmail(to, subject, html.toString());
    }

    /**
     * Envoi notifications aux admins après récupération du dernier tirage
     * @param to destinataire
     * @param tirage dernier tirage
     */
    public void sendAdminNotification(String to, LotoTirage tirage) {
        // Objet du mail
        String subject = "🤖 [JOB] Tirage récupéré : " + tirage.getDateTirage();

        // Contenu du mail
        String html = "<!DOCTYPE html><html><body style='font-family: monospace; background-color: #1e1e1e; color: #d4d4d4; padding: 20px;'>"

                // Cadre "Terminal"
                + "<div style='max-width: 600px; margin: 0 auto; border: 1px solid #333; border-radius: 5px; background-color: #252526; overflow: hidden;'>"

                // Header
                + "<div style='background-color: #333; padding: 10px; color: #fff; font-weight: bold;'>🖥️ Loto Master AI - System Report</div>"

                // Corps Log
                + "<div style='padding: 20px;'>" + "<p style='color: #4ec9b0;'>INFO: Job 'recupererResultatsFdj' executed successfully.</p>"
                + "<p>Date du tirage : <span style='color: #ce9178;'>" + tirage.getDateTirage() + "</span></p>"

                // Affichage des Boules
                + "<p>Résultat : <br>" + "<span style='color: #569cd6; font-weight: bold;'>[" + tirage.getBoule1() + ", " + tirage.getBoule2() + ", "
                + tirage.getBoule3() + ", " + tirage.getBoule4() + ", " + tirage.getBoule5() + "]</span>"
                + " Chance: <span style='color: #d16969; font-weight: bold;'>[" + tirage.getNumeroChance() + "]</span>" + "</p>"
                + "<p style='color: #6a9955;'>// Database updated successfully.</p>" + "</div>" + "</div></body></html>";

        sendHtmlEmail(to, subject, html);
    }
}
