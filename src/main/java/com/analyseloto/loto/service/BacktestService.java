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

    // --- OPTIMISATION VITESSE ---
    // On teste sur 25 grilles au lieu de 100 (suffisant pour la tendance)
    private static final int NB_GRILLES_PAR_TEST = 25;
    // On regarde les 60 derniers tirages (6 mois) au lieu de 150 (trop loin)
    private static final int DEPTH_BACKTEST = 60;

    public BacktestService(@Lazy LotoService lotoService) {
        this.lotoService = lotoService;
        // Random standard pour la vitesse (SecureRandom est trop lent pour l'IA)
        System.setProperty("io.jenetics.util.defaultRandomGenerator", "Random");
    }

    public LotoService.AlgoConfig trouverMeilleureConfig(List<LotoTirage> historiqueComplet) {
        log.info("🧬 Démarrage de la Méta-Optimisation IA (Jenetics)...");
        long start = System.currentTimeMillis();

        // 1. Préparation des données (Snapshot léger)
        List<LotoService.ScenarioSimulation> scenarios = lotoService.preparerScenariosBacktest(historiqueComplet, 120, DEPTH_BACKTEST);

        if (scenarios.isEmpty()) return LotoService.AlgoConfig.defaut();
        log.info("✅ {} Scénarios chargés. Lancement évolution...", scenarios.size());

        // 2. Génome (Poids)
        Factory<Genotype<DoubleGene>> gtf = Genotype.of(
                DoubleChromosome.of(1.0, 5.0),   // FreqJour
                DoubleChromosome.of(10.0, 30.0), // Forme (Plus agressif)
                DoubleChromosome.of(0.5, 3.0),   // Ecart
                DoubleChromosome.of(5.0, 20.0),  // Tension
                DoubleChromosome.of(0.0, 10.0),  // Markov
                DoubleChromosome.of(2.0, 10.0)   // Affinité
        );

        // 3. Moteur Evolutionnaire (Allégé)
        Engine<DoubleGene, Double> engine = Engine.builder(gt -> evaluerFitness(gt, scenarios), gtf)
                .populationSize(20) // 20 individus suffisent pour converger vite
                .executor(Executors.newWorkStealingPool())
                .survivorsSelector(new TournamentSelector<>(2))
                .offspringSelector(new RouletteWheelSelector<>())
                .alterers(new Mutator<>(0.20), new MeanAlterer<>(0.5))
                .build();

        // 4. Exécution (10 générations max = très rapide)
        Phenotype<DoubleGene, Double> bestPhenotype = engine.stream()
                .limit(10)
                .peek(r -> {
                    if(r.generation() == 1 || r.generation() % 5 == 0)
                        log.info("🏁 Gen {}/10 - Bilan: {} €", r.generation(), String.format("%.2f", r.bestFitness()));
                })
                .collect(EvolutionResult.toBestPhenotype());

        LotoService.AlgoConfig gagnante = decoderGenotype(bestPhenotype.genotype(), "AUTO_ML_FAST");
        gagnante.setBilanEstime(bestPhenotype.fitness());
        gagnante.setNbTiragesTestes(scenarios.size());

        log.info("🏆 Optimisation terminée en {} ms.", (System.currentTimeMillis() - start));
        return gagnante;
    }

    private double evaluerFitness(Genotype<DoubleGene> gt, List<LotoService.ScenarioSimulation> scenarios) {
        LotoService.AlgoConfig config = decoderGenotype(gt, "TEST");
        double bilan = 0;
        double depense = 0;

        for (LotoService.ScenarioSimulation scenar : scenarios) {
            // Génération purement RAM (très rapide)
            List<List<Integer>> grilles = lotoService.genererGrillesDepuisScenario(scenar, config, NB_GRILLES_PAR_TEST);
            depense += (grilles.size() * 2.20);

            LotoTirage target = scenar.getTirageReel();
            for (List<Integer> g : grilles) {
                bilan += calculerGainRapide(g, target);
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

    private double calculerGainRapide(List<Integer> grille, LotoTirage tirage) {
        if (grille.size() < 6) return 0.0;
        int matches = 0;
        // Boucle primitive sans stream pour performance max
        for(int i=0; i<5; i++) {
            int n = grille.get(i);
            if (n == tirage.getBoule1() || n == tirage.getBoule2() || n == tirage.getBoule3() ||
                    n == tirage.getBoule4() || n == tirage.getBoule5()) matches++;
        }
        boolean chanceMatch = (grille.get(5) == tirage.getNumeroChance());

        if (matches == 5) return chanceMatch ? 2_000_000.0 : 100_000.0;
        if (matches == 4) return chanceMatch ? 1_000.0 : 500.0;
        if (matches == 3) return chanceMatch ? 50.0 : 20.0;
        if (matches == 2) return chanceMatch ? 10.0 : 5.0;
        if (chanceMatch) return 2.20;
        return 0.0;
    }
}
