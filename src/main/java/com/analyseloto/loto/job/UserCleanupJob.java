package com.analyseloto.loto.job;

import com.analyseloto.loto.entity.ConfirmationToken;
import com.analyseloto.loto.entity.JobLog;
import com.analyseloto.loto.entity.User;
import com.analyseloto.loto.enums.JobExecutionStatus;
import com.analyseloto.loto.repository.ConfirmationTokenRepository;
import com.analyseloto.loto.repository.PasswordResetTokenRepository;
import com.analyseloto.loto.repository.UserRepository;
import com.analyseloto.loto.service.JobMonitorService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class UserCleanupJob {
    // Repositories
    private final ConfirmationTokenRepository tokenRepository;
    private final PasswordResetTokenRepository passwordResetTokenRepository;
    private final UserRepository userRepository;
    // Services
    private final JobMonitorService jobMonitorService;

    /**
     * Job tous les matins à 4h, permettant de supprimer les tokens et user non activés
     */
    @Scheduled(cron = "0 0 4 * * *")
    @Transactional
    public void supprimerComptesNonActives() {
        log.info("🧹 Lancement du nettoyage des comptes non activés...");

        // Enregistrement début job
        JobLog jobLog = jobMonitorService.startJob("SUPPRIMER_COMPTES_INACTIVES");

        // On récupère les token expirés n'ayant pas été confirmés
        List<ConfirmationToken> tokensExpires = tokenRepository.findAllByExpiresAtBeforeAndConfirmedAtIsNull(LocalDateTime.now());

        if (tokensExpires.isEmpty()) {
            log.info("Aucun utilisateur à supprimer aujourd'hui");
            // Enregistrement log
            jobMonitorService.endJob(jobLog, JobExecutionStatus.SUCCESS.getCode(), "Suppression comptes inactifs terminé.");
            return;
        }

        int count = 0;
        for (ConfirmationToken token : tokensExpires) {
            // Récupération de l'utilisateur lié au token
            User user = token.getUser();

            if (user != null && !user.isEnabled()) {
                // Suppression du token
                tokenRepository.delete(token);
                // Suppression de l'utilisateur
                userRepository.delete(user);

                log.info("Compte supprimé pour expiration : {}", user.getEmail());
                count++;
            }
        }
        // Enregistrement log
        jobMonitorService.endJob(jobLog, JobExecutionStatus.SUCCESS.getCode(), "Suppression comptes inactifs terminé.");
        log.info("✅ Nettoyage terminé. {} comptes supprimés.", count);
    }

    /**
     * Job de nettoyage de la base de données tous les dimanches à 3h du matin
     */
    @Scheduled(cron = "0 0 3 * * SUN")
    public void systemCleanup() {
        log.info("🧹 Lancement du nettoyage de la base de données...");

        // Enregistrement début job
        JobLog jobLog = jobMonitorService.startJob("NETTOYAGE_BDD");

        log.info("Début du nettoyage des tokens de renouvellement de mot de passe expirés.");
        // Supprimer les token de renouvellement de mot de passe expirés
        passwordResetTokenRepository.deleteByExpiryDateBefore(LocalDateTime.now());
        log.info("Fin du nettoyage des tokens de renouvellement de mot de passe expirés.");

        // Enregistrement log
        jobMonitorService.endJob(jobLog, JobExecutionStatus.SUCCESS.getCode(), "Nettoyage BDD terminé.");

        log.info("🧹 Base de données nettoyée.");
    }
}
