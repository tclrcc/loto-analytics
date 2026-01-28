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
import java.util.concurrent.Executors;

@Service
@Slf4j
public class BacktestService {
    private final LotoService lotoService;

    // 50 grilles par test est un bon équilibre statistique, on garde.
    private static final int NB_GRILLES_PAR_TEST = 50;

    // On augmente la profondeur pour une robustesse maximale (2.5 ans)
    private static final int DEPTH_BACKTEST = 300;

    public BacktestService(@Lazy LotoService lotoService) {
        this.lotoService = lotoService;
        System.setProperty("io.jenetics.util.defaultRandomGenerator", "Random");
    }

    public LotoService.AlgoConfig trouverMeilleureConfig(List<LotoTirage> historiqueComplet) {
        log.info("🧬 Démarrage de la Méta-Optimisation IA (Deep Learning)...");
        long start = System.currentTimeMillis();

        // 1. Préparation (400 snapshots pour avoir de la marge sur les 300 demandés)
        List<LotoService.ScenarioSimulation> scenarios = lotoService.preparerScenariosBacktest(historiqueComplet, 400, DEPTH_BACKTEST);

        if (scenarios.isEmpty()) return LotoService.AlgoConfig.defaut();
        log.info("✅ {} Scénarios chargés. Utilisation de 3 Cores pour le calcul.", scenarios.size());

        // 2. Génome (Intervalles élargis pour retrouver tes poids extrêmes)
        Factory<Genotype<DoubleGene>> gtf = Genotype.of(
                DoubleChromosome.of(0.0, 10.0),   // FreqJour
                DoubleChromosome.of(5.0, 30.0),   // Forme (Ta config était à 17.3)
                DoubleChromosome.of(0.1, 5.0),    // Ecart (Ta config était à 1.98)
                DoubleChromosome.of(5.0, 25.0),   // Tension (Ta config était à 16.2)
                DoubleChromosome.of(0.0, 15.0),   // Markov (Ta config était à 4.14)
                DoubleChromosome.of(1.0, 15.0)    // Affinité (Ta config était à 6.0)
        );

        // 3. Moteur Evolutionnaire "Heavy Duty"
        Engine<DoubleGene, Double> engine = Engine.builder(gt -> evaluerFitness(gt, scenarios), gtf)
                .populationSize(100) // On remet 100 individus pour la diversité
                .executor(Executors.newFixedThreadPool(3))
                // On laisse 1 cœur libre pour le système/BDD
                .survivorsSelector(new TournamentSelector<>(3))
                .offspringSelector(new RouletteWheelSelector<>())
                .alterers(
                        new Mutator<>(0.15),
                        new MeanAlterer<>(0.6)
                )
                .build();

        // 4. Exécution (50 générations)
        Phenotype<DoubleGene, Double> bestPhenotype = engine.stream()
                .limit(50)
                .peek(r -> log.info("🏁 Gen {}/50 - Bilan: {} €", r.generation(), String.format("%.2f", r.bestFitness())))
                .collect(EvolutionResult.toBestPhenotype());

        LotoService.AlgoConfig gagnante = decoderGenotype(bestPhenotype.genotype(), "AUTO_ML_DEEP");
        gagnante.setBilanEstime(bestPhenotype.fitness());
        gagnante.setNbTiragesTestes(scenarios.size());

        long duration = System.currentTimeMillis() - start;
        log.info("🏆 Deep Optimisation terminée en {} ms.", duration);
        return gagnante;
    }

    private double evaluerFitness(Genotype<DoubleGene> gt, List<LotoService.ScenarioSimulation> scenarios) {
        LotoService.AlgoConfig config = decoderGenotype(gt, "TEST");
        double bilan = 0;
        double depense = 0;

        for (LotoService.ScenarioSimulation scenar : scenarios) {
            List<List<Integer>> grilles = lotoService.genererGrillesDepuisScenario(scenar, config, NB_GRILLES_PAR_TEST);
            depense += (grilles.size() * 2.20);

            // Optimisation locale pour éviter getTirageReel() répété
            LotoTirage target = scenar.getTirageReel();
            int b1 = target.getBoule1(); int b2 = target.getBoule2(); int b3 = target.getBoule3();
            int b4 = target.getBoule4(); int b5 = target.getBoule5(); int bc = target.getNumeroChance();

            for (List<Integer> g : grilles) {
                // Inlining du calcul de gain pour performance extrême
                int matches = 0;
                for(int i=0; i<5; i++) {
                    int n = g.get(i);
                    if (n == b1 || n == b2 || n == b3 || n == b4 || n == b5) matches++;
                }
                boolean chanceMatch = (g.get(5) == bc);

                if (matches == 5) bilan += chanceMatch ? 2000000.0 : 100000.0;
                else if (matches == 4) bilan += chanceMatch ? 1000.0 : 500.0;
                else if (matches == 3) bilan += chanceMatch ? 50.0 : 20.0;
                else if (matches == 2) bilan += chanceMatch ? 10.0 : 5.0;
                else if (chanceMatch) bilan += 2.20;
            }
        }
        return bilan - depense;
    }

    private LotoService.AlgoConfig decoderGenotype(Genotype<DoubleGene> gt, String nom) {
        return new LotoService.AlgoConfig(nom,
                gt.get(0).get(0).doubleValue(), gt.get(1).get(0).doubleValue(),
                gt.get(2).get(0).doubleValue(), gt.get(3).get(0).doubleValue(),
                gt.get(4).get(0).doubleValue(), gt.get(5).get(0).doubleValue(), false);
    }
}
