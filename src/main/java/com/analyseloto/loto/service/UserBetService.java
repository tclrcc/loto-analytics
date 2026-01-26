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
        // Récupération du dernier tirage
        lotoTirageRepository.findTopByOrderByDateTirageDesc().ifPresent(tirage -> {
            // Ajout du tirage au modèle
            model.addAttribute("lastDraw", tirage);

            // On cherche l'utilisateur IA
            User aiUser = userRepository.findByEmail(mailUserIa).orElse(null);

            if (aiUser != null) {
                // 1. On récupère les JEUX DU DERNIER TIRAGE pour l'affichage visuel (Inchangé)
                List<UserBet> aiBetsDernierTirage = betRepository.findByUserAndDateJeu(aiUser, tirage.getDateTirage());
                model.addAttribute("aiBetsDernierTirage", aiBetsDernierTirage);

                // Récupération du dernier bilan
                Optional<UserBilan> lastBilanOpt = userBilanRepository.findTopByUserOrderByDateBilanDesc(aiUser);

                if (lastBilanOpt.isPresent()) {
                    UserBilan bilan = lastBilanOpt.get();
                    // Injection des données du Bilan en Base de données
                    model.addAttribute("aiTotalGrids", bilan.getNbGrillesJouees());
                    model.addAttribute("aiNbGagnants", bilan.getNbGrillesGagnantes());
                    model.addAttribute("aiTotalGains", bilan.getTotalGains());
                    model.addAttribute("aiSolde", bilan.getSolde());
                    model.addAttribute("aiRoi", bilan.getRoi());
                } else {
                    // Fallback propre si aucun bilan n'existe encore
                    model.addAttribute("aiTotalGrids", 0);
                    model.addAttribute("aiNbGagnants", 0);
                    model.addAttribute("aiTotalGains", 0.0);
                    model.addAttribute("aiSolde", 0.0);
                    model.addAttribute("aiRoi", 0.0);
                }
                model.addAttribute("dateDernierTirage", tirage.getDateTirage());
            } else {
                model.addAttribute("aiBetsDernierTirage", new ArrayList<>());
            }
        });
    }
}
