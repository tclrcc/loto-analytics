package com.analyseloto.loto.controller;

import com.analyseloto.loto.entity.User;
import com.analyseloto.loto.entity.UserBet;
import com.analyseloto.loto.repository.UserBetRepository;
import com.analyseloto.loto.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

import java.security.Principal;
import java.util.List;

@Controller
@RequiredArgsConstructor
public class HomeController {
    // Repositories
    private final UserRepository userRepository;
    private final UserBetRepository betRepository;

    @GetMapping("/")
    public String home(Model model, Principal principal) {
        String email = principal.getName();
        User user = userRepository.findByEmail(email).orElseThrow();

        model.addAttribute("prenom", user.getFirstName());
        model.addAttribute("astroSigne", user.getZodiacSign());

        // Gestion du bilan
        List<UserBet> bets = betRepository.findByUserOrderByDateJeuDesc(user);
        model.addAttribute("bets", bets);

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