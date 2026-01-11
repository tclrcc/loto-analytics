package com.analyseloto.loto.controller;

import com.analyseloto.loto.service.LotoService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

@Controller
@RequestMapping("/stats")
@RequiredArgsConstructor
public class StatsController {
    private final LotoService lotoService;

    @GetMapping
    public String statsPage(Model model) {
        // On récupère les stats globales
        var stats = lotoService.getStats(null); // null = tous les jours

        model.addAttribute("stats", stats);
        // On passe aussi la matrice des affinités pour la Heatmap
        model.addAttribute("matrice", lotoService.getMatriceAffinitesPublic());

        return "stats"; // Pointe vers templates/stats.html
    }
}
