package com.analyseloto.loto.job;

import com.analyseloto.loto.dto.PronosticResultDto;
import com.analyseloto.loto.service.EmailService;
import com.analyseloto.loto.service.LotoService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Component
@RequiredArgsConstructor
public class LotoJob {
    private final LotoService lotoService;
    private final EmailService emailService;

    @Value("${app.loto.recipient}")
    private String recipientEmail;

    // CRON : Secondes Minutes Heures JourMois Mois JourSemaine
    // "0 0 8 * * MON,WED,SAT" = 8h00 tous les Lundi, Mercredi, Samedi
    @Scheduled(cron = "0 0 8 * * MON,WED,SAT")
    public void envoyerPronosticsDuJour() {
        log.info("Lancement du Job Pronostics Loto...");
        LocalDate today = LocalDate.now();

        // 1. Générer 5 grilles STANDARD (sans profil astro)
        List<PronosticResultDto> pronostics = lotoService.genererMultiplesPronostics(today, 5);

        // 2. Construire le contenu du mail
        String subject = "🎱 Vos 5 Grilles pour ce soir (" + today.format(DateTimeFormatter.ofPattern("dd/MM/yyyy")) + ")";
        String htmlBody = buildHtmlBody(pronostics, today);

        // 3. Envoyer
        emailService.sendHtmlEmail(recipientEmail, subject, htmlBody);
        log.info("Mail de pronostics envoyé à {}", recipientEmail);
    }

    private String buildHtmlBody(List<PronosticResultDto> pronos, LocalDate date) {
        StringBuilder sb = new StringBuilder();
        sb.append("<html><body style='font-family: Arial, sans-serif;'>");
        sb.append("<div style='background-color: #f8f9fa; padding: 20px; border-radius: 10px;'>");
        sb.append("<h2 style='color: #0d6efd;'>🔮 Pronostics Loto Master</h2>");
        sb.append("<p>Bonjour ! Voici l'analyse algorithmique pour le tirage du <strong>")
                .append(date.format(DateTimeFormatter.ofPattern("EEEE d MMMM")))
                .append("</strong>.</p>");

        sb.append("<table style='width: 100%; border-collapse: collapse; margin-top: 20px;'>");

        int count = 1;
        for (PronosticResultDto p : pronos) {
            String boules = p.getBoules().stream().map(String::valueOf).collect(Collectors.joining(" - "));
            sb.append("<tr>");
            sb.append("<td style='padding: 10px; border-bottom: 1px solid #ddd;'><strong>Grille #").append(count++).append("</strong></td>");
            sb.append("<td style='padding: 10px; border-bottom: 1px solid #ddd; font-size: 18px;'>");
            sb.append("<span style='color: #333; letter-spacing: 2px;'>").append(boules).append("</span>");
            sb.append(" <span style='color: #dc3545; font-weight: bold; margin-left: 10px;'>(Chance ").append(p.getNumeroChance()).append(")</span>");
            sb.append("</td>");
            sb.append("<td style='padding: 10px; border-bottom: 1px solid #ddd; color: #666; font-size: 12px;'>");
            sb.append("Force Duo: ").append(p.getMaxRatioDuo()).append("x");
            sb.append("</td>");
            sb.append("</tr>");
        }

        sb.append("</table>");
        sb.append("<p style='margin-top: 20px; font-size: 12px; color: #999;'>Généré automatiquement par votre IA Loto Master.</p>");
        sb.append("<div style='text-align: center; margin-top: 20px;'>");
        sb.append("<a href='https://www.fdj.fr/jeux-de-tirage/loto' style='background-color: #198754; color: white; padding: 10px 20px; text-decoration: none; border-radius: 5px; font-weight: bold;'>Jouer sur FDJ.fr</a>");
        sb.append("</div>");
        sb.append("</div></body></html>");
        return sb.toString();
    }

}
