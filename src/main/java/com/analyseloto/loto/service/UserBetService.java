package com.analyseloto.loto.service;

import com.analyseloto.loto.entity.User;
import com.analyseloto.loto.entity.UserBet;
import com.analyseloto.loto.entity.UserBilan;
import com.analyseloto.loto.repository.LotoTirageRepository;
import com.analyseloto.loto.repository.UserBetRepository;
import com.analyseloto.loto.repository.UserBilanRepository;
import com.analyseloto.loto.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.ui.Model;

import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class UserBetService {
    // Repositories
    private final LotoTirageRepository lotoTirageRepository;
    private final UserRepository userRepository;
    private final UserBetRepository betRepository;
    private final UserBilanRepository userBilanRepository;

    /* Email de l'utilisateur ia */
    @Value("${user.ia.mail}")
    private String mailUserIa;

    public List<UserBet> recupererGrillesUtilisateurTriees(User user) {
        List<UserBet> rawBets = betRepository.findByUser(user);

        if (rawBets.isEmpty()) {
            return Collections.emptyList();
        }
        // On trie cette grille selon la date puis grilles classiques / code loto

        return rawBets.stream()
                .sorted(
                        Comparator.comparing(UserBet::getDateJeu).reversed()
                                .thenComparing(bet -> bet.getCodeLoto() != null)
                )
                .collect(Collectors.toList());
    }

    /**
     * Remplir le bilan de l'utilisateur IA dans le modèle
     * @param model modèle
     */
    public void remplirBilanUserIa(Model model) {
        // 1. Récupération du dernier tirage officiel
        lotoTirageRepository.findTopByOrderByDateTirageDesc().ifPresent(tirage -> {

            model.addAttribute("lastDraw", tirage);
            model.addAttribute("dateDernierTirage", tirage.getDateTirage());

            // 2. Récupération de l'utilisateur IA
            User aiUser = userRepository.findByEmail(mailUserIa).orElse(null);

            if (aiUser != null) {
                // 3. Récupération des grilles jouées par l'IA pour ce tirage précis
                List<UserBet> aiBets = betRepository.findByUserAndDateJeu(aiUser, tirage.getDateTirage());

                // --- CALCUL DES TOTAUX POUR L'EN-TÊTE ---
                double totalMise = 0.0;
                double totalGain = 0.0;
                int nbGagnants = 0;

                for (UserBet bet : aiBets) {
                    totalMise += bet.getMise(); // ex: 2.20

                    // A. On essaie de prendre le gain en base
                    Double gain = bet.getGain();

                    if (gain != null) {
                        totalGain += gain;
                        if (gain > 0) nbGagnants++;
                    }
                }

                model.addAttribute("aiBetsDernierTirage", aiBets);

                // 4. Création du résumé "Bilan Dernier Tirage" pour le Dashboard
                Map<String, Object> bilanTirage = new HashMap<>();
                bilanTirage.put("totalMise", totalMise);
                bilanTirage.put("totalGain", totalGain);
                bilanTirage.put("net", totalGain - totalMise);
                bilanTirage.put("nbGagnants", nbGagnants);

                model.addAttribute("bilanDernierTirage", bilanTirage);

                // --- 5. BILAN HISTORIQUE TOTAL (Données consolidées en BDD) ---
                // Ce bloc reste inchangé, il prend les stats globales stockées dans UserBilan
                Optional<UserBilan> lastBilanOpt = userBilanRepository.findTopByUserOrderByDateBilanDesc(aiUser);
                if (lastBilanOpt.isPresent()) {
                    UserBilan bilan = lastBilanOpt.get();
                    model.addAttribute("aiTotalGrids", bilan.getNbGrillesJouees());
                    model.addAttribute("aiNbGagnants", bilan.getNbGrillesGagnantes());
                    model.addAttribute("aiTotalGains", bilan.getTotalGains());
                    model.addAttribute("aiSolde", bilan.getSolde());
                    model.addAttribute("aiRoi", bilan.getRoi());
                } else {
                    // Valeurs par défaut si premier lancement
                    model.addAttribute("aiTotalGrids", 0);
                    model.addAttribute("aiNbGagnants", 0);
                    model.addAttribute("aiTotalGains", 0.0);
                    model.addAttribute("aiSolde", 0.0);
                    model.addAttribute("aiRoi", 0.0);
                }
            } else {
                model.addAttribute("aiBetsDernierTirage", new ArrayList<>());
            }
        });
    }
}
