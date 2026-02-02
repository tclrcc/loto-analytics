package com.analyseloto.loto.service;

import com.analyseloto.loto.entity.LotoTirage;
import io.jenetics.*;
import io.jenetics.engine.Engine;
import io.jenetics.engine.EvolutionResult;
import io.jenetics.util.Factory;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

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
    // Équilibrage pour un temps de calcul ~45-50 minutes

    // On teste 500 grilles par scénario (Suffisant pour la loi des grands nombres)
    private static final int NB_GRILLES_PAR_TEST = 500;
    // On garde une profondeur historique solide
    private static final int DEPTH_BACKTEST = 450;
    // 60 générations suffisent généralement pour converger
    private static final int MAX_GENERATIONS = 80;
    // Population plus large pour explorer plus de pistes, mais raisonnable
    private static final int POPULATION_SIZE = 1000;

    public BacktestService(@Lazy LotoService lotoService) {
        this.lotoService = lotoService;
    }

    /**
     * Recherche de la meilleure configuration de l'algorithme
     */
    public LotoService.AlgoConfig trouverMeilleureConfig(List<LotoTirage> historiqueComplet) {
        log.info("🧬 Démarrage de la Méta-Optimisation IA (Deep Learning)...");
        long start = System.currentTimeMillis();

        // 1. Préparation des scénarios
        List<LotoService.ScenarioSimulation> scenarios = lotoService.preparerScenariosBacktest(historiqueComplet, 500, DEPTH_BACKTEST);

        if (scenarios.isEmpty()) return LotoService.AlgoConfig.defaut();

        // DÉTECTION AUTOMATIQUE DES CŒURS (VPS Scalable)
        int availableProcessors = Runtime.getRuntime().availableProcessors();
        // On laisse toujours 1 cœur libre pour le Système et la BDD
        int nThreads = Math.max(1, availableProcessors - 1);

        log.info("✅ {} Scénarios chargés. Utilisation de {} Cores (sur {}) pour le calcul intensif.",
                scenarios.size(), nThreads, availableProcessors);

        // 2. Génome (Plages de recherche affinées pour convergence rapide)
        Factory<Genotype<DoubleGene>> gtf = Genotype.of(
                DoubleChromosome.of(0.0, 15.0),   // FreqJour
                DoubleChromosome.of(5.0, 80.0),   // Forme
                DoubleChromosome.of(0.1, 10.0),    // Ecart
                DoubleChromosome.of(5.0, 50.0),   // Tension
                DoubleChromosome.of(0.0, 50.0),   // Markov
                DoubleChromosome.of(1.0, 40.0)    // Affinité
        );

        // Gestionnaire de Threads Dédié
        ExecutorService executor = Executors.newFixedThreadPool(nThreads);

        try {
            // 3. Moteur Evolutionnaire "Heavy Duty"
            Engine<DoubleGene, Double> engine = Engine.builder(gt -> evaluerFitness(gt, scenarios, true), gtf)
                    .populationSize(POPULATION_SIZE)
                    .executor(executor) // Calcul parallèle
                    .survivorsSelector(new TournamentSelector<>(5))
                    .offspringSelector(new RouletteWheelSelector<>())
                    .alterers(
                            new GaussianMutator<>(0.4), // Exploration
                            new SwapMutator<>(0.1),     // Diversité (Petite dose)
                            new MeanAlterer<>(0.4)      // Convergence
                    )
                    .build();

            // 4. Exécution
            Phenotype<DoubleGene, Double> bestPhenotype = engine.stream()
                    .limit(MAX_GENERATIONS)
                    .peek(r -> {
                        // Log toutes les 5 générations pour ne pas spammer
                        if (r.generation() % 5 == 0 || r.generation() == 1) {
                            log.info("🏁 Gen {}/{} - Bilan: {} € (Meilleur Fitness)",
                                    r.generation(), MAX_GENERATIONS, String.format("%.2f", r.bestFitness()));
                        }
                    })
                    .collect(EvolutionResult.toBestPhenotype());

            // Décoder la meilleure configuration
            LotoService.AlgoConfig gagnante = decoderGenotype(bestPhenotype.genotype(), "AUTO_ML_VPS_V2");

            log.info("📊 Calcul du Bilan Financier RÉEL (sans les boosts IA)...");

            // On recalcule le bilan sans les multiplicateurs artificiels
            double bilanReel = evaluerFitness(bestPhenotype.genotype(), scenarios, false);

            int nbTirages = scenarios.size();
            double coutTotal = nbTirages * NB_GRILLES_PAR_TEST * 2.20;
            double roiReel = 0.0;
            if (coutTotal > 0) {
                roiReel = (bilanReel / coutTotal) * 100.0;
            }

            gagnante.setBilanEstime(bilanReel);
            gagnante.setNbTiragesTestes(nbTirages);
            gagnante.setNbGrillesParTest(NB_GRILLES_PAR_TEST);
            gagnante.setRoiEstime(roiReel);

            long duration = (System.currentTimeMillis() - start) / 1000;
            log.info("🏆 Terminé en {}s. ROI RÉEL: {}% (Bilan: {} €)", duration, String.format("%.2f", roiReel), String.format("%.2f", bilanReel));

            return gagnante;

        } finally {
            // Toujours fermer l'executor pour libérer les ressources système
            executor.shutdown();
        }
    }

    private double evaluerFitness(Genotype<DoubleGene> gt, List<LotoService.ScenarioSimulation> scenarios, boolean modeEntrainement) {
        LotoService.AlgoConfig config = decoderGenotype(gt, "TEST");
        double bilan = 0;
        double depense = 0;
        double coutGrille = 2.20;

        for (LotoService.ScenarioSimulation scenar : scenarios) {
            // Appel optimisé
            List<List<Integer>> grilles = lotoService.genererGrillesDepuisScenario(scenar, config, NB_GRILLES_PAR_TEST);
            int nbGrilles = grilles.size();
            depense += (nbGrilles * coutGrille);

            // Optimisation locale
            LotoTirage target = scenar.getTirageReel();
            int b1 = target.getBoule1(); int b2 = target.getBoule2(); int b3 = target.getBoule3();
            int b4 = target.getBoule4(); int b5 = target.getBoule5(); int bc = target.getNumeroChance();

            // Boucle critique (Hot Path)
            for (List<Integer> g : grilles) {
                int matches = 0;
                // Déroulage manuel pour perf max
                int g0 = g.get(0);
                if (g0 == b1 || g0 == b2 || g0 == b3 || g0 == b4 || g0 == b5)
                    matches++;
                int g1 = g.get(1);
                if (g1 == b1 || g1 == b2 || g1 == b3 || g1 == b4 || g1 == b5)
                    matches++;
                int g2 = g.get(2);
                if (g2 == b1 || g2 == b2 || g2 == b3 || g2 == b4 || g2 == b5)
                    matches++;
                int g3 = g.get(3);
                if (g3 == b1 || g3 == b2 || g3 == b3 || g3 == b4 || g3 == b5)
                    matches++;
                int g4 = g.get(4);
                if (g4 == b1 || g4 == b2 || g4 == b3 || g4 == b4 || g4 == b5)
                    matches++;
                boolean chanceMatch = (g.get(5) == bc);

                if (modeEntrainement) {
                    // MODE BOOST : On guide l'IA
                    if (matches == 5)
                        bilan += chanceMatch ? 5_000_000.0 : 500_000.0;
                    else if (matches == 4)
                        bilan += chanceMatch ? 5000.0 : 2000.0;
                    else if (matches == 3)
                        bilan += chanceMatch ? 200.0 : 100.0;
                    else if (matches == 2)
                        bilan += chanceMatch ? 20.0 : 10.0;
                    else if (chanceMatch)
                        bilan += 5.0;
                } else {
                    // MODE RÉEL : Gains officiels
                    if (matches == 5)
                        bilan += chanceMatch ? 2000000.0 : 100000.0;
                    else if (matches == 4)
                        bilan += chanceMatch ? 1000.0 : 500.0;
                    else if (matches == 3)
                        bilan += chanceMatch ? 50.0 : 20.0;
                    else if (matches == 2)
                        bilan += chanceMatch ? 10.0 : 5.0;
                    else if (chanceMatch)
                        bilan += 2.20;
                }
            }
        }
        return bilan - depense;
    }

    private LotoService.AlgoConfig decoderGenotype(Genotype<DoubleGene> gt, String nom) {
        return new LotoService.AlgoConfig(
                nom,
                gt.get(0).get(0).doubleValue(), // Poids FreqJour
                gt.get(1).get(0).doubleValue(), // Poids Forme
                gt.get(2).get(0).doubleValue(), // Poids Ecart
                gt.get(3).get(0).doubleValue(), // Poids Tension
                gt.get(4).get(0).doubleValue(), // Poids Markov
                gt.get(5).get(0).doubleValue(), // Poids Affinité
                false
        );
    }
}
