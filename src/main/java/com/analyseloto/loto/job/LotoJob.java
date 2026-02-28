package com.analyseloto.loto.job;

import com.analyseloto.loto.dto.PronosticResultDto;
import com.analyseloto.loto.entity.*;
import com.analyseloto.loto.enums.BetType;
import com.analyseloto.loto.enums.JobExecutionStatus;
import com.analyseloto.loto.enums.RoleUser;
import com.analyseloto.loto.event.NouveauTirageEvent;
import com.analyseloto.loto.repository.LotoTirageRepository;
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
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

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
    private final LotoTirageRepository tirageRepository;
    private final UserRepository userRepository;
    private final UserBetRepository betRepository;

    // Event
    private final ApplicationEventPublisher eventPublisher;
    private final AtomicBoolean isOptimizing = new AtomicBoolean(false); // Le verrou

    /* Email de l'utilisateur ia */
    @Value("${user.ia.mail}")
    private String mailUserIa;

    private final static String LOG_ERREUR = "Erreur : ";

    /**
     * POINT D'ENTRÉE AUTOMATIQUE (CRON)
     * Cette méthode est appelée par Spring. Elle n'a pas d'argument.
     * Elle appelle la logique métier avec "false".
     */
    @Scheduled(cron = "${loto.jobs.cron.fdj-recovery}", zone = "Europe/Paris")
    public void scheduledJobFdj() {
        executerRecuperationFdj(false);
    }

    /**
     * Appel manuel du job de récupération des résultats FDJ (via API admin)
     */
    public void triggerRecupererResultatsFdj() {
        executerRecuperationFdj(true);
    }

    /**
     * Contient tout le code. Elle prend le paramètre mais n'est PAS @Scheduled.
     */
    private void executerRecuperationFdj(boolean force) {
        String mode = force ? "MANUEL" : "AUTO";
        log.info("🤖 Job {} : Vérification FDJ...", mode);

        JobLog jobLog = jobMonitorService.startJob("RECUPERER_DERNIER_TIRAGE_" + mode);
        Optional<LotoTirage> newTirage = fdjService.recupererDernierTirage(force);

        if (newTirage.isPresent()) {
            log.info("✅ Base mise à jour avec le dernier tirage !");

            LotoTirage tirage = newTirage.get();
            Optional<LotoTirageRank> jackpot = tirage.getRanks().stream()
                    .filter(r -> r.getRankNumber() == 1)
                    .findFirst();

            if (jackpot.isPresent() && jackpot.get().getWinners() > 0) {
                log.info("💰 WOW ! Le JACKPOT a été remporté par {} personne(s) !", jackpot.get().getWinners());
            } else {
                log.info("📉 Pas de gagnant du jackpot ce soir.");
            }

            eventPublisher.publishEvent(new NouveauTirageEvent(this, tirage));

            List<User> admins = userRepository.findByRole(RoleUser.ADMIN.name()).stream().
                    filter(user -> !user.isSystemAccount()).toList();

            if (admins.isEmpty()) {
                log.warn("Aucun administrateur éligible pour recevoir la notification.");
            }

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
            // =========================================================
            // 🚀 SNIPER MODE : Filtre par Espérance Mathématique (EV)
            // =========================================================
            double prochainJackpot = estimerProchainJackpot();
            double ev = calculerEsperanceMathematique(prochainJackpot);

            log.info("🎯 [SNIPER MODE] Cagnotte estimée : {}M€ | EV (Rentabilité) : {}", prochainJackpot / 1_000_000.0, ev);

            if (ev < 0.85 && !force) {
                log.warn("🛑 [SNIPER MODE] Espérance mathématique faible (EV={}). Annulation pour protéger la Bankroll.", ev);
                jobMonitorService.endJob(jobLog, "SKIPPED", "EV négative, bankroll protégée");
                return;
            }
            log.info("🟢 [SNIPER MODE] EV Acceptable. Déclenchement des algorithmes !");
            // =========================================================

            User aiUser = userRepository.findByEmail(mailUserIa)
                    .orElseThrow(() -> new RuntimeException("Utilisateur IA (" + mailUserIa + ") introuvable en base !"));

            List<UserBet> existants = betRepository.findByUser(aiUser).stream()
                    .filter(b -> b.getDateJeu().isEqual(today))
                    .toList();

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

            // Génération optimisée
            List<PronosticResultDto> pronostics = lotoService.genererMultiplesPronostics(today, 5);

            for (PronosticResultDto prono : pronostics) {
                UserBet bet = new UserBet();
                bet.setUser(aiUser);
                bet.setDateJeu(today);
                bet.setMise(2.20);

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

            log.info("✅ {} Pronostics enregistrés pour le compte {}", pronostics.size() ,aiUser.getEmail());
            jobMonitorService.endJob(jobLog, JobExecutionStatus.SUCCESS.getCode(), pronostics.size() + " grilles générées");

        } catch (Exception e) {
            log.error("❌ Erreur génération pronostics IA", e);
            jobMonitorService.endJob(jobLog, JobExecutionStatus.FAILURE.getCode(), e.getMessage());
        }
    }

    // --- Helpers Mathématiques pour le SNIPER MODE ---

    private double estimerProchainJackpot() {
        try {
            List<LotoTirage> history = tirageRepository.findAll();
            if (!history.isEmpty()) {
                // On s'assure d'avoir le dernier tirage
                history.sort((t1, t2) -> t2.getDateTirage().compareTo(t1.getDateTirage()));
                LotoTirage last = history.get(0);

                Optional<LotoTirageRank> rank1 = last.getRanks().stream()
                        .filter(r -> r.getRankNumber() == 1)
                        .findFirst();

                if (rank1.isPresent()) {
                    if (rank1.get().getWinners() > 0) {
                        return 2_000_000.0; // Le jackpot est tombé, remise à 2M
                    } else {
                        return rank1.get().getPrize() + 1_000_000.0; // +1 million si pas de gagnant
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Impossible d'estimer précisément le jackpot, application du seuil bas.");
        }
        return 2_000_000.0;
    }

    private double calculerEsperanceMathematique(double jackpot) {
        double probaRang1 = 1.0 / 19_068_840.0;
        double evRang1 = jackpot * probaRang1;
        double evSecondaire = 0.60; // Valeur moyenne constante des rangs inférieurs

        double evTotale = (evRang1 + evSecondaire) / 2.20;
        return Math.round(evTotale * 100.0) / 100.0;
    }

    /**
     * Envoi mail pronostics à chaque utilisateur, à 8h les jours de tirage
     */
    @Scheduled(cron = "${loto.jobs.cron.send-emails}", zone = "Europe/Paris")
    public void envoyerPronosticsPersonnalises() {
        log.info("📢 Lancement du Job Pronostics Personnalisés...");

        JobLog jobLog = jobMonitorService.startJob("ENVOI_PRONOSTICS");
        LocalDate today = LocalDate.now();
        List<User> users = userRepository.findAll();

        if (users.isEmpty()) {
            log.warn("Aucun utilisateur trouvé en base.");
            return;
        }

        for (User user : users) {
            if (!user.isSubscribeToEmails()) continue;

            if (user.getBirthDate() == null || user.getZodiacSign() == null || user.getZodiacSign().isEmpty()) {
                log.info("L'utilisateur {} n'a pas d'infos astro. Pas d'email personnalisé.", user.getEmail());
                continue;
            }

            try {
                List<PronosticResultDto> pronostics = lotoService.genererMultiplesPronostics(today, 10);
                String subject = "🎱 " + user.getFirstName() + ", vos numéros chance pour ce soir !";
                String htmlBody = emailService.buildPersonalizedHtmlBody(pronostics, today, user.getFirstName());

                emailService.sendHtmlEmail(user.getEmail(), subject, htmlBody);
                log.info("✅ Mail envoyé avec succès à : {}", user.getEmail());

            } catch (Exception e) {
                log.error("❌ Erreur lors de l'envoi pour l'utilisateur {}", user.getEmail(), e);
                jobMonitorService.endJob(jobLog, JobExecutionStatus.FAILURE.getCode(), LOG_ERREUR + e.getMessage());
                return;
            }
        }
        jobMonitorService.endJob(jobLog, JobExecutionStatus.SUCCESS.getCode(), "Envoi pronostics terminé.");
        log.info("🏁 Fin du Job d'envoi massif.");
    }

    @Scheduled(cron = "${loto.jobs.cron.budget-alert}", zone = "Europe/Paris")
    public void alerteBudgetHebdo() {
        log.info("💰 Lancement du Job Coach Budgétaire...");

        JobLog jobLog = jobMonitorService.startJob("ALERTE_BUGDET_HEBDO");
        LocalDate today = LocalDate.now();
        LocalDate oneWeekAgo = today.minusWeeks(1);

        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("dd/MM");
        String periodeStr = "du " + oneWeekAgo.format(fmt) + " au " + today.format(fmt);

        List<User> users = userRepository.findAll();
        int countAlerts = 0;

        for (User user : users) {
            if (!user.isSubscribeToEmails()) continue;

            List<UserBet> weeklyBets = betRepository.findByUser(user).stream()
                    .filter(b -> b.getType() != null && b.getType().equals(BetType.GRILLE)
                            && b.getDateJeu().isAfter(oneWeekAgo) && b.getDateJeu().isBefore(today.plusDays(1)))
                    .toList();

            if (weeklyBets.isEmpty()) continue;

            double totalDepense = weeklyBets.stream().mapToDouble(UserBet::getMise).sum();
            double totalGains = weeklyBets.stream().mapToDouble(UserBet::getGain).sum();
            double benefice = totalGains - totalDepense;

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
                    jobMonitorService.endJob(jobLog, JobExecutionStatus.FAILURE.getCode(), LOG_ERREUR + e.getMessage());
                    return;
                }
            }
        }
        jobMonitorService.endJob(jobLog, JobExecutionStatus.SUCCESS.getCode(), "Alerte budget hebdo terminé.");
        log.info("🏁 Fin du Coach Budgétaire. {} alertes envoyées.", countAlerts);
    }

    @Scheduled(cron = "${loto.jobs.cron.optimisation-ia}", zone = "Europe/Paris")
    public void optimisationQuotidienne() {
        log.info("⏰ Réveil du Job d'Optimisation IA...");

        if (isOptimizing.get()) {
            log.warn("⚠️ Une optimisation est déjà en cours. On annule ce lancement.");
            return;
        }

        JobLog jobLog = jobMonitorService.startJob("OPTIMISATION_QUOTIDIENNE_IA");
        try {
            isOptimizing.set(true);
            lotoService.forceDailyOptimization();
            jobMonitorService.endJob(jobLog, JobExecutionStatus.SUCCESS.getCode(), "Optimisation IA terminée.");
        } catch (Exception e) {
            log.error("❌ Echec de l'optimisation nocturne", e);
            jobMonitorService.endJob(jobLog, JobExecutionStatus.FAILURE.getCode(), LOG_ERREUR + e.getMessage());
        } finally {
            isOptimizing.set(false);
        }
    }
}
