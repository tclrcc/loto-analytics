package com.analyseloto.loto.job;

import com.analyseloto.loto.dto.AstroProfileDto;
import com.analyseloto.loto.dto.PronosticResultDto;
import com.analyseloto.loto.entity.*;
import com.analyseloto.loto.enums.BetType;
import com.analyseloto.loto.enums.JobExecutionStatus;
import com.analyseloto.loto.event.NouveauTirageEvent;
import com.analyseloto.loto.repository.UserBetRepository;
import com.analyseloto.loto.repository.UserRepository;
import com.analyseloto.loto.service.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

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
    // Event
    private final ApplicationEventPublisher eventPublisher;
    /* Email de l'utilisateur ia */
    @Value("${user.ia.mail}")
    private String mailUserIa;

    /**
     * POINT D'ENTRÉE AUTOMATIQUE (CRON)
     * Cette méthode est appelée par Spring. Elle n'a pas d'argument.
     * Elle appelle la logique métier avec "false".
     */
    @Scheduled(cron = "${loto.jobs.cron.fdj-recovery}", zone = "Europe/Paris")
    public void scheduledJobFdj() {
        // On appelle la méthode logique avec "false" car c'est automatique
        executerRecuperationFdj(false);
    }

    /**
     * Appel manuel du job de récupération des résultats FDJ (via API admin)
     */
    public void triggerRecupererResultatsFdj() {
        // On appelle la méthode logique avec "true" car c'est manuel
        executerRecuperationFdj(true);
    }

    /**
     * Contient tout le code. Elle prend le paramètre mais n'est PAS @Scheduled.
     */
    private void executerRecuperationFdj(boolean force) {
        String mode = force ? "MANUEL" : "AUTO";
        log.info("🤖 Job {} : Vérification FDJ...", mode);

        // Enregistrement début job
        JobLog jobLog = jobMonitorService.startJob("RECUPERER_DERNIER_TIRAGE_" + mode);

        // Appel de la méthode de récupération
        Optional<LotoTirage> newTirage = fdjService.recupererDernierTirage(force);

        if (newTirage.isPresent()) {
            log.info("✅ Base mise à jour avec le dernier tirage !");

            LotoTirage tirage = newTirage.get();
            Optional<LotoTirageRank> jackpot = tirage.getRanks().stream()
                    .filter(r -> r.getRankNumber() == 1)
                    .findFirst();

            // Log si le jackpot a été remporté
            if (jackpot.isPresent() && jackpot.get().getWinners() > 0) {
                log.info("💰 WOW ! Le JACKPOT a été remporté par {} personne(s) !", jackpot.get().getWinners());
            } else {
                log.info("📉 Pas de gagnant du jackpot ce soir.");
            }

            // Déclenchement de l'événement pour calculer les gains
            eventPublisher.publishEvent(new NouveauTirageEvent(this, tirage));

            // Notification aux admins (sans user IA)
            List<User> admins = userRepository.findByRole("ADMIN").stream().
                    filter(user -> !user.isSystemAccount()).toList();

            if (admins.isEmpty()) {
                log.warn("Aucun administrateur éligible pour recevoir la notification.");
            }

            // Envoi mail à chaque admin
            for (User admin : admins) {
                emailService.sendAdminNotification(admin.getEmail(), tirage);
                log.info("\uD83D\uDCE7 Notification envoyée à l'admin : {}", admin.getEmail());
            }

            jobMonitorService.endJob(jobLog, JobExecutionStatus.SUCCESS.getCode(), "Récupération terminée (" + mode + ").");
        } else {
            jobMonitorService.endJob(jobLog, JobExecutionStatus.FAILURE.getCode(), "Récupération impossible (" + mode + ").");
        }
    }

    @Scheduled(cron = "${loto.jobs.cron.gen-pronos}", zone = "Europe/Paris")
    public void genererPronosticsDuJour() {
        executerGenerationPronostics(false);
    }

    public void executerGenerationPronostics(boolean force) {
        log.info("🔮 Lancement du Job : Génération des pronostics de référence...");
        JobLog jobLog = jobMonitorService.startJob("GEN_PRONOSTICS_IA");

        LocalDate today = LocalDate.now();

        try {
            // 1. Récupérer l'utilisateur "IA"
            User aiUser = userRepository.findByEmail(mailUserIa)
                    .orElseThrow(() -> new RuntimeException("Utilisateur IA (" + mailUserIa + ") introuvable en base !"));

            // 2. Vérifier si on n'a pas déjà généré pour aujourd'hui (pour éviter les doublons si restart)
            List<UserBet> existants = betRepository.findByUser(aiUser).stream()
                    .filter(b -> b.getDateJeu().isEqual(today))
                    .toList();

            // On supprime les pronos existants si on active mode Force le Job, sinon fin du Job
            if (!existants.isEmpty()) {
                if (force) {
                    log.info("♻️ Mode FORCE activé : Suppression des {} anciens pronostics...", existants.size());
                    betRepository.deleteAll(existants);
                } else {
                    log.info("⚠️ Pronostics déjà générés pour aujourd'hui. Annulation.");
                    jobMonitorService.endJob(jobLog, "SKIPPED", "Déjà existant");
                    return;
                }
            }

            // 3. Générer les 10 via l'algorithme (Sans profil astro = Config par défaut)
            List<PronosticResultDto> pronostics = lotoService.genererMultiplesPronostics(today, 10);

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
                bet.setType(BetType.GRILLE);

                betRepository.save(bet);
            }

            log.info("✅ {} Pronostics de référence enregistrés pour le compte {}", pronostics.size() ,aiUser.getEmail());
            jobMonitorService.endJob(jobLog, JobExecutionStatus.SUCCESS.getCode(), pronostics.size() + " grilles générées");

        } catch (Exception e) {
            log.error("❌ Erreur génération pronostics IA", e);
            jobMonitorService.endJob(jobLog, JobExecutionStatus.FAILURE.getCode(), e.getMessage());
        }
    }

    /**
     * Envoi mail pronostics à chaque utilisateur, à 8h les jours de tirage
     */
    @Scheduled(cron = "${loto.jobs.cron.send-emails}", zone = "Europe/Paris")
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
            // On saute ceux qui ont désactivé les notifs (si vous avez géré ce champ).
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
                List<PronosticResultDto> pronostics = lotoService.genererPronosticsHybrides(today, 10, profil);

                // C. Construction du mail personnalisé
                String subject = "🎱 " + user.getFirstName() + ", vos numéros chance pour ce soir !";
                String htmlBody = emailService.buildPersonalizedHtmlBody(pronostics, today, user.getFirstName());

                // D. Envoi
                emailService.sendHtmlEmail(user.getEmail(), subject, htmlBody);
                log.info("✅ Mail envoyé avec succès à : {}", user.getEmail());

            } catch (Exception e) {
                // Le try-catch est dans la boucle pour qu'une erreur d'un user ne bloque pas les autres
                log.error("❌ Erreur lors de l'envoi pour l'utilisateur {}", user.getEmail(), e);
                jobMonitorService.endJob(jobLog, JobExecutionStatus.FAILURE.getCode(), "Erreur : " + e.getMessage());
                return;
            }
        }
        // Enregistrement log
        jobMonitorService.endJob(jobLog, JobExecutionStatus.SUCCESS.getCode(), "Envoi pronostics terminé.");
        log.info("🏁 Fin du Job d'envoi massif.");
    }

    @Scheduled(cron = "${loto.jobs.cron.budget-alert}", zone = "Europe/Paris")
    public void alerteBudgetHebdo() {
        log.info("💰 Lancement du Job Coach Budgétaire...");

        // Enregistrement début job
        JobLog jobLog = jobMonitorService.startJob("ALERTE_BUGDET_HEBDO");

        LocalDate today = LocalDate.now();
        LocalDate oneWeekAgo = today.minusWeeks(1);

        // Formatage de la période pour le mail (ex : "du 12/05 au 19/05")
        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("dd/MM");
        String periodeStr = "du " + oneWeekAgo.format(fmt) + " au " + today.format(fmt);

        List<User> users = userRepository.findAll();
        int countAlerts = 0;

        for (User user : users) {
            // On saute ceux qui ont désactivé les notifs (si vous avez géré ce champ).
            if (!user.isSubscribeToEmails()) continue;

            // 1. Récupérer les paris de la semaine dernière uniquement
            List<UserBet> weeklyBets = betRepository.findByUser(user).stream()
                    .filter(b -> b.getType().equals(BetType.GRILLE) &&
                            b.getDateJeu().isAfter(oneWeekAgo) && b.getDateJeu().isBefore(today.plusDays(1)))
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
                    jobMonitorService.endJob(jobLog, JobExecutionStatus.FAILURE.getCode(), "Erreur : " + e.getMessage());
                    return;
                }
            }
        }
        // Enregistrement log
        jobMonitorService.endJob(jobLog, JobExecutionStatus.SUCCESS.getCode(), "Alerte budget hebdo terminé.");
        log.info("🏁 Fin du Coach Budgétaire. {} alertes envoyées.", countAlerts);
    }

    /**
     * Optimisation quotidienne de l'IA à 4h du matin
     */
    @Scheduled(cron = "${loto.jobs.cron.optimisation-ia}", zone = "Europe/Paris")
    public void optimisationQuotidienne() {
        log.info("⏰ Réveil du Job d'Optimisation IA...");
        try {
            lotoService.forceDailyOptimization();
        } catch (Exception e) {
            log.error("❌ Echec de l'optimisation nocturne", e);
        }
    }
}
