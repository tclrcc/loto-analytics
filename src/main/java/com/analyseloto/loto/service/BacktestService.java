package com.analyseloto.loto.service;

import com.analyseloto.loto.entity.LotoTirage;
import io.jenetics.*;
import io.jenetics.engine.Engine;
import io.jenetics.engine.EvolutionResult;
import io.jenetics.util.Factory;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Service
@Slf4j
public class BacktestService {
    // --- BLOC DE SÉCURITÉ ---
    // On force l'utilisation du générateur standard "Random" (java.util.Random)
    // dès le chargement de la classe pour éviter le crash "No implementation found".
    static {
        System.setProperty("io.jenetics.util.defaultRandomGenerator", "Random");
    }

    // Services
    private final LotoService lotoService;

    // --- CONSTANTES OPTIMISÉES VPS-2 (6 vCores / 12Go RAM) ---
    private static final int NB_GRILLES_PAR_TEST = 200;
    private static final int DEPTH_BACKTEST = 450;
    private static final int POPULATION_SIZE = 1500;
    private static final int MAX_GENERATIONS = 100;

    // NOUVEAU : Taille du batch d'entrainement (Plus grand = Plus stable)
    private static final int TRAINING_BATCH_SIZE = 400;

    public BacktestService(@Lazy LotoService lotoService) {
        this.lotoService = lotoService;
    }

    /**
     * Recherche de la meilleure configuration de l'algorithme
     */
    // DANS BacktestService.java

    public List<LotoService.AlgoConfig> trouverMeilleuresConfigs(List<LotoTirage> historique) {
        log.info("🧬 Démarrage de la Méta-Optimisation IA (Zero-Boxing & Multi-Objective)...");
        long start = System.currentTimeMillis();

        List<LotoService.ScenarioSimulation> scenarios = lotoService.preparerScenariosBacktest(historique, 500, DEPTH_BACKTEST);
        if (scenarios.isEmpty()) return List.of(LotoService.AlgoConfig.defaut());

        int nThreads = Math.max(1, Runtime.getRuntime().availableProcessors() - 1);
        ExecutorService executor = Executors.newFixedThreadPool(nThreads);

        Factory<Genotype<DoubleGene>> gtf = Genotype.of(
                DoubleChromosome.of(0.0, 30.0), DoubleChromosome.of(0.0, 100.0), // Freq, Forme
                DoubleChromosome.of(0.0, 30.0), DoubleChromosome.of(0.0, 80.0),  // Ecart, Tension
                DoubleChromosome.of(0.0, 80.0), DoubleChromosome.of(0.0, 80.0)   // Markov, Affinité
        );

        try {
            Engine<DoubleGene, Double> engine = Engine.builder(gt -> evaluerFitness(gt, scenarios, true), gtf)
                    .populationSize(POPULATION_SIZE)
                    .executor(executor)
                    .alterers(new GaussianMutator<>(0.2), new MeanAlterer<>(0.6))
                    .build();

            EvolutionResult<DoubleGene, Double> result = engine.stream()
                    .limit(MAX_GENERATIONS)
                    .collect(EvolutionResult.toBestEvolutionResult());

            // Extraction des 20 experts pour l'Ensemble
            List<LotoService.AlgoConfig> topConfigs = result.population().stream()
                    .sorted((p1, p2) -> Double.compare(p2.fitness(), p1.fitness()))
                    .limit(20)
                    .map(p -> decoderGenotype(p.genotype(), "EXPERT_" + p.fitness()))
                    .toList();

            log.info("🏆 Optimisation terminée en {}s. 20 experts prêts.", (System.currentTimeMillis() - start) / 1000);
            return topConfigs;
        } finally {
            executor.shutdown();
        }
    }

    private double evaluerFitness(Genotype<DoubleGene> gt, List<LotoService.ScenarioSimulation> scenarios, boolean training) {
        LotoService.AlgoConfig config = decoderGenotype(gt, "TEST");
        double bilan = 0; double depense = 0;
        int totalGagnant = 0; int totalGrilles = 0;

        List<LotoService.ScenarioSimulation> batch = training ? scenarios.subList(0, Math.min(scenarios.size(), TRAINING_BATCH_SIZE)) : scenarios;

        for (LotoService.ScenarioSimulation sc : batch) {
            // APPEL DE LA NOUVELLE MÉTHODE OPTIMISÉE
            List<int[]> grilles = lotoService.genererGrillesDepuisScenarioOptimise(sc, config, NB_GRILLES_PAR_TEST);

            depense += (grilles.size() * 2.20);
            totalGrilles += grilles.size();

            LotoTirage t = sc.getTirageReel();
            int b1 = t.getBoule1(); int b2 = t.getBoule2(); int b3 = t.getBoule3();
            int b4 = t.getBoule4(); int b5 = t.getBoule5(); int bc = t.getNumeroChance();

            for (int[] g : grilles) {
                int matches = 0;
                // Zero-Boxing : Accès direct aux primitives (g[0] à g[4] sont les boules, g[5] la chance)
                if (g[0]==b1 || g[0]==b2 || g[0]==b3 || g[0]==b4 || g[0]==b5) matches++;
                if (g[1]==b1 || g[1]==b2 || g[1]==b3 || g[1]==b4 || g[1]==b5) matches++;
                if (g[2]==b1 || g[2]==b2 || g[2]==b3 || g[2]==b4 || g[2]==b5) matches++;
                if (g[3]==b1 || g[3] == b2 || g[3] == b3 || g[3] == b4 || g[3] == b5) matches++;
                if (g[4]==b1 || g[4] == b2 || g[4] == b3 || g[4] == b4 || g[4] == b5) matches++;
                boolean chanceMatch = (g[5] == bc);

                double gain = calculerGainRapide(matches, chanceMatch, training);
                if (gain > 0) {
                    bilan += gain;
                    totalGagnant++;
                }
            }
        }

        if (depense == 0) return -1000.0;
        double roi = ((bilan - depense) / depense) * 100.0;

        if (training) {
            // FORMULE MULTI-OBJECTIF (Astuce 2)
            double couverture = (double) totalGagnant / totalGrilles;
            double penaliteVol = Math.log10(depense) * 5.0;
            // On veut du ROI ET de la couverture (remboursements réguliers)
            return roi + (couverture * 100.0) - penaliteVol;
        }
        return roi;
    }

    private double calculerGainRapide(int m, boolean c, boolean training) {
        if (m == 5) return c ? (training ? 50000 : 2000000) : (training ? 20000 : 100000);
        if (m == 4) return c ? 1000 : 400;
        if (m == 3) return c ? 50 : 20;
        if (m == 2) return c ? 10 : 5;
        if (c) return 2.20;
        return 0;
    }

    private LotoService.AlgoConfig decoderGenotype(Genotype<DoubleGene> gt, String nom) {
        return new LotoService.AlgoConfig(nom,
                gt.get(0).get(0).doubleValue(), gt.get(1).get(0).doubleValue(),
                gt.get(2).get(0).doubleValue(), gt.get(3).get(0).doubleValue(),
                gt.get(4).get(0).doubleValue(), gt.get(5).get(0).doubleValue(), false);
    }
}
