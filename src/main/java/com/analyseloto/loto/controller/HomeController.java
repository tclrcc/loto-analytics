package com.analyseloto.loto.controller;

import com.analyseloto.loto.entity.LotoTirage;
import com.analyseloto.loto.entity.User;
import com.analyseloto.loto.entity.UserBet;
import com.analyseloto.loto.repository.LotoTirageRepository;
import com.analyseloto.loto.repository.UserBetRepository;
import com.analyseloto.loto.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

import java.security.Principal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Controller
@RequiredArgsConstructor
public class HomeController {
    // Repositories
    private final UserRepository userRepository;
    private final UserBetRepository betRepository;
    private final LotoTirageRepository lotoTirageRepository;

    /**
     * Affichage de la page d'accueil
     * @param model model
     * @param principal utilisateur
     * @return
     */
    @GetMapping("/")
    public String home(Model model, Principal principal) {
        // Récupération utilisateur
        String email = principal.getName();
        User user = userRepository.findByEmail(email).orElseThrow();

        // Infos utilisateur
        model.addAttribute("prenom", user.getFirstName());
        model.addAttribute("lastLogin", user.getLastLogin());
        model.addAttribute("astroSigne", user.getZodiacSign());

        // Récupération des grilles
        List<UserBet> bets = betRepository.findByUserOrderByDateJeuDesc(user);
        model.addAttribute("bets", bets);

        // On récupère toutes les dates uniques jouées par l'utilisateur
        Set<LocalDate> datesJouees = bets.stream()
                .map(UserBet::getDateJeu)
                .collect(Collectors.toSet());

        // On recherche les résultats officiels pour ces dates
        List<LotoTirage> tiragesOfficiels = lotoTirageRepository.findByDateTirageIn(datesJouees);

        // Récupération du dernier tirage
        lotoTirageRepository.findTopByOrderByDateTirageDesc().ifPresent(tirage -> {
            model.addAttribute("lastDraw", tirage);
        });

        // On transforme la liste en Map
        Map<LocalDate, LotoTirage> resultsMap = tiragesOfficiels.stream()
                .collect(Collectors.toMap(LotoTirage::getDateTirage, Function.identity()));
        model.addAttribute("draws", resultsMap);

        // Calculs Financiers
        double totalDepense = bets.stream().mapToDouble(UserBet::getMise).sum();
        double totalGains = bets.stream()
                .filter(b -> b.getGain() != null)
                .mapToDouble(UserBet::getGain)
                .sum();
        double solde = totalGains - totalDepense;

        // Données pour le bilan financier
        model.addAttribute("totalDepense", totalDepense);
        model.addAttribute("totalGains", totalGains);
        model.addAttribute("solde", solde);

        // Données pour le pré-remplissage Astro
        model.addAttribute("birthDate", user.getBirthDate());
        model.addAttribute("birthTime", user.getBirthTime());
        model.addAttribute("birthCity", user.getBirthCity());

        return "index";
    }
}