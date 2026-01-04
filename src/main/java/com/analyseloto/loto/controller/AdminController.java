package com.analyseloto.loto.controller;

import com.analyseloto.loto.service.JobMonitorService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * Controlleur gérant les actions disponibles seulement aux ADMIN
 */
@Controller
@RequestMapping("/admin")
@RequiredArgsConstructor
public class AdminController {
    // Services
    private final JobMonitorService jobMonitorService;

    /**
     * Affichage de la page admin
     * @param model model
     * @return
     */
    @GetMapping
    public String adminPage(Model model) {
        // On récupère l'historique des jobs
        model.addAttribute("jobLogs", jobMonitorService.getHistory50Jobs());
        return "admin";
    }
}