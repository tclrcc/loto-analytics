package com.analyseloto.loto.controller;

import com.analyseloto.loto.service.JobMonitorService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

@Controller
@RequestMapping("/admin")
@RequiredArgsConstructor
public class AdminController {
    private final JobMonitorService jobMonitorService;

    @GetMapping
    public String adminPage(Model model) {
        // On récupère l'historique des jobs
        model.addAttribute("jobLogs", jobMonitorService.getHistory());
        return "admin";
    }
}