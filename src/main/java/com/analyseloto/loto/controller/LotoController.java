package com.analyseloto.loto.controller;

import com.analyseloto.loto.dto.*;
import com.analyseloto.loto.service.AstroService;
import com.analyseloto.loto.service.LotoService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/loto")
@RequiredArgsConstructor
public class LotoController {
    private final LotoService service;
    private final AstroService astroService;

    @PostMapping("/import")
    public ResponseEntity<String> uploadFile(@RequestParam("file") MultipartFile file) {
        try {
            service.importCsv(file);
            return ResponseEntity.ok("Import réussi !");
        } catch (Exception e) {
            return ResponseEntity.status(500).body("Erreur: " + e.getMessage());
        }
    }

    @PostMapping("/astro")
    public ResponseEntity<AstroResultDto> getAstro(@RequestBody AstroProfileDto dto) {
        return ResponseEntity.ok(astroService.analyserProfil(dto));
    }

    @PostMapping("/generate-hybrid")
    public ResponseEntity<List<PronosticResultDto>> generateHybrid(
            @RequestParam("date") String dateStr,
            @RequestParam(value = "count", defaultValue = "5") int count,
            @RequestBody AstroProfileDto profil
    ) {
        LocalDate date = LocalDate.parse(dateStr);
        return ResponseEntity.ok(service.genererPronosticsHybrides(date, count, profil));
    }

    @GetMapping("/comparer-algos")
    @ResponseBody // Pour renvoyer du JSON brut facile à lire
    public Map<String, List<PronosticResultDto>> comparerAlgorithmes(
            @RequestParam(required = false) String date) {

        LocalDate dateCible = (date != null) ? LocalDate.parse(date) : LocalDate.now();
        return service.comparerAlgorithmes(dateCible);
    }

    @GetMapping("/graph-data")
    public ResponseEntity<GraphDto> getGraphData() {
        // 1. Récupérer les stats brutes pour la taille des nœuds
        StatsReponse stats = service.getStats(null);
        List<GraphDto.Node> nodes = new ArrayList<>();

        // Création des Nœuds (Boules 1-49)
        for (LotoService.StatPoint p : stats.getPoints()) {
            if (!p.isChance()) {
                // Taille basée sur la fréquence
                nodes.add(new GraphDto.Node(p.getNumero(), String.valueOf(p.getNumero()), p.getFrequence(), "#4F46E5"));
            }
        }

        // 2. Récupérer la matrice d'affinité pour les Liens
        Map<Integer, Map<Integer, Integer>> matrix = service.getMatriceAffinitesPublic();
        List<GraphDto.Edge> edges = new ArrayList<>();

        // Création des Liens (On ne garde que les liens forts pour éviter un fouillis)
        for (Integer source : matrix.keySet()) {
            Map<Integer, Integer> cibles = matrix.get(source);
            for (Map.Entry<Integer, Integer> entry : cibles.entrySet()) {
                Integer target = entry.getKey();
                Integer weight = entry.getValue();

                // Règle : On affiche le lien seulement si source < target (pour éviter les doublons A-B et B-A)
                if (source < target && weight > 25) {
                    edges.add(new GraphDto.Edge(source, target, weight));
                }
            }
        }

        return ResponseEntity.ok(new GraphDto(nodes, edges));
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

    @PostMapping("/add-result")
    public ResponseEntity<String> ajouterManuel(@RequestBody TirageManuelDto dto) {
        try {
            service.ajouterTirageManuel(dto);
            return ResponseEntity.ok("Tirage ajouté avec succès !");
        } catch (Exception e) {
            return ResponseEntity.badRequest().body("Erreur : " + e.getMessage());
        }
    }
}
