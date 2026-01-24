package com.analyseloto.loto.service;

import com.analyseloto.loto.entity.LotoTirage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
@Slf4j
public class BacktestService {
    private final LotoService lotoService;

    public BacktestService(@Lazy LotoService lotoService) {
        this.lotoService = lotoService;
    }

    /**
     * Recherche de la meilleure configuration de l'algorithme : MODE SNIPER
     */
    public LotoService.AlgoConfig trouverMeilleureConfig(List<LotoTirage> historiqueComplet) {
        log.info("🧪 Démarrage de l'optimisation SNIPER (100 millions de simulations)...");
        long start = System.currentTimeMillis();

        // 1. PRÉ-CALCUL DES SCÉNARIOS (350 derniers tirages = 2.5 ans)
        int depthBacktest = 350;
        log.info("📸 Pré-calcul des snapshots sur {} tirages...", depthBacktest);

        List<LotoService.ScenarioSimulation> scenarios = lotoService.preparerScenariosBacktest(historiqueComplet, depthBacktest, 250);

        if (scenarios.isEmpty()) {
            log.warn("Pas assez d'historique. Retour config défaut.");
            return LotoService.AlgoConfig.defaut();
        }
        log.info("✅ {} Scénarios prêts en mémoire.", scenarios.size());

        // -------------------------------------------------------------
        // 2. RÉGLAGE DE LA PUISSANCE DE FEU (Grâce à l'optimisation)
        // -------------------------------------------------------------
        // On double la précision : 100 grilles simulées par tirage historique
        int nbGrillesParTest = 100;

        // -------------------------------------------------------------
        // 3. GÉNÉRATION CIBLÉE DES CONFIGS (Smart Range)
        // -------------------------------------------------------------
        List<LotoService.AlgoConfig> configsATester = new ArrayList<>();

        // On a vu que Forme ~12.5-17.5 et Ecart ~1.6-1.8 fonctionnaient bien.
        // On va scanner CETTE zone au microscope.

        int countId = 0;

        // Forme : De 10 à 20 par pas de 1 (Très précis) -> 11 steps
        for (double forme = 10.0; forme <= 20.0; forme += 1.0) {

            // Ecart : De 1.4 à 2.0 par pas de 0.1 -> 7 steps
            for (double ecart = 1.4; ecart <= 2.0; ecart += 0.1) {

                // Affinité : De 0 à 10 par pas de 1 -> 11 steps
                for (double affinite = 0.0; affinite <= 10.0; affinite += 1.0) {

                    // Tension : 0, 15, 30 -> 3 steps
                    for (double tension = 0.0; tension <= 30.0; tension += 15.0) {

                        configsATester.add(new LotoService.AlgoConfig(
                                "SNIPER_" + (++countId), 3.0, forme, ecart, tension, 0.0, affinite, false
                        ));
                    }
                }
            }
        }

        // Total Configs = 11 * 7 * 11 * 3 = 2541 configs.
        // Grilles générées = 2541 configs * 350 tirages * 100 grilles = ~89 Millions de grilles.
        log.info("📊 Analyse de {} stratégies haute-précision sur {} grilles chacune...", configsATester.size(), nbGrillesParTest);

        // 4. BACKTEST PARALLÈLE
        final var bestResultRef = new Object() {
            LotoService.AlgoConfig config = LotoService.AlgoConfig.defaut();
            double maxBilan = -Double.MAX_VALUE;
        };

        // Utilisation de parallelStream pour saturer le CPU
        configsATester.parallelStream().forEach(config -> {
            double bilan = 0;
            double depense = 0;

            for (LotoService.ScenarioSimulation scenar : scenarios) {

                List<List<Integer>> grilles = lotoService.genererGrillesDepuisScenario(scenar, config, nbGrillesParTest);

                depense += (grilles.size() * 2.20);

                for (List<Integer> g : grilles) {
                    bilan += calculerGainRapide(g, scenar.getTirageReel());
                }
            }

            double net = bilan - depense;

            synchronized (bestResultRef) {
                if (net > bestResultRef.maxBilan) {
                    bestResultRef.maxBilan = net;
                    bestResultRef.config = config;
                    log.info("🚀 Record : {} € (F={}, E={}, Aff={}, Tens={})",
                            String.format("%.2f", net),
                            config.getPoidsForme(), config.getPoidsEcart(), config.getPoidsAffinite(), config.getPoidsTension());
                }
            }
        });

        long duration = System.currentTimeMillis() - start;

        // Configuration gagnante finale
        LotoService.AlgoConfig gagnante = bestResultRef.config;
        gagnante.setBilanEstime(bestResultRef.maxBilan);
        gagnante.setNbTiragesTestes(depthBacktest);

        log.info("🏁 Terminé en {} ms. Config gagnante : {} (Bilan: {} €)",
                duration, gagnante.getNomStrategie(), String.format("%.2f", gagnante.getBilanEstime()));

        return gagnante;
    }

    private double calculerGainRapide(List<Integer> grille, LotoTirage t) {
        if (grille.size() < 6) return 0.0;

        List<Integer> boulesJouees = grille.subList(0, 5);
        int chanceJouee = grille.get(5);

        // Comptage rapide des bons numéros
        long bonsNumeros = boulesJouees.stream().filter(t.getBoules()::contains).count();
        boolean bonneChance = (chanceJouee == t.getNumeroChance());

        // --- Grille des Gains (Approximation FDJ) ---
        if (bonsNumeros == 5) return bonneChance ? 2_000_000.0 : 100_000.0;
        if (bonsNumeros == 4) return bonneChance ? 1_000.0 : 500.0;
        if (bonsNumeros == 3) return bonneChance ? 50.0 : 20.0;
        if (bonsNumeros == 2) return bonneChance ? 10.0 : 5.0;
        if (bonneChance) return 2.20;

        return 0.0;
    }
}
