package com.analyseloto.loto.controller;

import com.analyseloto.loto.dto.PronosticResultDto;
import com.analyseloto.loto.dto.StatsReponse;
import com.analyseloto.loto.entity.LotoTirage;
import com.analyseloto.loto.entity.User;
import com.analyseloto.loto.entity.UserBet;
import com.analyseloto.loto.repository.LotoTirageRepository;
import com.analyseloto.loto.repository.UserRepository;
import com.analyseloto.loto.service.LotoService;
import com.analyseloto.loto.service.UserBetService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

import java.security.Principal;
import java.time.LocalDate;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

@Controller
@RequiredArgsConstructor
public class HomeController {
    // Repositories
    private final UserRepository userRepository;
    private final LotoTirageRepository lotoTirageRepository;
    // Services
    private final LotoService lotoService;
    private final UserBetService userBetService;

    /**
     * Affichage de la page d'accueil
     * @param model model
     * @param principal utilisateur
     * @return Page d'accueil
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

        // --- 2. AJOUT : RÉCUPÉRATION DES STATS GLOBALES (Widget Dashboard) ---
        StatsReponse globalStats = lotoService.getStats(null);
        model.addAttribute("globalStats", globalStats);

        // Pré-génération des pronostics du prochain tirage
        try {
            // 1. Calculer la date du prochain tirage
            LocalDate nextDrawDate = lotoService.recupererDateProchainTirage();

            // 2. Générer CINQ grilles optimisées
            // Comme le WarmUp a tourné au démarrage, ce sera instantané !
            List<PronosticResultDto> aiBets = lotoService.genererMultiplesPronostics(nextDrawDate, 5);

            model.addAttribute("aiBets", aiBets);
            model.addAttribute("nextDrawDate", nextDrawDate); // Pour afficher la date en front

        } catch (Exception e) {
            // Si l'IA est en train de calculer ou erreur, on ne bloque pas la page
            model.addAttribute("aiBets", new ArrayList<>());
        }

        // Récupération des grilles du joueur
        List<UserBet> userBets = userBetService.recupererGrillesUtilisateurTriees(user);
        model.addAttribute("bets", userBets);

        // On récupère toutes les dates uniques jouées par l'utilisateur
        Set<LocalDate> datesJouees = userBets.stream()
                .map(UserBet::getDateJeu)
                .collect(Collectors.toSet());

        // On recherche les résultats officiels pour ces dates
        List<LotoTirage> tiragesOfficiels = lotoTirageRepository.findByDateTirageIn(datesJouees);
        // On transforme la liste en Map
        Map<LocalDate, LotoTirage> resultsMap = tiragesOfficiels.stream()
                .collect(Collectors.toMap(LotoTirage::getDateTirage, Function.identity()));
        // Ajout de map au modèle
        model.addAttribute("draws", resultsMap);

        // Remplissage du bilan IA
        userBetService.remplirBilanUserIa(model);

        // Données pour le pré-remplissage Astro
        model.addAttribute("birthDate", user.getBirthDate());
        model.addAttribute("birthTime", user.getBirthTime());
        model.addAttribute("birthCity", user.getBirthCity());

        return "index";
    }
}
