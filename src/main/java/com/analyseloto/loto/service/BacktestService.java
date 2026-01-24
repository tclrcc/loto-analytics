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
     * Recherche de la meilleure configuration : MODE "DEEP BLUE" (Volume Extrême)
     */
    public LotoService.AlgoConfig trouverMeilleureConfig(List<LotoTirage> historiqueComplet) {
        log.info("🧪 Démarrage de l'optimisation DEEP BLUE (Volume Extrême)...");
        long start = System.currentTimeMillis();

        int depthBacktest = 350;
        log.info("📸 Pré-calcul des snapshots sur {} tirages...", depthBacktest);

        List<LotoService.ScenarioSimulation> scenarios = lotoService.preparerScenariosBacktest(historiqueComplet, depthBacktest, 250);

        if (scenarios.isEmpty()) {
            log.warn("Pas assez d'historique. Retour config défaut.");
            return LotoService.AlgoConfig.defaut();
        }
        log.info("✅ {} Scénarios prêts en mémoire.", scenarios.size());

        // -------------------------------------------------------------
        // 1. PUISSANCE MAXIMALE : 200 Grilles / Tirage
        // -------------------------------------------------------------
        int nbGrillesParTest = 200;

        // -------------------------------------------------------------
        // 2. GÉNÉRATION HYPER-CIBLÉE + NOUVELLE VARIABLE (FreqJour)
        // -------------------------------------------------------------
        List<LotoService.AlgoConfig> configsATester = new ArrayList<>();

        int countId = 0;

        // NOUVEAU : On teste l'impact du Jour (Lundi/Merc/Sam)
        for (double freqJour = 1.0; freqJour <= 5.0; freqJour += 2.0) {

            // Forme : Autour de 14.0 (12 à 16)
            for (double forme = 12.0; forme <= 16.0; forme += 1.0) {

                // Ecart : Autour de 1.7 (1.5 à 1.9)
                for (double ecart = 1.5; ecart <= 1.9; ecart += 0.1) {

                    // Affinité : Autour de 5.0 (3.0 à 7.0)
                    for (double affinite = 3.0; affinite <= 7.0; affinite += 1.0) {

                        // Tension : Autour de 15.0 (10.0 à 20.0)
                        for (double tension = 10.0; tension <= 20.0; tension += 5.0) {

                            configsATester.add(new LotoService.AlgoConfig(
                                    "DEEP_" + (++countId), freqJour, forme, ecart, tension, 0.0, affinite, false
                            ));
                        }
                    }
                }
            }
        }

        // Total Configs = 3 * 5 * 5 * 5 * 3 = 1125 configs.
        // Grilles générées = 1125 configs * 350 tirages * 200 grilles = ~78 Millions de grilles.
        log.info("📊 Analyse de {} stratégies à volume extrême sur {} grilles chacune...", configsATester.size(), nbGrillesParTest);

        // 3. BACKTEST PARALLÈLE
        final var bestResultRef = new Object() {
            LotoService.AlgoConfig config = LotoService.AlgoConfig.defaut();
            double maxBilan = -Double.MAX_VALUE;
        };

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
                    log.info("🚀 Record : {} € (Freq={}, F={}, E={}, Aff={}, Tens={})",
                            String.format("%.2f", net),
                            config.getPoidsFreqJour(), config.getPoidsForme(), config.getPoidsEcart(), config.getPoidsAffinite(), config.getPoidsTension());
                }
            }
        });

        long duration = System.currentTimeMillis() - start;

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
        long bonsNumeros = boulesJouees.stream().filter(t.getBoules()::contains).count();
        boolean bonneChance = (chanceJouee == t.getNumeroChance());

        if (bonsNumeros == 5) return bonneChance ? 2_000_000.0 : 100_000.0;
        if (bonsNumeros == 4) return bonneChance ? 1_000.0 : 500.0;
        if (bonsNumeros == 3) return bonneChance ? 50.0 : 20.0;
        if (bonsNumeros == 2) return bonneChance ? 10.0 : 5.0;
        if (bonneChance) return 2.20;

        return 0.0;
    }
}
