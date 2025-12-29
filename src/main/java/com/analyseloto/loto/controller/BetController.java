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
    // Repositories
    private final UserBetRepository betRepository;
    private final UserRepository userRepository;

    /**
     * Ajouter une grille jouée par l'utilisateur
     * @param principal infos user
     * @param dateJeu date jeu
     * @param b1 boule 1
     * @param b2 boule 2
     * @param b3 boule 3
     * @param b4 boule 4
     * @param b5 boule 5
     * @param chance boule chance
     * @param mise mise
     * @return
     */
    @PostMapping("/add")
    public String addBet(Principal principal,
                         @RequestParam LocalDate dateJeu,
                         @RequestParam int b1, @RequestParam int b2, @RequestParam int b3, @RequestParam int b4, @RequestParam int b5,
                         @RequestParam int chance,
                         @RequestParam double mise) {
        // Récupération de l'utilisateur
        User user = userRepository.findByEmail(principal.getName()).orElseThrow();

        // Création objet UserBet
        UserBet bet = new UserBet();
        bet.setUser(user);
        bet.setDateJeu(dateJeu);
        bet.setB1(b1); bet.setB2(b2); bet.setB3(b3); bet.setB4(b4); bet.setB5(b5);
        bet.setChance(chance);
        bet.setMise(mise);

        // Enregistrement du jeu
        betRepository.save(bet);

        return "redirect:/?betAdded";
    }

    /**
     * Action de mise à jour d'un jeu
     * @param principal infos user
     * @param betId identifiant jeu
     * @param gain gain
     * @return
     */
    @PostMapping("/update")
    public String updateGain(Principal principal,
                             @RequestParam Long betId,
                             @RequestParam double gain) {
        // Récupération du jeu
        UserBet bet = betRepository.findById(betId).orElseThrow();

        // Vérifier que le pari appartient bien à l'utilisateur connecté
        if (!bet.getUser().getEmail().equals(principal.getName())) {
            return "redirect:/?error=unauthorized";
        }

        bet.setGain(gain);
        betRepository.save(bet);

        return "redirect:/?gainUpdated";
    }

    /**
     * Suppression d'un jeu
     * @param principal infos user
     * @param betId idenfitiant jeu
     * @return
     */
    @PostMapping("/delete")
    public String deleteBet(Principal principal, @RequestParam Long betId) {
        // Récupération du jeu
        UserBet bet = betRepository.findById(betId).orElseThrow();

        if (bet.getUser().getEmail().equals(principal.getName())) {
            betRepository.delete(bet);
        }

        return "redirect:/?betDeleted";
    }
}