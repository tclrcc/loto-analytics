package com.analyseloto.loto.job;

import com.analyseloto.loto.entity.ConfirmationToken;
import com.analyseloto.loto.entity.User;
import com.analyseloto.loto.repository.ConfirmationTokenRepository;
import com.analyseloto.loto.repository.UserRepository;
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
    private final ConfirmationTokenRepository tokenRepository;
    private final UserRepository userRepository;

    @Scheduled(cron = "0 0 4 * * *")
    @Transactional
    public void supprimerComptesNonActives() {
        log.info("🧹 Lancement du nettoyage des comptes non activés...");

        // On récupère les token expirés n'ayant pas été confirmés
        List<ConfirmationToken> tokensExpires = tokenRepository.findAllByExpiresAtBeforeAndConfirmedAtIsNull(LocalDateTime.now());

        if (tokensExpires.isEmpty()) {
            log.info("Aucun utilisateur à supprimer aujourd'hui");
            return;
        }

        int count = 0;
        for (ConfirmationToken token : tokensExpires) {
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

        log.info("✅ Nettoyage terminé. {} comptes supprimés.", count);
    }
}
