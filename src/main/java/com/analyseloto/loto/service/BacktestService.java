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
        double maxROI = -Double.MAX_VALUE;

        // Définition des plages de test (Grid Search simplifié)
        // On teste des variations raisonnables autour des valeurs par défaut
        double[] poidsFormeOpts = {10.0, 15.0, 25.0, 40.0};
        double[] poidsEcartOpts = {0.1, 0.5, 1.0};
        double[] poidsMarkovOpts = {0.0, 5.0, 15.0};

        int iterations = 0;

        for (double pForme : poidsFormeOpts) {
            for (double pEcart : poidsEcartOpts) {
                for (double pMarkov : poidsMarkovOpts) {

                    LotoService.AlgoConfig configTest = new LotoService.AlgoConfig(
                            "TEST_" + iterations++,
                            3.0, // FreqJour fixe
                            pForme,
                            pEcart,
                            12.0, // Tension fixe
                            pMarkov,
                            false
                    );

                    // On teste cette config sur les 20 derniers tirages (Simulation)
                    double roi = simulerSurHistorique(configTest, historiqueComplet, 20);

                    if (roi > maxROI) {
                        maxROI = roi;
                        bestConfig = configTest;
                        log.info("🚀 Nouveau record trouvé ! ROI: {}% avec Config: {}", String.format("%.2f", roi), configTest);
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

        // On remonte dans le temps
        // history.size() - 1 est le plus récent.
        // On commence à (size - nbTiragesTest) et on avance.

        // Sécurité
        if (historiqueComplet.size() < nbTiragesTest + 50) return 0.0;

        for (int i = 0; i < nbTiragesTest; i++) {
            // L'index du tirage "cible" qu'on essaie de deviner
            int targetIndex = i;

            // Le tirage qu'on doit trouver
            LotoTirage tirageReel = historiqueComplet.get(targetIndex);

            // L'historique connu À CE MOMENT LÀ (tout ce qui est APRÈS dans la liste, car trié DESC)
            List<LotoTirage> historiqueConnu = historiqueComplet.subList(targetIndex + 1, historiqueComplet.size());

            // On demande à LotoService de générer 5 grilles avec cet historique tronqué
            // ATTENTION : Il faut adapter LotoService pour accepter un historique externe (voir modif ci-dessous)
            // Pour l'instant, on simule l'appel :
            List<List<Integer>> grillesGenerees = lotoService.genererGrillesPourSimulation(historiqueConnu, config, 5);

            depense += (grillesGenerees.size() * 2.20);

            // Vérification des gains
            for (List<Integer> g : grillesGenerees) {
                // On simplifie le calcul des gains pour la simulation (Rang 1 à 6 approximé)
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
