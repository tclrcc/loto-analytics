package com.analyseloto.loto.service;

import com.analyseloto.loto.entity.LotoTirage;
import io.jenetics.*;
import io.jenetics.engine.Engine;
import io.jenetics.engine.EvolutionResult;
import io.jenetics.engine.Limits;
import io.jenetics.util.Factory;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

@Service
@Slf4j
public class BacktestService {

    static {
        System.setProperty("io.jenetics.util.defaultRandomGenerator", "Random");
    }

    private final LotoService lotoService;

    // --- CONSTANTES ---
    private static final int NB_GRILLES_PAR_TEST = 100;
    private static final int DEPTH_BACKTEST = 300;
    private static final int POPULATION_SIZE = 400;
    private static final int MAX_GENERATIONS = 50;
    private static final int TRAINING_BATCH_SIZE = 150;

    public BacktestService(@Lazy LotoService lotoService) {
        this.lotoService = lotoService;
    }

    public enum ExpertProfile {
        PRUDENT(0.2, 0.8, 0.1),
        EQUILIBRE(0.5, 0.5, 0.2),
        AGRESSIF(0.8, 0.2, 0.3),
        EXPLORATEUR(0.5, 0.5, 0.6);

        final double roiWeight;
        final double couvWeight;
        final double mutationRate;

        ExpertProfile(double rw, double cw, double mr) {
            this.roiWeight = rw;
            this.couvWeight = cw;
            this.mutationRate = mr;
        }
    }

    public List<LotoService.AlgoConfig> trouverMeilleuresConfigs(List<LotoTirage> historique) {
        log.info("🧬 Démarrage de la Méta-Optimisation Diversifiée (V3 Fix)...");
        long start = System.currentTimeMillis();

        List<LotoService.ScenarioSimulation> scenarios = lotoService.preparerScenariosBacktest(historique, 500, DEPTH_BACKTEST);
        if (scenarios.isEmpty()) {
            log.error("❌ Pas de scénarios disponibles pour le backtest !");
            return List.of(LotoService.AlgoConfig.defaut());
        }

        List<LotoService.AlgoConfig> ensembleFinal = new ArrayList<>();
        int nThreads = Math.max(1, Runtime.getRuntime().availableProcessors() - 1);
        ExecutorService executor = Executors.newFixedThreadPool(nThreads);

        // Définition du génome (correspondant aux 6 poids dans AlgoConfig)
        Factory<Genotype<DoubleGene>> gtf = Genotype.of(
                DoubleChromosome.of(0.0, 50.0),  // Poids FreqJour
                DoubleChromosome.of(0.0, 150.0), // Poids Forme
                DoubleChromosome.of(0.0, 50.0),  // Poids Ecart
                DoubleChromosome.of(0.0, 100.0), // Poids Tension
                DoubleChromosome.of(0.0, 100.0), // Poids Markov
                DoubleChromosome.of(0.0, 100.0)  // Poids Affinite
        );

        try {
            for (ExpertProfile profil : ExpertProfile.values()) {
                log.info("📡 Optimisation de l'école : {}", profil.name());

                AtomicInteger zeroGridCounter = new AtomicInteger(0);

                Function<Genotype<DoubleGene>, Double> fitnessFunc = gt -> evaluerFitness(gt, scenarios, profil, zeroGridCounter);

                Engine<DoubleGene, Double> engine = Engine.builder(fitnessFunc, gtf)
                        .populationSize(POPULATION_SIZE)
                        .executor(executor)
                        .alterers(
                                new GaussianMutator<>(profil.mutationRate),
                                new MeanAlterer<>(0.6)
                        )
                        .build();

                EvolutionResult<DoubleGene, Double> result = engine.stream()
                        .limit(Limits.bySteadyFitness(15))
                        .limit(MAX_GENERATIONS)
                        .peek(r -> {
                            if (r.generation() % 5 == 0) {
                                log.info("   [Gen {}] Fitness: {} | Warning 0 Grilles: {}/{}",
                                        r.generation(),
                                        String.format("%.2f", r.bestFitness()),
                                        zeroGridCounter.getAndSet(0),
                                        POPULATION_SIZE * 5
                                );
                            }
                        })
                        .collect(EvolutionResult.toBestEvolutionResult());

                LotoService.AlgoConfig bestConfig = decoderGenotype(result.bestPhenotype().genotype(), profil.name());

                // Recalcul du ROI pur pour l'affichage
                double roiPur = evaluerFitness(result.bestPhenotype().genotype(), scenarios, null, new AtomicInteger());
                bestConfig.setRoiEstime(roiPur);

                log.info("✅ Ecole {} terminée. Meilleur ROI pur : {}%", profil.name(), String.format("%.2f", roiPur));
                ensembleFinal.add(bestConfig);
            }

            log.info("🏆 Conseil des Sages prêt ({} experts) en {}s.", ensembleFinal.size(), (System.currentTimeMillis() - start) / 1000);
            return ensembleFinal;
        } finally {
            executor.shutdown();
        }
    }

    private double evaluerFitness(Genotype<DoubleGene> gt, List<LotoService.ScenarioSimulation> scenarios, ExpertProfile profil, AtomicInteger zeroGridMonitor) {
        LotoService.AlgoConfig config = decoderGenotype(gt, "TEST");

        double bilan = 0;
        double depense = 0;
        int totalGagnant = 0;
        int totalGrilles = 0;

        List<LotoService.ScenarioSimulation> batch = scenarios.subList(0, Math.min(scenarios.size(), TRAINING_BATCH_SIZE));

        for (int i = 0; i < batch.size(); i++) {
            LotoService.ScenarioSimulation sc = batch.get(i);
            List<int[]> grilles = lotoService.genererGrillesDepuisScenarioOptimise(sc, config, NB_GRILLES_PAR_TEST);

            if (grilles.isEmpty()) continue;

            depense += (grilles.size() * 2.20);
            totalGrilles += grilles.size();

            LotoTirage t = sc.getTirageReel();
            int[] tirageReel = {t.getBoule1(), t.getBoule2(), t.getBoule3(), t.getBoule4(), t.getBoule5()};
            int chanceReel = t.getNumeroChance();

            for (int[] g : grilles) {
                int matches = compteMatches(g, tirageReel);
                boolean chanceMatch = (g[5] == chanceReel);

                // NOUVEAU : On passe la grille pour évaluer son impopularité
                double gain = calculerGainRapide(matches, chanceMatch, g);
                bilan += gain;
                if (gain > 0) totalGagnant++;
            }

            if (profil != null && zeroGridMonitor.get() == 0 && i == 0) {
                log.info("🕵️ [DEBUG DATA] Target: {} | Joué: {} | Chance: {} vs {} | Matchs: {}",
                        java.util.Arrays.toString(tirageReel),
                        java.util.Arrays.toString(java.util.Arrays.copyOf(grilles.get(0), 5)),
                        chanceReel, grilles.get(0)[5],
                        compteMatches(grilles.get(0), tirageReel));
                zeroGridMonitor.incrementAndGet();
            }
        }

        if (depense == 0) {
            if (zeroGridMonitor.get() == 0) zeroGridMonitor.incrementAndGet();
            return -100.0 - (config.getPoidsFreqJour() + config.getPoidsEcart());
        }

        double roiPercent = ((bilan - depense) / depense) * 100.0;
        if (profil == null) return roiPercent;

        double couverture = (double) totalGagnant / totalGrilles;
        return (roiPercent * profil.roiWeight) + (couverture * 1000.0 * profil.couvWeight);
    }

    private int compteMatches(int[] grille, int[] tirage) {
        int m = 0;
        for(int i=0; i<5; i++) {
            for(int j=0; j<5; j++) {
                if(grille[i] == tirage[j]) {
                    m++;
                    break;
                }
            }
        }
        return m;
    }

    private double calculerGainRapide(int m, boolean c, int[] grille) {
        // Calcul du coefficient d'impopularité (Numéros > 31 correspondant aux dates de naissance)
        double multImpopularite = 1.0;
        int countSup31 = 0;
        for (int i = 0; i < 5; i++) {
            if (grille[i] > 31) countSup31++;
        }

        // Si la grille sort des sentiers battus, on simule un gain beaucoup plus élevé (moins de gagnants)
        if (countSup31 >= 3) multImpopularite = 1.5;
        if (countSup31 == 4) multImpopularite = 2.0;
        if (countSup31 == 5) multImpopularite = 3.5;

        if (m == 5 && c) return 100_000.0; // Le jackpot n'est pas multiplié (déjà énorme)
        if (m == 5) return 20_000.0 * multImpopularite;
        if (m == 4 && c) return 2000.0 * multImpopularite;
        if (m == 4) return 1000.0 * multImpopularite;
        if (m == 3 && c) return 150.0 * multImpopularite;
        if (m == 3) return 50.0 * multImpopularite;
        if (m == 2 && c) return 25.0; // Les petits rangs sont fixes FDJ
        if (m == 2) return 10.0;
        if (c) return 2.20;

        return 0.0;
    }

    private LotoService.AlgoConfig decoderGenotype(Genotype<DoubleGene> gt, String nom) {
        // Mapping dans l'ordre du constructeur de AlgoConfig
        // AlgoConfig(nom, pFreq, pForme, pEcart, pTens, pMark, pAff, gen)
        return new LotoService.AlgoConfig(nom,
                gt.get(0).get(0).doubleValue(), // Poids Freq
                gt.get(1).get(0).doubleValue(), // Poids Forme
                gt.get(2).get(0).doubleValue(), // Poids Ecart
                gt.get(3).get(0).doubleValue(), // Poids Tension
                gt.get(4).get(0).doubleValue(), // Poids Markov
                gt.get(5).get(0).doubleValue(), // Poids Affinite
                false);
    }
}
