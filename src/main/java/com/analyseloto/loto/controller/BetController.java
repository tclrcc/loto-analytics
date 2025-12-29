package com.analyseloto.loto.controller;

import com.analyseloto.loto.entity.User;
import com.analyseloto.loto.entity.UserBet;
import com.analyseloto.loto.repository.UserBetRepository;
import com.analyseloto.loto.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.security.Principal;
import java.time.LocalDate;

@Controller
@RequestMapping("/bets")
@RequiredArgsConstructor
public class BetController {

    private final UserBetRepository betRepository;
    private final UserRepository userRepository;

    // 1. Enregistrer une nouvelle grille jouée
    @PostMapping("/add")
    public String addBet(Principal principal,
                         @RequestParam LocalDate dateJeu,
                         @RequestParam int b1, @RequestParam int b2, @RequestParam int b3, @RequestParam int b4, @RequestParam int b5,
                         @RequestParam int chance,
                         @RequestParam double mise) {

        User user = userRepository.findByEmail(principal.getName()).orElseThrow();

        UserBet bet = new UserBet();
        bet.setUser(user);
        bet.setDateJeu(dateJeu);
        bet.setB1(b1); bet.setB2(b2); bet.setB3(b3); bet.setB4(b4); bet.setB5(b5);
        bet.setChance(chance);
        bet.setMise(mise);

        // Gain est null par défaut (en attente)

        betRepository.save(bet);
        return "redirect:/?betAdded";
    }

    // 2. Mettre à jour le gain (J'ai gagné ou perdu)
    @PostMapping("/update")
    public String updateGain(Principal principal,
                             @RequestParam Long betId,
                             @RequestParam double gain) {

        UserBet bet = betRepository.findById(betId).orElseThrow();

        // Sécurité : Vérifier que le pari appartient bien à l'utilisateur connecté
        if (!bet.getUser().getEmail().equals(principal.getName())) {
            return "redirect:/?error=unauthorized";
        }

        bet.setGain(gain);
        betRepository.save(bet);

        return "redirect:/?gainUpdated";
    }

    // 3. Supprimer un pari (en cas d'erreur de saisie)
    @PostMapping("/delete")
    public String deleteBet(Principal principal, @RequestParam Long betId) {
        UserBet bet = betRepository.findById(betId).orElseThrow();
        if (bet.getUser().getEmail().equals(principal.getName())) {
            betRepository.delete(bet);
        }
        return "redirect:/?betDeleted";
    }
}