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
    private static final int POPULATION_SIZE = 1000;
    private static final int MAX_GENERATIONS = 70;

    // NOUVEAU : Taille du batch d'entrainement (Plus grand = Plus stable)
    private static final int TRAINING_BATCH_SIZE = 350;

    public BacktestService(@Lazy LotoService lotoService) {
        this.lotoService = lotoService;
    }

    /**
     * Recherche de la meilleure configuration de l'algorithme
     */
    // DANS BacktestService.java

    public List<LotoService.AlgoConfig> trouverMeilleuresConfigs(List<LotoTirage> historiqueComplet) {
        log.info("🧬 Démarrage de la Méta-Optimisation IA (Deep Learning / Ensemble)...");
        long start = System.currentTimeMillis();

        // 1. Préparation des scénarios
        List<LotoService.ScenarioSimulation> scenarios = lotoService.preparerScenariosBacktest(historiqueComplet, 500, DEPTH_BACKTEST);

        if (scenarios.isEmpty()) {
            return List.of(LotoService.AlgoConfig.defaut());
        }

        // DÉTECTION AUTOMATIQUE DES CŒURS (VPS Scalable)
        int availableProcessors = Runtime.getRuntime().availableProcessors();
        // On laisse toujours 1 cœur libre pour le Système et la BDD
        int nThreads = Math.max(1, availableProcessors - 1);

        log.info("✅ {} Scénarios chargés. Utilisation de {} Cores (sur {}) pour le calcul intensif.",
                scenarios.size(), nThreads, availableProcessors);

        // 2. Génome (Plages de recherche affinées pour convergence rapide)
        Factory<Genotype<DoubleGene>> gtf = Genotype.of(
                DoubleChromosome.of(0.0, 20.0),   // FreqJour
                DoubleChromosome.of(0.0, 80.0),   // Forme
                DoubleChromosome.of(0.0, 20.0),   // Ecart
                DoubleChromosome.of(0.0, 60.0),   // Tension
                DoubleChromosome.of(0.0, 60.0),   // Markov
                DoubleChromosome.of(0.0, 60.0)    // Affinité
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
                            new GaussianMutator<>(0.15), // Exploration (Réduit pour stabilité)
                            new SwapMutator<>(0.15),     // Diversité
                            new MeanAlterer<>(0.6)       // Convergence (Consensus)
                    )
                    .build();

            // 4. Exécution et récupération de TOUTE la population finale
            EvolutionResult<DoubleGene, Double> result = engine.stream()
                    .limit(MAX_GENERATIONS)
                    .peek(r -> {
                        // Log toutes les 5 générations
                        if (r.generation() % 5 == 0 || r.generation() == 1) {
                            log.info("🏁 Gen {}/{} - Bilan Top 1: {} (Fitness)",
                                    r.generation(), MAX_GENERATIONS, String.format("%.2f", r.bestFitness()));
                        }
                    })
                    .collect(EvolutionResult.toBestEvolutionResult()); // On garde tout le résultat, pas juste le meilleur

            // 5. Extraction et Filtrage "Ensemble Elite"
            log.info("📊 Extraction des stratégies 'Elite' pour le consensus...");

            List<Phenotype<DoubleGene, Double>> sortedPopulation = result.population().stream()
                    .sorted((p1, p2) -> Double.compare(p2.fitness(), p1.fitness())) // Tri décroissant (Meilleur en premier)
                    .toList();

            List<LotoService.AlgoConfig> topConfigs = new ArrayList<>();
            int count = 0;

            for (Phenotype<DoubleGene, Double> phenotype : sortedPopulation) {
                // On s'arrête si on a nos 20 experts
                if (topConfigs.size() >= 20) break;

                LotoService.AlgoConfig candidat = decoderGenotype(phenotype.genotype(), "ELITE_" + (++count));

                // FILTRE DE DIVERSITÉ : On évite les doublons quasi-identiques
                // Si une config existante est trop proche de la candidate, on ignore la candidate.
                boolean isTooSimilar = topConfigs.stream().anyMatch(existing ->
                        Math.abs(existing.getPoidsForme() - candidat.getPoidsForme()) < 0.5 &&
                                Math.abs(existing.getPoidsEcart() - candidat.getPoidsEcart()) < 0.5 &&
                                Math.abs(existing.getPoidsAffinite() - candidat.getPoidsAffinite()) < 0.5
                );

                if (!isTooSimilar) {
                    // Calcul rapide des stats réelles pour information (sur le Top 1 seulement pour pas ralentir)
                    if (topConfigs.isEmpty()) {
                        double bilanReel = evaluerFitness(phenotype.genotype(), scenarios, false);
                        int nbTirages = scenarios.size();
                        double coutTotal = nbTirages * NB_GRILLES_PAR_TEST * 2.20;
                        double roiReel = (coutTotal > 0) ? (bilanReel / coutTotal) * 100.0 : 0.0;

                        candidat.setBilanEstime(bilanReel);
                        candidat.setRoiEstime(roiReel);
                        log.info("🥇 Leader du groupe : ROI Réel estimé à {}%", String.format("%.2f", roiReel));
                    }

                    topConfigs.add(candidat);
                }
            }

            long duration = (System.currentTimeMillis() - start) / 1000;
            log.info("🏆 Terminé en {}s. {} Stratégies uniques retenues pour le vote.", duration, topConfigs.size());

            return topConfigs;
        } finally {
            executor.shutdown();
        }
    }

    private double evaluerFitness(Genotype<DoubleGene> gt, List<LotoService.ScenarioSimulation> scenarios, boolean modeEntrainement) {
        LotoService.AlgoConfig config = decoderGenotype(gt, "TEST");
        double bilan = 0;
        double depense = 0;
        double coutGrille = 2.20;

        List<LotoService.ScenarioSimulation> batchScenarios = scenarios;
        if (modeEntrainement && scenarios.size() > TRAINING_BATCH_SIZE) {
            // On prend une sous-liste aléatoire ou glissante pour éviter l'overfitting sur une petite période
            batchScenarios = scenarios.subList(0, TRAINING_BATCH_SIZE);
        }

        for (LotoService.ScenarioSimulation scenar : batchScenarios) {
            int grillesAProduire = modeEntrainement ? 200 : NB_GRILLES_PAR_TEST;

            // Appel optimisé
            List<List<Integer>> grilles = lotoService.genererGrillesDepuisScenario(scenar, config, grillesAProduire);
            int nbGrilles = grilles.size();
            depense += (nbGrilles * coutGrille);

            // Optimisation locale (Extraction des variables pour éviter les getters dans la boucle)
            LotoTirage target = scenar.getTirageReel();
            int b1 = target.getBoule1(); int b2 = target.getBoule2(); int b3 = target.getBoule3();
            int b4 = target.getBoule4(); int b5 = target.getBoule5(); int bc = target.getNumeroChance();

            // Boucle critique (Hot Path)
            for (List<Integer> g : grilles) {
                int matches = 0;
                // Déroulage manuel de la boucle pour performance maximale (CPU Branch Prediction)
                int g0 = g.get(0); if (g0 == b1 || g0 == b2 || g0 == b3 || g0 == b4 || g0 == b5) matches++;
                int g1 = g.get(1); if (g1 == b1 || g1 == b2 || g1 == b3 || g1 == b4 || g1 == b5) matches++;
                int g2 = g.get(2); if (g2 == b1 || g2 == b2 || g2 == b3 || g2 == b4 || g2 == b5) matches++;
                int g3 = g.get(3); if (g3 == b1 || g3 == b2 || g3 == b3 || g3 == b4 || g3 == b5) matches++;
                int g4 = g.get(4); if (g4 == b1 || g4 == b2 || g4 == b3 || g4 == b4 || g4 == b5) matches++;
                boolean chanceMatch = (g.get(5) == bc);

                double gainGrille = 0.0;

                if (matches == 5) {
                    // Jackpot : On le cap à 50k en entraînement pour éviter qu'une seule grille
                    // chanceuse ne fausse tout l'apprentissage (Variance reduction).
                    // En mode REEL, on met le vrai montant estimé.
                    if (modeEntrainement) gainGrille = chanceMatch ? 50_000.0 : 20_000.0;
                    else gainGrille = chanceMatch ? 2_000_000.0 : 100_000.0;
                }
                else if (matches == 4) {
                    gainGrille = chanceMatch ? 1000.0 : 500.0;
                }
                else if (matches == 3) {
                    gainGrille = chanceMatch ? 50.0 : 20.0;
                }
                else if (matches == 2) {
                    // C'est ici que se joue le ROI : 2 numéros rapportent peu vs le coût (2.20€).
                    // Votre ancien code donnait trop de points ici.
                    gainGrille = chanceMatch ? 10.0 : 5.0;
                }
                else if (chanceMatch) {
                    gainGrille = 2.20; // Remboursement exact
                }

                bilan += gainGrille;
            }
        }

        if (depense == 0) return -1000.0; // Sécurité division par zéro

        double netProfit = bilan - depense;
        double roiPercent = (netProfit / depense) * 100.0;

        if (modeEntrainement) {
            // FORMULE "EFFICIENCE CHIRURGICALE"
            // 1. On base le score sur le ROI % (Rentabilité pure).
            // 2. On applique une pénalité logarithmique sur la dépense.
            //    Pourquoi ? Pour gagner 10€, il vaut mieux dépenser 10€ (ROI 0%) que 1000€ (ROI 0%).
            //    Cela force l'IA à chercher des grilles "Sûres" plutôt que de spammer des grilles au hasard.

            double penaliteVolume = Math.log10(depense) * 5.0;

            // Exemple :
            // Strat A : Dépense 100€, Gain 110€. ROI +10%. Pénalité Log(100)*5 = 10. Score = 0.
            // Strat B : Dépense 10000€, Gain 11000€. ROI +10%. Pénalité Log(10000)*5 = 20. Score = -10.
            // → L'IA préférera la Strat A (moins risquée).

            return roiPercent - penaliteVolume;
        }

        // En mode affichage final (Genotype décodé), on retourne le VRAI bilan financier
        return netProfit;
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
