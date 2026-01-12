package com.analyseloto.loto.controller;

import com.analyseloto.loto.job.LotoJob;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
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

    @PostMapping("/trigger-prono")
    public ResponseEntity<String> triggerPronoGen(@RequestParam(defaultValue = "false") boolean force) {
        // On lance le traitement dans un thread séparé pour ne pas bloquer l'IHM
        new Thread(() -> lotoJob.executerGenerationPronostics(force)).start();

        return ResponseEntity.ok("Génération des pronostics lancée (Force=" + force + ").");
    }
}
