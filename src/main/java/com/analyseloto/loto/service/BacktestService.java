package com.analyseloto.loto.service;

import com.analyseloto.loto.entity.LotoTirage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

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
        log.info("🧪 Démarrage de l'optimisation génétique des poids...");

        LotoService.AlgoConfig bestConfig = LotoService.AlgoConfig.defaut();
        double maxBilan = -Double.MAX_VALUE;

        // Définition des plages de tests
        double[] poidsFormeOpts = {8.0, 12.0, 16.0, 20.0, 24.0};
        double[] poidsEcartOpts = {0.8, 1.0, 1.2, 1.5};
        double[] poidsMarkovOpts = {0.0, 2.0};
        double[] poidsAffiniteOpts = {0.0, 1.0, 3.0};

        int iterations = 0;
        int totalCombinaisons = poidsFormeOpts.length * poidsEcartOpts.length * poidsMarkovOpts.length * poidsAffiniteOpts.length;

        log.info("📊 Analyse de {} combinaisons stratégiques...", totalCombinaisons);

        for (double pForme : poidsFormeOpts) {
            for (double pEcart : poidsEcartOpts) {
                for (double pMarkov : poidsMarkovOpts) {

                    // --- NOUVELLE BOUCLE POUR L'AFFINITÉ ---
                    for (double pAffinite : poidsAffiniteOpts) {

                        LotoService.AlgoConfig configTest = new LotoService.AlgoConfig(
                                "TEST_" + iterations++,
                                3.0, // FreqJour fixe (valeur sûre)
                                pForme,
                                pEcart,
                                12.0, // Tension fixe
                                pMarkov,
                                pAffinite, // <--- Injection du paramètre variable
                                false
                        );

                        // On teste cette config sur les 50 derniers tirages
                        double bilanNet = simulerSurHistorique(configTest, historiqueComplet, 50);

                        if (bilanNet > maxBilan) {
                            maxBilan = bilanNet;
                            bestConfig = configTest;

                            log.info("🚀 Record ! Bilan: {} € | Config: Forme={}, Ecart={}, Markov={}, Affinité={}",
                                    String.format("%.2f", bilanNet),
                                    pForme, pEcart, pMarkov, pAffinite);
                        }
                    }
                }
            }
        }

        log.info("🏁 Optimisation terminée. Meilleure Config retenue : {}", bestConfig);
        return bestConfig;
    }

    private double simulerSurHistorique(LotoService.AlgoConfig config, List<LotoTirage> historiqueComplet, int nbTiragesTest) {
        double depense = 0;
        double gain = 0;

        // Sécurité
        if (historiqueComplet.size() < nbTiragesTest + 100) return 0.0;

        for (int i = 0; i < nbTiragesTest; i++) {
            int targetIndex = i;
            LotoTirage tirageReel = historiqueComplet.get(targetIndex);

            // --- OPTIMISATION ---
            // On ne prend que les 300 tirages précédant le tirage cible pour l'analyse
            // Cela accélère énormément les streams et boules dans LotoService
            int endSubList = Math.min(targetIndex + 300, historiqueComplet.size());
            List<LotoTirage> historiqueConnu = historiqueComplet.subList(targetIndex + 1, endSubList);
            // --------------------

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
