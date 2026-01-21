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
        log.info("🧪 Démarrage de l'optimisation HYPER-RAPIDE...");
        long start = System.currentTimeMillis();

        // 1. PRÉ-CALCUL DES SCÉNARIOS (Le secret de la vitesse)
        // On prépare les données pour les 100 derniers tirages (Beaucoup plus représentatif que 50)
        // HistoryDepth = 250 (suffisant pour les matrices)
        log.info("📸 Pré-calcul des snapshots historiques...");
        List<LotoService.ScenarioSimulation> scenarios = lotoService.preparerScenariosBacktest(historiqueComplet, 100, 250);
        log.info("✅ {} Scénarios prêts en mémoire.", scenarios.size());

        // 2. Génération des Configs à tester
        List<LotoService.AlgoConfig> configsATester = new ArrayList<>();
        double[] poidsFormeOpts = {10.0, 20.0, 30.0, 40.0};
        double[] poidsEcartOpts = {0.5, 1.0, 1.5};
        double[] poidsAffiniteOpts = {1.0, 3.0, 5.0}; // On insiste sur l'affinité
        // On fixe les autres pour réduire le volume si besoin, ou on boucle

        int i = 0;
        for (double pForme : poidsFormeOpts) {
            for (double pEcart : poidsEcartOpts) {
                for (double pAff : poidsAffiniteOpts) {
                    configsATester.add(new LotoService.AlgoConfig(
                            "TEST_" + i++, 3.0, pForme, pEcart, 12.0, 0.0, pAff, false
                    ));
                }
            }
        }

        // 3. BACKTEST PARALLÈLE
        final var bestResultRef = new Object() {
            LotoService.AlgoConfig config = LotoService.AlgoConfig.defaut();
            double maxBilan = -Double.MAX_VALUE;
        };

        configsATester.parallelStream().forEach(config -> {
            double bilan = 0;
            double depense = 0;

            // Boucle sur les scénarios pré-calculés (Pas d'accès BDD, pas de calcul matrice !)
            for (LotoService.ScenarioSimulation scenar : scenarios) {
                // Génération éclair (3 grilles par tirage suffisent pour la tendance)
                List<List<Integer>> grilles = lotoService.genererGrillesDepuisScenario(scenar, config, 3);

                depense += (grilles.size() * 2.20);
                for (List<Integer> g : grilles) {
                    bilan += calculerGainRapide(g, scenar.getTirageReel());
                }
            }

            double net = bilan - depense;

            synchronized (bestResultRef) {
                if (net > bestResultRef.maxBilan) {
                    bestResultRef.maxBilan = net;
                    bestResultRef.config = config;
                    log.info("🚀 Record : {} € (Forme={}, Ecart={}, Aff={})", String.format("%.2f", net), config.getPoidsForme(), config.getPoidsEcart(), config.getPoidsAffinite());
                }
            }
        });

        long duration = System.currentTimeMillis() - start;
        log.info("🏁 Terminé en {} ms. Meilleure config retenue.", duration);

        return bestResultRef.config;
    }

    private double simulerSurHistorique(LotoService.AlgoConfig config, List<LotoTirage> historiqueComplet, int nbTiragesTest) {
        double depense = 0;
        double gain = 0;

        // Optimisation : On limite l'historique passé à 200 tirages max pour les calculs de stats
        // (Inutile de remonter à 2008 pour savoir qu'un numéro est en forme ce mois-ci)
        int historyLimit = 200;

        // Sécurité
        if (historiqueComplet.size() < nbTiragesTest + 100) return 0.0;

        for (int i = 0; i < nbTiragesTest; i++) {
            int targetIndex = i;
            LotoTirage tirageReel = historiqueComplet.get(targetIndex);

            // On coupe l'historique pour aller plus vite
            int endSub = Math.min(targetIndex + 1 + historyLimit, historiqueComplet.size());
            List<LotoTirage> historiqueConnu = historiqueComplet.subList(targetIndex + 1, endSub);
            // --------------------

            // On génère 5 grilles par test
            List<List<Integer>> grillesGenerees = lotoService.genererGrillesPourSimulation(historiqueConnu, config, 5);

            depense += (grillesGenerees.size() * 2.20);
            for (List<Integer> g : grillesGenerees) {
                gain += calculerGainRapide(g, tirageReel);
            }
        }
        return gain - depense;
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
