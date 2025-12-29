package com.analyseloto.loto.job;

import com.analyseloto.loto.dto.AstroProfileDto;
import com.analyseloto.loto.dto.PronosticResultDto;
import com.analyseloto.loto.entity.User;
import com.analyseloto.loto.repository.UserRepository;
import com.analyseloto.loto.service.AstroService;
import com.analyseloto.loto.service.EmailService;
import com.analyseloto.loto.service.LotoService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

@Slf4j
@Component
@RequiredArgsConstructor
public class LotoJob {

    private final LotoService lotoService;
    private final EmailService emailService;
    private final UserRepository userRepository; // Nouveau : accès à la BDD utilisateurs

    // CRON : 08h00 tous les Lundi, Mercredi, Samedi
    @Scheduled(cron = "0 0 8 * * MON,WED,SAT")
    public void envoyerPronosticsPersonnalises() {
        log.info("📢 Lancement du Job Pronostics Personnalisés...");
        LocalDate today = LocalDate.now();

        // 1. Récupérer tous les utilisateurs
        List<User> users = userRepository.findAll();

        if (users.isEmpty()) {
            log.warn("Aucun utilisateur trouvé en base.");
            return;
        }

        // 2. Boucler sur chaque utilisateur
        for (User user : users) {
            // On saute ceux qui ont désactivé les notifs (si vous avez géré ce champ)
            if (!user.isSubscribeToEmails()) continue;

            try {
                // A. Construction du Profil Astral de l'utilisateur
                AstroProfileDto profil = new AstroProfileDto(
                        user.getBirthDate().toString(),
                        user.getBirthTime(),
                        user.getBirthCity(),
                        user.getZodiacSign()
                );

                // B. Génération des pronostics HYBRIDES (Spécifiques à LUI)
                List<PronosticResultDto> pronostics = lotoService.genererPronosticsHybrides(today, 5, profil);

                // C. Construction du mail personnalisé
                String subject = "🎱 " + user.getFirstName() + ", vos numéros chance pour ce soir !";
                String htmlBody = buildPersonalizedHtmlBody(pronostics, today, user.getFirstName());

                // D. Envoi
                emailService.sendHtmlEmail(user.getEmail(), subject, htmlBody);
                log.info("✅ Mail envoyé avec succès à : {}", user.getEmail());

            } catch (Exception e) {
                // Le try-catch est dans la boucle pour qu'une erreur sur un user ne bloque pas les autres
                log.error("❌ Erreur lors de l'envoi pour l'utilisateur " + user.getEmail(), e);
            }
        }
        log.info("🏁 Fin du Job d'envoi massif.");
    }

    private String buildPersonalizedHtmlBody(List<PronosticResultDto> pronos, LocalDate date, String prenom) {
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
}