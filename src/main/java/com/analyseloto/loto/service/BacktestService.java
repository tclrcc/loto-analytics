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
        log.info("🧪 Démarrage de l'optimisation PARALLÈLE...");
        long start = System.currentTimeMillis();

        // 1. On génère toutes les combinaisons possibles dans une liste
        List<LotoService.AlgoConfig> configsATester = new ArrayList<>();

        double[] poidsFormeOpts = {10.0, 15.0, 20.0, 25.0, 30.0};
        double[] poidsEcartOpts = {0.6, 0.8, 1.0, 1.2, 1.4};
        double[] poidsMarkovOpts = {0.0, 2.0};
        double[] poidsAffiniteOpts = {0.0, 1.0, 3.0};

        int i = 0;
        for (double pForme : poidsFormeOpts) {
            for (double pEcart : poidsEcartOpts) {
                for (double pMarkov : poidsMarkovOpts) {
                    for (double pAff : poidsAffiniteOpts) {
                        configsATester.add(new LotoService.AlgoConfig(
                                "TEST_" + i++, 3.0, pForme, pEcart, 12.0, pMarkov, pAff, false
                        ));
                    }
                }
            }
        }

        log.info("📊 Analyse de {} stratégies sur tous les cœurs CPU...", configsATester.size());

        // 2. Variable atomique pour gérer la concurrence (Thread-safe)
        // On utilise un wrapper pour stocker le meilleur résultat
        final var bestResultRef = new Object() {
            LotoService.AlgoConfig config = LotoService.AlgoConfig.defaut();
            double maxBilan = -Double.MAX_VALUE;
        };

        // 3. TRAITEMENT PARALLÈLE (Le turbo !)
        configsATester.parallelStream().forEach(config -> {

            // 50 tirages tests
            double bilan = simulerSurHistorique(config, historiqueComplet, 50);

            // Bloc synchronisé pour mettre à jour le record
            synchronized (bestResultRef) {
                if (bilan > bestResultRef.maxBilan) {
                    bestResultRef.maxBilan = bilan;
                    bestResultRef.config = config;
                    log.info("🚀 Record [Thread] ! Bilan: {} € | Config: F={}, E={}, M={}, A={}",
                            String.format("%.2f", bilan), config.getPoidsForme(), config.getPoidsEcart(), config.getPoidsMarkov(), config.getPoidsAffinite());
                }
            }
        });

        long duration = System.currentTimeMillis() - start;
        log.info("🏁 Optimisation terminée en {} ms.", duration);

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
        long bonsNumeros = grille.stream().filter(t.getBoules()::contains).count();
        // Note: Pour le backtest rapide, on ignore le numéro chance ou on le fixe à 1/10 proba
        // Gains approximatifs FDJ
        if (bonsNumeros == 5) return 100000.0; // Jackpot théorique réduit
        if (bonsNumeros == 4) return 500.0;
        if (bonsNumeros == 3) return 20.0;
        if (bonsNumeros == 2) return 5.0;
        return 0.0;
    }
}
