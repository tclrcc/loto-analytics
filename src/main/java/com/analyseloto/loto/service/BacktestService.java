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
     * Recherche de la meilleure configuration de l'algorithme par optimisation génétique simplifiée
     * @param historiqueComplet historique complet des tirages
     * @return Meilleure configuration trouvée
     */
    public LotoService.AlgoConfig trouverMeilleureConfig(List<LotoTirage> historiqueComplet) {
        log.info("🧪 Démarrage de l'optimisation MASSIVE...");
        long start = System.currentTimeMillis();

        // 1. PRÉ-CALCUL DES SCÉNARIOS (On augmente la profondeur)
        // On teste sur les 350 derniers tirages (2.5 ans) pour une robustesse maximale
        int depthBacktest = 350;
        log.info("📸 Pré-calcul des snapshots sur {} tirages...", depthBacktest);

        List<LotoService.ScenarioSimulation> scenarios = lotoService.preparerScenariosBacktest(historiqueComplet, depthBacktest, 250);

        if (scenarios.isEmpty()) {
            log.warn("Pas assez d'historique. Retour config défaut.");
            return LotoService.AlgoConfig.defaut();
        }
        log.info("✅ {} Scénarios prêts en mémoire.", scenarios.size());

        // 2. GÉNÉRATION EXPLOSIVE DES CONFIGS (Brute Force Intelligent)
        List<LotoService.AlgoConfig> configsATester = new ArrayList<>();

        int countId = 0;
        int nbGrillesParTest = 50;

        for (double forme = 5.0; forme <= 50.0; forme += 2.5) {
            for (double ecart = 0.4; ecart <= 1.8; ecart += 0.2) {
                for (double affinite = 0.0; affinite <= 10.0; affinite += 1.0) {
                    for (double tension = 0.0; tension <= 30.0; tension += 15.0) {
                        // On ajoute aussi Markov 0 ou 2 pour voir
                        configsATester.add(new LotoService.AlgoConfig(
                                "TEST_" + (++countId), 3.0, forme, ecart, tension, 0.0, affinite, false
                        ));
                    }
                }
            }
        }

        log.info("📊 Analyse de {} stratégies complexes sur tous les cœurs CPU...", configsATester.size());

        // 3. BACKTEST PARALLÈLE
        final var bestResultRef = new Object() {
            LotoService.AlgoConfig config = LotoService.AlgoConfig.defaut();
            double maxBilan = -Double.MAX_VALUE;
        };

        // Utilisation de parallelStream pour saturer le CPU
        configsATester.parallelStream().forEach(config -> {
            double bilan = 0;
            double depense = 0;

            // Boucle sur les scénarios (Lecture seule = Thread Safe & Rapide)
            for (LotoService.ScenarioSimulation scenar : scenarios) {

                // ON AUGMENTE LA PRÉCISION : 10 grilles par tirage au lieu de 3
                // Cela évite les "coups de chance" isolés. Une bonne stratégie doit gagner souvent.
                List<List<Integer>> grilles = lotoService.genererGrillesDepuisScenario(scenar, config, nbGrillesParTest);

                depense += (grilles.size() * 2.20);

                // Calcul rapide du gain
                for (List<Integer> g : grilles) {
                    bilan += calculerGainRapide(g, scenar.getTirageReel());
                }
            }

            double net = bilan - depense;

            // Mise à jour Thread-Safe du meilleur résultat
            synchronized (bestResultRef) {
                if (net > bestResultRef.maxBilan) {
                    bestResultRef.maxBilan = net;
                    bestResultRef.config = config;
                    log.info("🚀 Record : {} € (Forme={}, Ecart={}, Aff={}, Tens={})",
                            String.format("%.2f", net),
                            config.getPoidsForme(), config.getPoidsEcart(), config.getPoidsAffinite(), config.getPoidsTension());
                }
            }
        });

        // Durée du traitement
        long duration = System.currentTimeMillis() - start;

        // Configuration gagnante
        LotoService.AlgoConfig gagnante = bestResultRef.config;
        gagnante.setBilanEstime(bestResultRef.maxBilan);
        gagnante.setNbTiragesTestes(depthBacktest);

        log.info("🏁 Terminé en {} ms. Config gagnante : {} (Bilan: {} €)",
                duration, gagnante.getNomStrategie(), String.format("%.2f", gagnante.getBilanEstime()));

        return gagnante;
    }

    private double calculerGainRapide(List<Integer> grille, LotoTirage t) {
        // Sécurité : on s'attend à 6 numéros (5 boules + 1 chance)
        if (grille.size() < 6) return 0.0;

        // On sépare les boules et la chance
        // subList(0, 5) prend les index 0, 1, 2, 3, 4
        List<Integer> boulesJouees = grille.subList(0, 5);
        int chanceJouee = grille.get(5); // Le dernier élément est la chance

        // Vérification des boules
        long bonsNumeros = boulesJouees.stream().filter(t.getBoules()::contains).count();

        // Vérification de la chance
        boolean bonneChance = (chanceJouee == t.getNumeroChance());

        // --- Grille des Gains (Approximation réaliste FDJ) ---

        // 5 Bons numéros
        if (bonsNumeros == 5) {
            return bonneChance ? 2_000_000.0 : 100_000.0; // Jackpot (Rank 1) vs Rank 2
        }

        // 4 Bons numéros
        if (bonsNumeros == 4) {
            return bonneChance ? 1_000.0 : 500.0;
        }

        // 3 Bons numéros
        if (bonsNumeros == 3) {
            return bonneChance ? 50.0 : 20.0;
        }

        // 2 Bons numéros
        if (bonsNumeros == 2) {
            return bonneChance ? 10.0 : 5.0;
        }

        // 0 ou 1 Bon numéro mais Bonne Chance (Remboursement)
        if (bonneChance) {
            return 2.20;
        }

        return 0.0;
    }
}
