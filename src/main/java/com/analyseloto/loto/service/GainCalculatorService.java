package com.analyseloto.loto.service;

import com.analyseloto.loto.entity.LotoTirage;
import com.analyseloto.loto.entity.User;
import com.analyseloto.loto.entity.UserBet;
import com.analyseloto.loto.entity.UserBilan;
import com.analyseloto.loto.event.NouveauTirageEvent;
import com.analyseloto.loto.repository.UserBetRepository;
import com.analyseloto.loto.repository.UserBilanRepository;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@Slf4j
@RequiredArgsConstructor
public class GainCalculatorService {
    // Repositories
    private final UserBetRepository userBetRepository;
    private final UserBilanRepository userBilanRepository;
    // Services
    private final LotoService lotoService;
    private final EmailService emailService;

    /**
     * Evenement déclenché lors de la récupération du tirage officiel pour mettre à jour les gains des utilisateurs
     * @param event evenement tirage
     */
    @Async
    @EventListener
    @Transactional
    public void onNouveauTirage(NouveauTirageEvent event) {
        LotoTirage tirage = event.getTirage();
        LocalDate dateTirage = tirage.getDateTirage();

        // 1. CALCUL DES GAINS
        List<UserBet> parisATraiter = userBetRepository.findByDateJeuAndGainIsNull(dateTirage);
        if (!parisATraiter.isEmpty()) {
            for (UserBet bet : parisATraiter) {
                bet.setGain(lotoService.calculerGainSimule(bet, tirage));
                userBetRepository.save(bet);
            }
            log.info("✅ {} paris mis à jour.", parisATraiter.size());
        }

        // 2. RÉCUPÉRATION GLOBALE POUR EMAILS ET BILANS
        List<UserBet> tousLesParisDuJour = userBetRepository.findByDateJeu(dateTirage);
        Map<User, List<UserBet>> parisParUtilisateur = tousLesParisDuJour.stream()
                .collect(Collectors.groupingBy(UserBet::getUser));

        parisParUtilisateur.forEach((user, bets) -> {
            // A. Envoi Email
            envoyerNotificationEmail(user, tirage, bets);

            // B. Mise à jour du Bilan (LA CORRECTION EST ICI)
            mettreAJourBilanFinancier(user, dateTirage, bets);
        });

        log.info("📈 Traitement terminé pour {} utilisateurs.", parisParUtilisateur.size());
    }

    private void envoyerNotificationEmail(User user, LotoTirage tirage, List<UserBet> bets) {
        if (user.isSubscribeToEmails()) {
            try {
                emailService.sendDrawResultNotification(user, tirage, bets);
            } catch (Exception e) {
                log.error("Erreur mail {}", user.getEmail(), e);
            }
        }
    }

    private void mettreAJourBilanFinancier(User user, LocalDate dateTirage, List<UserBet> parisDuJour) {
        // 1. On récupère le cumul historique jusqu'au tirage PRÉCÉDENT
        UserBilan precedent = userBilanRepository
                .findTopByUserAndDateBilanBeforeOrderByDateBilanDesc(user, dateTirage)
                .orElse(null);

        // 2. On cherche si un bilan existe déjà pour AUJOURD'HUI (cas du re-run)
        UserBilan actuel = userBilanRepository.findByUserAndDateBilan(user, dateTirage)
                .orElse(new UserBilan(user, dateTirage));

        // 3. Calcul du delta (uniquement ce qui s'est passé sur ce tirage)
        double gainsAujourdhui = parisDuJour.stream()
                .filter(b -> b.getGain() != null)
                .mapToDouble(UserBet::getGain).sum();
        double depenseAujourdhui = parisDuJour.stream()
                .mapToDouble(UserBet::getMise).sum();
        int gagnantsAujourdhui = (int) parisDuJour.stream()
                .filter(b -> b.getGain() != null && b.getGain() > 0).count();

        // 4. Initialisation des cumuls (Base précédente ou 0)
        double baseDepense = (precedent != null) ? precedent.getTotalDepense() : 0.0;
        double baseGains = (precedent != null) ? precedent.getTotalGains() : 0.0;
        int baseNbGrilles = (precedent != null) ? precedent.getNbGrillesJouees() : 0;
        int baseNbGagnantes = (precedent != null) ? precedent.getNbGrillesGagnantes() : 0;

        // 5. Mise à jour du bilan actuel avec le cumul
        actuel.setTotalDepense(baseDepense + depenseAujourdhui);
        actuel.setTotalGains(baseGains + gainsAujourdhui);
        actuel.setSolde(actuel.getTotalGains() - actuel.getTotalDepense());
        actuel.setNbGrillesJouees(baseNbGrilles + parisDuJour.size());
        actuel.setNbGrillesGagnantes(baseNbGagnantes + gagnantsAujourdhui);

        // Calcul du ROI global
        if (actuel.getTotalDepense() > 0) {
            actuel.setRoi((actuel.getSolde() / actuel.getTotalDepense()) * 100);
        }

        userBilanRepository.save(actuel);
    }
}
