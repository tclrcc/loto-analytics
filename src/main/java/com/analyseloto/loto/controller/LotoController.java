package com.analyseloto.loto.controller;

import com.analyseloto.loto.dto.StatsReponse;
import com.analyseloto.loto.dto.TirageManuelDto;
import com.analyseloto.loto.service.LotoService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@RestController
@RequestMapping("/api/loto")
@RequiredArgsConstructor
public class LotoController {
    private final LotoService service;

    @PostMapping("/import")
    public ResponseEntity<String> uploadFile(@RequestParam("file") MultipartFile file) {
        try {
            service.importCsv(file);
            return ResponseEntity.ok("Import réussi !");
        } catch (Exception e) {
            return ResponseEntity.status(500).body("Erreur: " + e.getMessage());
        }
    }

    @GetMapping("/stats")
    public StatsReponse getStats(@RequestParam(required = false) String jour) {
        return service.getStats(jour);
    }

    @PostMapping("/add")
    public ResponseEntity<String> ajouterManuel(@RequestBody TirageManuelDto dto) {
        try {
            service.ajouterTirageManuel(dto);
            return ResponseEntity.ok("Tirage ajouté avec succès !");
        } catch (Exception e) {
            return ResponseEntity.badRequest().body("Erreur : " + e.getMessage());
        }
    }
}
