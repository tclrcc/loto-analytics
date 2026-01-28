package com.analyseloto.loto.controller;

import com.analyseloto.loto.entity.JobLog;
import com.analyseloto.loto.service.JobMonitorService;
import com.analyseloto.loto.service.LotoService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseBody;

import java.util.List;

/**
 * Controlleur gérant les actions disponibles seulement aux ADMIN
 */
@Controller
@RequestMapping("/admin")
@RequiredArgsConstructor
public class AdminController {
    // Services
    private final JobMonitorService jobMonitorService;
    private final LotoService lotoService;

    /**
     * Affichage de la page admin
     * @param model model
     * @return
     */
    @GetMapping
    public String adminPage(Model model) {
        // Par défaut, on ne charge que les jobs des dernières 48h
        List<JobLog> recentJobs= jobMonitorService.getRecentJobs(48);
        model.addAttribute("jobLogs", recentJobs);

        // On récupère les futurs jobs planifiés
        model.addAttribute("upcomingJobs", jobMonitorService.getUpcomingJobs());

        return "admin";
    }

    @GetMapping("/history/all")
    @ResponseBody
    public List<JobLog> getAllHistory() {
        return jobMonitorService.getHistory();
    }
}
