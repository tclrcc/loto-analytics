package com.analyseloto.loto.controller;

import com.analyseloto.loto.dto.*;
import com.analyseloto.loto.service.LotoService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

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

    @PostMapping("/simuler")
    public ResponseEntity<SimulationResultDto> simuler(@RequestBody SimuRequest req) {
        // On accepte maintenant entre 2 et 5 numéros
        if (req.getBoules() == null || req.getBoules().size() < 2 || req.getBoules().size() > 5 || req.getDate() == null) {
            return ResponseEntity.badRequest().build();
        }
        return ResponseEntity.ok(service.simulerGrilleDetaillee(req.getBoules(), req.getDate()));
    }

    @GetMapping("/generate")
    public ResponseEntity<List<PronosticResultDto>> generateGrid(
            @RequestParam("date") String dateStr,
            @RequestParam(value = "count", defaultValue = "1") int count) {

        LocalDate date = LocalDate.parse(dateStr);
        return ResponseEntity.ok(service.genererMultiplesPronostics(date, count));
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
