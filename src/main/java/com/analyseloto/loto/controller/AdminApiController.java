package com.analyseloto.loto.controller;

import com.analyseloto.loto.job.LotoJob;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
public class AdminApiController {
    private final LotoJob lotoJob;

    @PostMapping("/trigger-fdj")
    public ResponseEntity<String> triggerFdjSync() {
        // On appelle manuellement le job de récupération FDJ
        lotoJob.triggerRecupererResultatsFdj();

        return ResponseEntity.ok("Le job de récupération FDJ a été exécuté.");
    }
}
