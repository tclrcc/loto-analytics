package com.analyseloto.loto.service;

import com.analyseloto.loto.entity.User;
import com.analyseloto.loto.entity.UserBet;
import com.analyseloto.loto.enums.BetType;
import com.analyseloto.loto.repository.LotoTirageRepository;
import com.analyseloto.loto.repository.UserBetRepository;
import com.analyseloto.loto.repository.UserRepository;
import com.analyseloto.loto.util.Constantes;
import lombok.RequiredArgsConstructor;
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
        // Récupération du dernier tirage
        lotoTirageRepository.findTopByOrderByDateTirageDesc().ifPresent(tirage -> {
            // Ajout du tirage au modèle
            model.addAttribute("lastDraw", tirage);

            // On cherche l'utilisateur IA
            User aiUser = userRepository.findByEmail("ai@loto.com").orElse(null);

            if (aiUser != null) {
                // On récupère ses jeux (pronostics) pour la date du dernier tirage
                List<UserBet> aiBetsDernierTirage = betRepository.findByUserAndDateJeu(aiUser, tirage.getDateTirage());
                model.addAttribute("aiBetsDernierTirage", aiBetsDernierTirage);

                // Récupération de toutes les grilles de l'IA pour faire le bilan complet
                List<UserBet> allAiBets = betRepository.findByUser(aiUser);

                // Récupération des valeurs du bilan de l'IA pour ce tirage
                Map<String, Double> mapValuesBilan = getMapValuesBilan(allAiBets);
                // Récupération valeur bilan map
                double aiTotalDepense = mapValuesBilan.get(Constantes.MAP_KEY_TOTAL_DEPENSE);
                double aiTotalGains = mapValuesBilan.get(Constantes.MAP_KEY_TOTAL_GAINS);
                double aiSolde = mapValuesBilan.get(Constantes.MAP_KEY_SOLDE);

                // Nombre de grilles gagnantes (gain > 0)
                long aiNbGagnants = allAiBets.stream()
                        .filter(b -> b.getGain() != null && b.getGain() > 0
                                && b.getType() != null && b.getType().equals(BetType.GRILLE))
                        .count();

                // ROI (Retour sur investissement) en %
                double aiRoi = (aiTotalDepense > 0) ? (aiSolde / aiTotalDepense) * 100 : 0.0;

                // Injection dans le modèle
                model.addAttribute("aiTotalGrids", allAiBets.size());
                model.addAttribute("aiNbGagnants", aiNbGagnants);
                model.addAttribute("aiTotalGains", aiTotalGains);
                model.addAttribute("aiSolde", aiSolde);
                model.addAttribute("aiRoi", aiRoi);
                model.addAttribute("dateDernierTirage", tirage.getDateTirage());
            } else {
                model.addAttribute("aiBetsDernierTirage", new ArrayList<>());
            }
        });
    }

    /**
     * Calcul des valeurs du bilan financier d'un utilisateur
     * @param bets liste des grilles de l'utilisateur
     * @return Map avec les clés : "solde", "totalDepense", "totalGains"
     */
    public Map<String, Double> getMapValuesBilan(List<UserBet> bets) {
        Map<String, Double> mapValuesBilan = new HashMap<>();

        // Somme des dépenses totales et des gains totaux (grilles terminées)
        double totalDepense = bets.stream()
                .filter(b -> b.getGain() != null && b.getType() != null &&  b.getType().equals(BetType.GRILLE))
                .mapToDouble(UserBet::getMise)
                .sum();
        double totalGains = bets.stream()
                .filter(b -> b.getGain() != null && b.getType() != null && b.getType().equals(BetType.GRILLE))
                .mapToDouble(UserBet::getGain)
                .sum();
        // Calcul du solde
        double solde = totalGains - totalDepense;

        // Ajout des valeurs dans la map à retourner
        mapValuesBilan.put(Constantes.MAP_KEY_SOLDE, solde);
        mapValuesBilan.put(Constantes.MAP_KEY_TOTAL_DEPENSE, totalDepense);
        mapValuesBilan.put(Constantes.MAP_KEY_TOTAL_GAINS, totalGains);

        return mapValuesBilan;
    }
}
