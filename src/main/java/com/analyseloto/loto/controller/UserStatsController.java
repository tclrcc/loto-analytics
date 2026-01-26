package com.analyseloto.loto.controller;

import com.analyseloto.loto.dto.UserStatsDto;
import com.analyseloto.loto.entity.User;
import com.analyseloto.loto.repository.UserRepository;
import com.analyseloto.loto.service.UserStatsService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

import java.security.Principal;

@Controller
@RequiredArgsConstructor
public class UserStatsController {
    private final UserRepository userRepository;
    private final UserStatsService userStatsService;

    @GetMapping("/profile/stats")
    public String userStats(Model model, Principal principal) {
        User user = userRepository.findByEmail(principal.getName()).orElseThrow();

        // Appel méthode calcul des stats
        UserStatsDto stats = userStatsService.calculerStatistiquesJoueur(user);

        // Ajout des données au model
        model.addAttribute("stats", stats);
        model.addAttribute("prenom", user.getFirstName());

        return "user-stats";
    }
}
