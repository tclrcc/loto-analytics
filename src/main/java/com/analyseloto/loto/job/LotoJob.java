package com.analyseloto.loto.job;

import com.analyseloto.loto.dto.AstroProfileDto;
import com.analyseloto.loto.dto.PronosticResultDto;
import com.analyseloto.loto.entity.JobLog;
import com.analyseloto.loto.entity.User;
import com.analyseloto.loto.entity.UserBet;
import com.analyseloto.loto.repository.UserBetRepository;
import com.analyseloto.loto.repository.UserRepository;
import com.analyseloto.loto.service.*;
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
    // Services
    private final LotoService lotoService;
    private final EmailService emailService;
    private final JobMonitorService jobMonitorService;
    private final FdjService fdjService;
    // Repositories
    private final UserRepository userRepository;
    private final UserBetRepository betRepository;
    // Email de l'utilisateur "ROBOT" qui stockera les pronostics de référence
    private static final String EMAIL_IA_SYSTEM = "ai@loto.com";

    /**
     * Récupération automatique du dernier tirage tous les soirs de tirage à 22h
     */
    @Scheduled(cron = "0 0 22 * * MON,WED,SAT", zone = "Europe/Paris")
    public void recupererResultatsFdj() {
        log.info("🤖 Job Auto : Vérification FDJ...");

        // Appel de la méthode de récupération
        boolean newTirage = fdjService.recupererDernierTirage();

        if (newTirage) {
            log.info("✅ Base mise à jour avec le dernier tirage !");
        }
    }

    @Scheduled(cron = "0 0 9 * * MON,WED,SAT")
    public void genererPronosticsDuJour() {
        log.info("🔮 Lancement du Job : Génération des pronostics de référence...");
        JobLog jobLog = jobMonitorService.startJob("GEN_PRONOSTICS_IA");

        LocalDate today = LocalDate.now();

        try {
            // 1. Récupérer l'utilisateur "IA"
            User aiUser = userRepository.findByEmail(EMAIL_IA_SYSTEM)
                    .orElseThrow(() -> new RuntimeException("Utilisateur IA (" + EMAIL_IA_SYSTEM + ") introuvable en base !"));

            // 2. Vérifier si on n'a pas déjà généré pour aujourd'hui (pour éviter les doublons si restart)
            List<UserBet> existants = betRepository.findByUser(aiUser).stream()
                    .filter(b -> b.getDateJeu().isEqual(today))
                    .toList();

            if (!existants.isEmpty()) {
                log.info("⚠️ Pronostics déjà générés pour aujourd'hui. Annulation.");
                jobMonitorService.endJob(jobLog, "SKIPPED", "Déjà existant");
                return;
            }

            // 3. Générer les 5 grilles via l'algorithme (Sans profil astro = Config par défaut)
            List<PronosticResultDto> pronostics = lotoService.genererMultiplesPronostics(today, 5);

            // 4. Sauvegarder en base
            for (PronosticResultDto prono : pronostics) {
                UserBet bet = new UserBet();
                bet.setUser(aiUser);
                bet.setDateJeu(today);
                bet.setMise(2.20); // Prix théorique

                // On trie les boules pour le stockage propre
                List<Integer> sorted = prono.getBoules().stream().sorted().toList();
                bet.setB1(sorted.get(0));
                bet.setB2(sorted.get(1));
                bet.setB3(sorted.get(2));
                bet.setB4(sorted.get(3));
                bet.setB5(sorted.get(4));
                bet.setChance(prono.getNumeroChance());

                betRepository.save(bet);
            }

            log.info("✅ 5 Pronostics de référence enregistrés pour le compte {}", aiUser.getEmail());
            jobMonitorService.endJob(jobLog, "SUCCESS", "5 grilles générées");

        } catch (Exception e) {
            log.error("❌ Erreur génération pronostics IA", e);
            jobMonitorService.endJob(jobLog, "FAILURE", e.getMessage());
        }
    }

    /**
     * Envoi mail pronostics à chaque utilisateur, à 8h les jours de tirage
     */
    @Scheduled(cron = "0 0 8 * * MON,WED,SAT")
    public void envoyerPronosticsPersonnalises() {
        log.info("📢 Lancement du Job Pronostics Personnalisés...");

        // Enregistrement début job
        JobLog jobLog = jobMonitorService.startJob("ENVOI_PRONOSTICS");

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

            if (user.getBirthDate() == null || user.getZodiacSign() == null || user.getZodiacSign().isEmpty()) {
                log.info("L'utilisateur {} n'a pas d'infos astro. Pas d'email personnalisé.", user.getEmail());
                continue; // On passe au suivant
            }

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
                String htmlBody = emailService.buildPersonalizedHtmlBody(pronostics, today, user.getFirstName());

                // D. Envoi
                emailService.sendHtmlEmail(user.getEmail(), subject, htmlBody);
                log.info("✅ Mail envoyé avec succès à : {}", user.getEmail());

            } catch (Exception e) {
                // Le try-catch est dans la boucle pour qu'une erreur sur un user ne bloque pas les autres
                log.error("❌ Erreur lors de l'envoi pour l'utilisateur " + user.getEmail(), e);
                jobMonitorService.endJob(jobLog, "FAILURE", "Erreur : " + e.getMessage());
                return;
            }
        }
        // Enregistrement log
        jobMonitorService.endJob(jobLog, "SUCCESS", "Nettoyage terminé.");
        log.info("🏁 Fin du Job d'envoi massif.");
    }

    @Scheduled(cron = "0 0 9 * * MON")
    public void alerteBudgetHebdo() {
        log.info("💰 Lancement du Job Coach Budgétaire...");

        // Enregistrement début job
        JobLog jobLog = jobMonitorService.startJob("ALERTE_BUGDET_HEBDO");

        LocalDate today = LocalDate.now();
        LocalDate oneWeekAgo = today.minusWeeks(1);

        // Formatage de la période pour le mail (ex: "du 12/05 au 19/05")
        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("dd/MM");
        String periodeStr = "du " + oneWeekAgo.format(fmt) + " au " + today.format(fmt);

        List<User> users = userRepository.findAll();
        int countAlerts = 0;

        for (User user : users) {
            // 1. Récupérer les paris de la semaine dernière uniquement
            List<UserBet> weeklyBets = betRepository.findByUser(user).stream()
                    .filter(b -> b.getDateJeu().isAfter(oneWeekAgo) && b.getDateJeu().isBefore(today.plusDays(1)))
                    .toList();

            if (weeklyBets.isEmpty()) continue;

            // 2. Calculer la somme
            double totalDepense = weeklyBets.stream().mapToDouble(UserBet::getMise).sum();
            double totalGains = weeklyBets.stream().mapToDouble(UserBet::getGain).sum();
            double benefice = totalGains - totalDepense;

            // Si au moins une mise a été faite
            if (totalDepense > 0) {
                try {
                    emailService.sendBudgetAlertEmail(
                            user.getEmail(),
                            user.getFirstName(),
                            totalDepense,
                            totalGains,
                            benefice,
                            periodeStr
                    );
                    countAlerts++;
                    log.info("📩 Alerte budget envoyée à {} ({} €)", user.getEmail(), totalDepense);
                } catch (Exception e) {
                    log.error("Erreur envoi mail budget pour {}", user.getEmail(), e);
                    jobMonitorService.endJob(jobLog, "FAILURE", "Erreur : " + e.getMessage());
                    return;
                }
            }
        }
        // Enregistrement log
        jobMonitorService.endJob(jobLog, "SUCCESS", "Alerte budget hebdo terminé.");
        log.info("🏁 Fin du Coach Budgétaire. {} alertes envoyées.", countAlerts);
    }
}
