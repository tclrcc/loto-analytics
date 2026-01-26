package com.analyseloto.loto.service;

import com.analyseloto.loto.entity.LotoTirage;
import io.jenetics.*;
import io.jenetics.engine.Engine;
import io.jenetics.engine.EvolutionResult;
import io.jenetics.util.Factory;
import io.jenetics.util.RandomRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Random;

@Service
@Slf4j
public class BacktestService {
    private final LotoService lotoService;

    // Constante pour le volume du test de fitness
    private static final int NB_GRILLES_PAR_TEST = 300;

    public BacktestService(@Lazy LotoService lotoService) {
        this.lotoService = lotoService;

        // On force la propriété système AVANT que Jenetics ne soit chargé en mémoire.
        // Cela empêche l'erreur "L64X256MixRandom not available" sur les JVM allégées.
        System.setProperty("io.jenetics.util.defaultRandomGenerator", "Random");
    }

    /**
     * Recherche de la meilleure configuration : MODE "AUTO-ML" via JENETICS
     */
    public LotoService.AlgoConfig trouverMeilleureConfig(List<LotoTirage> historiqueComplet) {
        log.info("🧬 Démarrage de la Méta-Optimisation IA (Jenetics)...");
        long start = System.currentTimeMillis();

        // Entrainement de l'IA sur 500 tirages du passé
        int depthBacktest = 500;
        List<LotoService.ScenarioSimulation> scenarios = lotoService.preparerScenariosBacktest(historiqueComplet, depthBacktest, 300);

        if (scenarios.isEmpty()) return LotoService.AlgoConfig.defaut();
        log.info("✅ {} Scénarios prêts en mémoire.", scenarios.size());

        // 1. DÉFINITION DU GÉNOME (Les 6 poids de notre config)
        // L'IA peut choisir n'importe quelle valeur (Double) dans ces intervalles.
        Factory<Genotype<DoubleGene>> gtf = Genotype.of(
                DoubleChromosome.of(1.0, 5.0),   // 0: Poids FreqJour
                DoubleChromosome.of(12.0, 25.0), // 1: Poids Forme
                DoubleChromosome.of(1.5, 2.0),   // 2: Poids Ecart
                DoubleChromosome.of(10.0, 35.0), // 3: Poids Tension
                DoubleChromosome.of(0.0, 15.0),  // 4: Poids Markov
                DoubleChromosome.of(5.0, 10.0)   // 5: Poids Affinité
        );

        // 2. CONFIGURATION DU MOTEUR ÉVOLUTIONNAIRE
        Engine<DoubleGene, Double> engine = Engine.builder(gt -> evaluerFitness(gt, scenarios), gtf)
                .populationSize(80) // 80 configurations testées par génération
                .survivorsSelector(new TournamentSelector<>(3)) // Sélection des meilleurs
                .offspringSelector(new RouletteWheelSelector<>()) // Reproduction pondérée
                .alterers(
                        new Mutator<>(0.20),      // 20% de mutation (exploration)
                        new MeanAlterer<>(0.7)    // 70% de croisement par la moyenne
                )
                .build();

        log.info("🚀 Lancement de l'évolution sur 15 générations...");

        // 3. EXÉCUTION DU MOTEUR (Automatiquement Parallélisé par Jenetics)
        Phenotype<DoubleGene, Double> bestPhenotype = engine.stream()
                .limit(30) // On s'arrête après 25 générations
                .peek(result -> log.info("🏁 Génération {} terminée. Meilleur Bilan Actuel : {} €", result.generation(), String.format("%.2f", result.bestFitness())))
                .collect(EvolutionResult.toBestPhenotype());

        // 4. RÉCUPÉRATION DE L'ULTIME VAINQUEUR
        Genotype<DoubleGene> bestGeno = bestPhenotype.genotype();
        double bestBilan = bestPhenotype.fitness();

        LotoService.AlgoConfig gagnante = decoderGenotype(bestGeno, "AUTO_ML_FINAL");
        gagnante.setBilanEstime(bestBilan);
        gagnante.setNbTiragesTestes(depthBacktest);

        long duration = System.currentTimeMillis() - start;
        log.info("🏆 Méta-Optimisation terminée en {} ms.", duration);
        log.info("🔬 Config Finale : Freq={}, F={}, E={}, Tens={}, Mk={}, Aff={}",
                String.format("%.1f", gagnante.getPoidsFreqJour()),
                String.format("%.1f", gagnante.getPoidsForme()),
                String.format("%.1f", gagnante.getPoidsEcart()),
                String.format("%.1f", gagnante.getPoidsTension()),
                String.format("%.1f", gagnante.getPoidsMarkov()),
                String.format("%.1f", gagnante.getPoidsAffinite()));

        return gagnante;
    }

    /**
     * FONCTION DE FITNESS (L'épreuve du feu pour chaque configuration)
     */
    private double evaluerFitness(Genotype<DoubleGene> gt, List<LotoService.ScenarioSimulation> scenarios) {
        LotoService.AlgoConfig config = decoderGenotype(gt, "TEST_TEMP");

        double bilan = 0;
        double depense = 0;

        for (LotoService.ScenarioSimulation scenar : scenarios) {
            List<List<Integer>> grilles = lotoService.genererGrillesDepuisScenario(scenar, config, NB_GRILLES_PAR_TEST);
            depense += (grilles.size() * 2.20);
            for (List<Integer> g : grilles) {
                bilan += calculerGainRapide(g, scenar.getTirageReel());
            }
        }
        return bilan - depense; // Le bénéfice net est la note de fitness
    }

    /**
     * Convertit l'ADN Jenetics en objet de configuration Loto
     */
    private LotoService.AlgoConfig decoderGenotype(Genotype<DoubleGene> gt, String nom) {
        return new LotoService.AlgoConfig(
                nom,
                gt.get(0).get(0).doubleValue(), // FreqJour
                gt.get(1).get(0).doubleValue(), // Forme
                gt.get(2).get(0).doubleValue(), // Ecart
                gt.get(3).get(0).doubleValue(), // Tension
                gt.get(4).get(0).doubleValue(), // Markov
                gt.get(5).get(0).doubleValue(), // Affinité
                false
        );
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
