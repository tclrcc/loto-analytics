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
    // Services
    private final LotoService lotoService;

    // --- CONSTANTES OPTIMISÉES VPS-2 (6 vCores / 12Go RAM) ---
    // On augmente la précision statistique (1000 grilles au lieu de 500)
    private static final int NB_GRILLES_PAR_TEST = 1000;
    // On garde une profondeur d'analyse décente
    private static final int DEPTH_BACKTEST = 450;
    // Nombre de générations pour laisser l'IA converger (50 → 100)
    private static final int MAX_GENERATIONS = 100;
    // Taille de la population (300 → 2000) car on a beaucoup de RAM
    private static final int POPULATION_SIZE = 2000;

    /**
     * Constructeur avec LotoService appelé lui-même
     * @param lotoService service
     */
    public BacktestService(@Lazy LotoService lotoService) {
        this.lotoService = lotoService;
        // Optimisation du générateur aléatoire pour le multithreading
        System.setProperty("io.jenetics.util.defaultRandomGenerator", "L64X128StarStarRandom");
    }

    /**
     * Recherche de la meilleure configuration de l'algorithme
     * @param historiqueComplet historique des tirages
     * @return Config
     */
    public LotoService.AlgoConfig trouverMeilleureConfig(List<LotoTirage> historiqueComplet) {
        log.info("🧬 Démarrage de la Méta-Optimisation IA (Deep Learning)...");
        long start = System.currentTimeMillis();

        // 1. Préparation des scénarios (Optimisé par LotoService et ses int[][])
        List<LotoService.ScenarioSimulation> scenarios = lotoService.preparerScenariosBacktest(historiqueComplet, 500, DEPTH_BACKTEST);

        if (scenarios.isEmpty()) return LotoService.AlgoConfig.defaut();

        // DÉTECTION AUTOMATIQUE DES CŒURS (VPS Scalable)
        int availableProcessors = Runtime.getRuntime().availableProcessors();
        // On laisse toujours 1 cœur libre pour le Système et la BDD
        int nThreads = Math.max(1, availableProcessors - 1);

        log.info("✅ {} Scénarios chargés. Utilisation de {} Cores (sur {}) pour le calcul intensif.",
                scenarios.size(), nThreads, availableProcessors);

        // 2. Génome (Plages de recherche affinées)
        Factory<Genotype<DoubleGene>> gtf = Genotype.of(
                DoubleChromosome.of(0.0, 15.0),   // FreqJour
                DoubleChromosome.of(5.0, 80.0),   // Forme (On autorise des poids plus forts)
                DoubleChromosome.of(0.1, 10.0),   // Ecart
                DoubleChromosome.of(5.0, 50.0),   // Tension
                DoubleChromosome.of(0.0, 50.0),   // Markov
                DoubleChromosome.of(1.0, 40.0)    // Affinité (Critique)
        );

        // Gestionnaire de Threads Dédié
        ExecutorService executor = Executors.newFixedThreadPool(nThreads);

        try {
            // 3. Moteur Evolutionnaire "Heavy Duty"
            Engine<DoubleGene, Double> engine = Engine.builder(gt -> evaluerFitness(gt, scenarios, true), gtf)
                    .populationSize(POPULATION_SIZE) // 2000 individus en mémoire (RAM 12Go OK)
                    .executor(executor) // Calcul parallèle sur 5 cœurs
                    .survivorsSelector(new TournamentSelector<>(5))
                    .offspringSelector(new RouletteWheelSelector<>())
                    .alterers(
                            // Stratégie hybride : Mutation + Croisement + Echange
                            new GaussianMutator<>(0.4), // Exploration (Petits changements)
                            new SwapMutator<>(0.2),     // Diversité (Echange de gènes)
                            new MeanAlterer<>(0.4)      // Convergence (Moyenne des parents)
                    )
                    .build();

            // 4. Exécution (100 générations)
            Phenotype<DoubleGene, Double> bestPhenotype = engine.stream()
                    .limit(MAX_GENERATIONS)
                    .peek(r -> {
                        if (r.generation() % 5 == 0 || r.generation() == 1) { // Log moins verbeux
                            log.info("🏁 Gen {}/{} - Bilan: {} € (Meilleur Fitness)",
                                    r.generation(), MAX_GENERATIONS, String.format("%.2f", r.bestFitness()));
                        }
                    })
                    .collect(EvolutionResult.toBestPhenotype());

            // Décoder la meilleur configuration
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

        // Coût fixe calculé une seule fois
        double coutGrille = 2.20;

        // Boucle sur les scénarios historiques
        for (LotoService.ScenarioSimulation scenar : scenarios) {
            // Appel optimisé (qui utilise maintenant les int[][] de LotoService)
            List<List<Integer>> grilles = lotoService.genererGrillesDepuisScenario(scenar, config, NB_GRILLES_PAR_TEST);

            int nbGrilles = grilles.size();
            depense += (nbGrilles * coutGrille);

            // Optimisation locale : Extraction des primitives pour éviter les getters dans la boucle interne
            LotoTirage target = scenar.getTirageReel();
            int b1 = target.getBoule1();
            int b2 = target.getBoule2();
            int b3 = target.getBoule3();
            int b4 = target.getBoule4();
            int b5 = target.getBoule5();
            int bc = target.getNumeroChance();

            // Boucle critique (Hot Path)
            for (List<Integer> g : grilles) {
                int matches = 0;

                // Déroulage de boucle manuel pour éviter l'overhead de l'itérateur
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
                    // MODE BOOST : On guide l'IA pour qu'elle apprenne vite
                    // Récompenses ajustées pour favoriser la découverte de modèles
                    if (matches == 5)
                        bilan += chanceMatch ? 5_000_000.0 : 500_000.0; // Jackpot prioritaire
                    else if (matches == 4)
                        bilan += chanceMatch ? 5000.0 : 2000.0;    // Rang 2 et 3 valorisés
                    else if (matches == 3)
                        bilan += chanceMatch ? 200.0 : 100.0;
                    else if (matches == 2)
                        bilan += chanceMatch ? 20.0 : 10.0;        // "Remboursement" encouragé
                    else if (chanceMatch)
                        bilan += 5.0; // Chance seule valorisée en entraînement
                } else {
                    // MODE RÉEL : Vrais gains FDJ (Tableau officiel)
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

    /**
     * 🧬 DÉCODEUR GÉNÉTIQUE (Genotype -> AlgoConfig)
     * <p>
     * Cette méthode traduit l'ADN abstrait de l'IA (une liste de nombres) en une configuration
     * métier concrète utilisable par le moteur de Loto.
     * <p>
     * ⚠️ IMPORTANT : L'ordre des index (get(0), get(1)...) doit correspondre EXACTEMENT
     * à l'ordre de déclaration dans la Factory du 'trouverMeilleureConfig'.
     *
     * @param gt  Le génotype (individu) proposé par Jenetics.
     * @param nom Le nom de cette configuration (ex : "TEST", "AUTO_ML_ULTRA").
     * @return Une configuration prête à être testée.
     */
    private LotoService.AlgoConfig decoderGenotype(Genotype<DoubleGene> gt, String nom) {
        return new LotoService.AlgoConfig(
                nom,
                gt.get(0).get(0).doubleValue(), // Index 0 : Poids Fréquence Jour
                gt.get(1).get(0).doubleValue(), // Index 1 : Poids Forme
                gt.get(2).get(0).doubleValue(), // Index 2 : Poids Écart
                gt.get(3).get(0).doubleValue(), // Index 3: Poids Tension
                gt.get(4).get(0).doubleValue(), // Index 4 : Poids Markov
                gt.get(5).get(0).doubleValue(), // Index 5 : Poids Affinité
                false
        );
    }
}
