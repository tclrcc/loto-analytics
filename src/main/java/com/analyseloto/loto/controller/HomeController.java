package com.analyseloto.loto.controller;

import com.analyseloto.loto.entity.Tirage;
import com.analyseloto.loto.entity.User;
import com.analyseloto.loto.entity.UserBet;
import com.analyseloto.loto.repository.TirageRepository;
import com.analyseloto.loto.repository.UserBetRepository;
import com.analyseloto.loto.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

import java.security.Principal;
import java.time.LocalDate;
import java.util.Comparator;
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
    private final TirageRepository tirageRepository;

    @GetMapping("/")
    public String home(Model model, Principal principal) {
        String email = principal.getName();
        User user = userRepository.findByEmail(email).orElseThrow();

        model.addAttribute("prenom", user.getFirstName());
        model.addAttribute("astroSigne", user.getZodiacSign());

        // Gestion du bilan
        List<UserBet> bets = betRepository.findByUserOrderByDateJeuDesc(user);
        // Tri de la liste selon gains
        List<UserBet> sortedBets = bets.stream()
                        .sorted(
                            Comparator.comparing(UserBet::getDateJeu).reversed()
                                    .thenComparing(UserBet::getGain, Comparator.nullsFirst(Comparator.reverseOrder()))
                        )
                        .toList();
        model.addAttribute("bets", sortedBets);

        // On récupère toutes les dates uniques jouées par l'utilisateur
        Set<LocalDate> datesJouees = sortedBets.stream()
                .map(UserBet::getDateJeu)
                .collect(Collectors.toSet());

        // On recherche les résultats officiels pour ces dates
        List<Tirage> tiragesOfficiels = tirageRepository.findByDateTirageIn(datesJouees);

        // On transforme la liste en Map
        Map<LocalDate, Tirage> resultsMap = tiragesOfficiels.stream()
                .collect(Collectors.toMap(Tirage::getDateTirage, Function.identity()));
        model.addAttribute("draws", resultsMap);

        // Calculs Financiers
        double totalDepense = sortedBets.stream().mapToDouble(UserBet::getMise).sum();
        double totalGains = sortedBets.stream()
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