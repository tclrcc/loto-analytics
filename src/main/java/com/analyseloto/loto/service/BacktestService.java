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
import java.util.function.Function;

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

    public enum ExpertProfile {
        PRUDENT(0.2, 0.8, 0.1),    // ROI weight, Couverture weight, Mutation rate
        EQUILIBRE(0.5, 0.5, 0.2),
        AGRESSIF(0.8, 0.2, 0.3),
        EXPLORATEUR(0.5, 0.5, 0.6); // Mutation très forte

        final double roiWeight;
        final double couvWeight;
        final double mutationRate;

        ExpertProfile(double rw, double cw, double mr) {
            this.roiWeight = rw;
            this.couvWeight = cw;
            this.mutationRate = mr;
        }
    }

    /**
     * Recherche de la meilleure configuration de l'algorithme
     */
    public List<LotoService.AlgoConfig> trouverMeilleuresConfigs(List<LotoTirage> historique) {
        log.info("🧬 Démarrage de la Méta-Optimisation Diversifiée...");
        long start = System.currentTimeMillis();

        List<LotoService.ScenarioSimulation> scenarios = lotoService.preparerScenariosBacktest(historique, 500, DEPTH_BACKTEST);
        if (scenarios.isEmpty()) return List.of(LotoService.AlgoConfig.defaut());

        List<LotoService.AlgoConfig> ensembleFinal = new ArrayList<>();
        int nThreads = Math.max(1, Runtime.getRuntime().availableProcessors() - 1);
        ExecutorService executor = Executors.newFixedThreadPool(nThreads);

        // Définition du génome
        Factory<Genotype<DoubleGene>> gtf = Genotype.of(
                DoubleChromosome.of(0.0, 30.0), DoubleChromosome.of(0.0, 100.0),
                DoubleChromosome.of(0.0, 30.0), DoubleChromosome.of(0.0, 80.0),
                DoubleChromosome.of(0.0, 80.0), DoubleChromosome.of(0.0, 80.0)
        );

        try {
            for (ExpertProfile profil : ExpertProfile.values()) {
                log.info("📡 Optimisation de l'école : {}", profil.name());

                int adaptivePop = (profil == ExpertProfile.AGRESSIF || profil == ExpertProfile.EXPLORATEUR)
                        ? POPULATION_SIZE / 3
                        : POPULATION_SIZE / 5;

                // Correction "Ambiguous method call" : on définit la fonction explicitement
                Function<Genotype<DoubleGene>, Double> fitnessFunc = gt -> evaluerFitness(gt, scenarios, profil);

                Engine<DoubleGene, Double> engine = Engine.builder(fitnessFunc, gtf)
                        .populationSize(adaptivePop)
                        .executor(executor)
                        .alterers(
                                new GaussianMutator<>(profil.mutationRate),
                                new MeanAlterer<>(0.6)
                        )
                        .build();

                EvolutionResult<DoubleGene, Double> result = engine.stream()
                        .limit(Limits.bySteadyFitness(profil == ExpertProfile.EXPLORATEUR ? 15 : 10))
                        .limit(MAX_GENERATIONS / 2)
                        .peek(r -> {
                            if (r.generation() % 5 == 0) {
                                log.info("   [Gen {}] Meilleure Fitness ({}) : {}", r.generation(), profil.name(), String.format("%.2f", r.bestFitness()));
                            }
                        })
                        .collect(EvolutionResult.toBestEvolutionResult());

                // EXTRACTION ET CALCUL DU ROI RÉEL POUR LE LOG
                List<LotoService.AlgoConfig> topProfil = result.population().stream()
                        .sorted((p1, p2) -> Double.compare(p2.fitness(), p1.fitness()))
                        .limit(5)
                        .map(p -> {
                            LotoService.AlgoConfig cfg = decoderGenotype(p.genotype(), profil.name() + "_" + String.format("%.1f", p.fitness()));

                            // IMPORTANT : On recalcule le ROI pur (sans les bonus de couverture) pour le stockage et le log
                            double roiPur = evaluerFitness(p.genotype(), scenarios, null); // profil null = ROI pur dans notre nouvelle logique
                            cfg.setRoiEstime(roiPur);
                            return cfg;
                        })
                        .toList();

                log.info("✅ Ecole {} terminée. Meilleur ROI pur détecté : {}%", profil.name(), String.format("%.2f", topProfil.get(0).getRoiEstime()));
                ensembleFinal.addAll(topProfil);
            }

            log.info("🏆 Conseil des Sages prêt ({} experts) en {}s.", ensembleFinal.size(), (System.currentTimeMillis() - start) / 1000);
            return ensembleFinal;
        } finally {
            executor.shutdown();
        }
    }

    private double evaluerFitness(Genotype<DoubleGene> gt, List<LotoService.ScenarioSimulation> scenarios, ExpertProfile profil) {
        LotoService.AlgoConfig config = decoderGenotype(gt, "TEST");
        double bilan = 0;
        double depense = 0;
        int totalGagnant = 0;
        int totalGrilles = 0;

        // On utilise un sous-échantillon pour l'entraînement
        List<LotoService.ScenarioSimulation> batch = scenarios.subList(0, Math.min(scenarios.size(), TRAINING_BATCH_SIZE));

        for (LotoService.ScenarioSimulation sc : batch) {
            List<int[]> grilles = lotoService.genererGrillesDepuisScenarioOptimise(sc, config, NB_GRILLES_PAR_TEST);

            depense += (grilles.size() * 2.20);
            totalGrilles += grilles.size();

            LotoTirage t = sc.getTirageReel();
            int b1 = t.getBoule1(); int b2 = t.getBoule2(); int b3 = t.getBoule3();
            int b4 = t.getBoule4(); int b5 = t.getBoule5(); int bc = t.getNumeroChance();

            for (int[] g : grilles) {
                int matches = 0;
                if (g[0]==b1 || g[0]==b2 || g[0]==b3 || g[0]==b4 || g[0]==b5) matches++;
                if (g[1]==b1 || g[1]==b2 || g[1]==b3 || g[1]==b4 || g[1]==b5) matches++;
                if (g[2]==b1 || g[2]==b2 || g[2]==b3 || g[2]==b4 || g[2]==b5) matches++;
                if (g[3]==b1 || g[3]==b2 || g[3]==b3 || g[3]==b4 || g[3]==b5) matches++;
                if (g[4]==b1 || g[4]==b2 || g[4]==b3 || g[4]==b4 || g[4]==b5) matches++;
                boolean chanceMatch = (g[5] == bc);

                // Mode entraînement : on utilise des gains lissés pour la fitness
                double gain = calculerGainRapide(matches, chanceMatch);
                if (gain > 0) {
                    bilan += gain;
                    totalGagnant++;
                }
            }
        }

        double regulariteGains = (double) totalGagnant / batch.size();

        if (depense == 0) {
            return -2000.0; // Une fitness très basse pour éliminer cet individu du pool génétique
        }

        double roiPercent = ((bilan - depense) / depense) * 100.0;
        if (profil == null) return roiPercent;

        double couverture = (double) totalGagnant / totalGrilles;
        double penaliteVol = Math.log10(depense) * 5.0;

        // Ajout d'un bonus de régularité pour favoriser la stabilité du ROI
        double bonusStabilite = regulariteGains * 15.0;

        return (roiPercent * profil.roiWeight) + (couverture * 100.0 * profil.couvWeight) + bonusStabilite - penaliteVol;
    }

    private double calculerGainRapide(int m, boolean c) {
        if (m == 5) return c ? 2_000_000.0 : 100_000.0; // Valeurs lissées pour l'IA
        if (m == 4) return c ? 1000.0 : 400.0;
        if (m == 3) return c ? 50.0 : 20.0;
        if (m == 2) return c ? 10.0 : 5.0;
        if (c) return 2.20;
        return 0.0;
    }

    private LotoService.AlgoConfig decoderGenotype(Genotype<DoubleGene> gt, String nom) {
        return new LotoService.AlgoConfig(nom,
                gt.get(0).get(0).doubleValue(), gt.get(1).get(0).doubleValue(),
                gt.get(2).get(0).doubleValue(), gt.get(3).get(0).doubleValue(),
                gt.get(4).get(0).doubleValue(), gt.get(5).get(0).doubleValue(), false);
    }
}
