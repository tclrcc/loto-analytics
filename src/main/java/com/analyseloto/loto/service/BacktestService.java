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
     * Recherche de la meilleure configuration : MODE "ORACLE" (Markov + Volume Titanesque)
     */
    public LotoService.AlgoConfig trouverMeilleureConfig(List<LotoTirage> historiqueComplet) {
        log.info("🧪 Démarrage de l'optimisation ORACLE (Cible: ~180 Millions de simulations)...");
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
        // 1. PUISSANCE MONSTRUEUSE : 300 Grilles / Tirage
        // -------------------------------------------------------------
        int nbGrillesParTest = 300;

        // -------------------------------------------------------------
        // 2. GÉNÉRATION DES CONFIGS + INJECTION DE MARKOV
        // -------------------------------------------------------------
        List<LotoService.AlgoConfig> configsATester = new ArrayList<>();

        int countId = 0;

        // FreqJour : 1.0, 3.0, 5.0 (3 steps)
        for (double freqJour = 1.0; freqJour <= 5.0; freqJour += 2.0) {

            // Forme : 14 à 17 (4 steps)
            for (double forme = 14.0; forme <= 17.0; forme += 1.0) {

                // Ecart : 1.6 à 1.9 (4 steps)
                for (double ecart = 1.6; ecart <= 1.9; ecart += 0.1) {

                    // Affinité : 6.0 à 9.0 (4 steps)
                    for (double affinite = 6.0; affinite <= 9.0; affinite += 1.0) {

                        // Tension : 15.0 à 25.0 (3 steps)
                        for (double tension = 15.0; tension <= 25.0; tension += 5.0) {

                            // --- NOUVEAU : Test du Poids MARKOV (Transitions) ---
                            // 0.0, 5.0, 10.0 (3 steps)
                            for (double markov = 0.0; markov <= 10.0; markov += 5.0) {

                                configsATester.add(new LotoService.AlgoConfig(
                                        "ORACLE_" + (++countId), freqJour, forme, ecart, tension, markov, affinite, false
                                ));
                            }
                        }
                    }
                }
            }
        }

        // Total Configs = 3 * 4 * 4 * 4 * 3 * 3 = 1728 configs.
        // Grilles générées = 1728 configs * 350 tirages * 300 grilles = ~181,440,000 de grilles.
        log.info("📊 Analyse de {} stratégies (INCLUANT MARKOV) sur {} grilles chacune...", configsATester.size(), nbGrillesParTest);

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
                    log.info("🚀 Record : {} € (Mk={}, Freq={}, F={}, E={}, Aff={}, Tens={})",
                            String.format("%.2f", net),
                            config.getPoidsMarkov(), config.getPoidsFreqJour(), config.getPoidsForme(), config.getPoidsEcart(), config.getPoidsAffinite(), config.getPoidsTension());
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
