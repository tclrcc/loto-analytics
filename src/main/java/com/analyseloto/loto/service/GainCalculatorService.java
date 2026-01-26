package com.analyseloto.loto.service;

import com.analyseloto.loto.entity.LotoTirage;
import com.analyseloto.loto.entity.User;
import com.analyseloto.loto.entity.UserBet;
import com.analyseloto.loto.entity.UserBilan;
import com.analyseloto.loto.event.NouveauTirageEvent;
import com.analyseloto.loto.repository.UserBetRepository;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@Slf4j
@RequiredArgsConstructor
public class GainCalculatorService {
    // Repositories
    private final UserBetRepository userBetRepository;
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

        // 1. PHASE DE CALCUL : On ne prend QUE ceux qui n'ont pas de gain (Optimisation)
        List<UserBet> parisATraiter = userBetRepository.findByDateJeuAndGainIsNull(tirage.getDateTirage());

        if (parisATraiter.isEmpty()) {
            log.info("Aucun pari en attente de traitement pour ce tirage.");
        } else {
            // Calcul et Mise à jour
            for (UserBet bet : parisATraiter) {
                double gain = lotoService.calculerGainSimule(bet, tirage);
                bet.setGain(gain);
                userBetRepository.save(bet);
            }
            log.info("✅ {} paris mis à jour.", parisATraiter.size());
        }

        // 2. PHASE D'ENVOI D'EMAILS : On récupère TOUT pour avoir un bilan complet
        // C'est important de refaire une requête ici pour avoir l'ensemble des grilles (anciennes + nouvelles)
        List<UserBet> tousLesParisDuJour = userBetRepository.findByDateJeu(tirage.getDateTirage());

        // Regroupement par utilisateur
        Map<User, List<UserBet>> parisParUtilisateur = tousLesParisDuJour.stream()
                .collect(Collectors.groupingBy(UserBet::getUser));

        // Envoi
        parisParUtilisateur.forEach((user, bets) -> {
            if (user.isSubscribeToEmails()) {
                try {
                    emailService.sendDrawResultNotification(user, tirage, bets);
                } catch (Exception e) {
                    log.error("Erreur mail {}", user.getEmail(), e);
                }
            }
        });

        // Enregistrement du bilan financier
        parisParUtilisateur.forEach((user, bets) -> {

            // On recalcule LE TOTAL historique (une seule fois par jour)
            List<UserBet> toutHistorique = userBetRepository.findByUser(user);

            double totalDepense = toutHistorique.stream().mapToDouble(UserBet::getMise).sum();
            double totalGains = toutHistorique.stream().filter(b -> b.getGain() != null).mapToDouble(UserBet::getGain).sum();
            long grillesGagnantes = toutHistorique.stream().filter(b -> b.getGain() != null && b.getGain() > 0).count();

            // Création du Bilan du jour
            UserBilan bilanDuJour = new UserBilan(user, tirage.getDateTirage());
            bilanDuJour.setTotalDepense(totalDepense);
            bilanDuJour.setTotalGains(totalGains);
            bilanDuJour.setSolde(totalGains - totalDepense);
            bilanDuJour.setNbGrillesJouees(toutHistorique.size());
            bilanDuJour.setNbGrillesGagnantes((int) grillesGagnantes);

            // Calcul ROI
            if (totalDepense > 0) {
                bilanDuJour.setRoi((bilanDuJour.getSolde() / totalDepense) * 100);
            }

            // Sauvegarde en BDD (Nécessite de créer un UserBilanRepository)
            userBilanRepository.save(bilanDuJour);
        });

        log.info("📈 Bilans financiers mis à jour pour {} utilisateurs.", parisParUtilisateur.size());
    }
}
