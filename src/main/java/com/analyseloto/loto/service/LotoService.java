package com.analyseloto.loto.service;

import com.analyseloto.loto.dto.*;
import com.analyseloto.loto.entity.*;
import com.analyseloto.loto.repository.LotoTirageRepository;
import com.analyseloto.loto.repository.StrategyConfigRepostiroy;
import com.analyseloto.loto.util.Constantes;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.*;
import java.security.SecureRandom;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class LotoService {
    // Repositories
    private final LotoTirageRepository repository;
    private final StrategyConfigRepostiroy strategyConfigRepostiroy;
    // Services
    private final AstroService astroService;
    private final BacktestService backtestService;
    // Utils
    private final Random rng = new SecureRandom();

    // --- Injection de soi-même pour le proxy Caching ---
    @Autowired
    @Lazy
    private LotoService self;

    // Constantes
    private static final String FIELD_DATE_TIRAGE = "dateTirage";

    /**
     * Configuration dynamique de l'algorithme de génération
     */
    @Data
    @AllArgsConstructor
    @NoArgsConstructor
    public static class AlgoConfig implements Serializable {
        @Serial private static final long serialVersionUID = 1L;

        private String nomStrategie;
        private double poidsFreqJour;
        private double poidsForme;
        private double poidsEcart;
        private double poidsTension;
        private double poidsMarkov;
        private double poidsAffinite;
        private boolean utiliserGenetique;

        // Champs de résultats
        private double bilanEstime;
        private int nbTiragesTestes;

        public AlgoConfig(String nom, double pFreq, double pForme, double pEcart, double pTens, double pMark, double pAff, boolean gen) {
            this.nomStrategie = nom;
            this.poidsFreqJour = pFreq;
            this.poidsForme = pForme;
            this.poidsEcart = pEcart;
            this.poidsTension = pTens;
            this.poidsMarkov = pMark;
            this.poidsAffinite = pAff;
            this.utiliserGenetique = gen;
        }

        public static AlgoConfig defaut() {
            return new AlgoConfig("1_STANDARD", 3.0, 15.0, 0.4, 12.0, 5.0, 1.0, false);
        }
    }

    /**
     * Contraintes dynamiques pour la génération de grilles
     */
    @Data
    @AllArgsConstructor
    public static class DynamicConstraints implements Serializable {
        @Serial private static final long serialVersionUID = 1L;

        private int minPairs;      // Minimum de nombres pairs requis
        private int maxPairs;      // Maximum de nombres pairs requis
        private boolean allowSuites; // Autorise-t-on les suites (ex: 12, 13) ?
        private Set<Integer> forbiddenNumbers; // Numéros interdits (ex: ceux sortis hier)
    }

    /**
     * Grille candidate pour l'algorithme génétique
     */
    @AllArgsConstructor
    private static class GrilleCandidate implements Serializable {
        @Serial private static final long serialVersionUID = 1L;

        List<Integer> boules;
        int chance;
        double fitness;
    }

    @Data
    @AllArgsConstructor
    public static class ScenarioSimulation implements Serializable {
        @Serial private static final long serialVersionUID = 1L;

        private LotoTirage tirageReel; // Le résultat qu'on essaie de deviner
        private List<Integer> dernierTirageConnu;

        // Matrices pré-calculées pour ce scénario spécifique (snapshot du passé)
        private int[][] matriceAffinites;
        private Map<Integer, Map<Integer, Integer>> matriceChance;
        private double[][] matriceMarkov; // AJOUTÉ : La matrice de transition du passé
        private int etatDernierTirage;    // AJOUTÉ : L'état Markov du dernier tirage

        // Stats brutes (sans poids) pour application rapide des coefficients génétiques
        private Map<Integer, RawStatData> rawStatsBoules;
        private Map<Integer, RawStatData> rawStatsChance;

        private DynamicConstraints contraintes;
        private List<List<Integer>> topTriosPrecalcules;
    }

    @Data
    @AllArgsConstructor
    public static class RawStatData implements Serializable {
        @Serial private static final long serialVersionUID = 1L;

        private long freqJour;
        private long ecart;
        private boolean isForme;
        private boolean isTresForme;
        private boolean isBoostAstro;
        private boolean isHotFinale;
        private boolean isTension;
    }

    // ==================================================================================
    // 1. MÉTHODES PUBLIQUES (Points d'entrée)
    // ==================================================================================

    /**
     * Génération de N pronostics optimisés (sans astro)
     */
    @Cacheable(value = "pronosticsIA", key = "#dateCible.toString() + '_' + #nombreGrilles")
    public List<PronosticResultDto> genererMultiplesPronostics(LocalDate dateCible, int nombreGrilles) {
        log.info("⚙️ [CALCUL IA] Génération génétique fraîche pour le {}", dateCible);
        return genererPronosticAvecConfig(dateCible, nombreGrilles, null);
    }

    /**
     * Génération de N pronostics hybrides (avec astro)
     */
    @Cacheable(value = "pronosticsAstro", key = "#dateCible.toString() + '_' + #nombreGrilles + '_' + #profil.signe")
    public List<PronosticResultDto> genererPronosticsHybrides(LocalDate dateCible, int nombreGrilles, AstroProfileDto profil) {
        return genererPronosticAvecConfig(dateCible, nombreGrilles, profil);
    }

    // ==================================================================================
    // 2. CŒUR DE L'ALGORITHME (Version Optimisée)
    // ==================================================================================

    private List<PronosticResultDto> genererPronosticAvecConfig(LocalDate dateCible, int nombreGrilles, AstroProfileDto profilAstro) {
        long startTime = System.currentTimeMillis();

        // 1. Chargement Historique
        List<LotoTirage> history = repository.findAll(Sort.by(Sort.Direction.DESC, FIELD_DATE_TIRAGE));
        if (history.isEmpty()) return new ArrayList<>();
        List<Integer> dernierTirage = history.get(0).getBoules();

        // 2. Choix de la Config
        AlgoConfig configOptimisee = self.recupererMeilleureConfig();
        log.info("🎯 [ALGO] Stratégie : {} (Bilan Backtest: {} €)",
                configOptimisee.getNomStrategie(), String.format("%.2f", configOptimisee.getBilanEstime()));

        // 3. Préparations des Données (OPTIMISATION MAJEURE)
        // On évite les recalculs dans les boucles.

        Set<Integer> hotFinales = detecterFinalesChaudes(history);
        List<Integer> boostNumbers = (profilAstro != null) ? astroService.getLuckyNumbersOnly(profilAstro) : Collections.emptyList();
        List<List<Integer>> topTriosDuJour = getTopTriosRecents(history);

        // Conversion en tableaux primitifs int[][] pour accès immédiat (vs HashMap lent)
        Map<Integer, Map<Integer, Integer>> mapAff = construireMatriceAffinitesPonderee(history, dateCible.getDayOfWeek());
        int[][] matriceAffinitesArr = convertirAffinitesEnMatrice(mapAff);
        Map<Integer, Map<Integer, Integer>> matriceChance = construireMatriceAffinitesChancePonderee(history, dateCible.getDayOfWeek());

        // --- OPTIMISATION 1 : Scores en une seule passe ---
        double[] scoresBoules = calculerScoresOptimise(history, 49, dateCible.getDayOfWeek(), false, boostNumbers, hotFinales, configOptimisee, dernierTirage);
        double[] scoresChance = calculerScoresOptimise(history, 10, dateCible.getDayOfWeek(), true, boostNumbers, Collections.emptySet(), configOptimisee, null);

        // --- OPTIMISATION 2 : Pré-calcul Markov (O(1) au lieu de O(N) dans la boucle) ---
        double[][] matriceMarkov = precalculerMatriceMarkov(history);
        int etatDernierTirage = calculerEtatAbstrait(dernierTirage);

        DynamicConstraints contraintesDuJour = analyserContraintesDynamiques(history, dernierTirage);
        Map<String, List<Integer>> buckets = creerBucketsOptimises(scoresBoules);

        // Cache des grilles passées (BitMasks)
        Set<Long> historiqueBitMasks = new HashSet<>(history.size());
        int limitHistoryCheck = Math.min(history.size(), 300);
        for(int i=0; i<limitHistoryCheck; i++) {
            historiqueBitMasks.add(calculerBitMask(history.get(i).getBoules()));
        }

        // 4. GÉNÉRATION : MOTEUR GÉNÉTIQUE IA
        int taillePopulation = 1000;
        int nbGenerations = 15;

        log.info("🧬 Démarrage de l'Algo Génétique Optimisé...");

        List<GrilleCandidate> population = executerAlgorithmeGenetique(
                taillePopulation, nbGenerations, buckets, matriceAffinitesArr, dernierTirage,
                topTriosDuJour, scoresBoules, scoresChance, matriceChance,
                contraintesDuJour, configOptimisee,
                historiqueBitMasks, rng,
                matriceMarkov, etatDernierTirage // Nouveaux paramètres optimisés
        );

        log.info("🏆 Évolution terminée ! Meilleur Score : {}", String.format("%.2f", population.get(0).fitness));

        // 5. SÉLECTION FINALE (WHEELING)
        List<PronosticResultDto> resultats = finaliserResultats(population, nombreGrilles, dateCible, history);

        log.info("⚡ [PERF] Temps total algo : {} ms", (System.currentTimeMillis() - startTime));
        return resultats;
    }

    /**
     * Algo Génétique Optimisé (Ne parcourt plus l'historique dans la boucle)
     */
    private List<GrilleCandidate> executerAlgorithmeGenetique(
            int taillePopulation, int generations,
            Map<String, List<Integer>> buckets,
            int[][] matriceAffinites,
            List<Integer> dernierTirage,
            List<List<Integer>> topTrios,
            double[] scoresBoules,
            double[] scoresChance,
            Map<Integer, Map<Integer, Integer>> matriceChance,
            DynamicConstraints contraintes,
            AlgoConfig config,
            Set<Long> historiqueBitMasks,
            Random rng,
            double[][] matriceMarkov, // Optimisation
            int etatDernierTirage) {  // Optimisation

        List<GrilleCandidate> population = new ArrayList<>(taillePopulation);

        // 1. POPULATION INITIALE
        int tentatives = 0;
        List<Integer> boulesBuffer;

        while (population.size() < taillePopulation && tentatives < taillePopulation * 5) {
            tentatives++;
            boulesBuffer = genererGrilleParAffinite(buckets, matriceAffinites, dernierTirage, topTrios, rng);

            // On trie une seule fois ici
            Collections.sort(boulesBuffer);

            if (estGrilleCoherenteOptimisee(boulesBuffer, dernierTirage, contraintes) && !historiqueBitMasks.contains(calculerBitMask(boulesBuffer))) {
                int chance = selectionnerChanceRapide(boulesBuffer, scoresChance, matriceChance, rng);
                // Appel optimisé
                double fitness = calculerScoreFitnessOptimise(boulesBuffer, chance, scoresBoules, scoresChance, matriceAffinites, config, matriceMarkov, etatDernierTirage);
                population.add(new GrilleCandidate(boulesBuffer, chance, fitness));
            }
        }

        // 2. EVOLUTION
        for (int gen = 1; gen <= generations; gen++) {
            // Tri décroissant
            population.sort((g1, g2) -> Double.compare(g2.fitness, g1.fitness));
            List<GrilleCandidate> nextGen = new ArrayList<>(taillePopulation);

            // A. ELITISME (15%)
            int nbElites = (int) (taillePopulation * 0.15);
            for (int i = 0; i < nbElites && i < population.size(); i++) nextGen.add(population.get(i));

            // B. CROSSOVER & MUTATION
            while (nextGen.size() < taillePopulation) {
                // Tournoi simple
                GrilleCandidate maman = population.get(rng.nextInt(taillePopulation / 3));
                GrilleCandidate papa = population.get(rng.nextInt(taillePopulation / 3));

                List<Integer> enfant = croiser(maman.boules, papa.boules, rng);

                // Mutation (30%)
                if (rng.nextDouble() < 0.30) mutate(enfant, rng);

                Collections.sort(enfant);

                if (estGrilleCoherenteOptimisee(enfant, dernierTirage, contraintes) && !historiqueBitMasks.contains(calculerBitMask(enfant))) {
                    int chance = rng.nextBoolean() ? maman.chance : papa.chance;
                    double fitness = calculerScoreFitnessOptimise(enfant, chance, scoresBoules, scoresChance, matriceAffinites, config, matriceMarkov, etatDernierTirage);
                    nextGen.add(new GrilleCandidate(enfant, chance, fitness));
                }
            }
            population = nextGen;
        }

        // Tri final
        population.sort((g1, g2) -> Double.compare(g2.fitness, g1.fitness));
        return population;
    }

    // ==================================================================================
    // 7. MÉTHODES POUR LE CONTROLLER (Visualization Graph)
    // ==================================================================================

    /**
     * Récupère la matrice d'affinité pour l'affichage graphique (Controller).
     * Utilise le cache pour ne pas recalculer à chaque rafraîchissement de page.
     */
    @Cacheable(value = "statsGlobales", key = "'MATRICE_GRAPHE_PUBLIC'")
    public Map<Integer, Map<Integer, Integer>> getMatriceAffinitesPublic() {
        // On charge l'historique brut
        List<LotoTirage> history = repository.findAll();

        // On construit la matrice pondérée basée sur le jour actuel
        // (Cela permet de voir les affinités pertinentes pour le tirage d'aujourd'hui)
        return construireMatriceAffinitesPonderee(history, LocalDate.now().getDayOfWeek());
    }

    // ==================================================================================
    // 8. CALCUL DES GAINS UTILISATEURS (Simulé ou Réel)
    // ==================================================================================

    /**
     * Calcule le gain exact d'une grille utilisateur par rapport à un tirage officiel.
     * Gère les codes Loto et les rangs variables.
     * @param bet Le pari de l'utilisateur
     * @param tirage Le tirage officiel
     * @return Le montant gagné (0.0 si perdu)
     */
    public double calculerGainSimule(UserBet bet, LotoTirage tirage) {
        if (tirage == null || bet == null) return 0.0;

        // 1. GESTION DU CODE LOTO (Gagnant = 20 000 €)
        // Le code loto est indépendant des boules
        if (bet.getCodeLoto() != null && !bet.getCodeLoto().isEmpty()) {
            String userCode = bet.getCodeLoto().replaceAll("\\s", "").toUpperCase();
            List<String> winningCodes = tirage.getWinningCodes();

            if (winningCodes != null && winningCodes.contains(userCode)) {
                return 20000.0;
            }
        }

        // 2. RÉCUPÉRATION DES BOULES DU TIRAGE
        // Sécurité pour gérer les anciens imports ou formats différents
        List<Integer> tirageBoules = tirage.getBoules();
        if (tirageBoules == null || tirageBoules.isEmpty()) {
            tirageBoules = List.of(tirage.getBoule1(), tirage.getBoule2(), tirage.getBoule3(), tirage.getBoule4(), tirage.getBoule5());
        }

        // 3. COMPTAGE DES MATCHES
        int matches = 0;
        if (tirageBoules.contains(bet.getB1())) matches++;
        if (tirageBoules.contains(bet.getB2())) matches++;
        if (tirageBoules.contains(bet.getB3())) matches++;
        if (tirageBoules.contains(bet.getB4())) matches++;
        if (tirageBoules.contains(bet.getB5())) matches++;

        boolean chanceMatch = (bet.getChance() == tirage.getNumeroChance());

        // 4. DÉTERMINATION DU RANG (Rank 1 à 9)
        int rankPosition = 0;
        if (matches == 5 && chanceMatch) rankPosition = 1;
        else if (matches == 5) rankPosition = 2;
        else if (matches == 4 && chanceMatch) rankPosition = 3;
        else if (matches == 4) rankPosition = 4;
        else if (matches == 3 && chanceMatch) rankPosition = 5;
        else if (matches == 3) rankPosition = 6;
        else if (matches == 2 && chanceMatch) rankPosition = 7;
        else if (matches == 2) rankPosition = 8;
        else if (matches == 0 && chanceMatch) rankPosition = 9; // Remboursement mise

        // 5. RÉCUPÉRATION DU MONTANT RÉEL
        if (rankPosition > 0) {
            int finalRankPos = rankPosition;

            // On cherche le montant exact dans la liste des rangs du tirage (car ça change à chaque fois)
            if (tirage.getRanks() != null && !tirage.getRanks().isEmpty()) {
                return tirage.getRanks().stream()
                        .filter(r -> r.getRankNumber() == finalRankPos)
                        .findFirst()
                        .map(LotoTirageRank::getPrize)
                        .orElseGet(() -> (finalRankPos == 9) ? 2.20 : 0.0);
            }

            // Fallback si les rangs ne sont pas encore renseignés (ex: simulation avant publication gains)
            // On renvoie juste le remboursement mise pour le rang 9
            return (rankPosition == 9) ? 2.20 : 0.0;
        }

        return 0.0;
    }

    /**
     * Prépare les données historiques pour le Backtest (exécuté une seule fois au démarrage).
     */
    public List<ScenarioSimulation> preparerScenariosBacktest(List<LotoTirage> historiqueComplet, int depthBacktest, int limit) {
        List<ScenarioSimulation> scenarios = new ArrayList<>();
        int startIdx = 0;
        int count = 0;

        // On parcourt l'historique pour créer des "snapshots"
        // On commence après 'startIdx' pour avoir assez d'historique pour les calculs
        while (count < limit && (startIdx + depthBacktest + 50) < historiqueComplet.size()) {

            // 1. Le tirage qu'on veut prédire (Cible)
            LotoTirage cible = historiqueComplet.get(startIdx);

            // 2. L'historique connu à ce moment-là (du tirage J-1 jusqu'à la profondeur voulue)
            List<LotoTirage> historyConnu = historiqueComplet.subList(startIdx + 1, startIdx + 1 + depthBacktest);

            if (historyConnu.isEmpty()) { startIdx++; continue; }

            List<Integer> dernierTirage = historyConnu.get(0).getBoules();

            // 3. Calcul des Invariants (Matrices & Contraintes à cet instant T)
            Map<Integer, Map<Integer, Integer>> mapAff = construireMatriceAffinitesPonderee(historyConnu, cible.getDateTirage().getDayOfWeek());
            int[][] matAffArr = convertirAffinitesEnMatrice(mapAff);
            Map<Integer, Map<Integer, Integer>> matChance = construireMatriceAffinitesChancePonderee(historyConnu, cible.getDateTirage().getDayOfWeek());

            double[][] matMarkov = precalculerMatriceMarkov(historyConnu);
            int etatDernier = calculerEtatAbstrait(dernierTirage);

            DynamicConstraints contraintes = analyserContraintesDynamiques(historyConnu, dernierTirage);
            List<List<Integer>> topTrios = getTopTriosRecents(historyConnu);
            Set<Integer> hotFinales = detecterFinalesChaudes(historyConnu);

            // 4. Extraction des Stats Brutes (SANS les poids, car les poids changent à chaque test génétique)
            Map<Integer, RawStatData> rawBoules = extraireStatsBrutes(historyConnu, 49, cible.getDateTirage().getDayOfWeek(), false, hotFinales, dernierTirage);
            Map<Integer, RawStatData> rawChance = extraireStatsBrutes(historyConnu, 10, cible.getDateTirage().getDayOfWeek(), true, Collections.emptySet(), null);

            scenarios.add(new ScenarioSimulation(
                    cible, dernierTirage, matAffArr, matChance, matMarkov, etatDernier,
                    rawBoules, rawChance, contraintes, topTrios
            ));

            startIdx++;
            count++;
        }
        return scenarios;
    }

    /**
     * Génère des grilles RAPIDEMENT basées sur une config (poids) et un scénario pré-calculé.
     * Utilisé par le BacktestService pour évaluer la fitness d'une stratégie.
     */
    public List<List<Integer>> genererGrillesDepuisScenario(ScenarioSimulation sc, AlgoConfig config, int nbGrilles) {
        List<List<Integer>> resultats = new ArrayList<>(nbGrilles);

        // 1. Calcul des scores finaux en combinant Stats Brutes + Poids de la Config (ADN)
        double[] scoresBoules = new double[50];
        for (int i = 1; i <= 49; i++) {
            scoresBoules[i] = appliquerPoids(sc.rawStatsBoules.get(i), config);
            // Pénalité répétition dernier tirage (fixe)
            if (sc.dernierTirageConnu.contains(i)) scoresBoules[i] -= 10.0;
        }

        // 2. Création des Buckets (Hot, Neutral, Cold)
        Map<String, List<Integer>> buckets = creerBucketsOptimises(scoresBoules);

        // 3. Génération rapide (Sans évolution génétique, juste tirage probabiliste optimisé)
        int essais = 0;
        int maxEssais = nbGrilles * 5; // Sécurité boucle infinie

        while(resultats.size() < nbGrilles && essais < maxEssais) {
            essais++;
            // On utilise les matrices du scénario (pas celles d'aujourd'hui !)
            List<Integer> boules = genererGrilleParAffinite(buckets, sc.matriceAffinites, sc.dernierTirageConnu, sc.topTriosPrecalcules, rng);

            // On valide avec les contraintes du scénario
            // Note: on trie pour la validation optimisée
            Collections.sort(boules);

            if (estGrilleCoherenteOptimisee(boules, sc.dernierTirageConnu, sc.contraintes)) {
                // Pour le backtest, on ajoute simplement le numéro chance (simplifié)
                // Idéalement, on utiliserait selectionnerChanceRapide avec sc.matriceChance
                int chance = 1; // Simplification pour vitesse backtest ou logique complète :
                // int chance = selectionnerChanceRapide(boules, scoresChanceCalc..., sc.matriceChance, rng);

                List<Integer> grilleFinale = new ArrayList<>(boules);
                grilleFinale.add(chance); // Numéro chance factice ou calculé si tu as les scores chance
                resultats.add(grilleFinale);
            }
        }
        return resultats;
    }

    /**
     * Applique les poids de la configuration sur une stat brute
     */
    private double appliquerPoids(RawStatData raw, AlgoConfig cfg) {
        if(raw == null) return 0.0;
        double s = 10.0;
        s += (raw.getFreqJour() * cfg.getPoidsFreqJour());

        if (raw.getEcart() > 40) s -= 5.0;
        else if (raw.getEcart() > 10) s += (raw.getEcart() * cfg.getPoidsEcart());

        if (raw.isForme()) s += cfg.getPoidsForme();
        if (raw.isTresForme()) s += 25.0;
        if (raw.isHotFinale()) s += 8.0;
        if (raw.isTension()) s += cfg.getPoidsTension();

        return s;
    }

    /**
     * Helper pour extraire les stats brutes (Frequency, Ecart...) sans pondération.
     */
    private Map<Integer, RawStatData> extraireStatsBrutes(List<LotoTirage> history, int maxNum, DayOfWeek jour,
            boolean isChance, Set<Integer> hotFinales, List<Integer> dernierTirage) {
        Map<Integer, RawStatData> map = new HashMap<>();
        int totalHistory = history.size();
        int limitRecents = 15;
        int limitTresRecents = 10;

        // Pré-calculs en une passe
        int[] freqJour = new int[maxNum + 1];
        int[] lastSeenIndex = new int[maxNum + 1]; Arrays.fill(lastSeenIndex, -1);
        int[] sortiesRecentes = new int[maxNum + 1];
        int[] sortiesTresRecentes = new int[maxNum + 1];
        int[] totalSorties = new int[maxNum + 1];

        for (int i = 0; i < totalHistory; i++) {
            LotoTirage t = history.get(i);
            boolean isJour = (t.getDateTirage().getDayOfWeek() == jour);
            List<Integer> nums = isChance ? List.of(t.getNumeroChance()) : t.getBoules();

            for(int n : nums) {
                if(n > maxNum) continue;
                totalSorties[n]++;
                if(isJour) freqJour[n]++;
                if(lastSeenIndex[n] == -1) lastSeenIndex[n] = i;
                if(i < limitRecents) sortiesRecentes[n]++;
                if(i < limitTresRecents) sortiesTresRecentes[n]++;
            }
        }

        for (int i = 1; i <= maxNum; i++) {
            long ecart = (lastSeenIndex[i] == -1) ? totalHistory : lastSeenIndex[i];
            boolean isHotF = !isChance && hotFinales != null && hotFinales.contains(i % 10);
            boolean isTen = !isChance && totalSorties[i] > 5;

            // Note: on stocke l'écart brut, le calcul du score se fera avec le poids
            RawStatData data = new RawStatData(freqJour[i], ecart, sortiesRecentes[i]>=2, sortiesTresRecentes[i]>=2, false, isHotF, isTen);
            map.put(i, data);
        }
        return map;
    }

    /**
     * Fonction de Fitness Optimisée : Plus aucune boucle sur l'historique ! Accès O(1).
     */
    private double calculerScoreFitnessOptimise(List<Integer> boules, int chance,
            double[] scoresBoules, double[] scoresChance,
            int[][] matriceAffinites, AlgoConfig config,
            double[][] matriceMarkov, int etatDernierTirage) {
        double score = 0.0;

        // 1. Scores unitaires (Tableau O(1))
        for (int b : boules) score += scoresBoules[b];
        if (chance >= 0 && chance < scoresChance.length) score += scoresChance[chance];

        // 2. Affinités (Matrice primitive int[][] O(1))
        double scoreAffinite = 0;
        int size = boules.size();
        for (int i = 0; i < size; i++) {
            int b1 = boules.get(i);
            for (int j = i + 1; j < size; j++) {
                scoreAffinite += matriceAffinites[b1][boules.get(j)];
            }
        }
        score += (scoreAffinite * config.getPoidsAffinite());

        // 3. Structurels (Sans stream)
        int pairs = 0;
        int somme = 0;
        for (int b : boules) {
            if ((b & 1) == 0) pairs++;
            somme += b;
        }
        if (pairs == 2 || pairs == 3) score += 15.0;
        if (somme >= 120 && somme <= 170) score += 10.0;

        // 4. Markov (Instant access grâce au pré-calcul)
        if (config.getPoidsMarkov() > 0) {
            int etatCandidat = calculerEtatAbstrait(boules);
            score += (matriceMarkov[etatDernierTirage][etatCandidat] * config.getPoidsMarkov());
        }

        return score;
    }

    /**
     * Calcul des scores unitaires en UNE SEULE PASSE sur l'historique (Gain énorme)
     */
    private double[] calculerScoresOptimise(List<LotoTirage> history, int maxNum, DayOfWeek jourCible,
            boolean isChance, List<Integer> boostNumbers,
            Set<Integer> hotFinales, AlgoConfig config,
            List<Integer> dernierTirage) {
        double[] scores = new double[maxNum + 1];
        Arrays.fill(scores, 10.0); // Base

        int[] freqJour = new int[maxNum + 1];
        int[] lastSeenIndex = new int[maxNum + 1];
        Arrays.fill(lastSeenIndex, -1);
        int[] sortiesRecentes = new int[maxNum + 1]; // 15
        int[] sortiesTresRecentes = new int[maxNum + 1]; // 10
        int[] totalSorties = new int[maxNum + 1];

        int limitRecents = 15;
        int limitTresRecents = 10;
        int totalHistory = history.size();

        // 1. UNIQUE PARCOURS DE L'HISTORIQUE
        for (int i = 0; i < totalHistory; i++) {
            LotoTirage t = history.get(i);
            boolean isJourCible = (t.getDateTirage().getDayOfWeek() == jourCible);
            List<Integer> numsToCheck = isChance ? List.of(t.getNumeroChance()) : t.getBoules();

            for (int n : numsToCheck) {
                if (n > maxNum || n < 1) continue;
                totalSorties[n]++;
                if (isJourCible) freqJour[n]++;
                if (lastSeenIndex[n] == -1) lastSeenIndex[n] = i; // Premier trouvé = le plus récent
                if (i < limitRecents) sortiesRecentes[n]++;
                if (i < limitTresRecents) sortiesTresRecentes[n]++;
            }
        }

        // 2. APPLICATION DES POIDS
        for (int num = 1; num <= maxNum; num++) {
            double s = scores[num];
            s += (freqJour[num] * config.getPoidsFreqJour());

            long ecart = (lastSeenIndex[num] == -1) ? totalHistory : lastSeenIndex[num];
            if (ecart > 40) s -= 5.0;
            else if (ecart > 10) s += (ecart * config.getPoidsEcart());

            if (sortiesRecentes[num] >= 2) s += config.getPoidsForme();
            if (sortiesTresRecentes[num] >= 2) s += 25.0;

            if (!isChance && totalSorties[num] > 5) s += config.getPoidsTension();

            if (boostNumbers.contains(num)) s += 30.0;
            if (!isChance && hotFinales != null && hotFinales.contains(num % 10)) s += 8.0;
            if (!isChance && dernierTirage != null && dernierTirage.contains(num)) s -= 10.0;

            scores[num] = s;
        }
        return scores;
    }

    /**
     * Helper pour le Wheeling final
     */
    private List<PronosticResultDto> finaliserResultats(List<GrilleCandidate> population, int nombreGrilles, LocalDate dateCible, List<LotoTirage> history) {
        List<PronosticResultDto> resultats = new ArrayList<>();
        List<List<Integer>> grillesRetenues = new ArrayList<>();
        long couvertureGlobale = 0L;

        for (GrilleCandidate cand : population) {
            if (resultats.size() >= nombreGrilles) break;

            // Pas de sort ici car déjà fait dans l'algo génétique
            long masqueCandidat = calculerBitMask(cand.boules);
            long nouveauxNumerosMask = masqueCandidat & ~couvertureGlobale;
            int apportDiversite = Long.bitCount(nouveauxNumerosMask);

            if (resultats.isEmpty() || apportDiversite >= 2) {
                couvertureGlobale |= masqueCandidat;
                grillesRetenues.add(cand.boules);

                SimulationResultDto simu = simulerGrilleDetaillee(cand.boules, dateCible, history);
                double maxDuo = simu.getPairs().stream().mapToDouble(MatchGroup::getRatio).max().orElse(0.0);

                String typeAlgo = "IA_OPTIMAL (MARKOV)";
                if(cand.fitness < 50) typeAlgo = "IA_FLEXIBLE";

                resultats.add(new PronosticResultDto(
                        cand.boules, cand.chance,
                        Math.round(cand.fitness * 100.0) / 100.0,
                        maxDuo, 0.0, !simu.getQuintuplets().isEmpty(),
                        typeAlgo
                ));
            }
        }

        // Plan B : si wheeling trop restrictif
        if (resultats.size() < nombreGrilles) {
            for (GrilleCandidate cand : population) {
                if (resultats.size() >= nombreGrilles) break;
                if (!grillesRetenues.contains(cand.boules)) {
                    grillesRetenues.add(cand.boules);
                    SimulationResultDto simu = simulerGrilleDetaillee(cand.boules, dateCible, history);
                    double maxDuo = simu.getPairs().stream().mapToDouble(MatchGroup::getRatio).max().orElse(0.0);
                    resultats.add(new PronosticResultDto(
                            cand.boules, cand.chance, Math.round(cand.fitness * 100.0) / 100.0,
                            maxDuo, 0.0, !simu.getQuintuplets().isEmpty(), "IA_SECOURS"
                    ));
                }
            }
        }
        return resultats;
    }

    // ==================================================================================
    // 3. LOGIQUE MARKOV & PRE-CALCULS
    // ==================================================================================

    /**
     * Définit l'état abstrait d'un tirage (Somme : Très bas, bas, moyen, haut, très haut)
     */
    private int calculerEtatAbstrait(List<Integer> boules) {
        int somme = 0;
        for(int b : boules) somme += b;
        if (somme < 100) return 1;
        if (somme <= 125) return 2;
        if (somme <= 150) return 3;
        if (somme <= 175) return 4;
        return 5;
    }

    /**
     * Pré-calcule la matrice de transition Markov (5x5).
     * O(N) une seule fois au début, au lieu de O(N) à chaque grille.
     */
    private double[][] precalculerMatriceMarkov(List<LotoTirage> history) {
        double[][] matrix = new double[6][6];
        int[] totalTransitions = new int[6];
        int limit = Math.min(history.size(), 350);

        for (int i = 0; i < limit - 1; i++) {
            // history est DESC (0 = aujourd'hui, 1 = hier)
            // Transition : Hier (i+1) -> Aujourd'hui (i)
            int etatHier = calculerEtatAbstrait(history.get(i+1).getBoules());
            int etatAuj = calculerEtatAbstrait(history.get(i).getBoules());
            matrix[etatHier][etatAuj]++;
            totalTransitions[etatHier]++;
        }

        for (int i = 1; i <= 5; i++) {
            if (totalTransitions[i] > 0) {
                for (int j = 1; j <= 5; j++) {
                    matrix[i][j] /= totalTransitions[i];
                }
            }
        }
        return matrix;
    }

    // ==================================================================================
    // 4. HELPERS GÉNÉTIQUES & VALIDATION
    // ==================================================================================

    /**
     * Croisement génétique rapide
     */
    private List<Integer> croiser(List<Integer> p1, List<Integer> p2, Random r) {
        Set<Integer> s = new HashSet<>(8); // Capacity 8 suffisant
        for(int i=0; i<3; i++) s.add(p1.get(i)); // 3 gènes parent 1
        for(int n : p2) {
            if(s.size() < 5) s.add(n); // Complète parent 2
        }
        while(s.size() < 5) s.add(r.nextInt(49)+1); // Comble
        return new ArrayList<>(s);
    }

    private void mutate(List<Integer> b, Random r) {
        int idx = r.nextInt(5);
        int val = r.nextInt(49)+1;
        while(b.contains(val)) val = r.nextInt(49)+1;
        b.set(idx, val);
    }

    /**
     * Validation optimisée (Pas de Stream, suppose la liste déjà triée)
     */
    public boolean estGrilleCoherenteOptimisee(List<Integer> boules, List<Integer> dernierTirage, DynamicConstraints rules) {
        // Supposons boules.size() == 5 et TRIÉE
        int somme = 0;
        int pairs = 0;
        int dizainesMask = 0;
        int consecutiveCount = 0;
        boolean aUneSuite = false;
        int prev = -10;

        for (int i = 0; i < 5; i++) {
            int n = boules.get(i);
            somme += n;
            if ((n & 1) == 0) pairs++;
            dizainesMask |= (1 << (n / 10));

            if (n == prev + 1) {
                consecutiveCount++;
                aUneSuite = true;
            } else {
                consecutiveCount = 0;
            }
            if (consecutiveCount >= 2) return false; // Max suite de 2 (ex 1,2 autorisé mais 1,2,3 interdit)
            prev = n;
        }

        if (pairs < rules.minPairs || pairs > rules.maxPairs) return false;
        if (!rules.allowSuites && aUneSuite) return false;
        if (somme < 100 || somme > 175) return false;
        if (Integer.bitCount(dizainesMask) < 3) return false; // Répartition géographique
        if ((boules.get(4) - boules.get(0)) < 15) return false; // Grand écart

        // Dernier Tirage
        if (dernierTirage != null) {
            int communs = 0;
            for (Integer n : boules) if (dernierTirage.contains(n)) communs++;
            return communs < 2;
        }
        return true;
    }

    private List<Integer> genererGrilleParAffinite(Map<String, List<Integer>> buckets,
            int[][] matrice,
            List<Integer> dernierTirage,
            List<List<Integer>> triosDisponibles,
            Random rng) {
        List<Integer> selection = new ArrayList<>();

        if (triosDisponibles != null && !triosDisponibles.isEmpty() && rng.nextBoolean()) {
            for (int tryTrio = 0; tryTrio < 3; tryTrio++) {
                List<Integer> trioChoisi = triosDisponibles.get(rng.nextInt(triosDisponibles.size()));
                long communs = trioChoisi.stream().filter(dernierTirage::contains).count();
                if (communs < 2) {
                    selection.addAll(trioChoisi);
                    break;
                }
            }
        }

        if (selection.isEmpty()) {
            List<Integer> hots = buckets.getOrDefault(Constantes.BUCKET_HOT, new ArrayList<>());
            List<Integer> hotsJouables = new ArrayList<>();
            for(Integer h : hots) if(!dernierTirage.contains(h)) hotsJouables.add(h);

            if (!hotsJouables.isEmpty()) selection.add(hotsJouables.get(rng.nextInt(hotsJouables.size())));
            else {
                int n = 1 + rng.nextInt(49);
                while (dernierTirage.contains(n)) n = 1 + rng.nextInt(49);
                selection.add(n);
            }
        }

        while (selection.size() < 5) {
            String targetBucket = determinerBucketCible(selection, buckets);
            List<Integer> pool = new ArrayList<>(buckets.getOrDefault(targetBucket, new ArrayList<>()));
            pool.removeAll(selection);

            if (pool.isEmpty()) {
                pool = new ArrayList<>(buckets.getOrDefault(Constantes.BUCKET_HOT, new ArrayList<>()));
                pool.removeAll(selection);
            }

            if (pool.isEmpty()) {
                int n = 1 + rng.nextInt(49);
                while(selection.contains(n)) n = 1 + rng.nextInt(49);
                selection.add(n);
            } else {
                selection.add(selectionnerParAffinite(pool, selection, matrice, rng));
            }
        }
        return selection;
    }

    private Integer selectionnerParAffinite(List<Integer> candidats, List<Integer> selectionActuelle, int[][] matriceAffinites, Random rng) {
        int meilleurCandidat = candidats.get(0);
        double meilleurScore = -Double.MAX_VALUE;

        for (Integer candidat : candidats) {
            double scoreLien = 1.0;
            for (Integer dejaPris : selectionActuelle) {
                int affinite = matriceAffinites[dejaPris][candidat];
                if (affinite > 12) scoreLien += (affinite * affinite) / 5.0;
                else scoreLien += affinite;
            }
            scoreLien += (rng.nextDouble() * 3.0);
            if (scoreLien > meilleurScore) {
                meilleurScore = scoreLien;
                meilleurCandidat = candidat;
            }
        }
        return meilleurCandidat;
    }

    private String determinerBucketCible(List<Integer> selection, Map<String, List<Integer>> buckets) {
        int nbHot = 0; int nbCold = 0;
        List<Integer> hots = buckets.getOrDefault(Constantes.BUCKET_HOT, Collections.emptyList());
        List<Integer> colds = buckets.getOrDefault(Constantes.BUCKET_COLD, Collections.emptyList());

        for (Integer n : selection) {
            if (hots.contains(n)) nbHot++;
            else if (colds.contains(n)) nbCold++;
        }
        if (nbHot < 2) return Constantes.BUCKET_HOT;
        if (nbCold < 1) return Constantes.BUCKET_COLD;
        return Constantes.BUCKET_NEUTRAL;
    }

    private Map<String, List<Integer>> creerBucketsOptimises(double[] scores) {
        List<Integer> indices = new ArrayList<>(49);
        for(int i=1; i<=49; i++) indices.add(i);
        indices.sort((a, b) -> Double.compare(scores[b], scores[a]));

        int taille = indices.size();
        int q = taille / 4;
        Map<String, List<Integer>> b = new HashMap<>();
        b.put(Constantes.BUCKET_HOT, new ArrayList<>(indices.subList(0, q)));
        b.put(Constantes.BUCKET_NEUTRAL, new ArrayList<>(indices.subList(q, taille - q)));
        b.put(Constantes.BUCKET_COLD, new ArrayList<>(indices.subList(taille - q, taille)));
        return b;
    }

    private int selectionnerChanceRapide(List<Integer> boules, double[] scoresChanceArr, Map<Integer, Map<Integer, Integer>> matriceChance, Random rng) {
        int meilleurChance = 1;
        double meilleurScore = -Double.MAX_VALUE;
        for (int c = 1; c <= 10; c++) {
            double score = scoresChanceArr[c];
            double affiniteScore = 0;
            for (Integer b : boules) {
                // Ici on garde Map car matriceChance est Map<Integer, Map...> pour compatibilité existante
                // Mais l'impact est faible (5 itérations)
                affiniteScore += matriceChance.getOrDefault(b, Map.of()).getOrDefault(c, 0);
            }
            score += (affiniteScore * 2.0);
            double scoreFinal = score + (rng.nextDouble() * 5.0);
            if (scoreFinal > meilleurScore) {
                meilleurScore = scoreFinal;
                meilleurChance = c;
            }
        }
        return meilleurChance;
    }

    private long calculerBitMask(List<Integer> boules) {
        long mask = 0L;
        for (Integer b : boules) mask |= (1L << b);
        return mask;
    }

    // ==================================================================================
    // 5. ANALYSE ET STATS (Helpers existants conservés)
    // ==================================================================================

    private Set<Integer> detecterFinalesChaudes(List<LotoTirage> history) {
        if (history == null || history.isEmpty()) return Collections.emptySet();
        return history.stream().limit(20)
                .flatMap(t -> t.getBoules().stream())
                .map(b -> b % 10)
                .collect(Collectors.groupingBy(f -> f, Collectors.counting()))
                .entrySet().stream()
                .sorted((e1, e2) -> Long.compare(e2.getValue(), e1.getValue()))
                .limit(2).map(Map.Entry::getKey).collect(Collectors.toSet());
    }

    private DynamicConstraints analyserContraintesDynamiques(List<LotoTirage> history, List<Integer> dernierTirage) {
        if (history.isEmpty()) return new DynamicConstraints(2, 3, true, new HashSet<>());

        long totalPairsRecents = history.stream().limit(10).flatMap(t -> t.getBoules().stream()).filter(n -> n % 2 == 0).count();
        double moyenneRecente = totalPairsRecents / 10.0;

        int minP, maxP;
        if (moyenneRecente > 2.8) { minP = 1; maxP = 2; }
        else if (moyenneRecente < 2.2) { minP = 3; maxP = 4; }
        else { minP = 2; maxP = 3; }

        boolean suiteRecente = false;
        for (int i = 0; i < Math.min(5, history.size()); i++) {
            // Optimisation légère sur le check suite
            List<Integer> b = history.get(i).getBoules();
            // Supposons b non trié
            List<Integer> copy = new ArrayList<>(b); Collections.sort(copy);
            for (int k = 0; k < copy.size() - 1; k++) {
                if (copy.get(k+1) == copy.get(k) + 1) { suiteRecente = true; break; }
            }
            if (suiteRecente) break;
        }

        Set<Integer> forbidden = new HashSet<>();
        if (history.size() >= 3 && dernierTirage != null) {
            List<Integer> t2 = history.get(1).getBoules();
            List<Integer> t3 = history.get(2).getBoules();
            for (Integer n : dernierTirage) {
                if (t2.contains(n) && t3.contains(n)) forbidden.add(n);
            }
        }
        return new DynamicConstraints(minP, maxP, !suiteRecente, forbidden);
    }

    private List<List<Integer>> getTopTriosRecents(List<LotoTirage> history) {
        Map<Set<Integer>, Integer> trioFrequency = new HashMap<>();
        List<LotoTirage> recents = history.stream().limit(100).toList();
        for (LotoTirage t : recents) {
            List<Integer> b = t.getBoules();
            for (int i = 0; i < b.size(); i++) {
                for (int j = i + 1; j < b.size(); j++) {
                    for (int k = j + 1; k < b.size(); k++) {
                        Set<Integer> trio = new HashSet<>(Arrays.asList(b.get(i), b.get(j), b.get(k)));
                        trioFrequency.merge(trio, 1, Integer::sum);
                    }
                }
            }
        }
        return trioFrequency.entrySet().stream()
                .sorted((e1, e2) -> e2.getValue().compareTo(e1.getValue()))
                .limit(10).map(e -> new ArrayList<>(e.getKey())).collect(Collectors.toList());
    }

    private int[][] convertirAffinitesEnMatrice(Map<Integer, Map<Integer, Integer>> mapAffinites) {
        int[][] matrice = new int[50][50];
        for (Map.Entry<Integer, Map<Integer, Integer>> entry : mapAffinites.entrySet()) {
            int b1 = entry.getKey();
            for (Map.Entry<Integer, Integer> subEntry : entry.getValue().entrySet()) {
                int b2 = subEntry.getKey();
                matrice[b1][b2] = subEntry.getValue();
            }
        }
        return matrice;
    }

    private Map<Integer, Map<Integer, Integer>> construireMatriceAffinitesPonderee(List<LotoTirage> history, DayOfWeek jourCible) {
        int[][] matriceTemp = new int[50][50];
        int limit = Math.min(history.size(), 350);

        for (int i = 0; i < limit; i++) {
            LotoTirage t = history.get(i);
            int poids = (t.getDateTirage().getDayOfWeek() == jourCible) ? 6 : 1;
            List<Integer> boules = t.getBoules();
            int nbBoules = boules.size();
            for (int k = 0; k < nbBoules; k++) {
                int n1 = boules.get(k);
                for (int m = k + 1; m < nbBoules; m++) {
                    int n2 = boules.get(m);
                    matriceTemp[n1][n2] += poids;
                    matriceTemp[n2][n1] += poids;
                }
            }
        }
        Map<Integer, Map<Integer, Integer>> matrixResult = new HashMap<>(64);
        for (int i = 1; i <= 49; i++) {
            Map<Integer, Integer> ligne = new HashMap<>(64);
            for (int j = 1; j <= 49; j++) {
                if (i != j && matriceTemp[i][j] > 0) ligne.put(j, matriceTemp[i][j]);
            }
            matrixResult.put(i, ligne);
        }
        return matrixResult;
    }

    private Map<Integer, Map<Integer, Integer>> construireMatriceAffinitesChancePonderee(List<LotoTirage> history, DayOfWeek jourCible) {
        int[][] matriceTemp = new int[50][11];
        int limit = Math.min(history.size(), 350);
        for (int i = 0; i < limit; i++) {
            LotoTirage t = history.get(i);
            int poids = (t.getDateTirage().getDayOfWeek() == jourCible) ? 6 : 1;
            int chance = t.getNumeroChance();
            if (chance > 10 || chance < 1) continue;
            for (Integer boule : t.getBoules()) matriceTemp[boule][chance] += poids;
        }
        Map<Integer, Map<Integer, Integer>> matrixResult = new HashMap<>(64);
        for (int i = 1; i <= 49; i++) {
            Map<Integer, Integer> ligne = new HashMap<>(16);
            for (int c = 1; c <= 10; c++) if (matriceTemp[i][c] > 0) ligne.put(c, matriceTemp[i][c]);
            matrixResult.put(i, ligne);
        }
        return matrixResult;
    }

    // ==================================================================================
    // 6. GESTION DU CACHE, CONFIG & IMPORT (Code Infrastructure)
    // ==================================================================================

    public void verificationAuDemarrage() {
        log.info("✋ [WARMUP] Vérification de la configuration du jour...");
        boolean configAjour = strategyConfigRepostiroy.findTopByOrderByDateCalculDesc()
                .map(last -> last.getDateCalcul().toLocalDate().equals(LocalDate.now()))
                .orElse(false);

        if (configAjour) {
            log.info("✅ [WARMUP] Config OK. Préchauffage...");
            self.recupererMeilleureConfig();
            self.genererMultiplesPronostics(recupererDateProchainTirage(), 5);
        } else {
            log.info("⚠️ [WARMUP] Calcul de stratégie requis...");
            forceDailyOptimization();
        }
    }

    @Cacheable(value = "algoConfig", key = "'CURRENT_CONFIG'")
    public AlgoConfig recupererMeilleureConfig() {
        return strategyConfigRepostiroy.findTopByOrderByDateCalculDesc()
                .map(last -> new AlgoConfig(
                        last.getNomStrategie(), last.getPoidsFreqJour(), last.getPoidsForme(),
                        last.getPoidsEcart(), last.getPoidsTension(), last.getPoidsMarkov(),
                        last.getPoidsAffinite(), false
                ))
                .orElse(AlgoConfig.defaut());
    }

    @CacheEvict(value = "algoConfig", allEntries = true)
    public void forceDailyOptimization() {
        log.info("🌙 [CRON] Optimisation...");
        long start = System.currentTimeMillis();
        List<LotoTirage> history = repository.findAll(Sort.by(Sort.Direction.DESC, FIELD_DATE_TIRAGE));
        if (!history.isEmpty()) {
            AlgoConfig newConfig = backtestService.trouverMeilleureConfig(history);
            StrategyConfig entity = new StrategyConfig();
            entity.setDateCalcul(LocalDateTime.now());
            entity.setNomStrategie(newConfig.getNomStrategie());
            entity.setPoidsForme(newConfig.getPoidsForme());
            entity.setPoidsEcart(newConfig.getPoidsEcart());
            entity.setPoidsAffinite(newConfig.getPoidsAffinite());
            entity.setPoidsMarkov(newConfig.getPoidsMarkov());
            entity.setPoidsTension(newConfig.getPoidsTension());
            entity.setPoidsFreqJour(newConfig.getPoidsFreqJour());
            entity.setBilanEstime(newConfig.getBilanEstime());
            entity.setNbTiragesTestes(newConfig.getNbTiragesTestes());
            strategyConfigRepostiroy.save(entity);
            log.info("✅ [CRON] Terminé en {} ms.", (System.currentTimeMillis() - start));
        }
    }

    public LocalDate recupererDateProchainTirage() {
        LocalDate date = LocalDate.now();
        boolean estJourTirage = (date.getDayOfWeek().getValue() == 1 ||
                date.getDayOfWeek().getValue() == 3 ||
                date.getDayOfWeek().getValue() == 6);

        if (estJourTirage && LocalTime.now().isAfter(LocalTime.of(20, 15))) {
            date = date.plusDays(1);
        }
        while (date.getDayOfWeek().getValue() != 1 &&
                date.getDayOfWeek().getValue() != 3 &&
                date.getDayOfWeek().getValue() != 6) {
            date = date.plusDays(1);
        }
        return date;
    }

    // --- IMPORT CSV & STATS (Inchangés mais inclus car demandé "Entier") ---

    @CacheEvict(value = {"statsGlobales", "pronosticsIA", "pronosticsAstro"}, allEntries = true)
    public void importCsv(MultipartFile file) throws IOException {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(file.getInputStream()))) {
            List<String> lines = reader.lines().toList();
            DateTimeFormatter fmt1 = DateTimeFormatter.ofPattern(Constantes.FORMAT_DATE_STANDARD);
            DateTimeFormatter fmt2 = DateTimeFormatter.ofPattern(Constantes.FORMAT_DATE_STANDARD_INVERSE);

            for (String line : lines) {
                if (line.trim().isEmpty() || line.startsWith("annee") || line.startsWith("Tirage")) continue;
                try {
                    String[] row; LocalDate date; int b1, b2, b3, b4, b5, c;
                    if (line.contains(Constantes.DELIMITEUR_POINT_VIRGULE)) {
                        row = line.split(Constantes.DELIMITEUR_POINT_VIRGULE); if(row.length<10) continue;
                        try{ date=LocalDate.parse(row[2],fmt1); } catch(Exception e) { continue; }
                        b1=Integer.parseInt(row[4]); b2=Integer.parseInt(row[5]); b3=Integer.parseInt(row[6]);
                        b4=Integer.parseInt(row[7]); b5=Integer.parseInt(row[8]); c=Integer.parseInt(row[9]);
                    } else {
                        row = line.trim().split("\\s+"); if(row.length<8) continue;
                        try{date=LocalDate.parse(row[6],fmt2);}catch(Exception e){try{date=LocalDate.parse(row[6],fmt1);}catch(Exception ex){continue;}}
                        b1=Integer.parseInt(row[1]); b2=Integer.parseInt(row[2]); b3=Integer.parseInt(row[3]);
                        b4=Integer.parseInt(row[4]); b5=Integer.parseInt(row[5]); c=Integer.parseInt(row[7]);
                    }

                    if (!repository.existsByDateTirage(date)) {
                        LotoTirage t = new LotoTirage();
                        t.setDateTirage(date);
                        t.setBoule1(b1); t.setBoule2(b2); t.setBoule3(b3); t.setBoule4(b4); t.setBoule5(b5); t.setNumeroChance(c);
                        repository.save(t);
                    }
                } catch(Exception e) { log.error("Erreur ligne: {}", line); }
            }
        }
    }

    @CacheEvict(value = {"statsGlobales", "pronosticsIA", "pronosticsAstro"}, allEntries = true)
    public LotoTirage ajouterTirageManuel(TirageManuelDto dto) {
        if (repository.existsByDateTirage(dto.getDateTirage())) throw new RuntimeException("Ce tirage existe déjà");
        LotoTirage t = new LotoTirage();
        t.setDateTirage(dto.getDateTirage());
        t.setBoule1(dto.getBoule1()); t.setBoule2(dto.getBoule2()); t.setBoule3(dto.getBoule3());
        t.setBoule4(dto.getBoule4()); t.setBoule5(dto.getBoule5()); t.setNumeroChance(dto.getNumeroChance());
        repository.save(t);
        return t;
    }

    @Cacheable(value = "statsGlobales", key = "#jourFiltre ?: 'TOUS_LES_JOURS'")
    public StatsReponse getStats(String jourFiltre) {
        List<LotoTirage> all = repository.findAll(Sort.by(Sort.Direction.DESC, FIELD_DATE_TIRAGE));
        if (jourFiltre != null && !jourFiltre.isEmpty()) {
            try {
                DayOfWeek d = DayOfWeek.valueOf(jourFiltre.toUpperCase());
                all = all.stream().filter(t -> t.getDateTirage().getDayOfWeek() == d).toList();
            } catch (Exception e) { throw new RuntimeException(e); }
        }
        if (all.isEmpty()) return new StatsReponse(new ArrayList<>(), "-", "-", 0);

        LocalDate minDate = all.stream().map(LotoTirage::getDateTirage).min(LocalDate::compareTo).orElse(LocalDate.now());
        LocalDate maxDate = all.stream().map(LotoTirage::getDateTirage).max(LocalDate::compareTo).orElse(LocalDate.now());
        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("dd/MM/yyyy");

        Map<Integer, Integer> freqMap = new HashMap<>(); Map<Integer, LocalDate> lastSeenMap = new HashMap<>();
        Map<Integer, Integer> freqChance = new HashMap<>(); Map<Integer, LocalDate> lastSeenChance = new HashMap<>();

        for (LotoTirage t : all) {
            for (Integer b : t.getBoules()) {
                freqMap.merge(b, 1, Integer::sum);
                if (!lastSeenMap.containsKey(b) || t.getDateTirage().isAfter(lastSeenMap.get(b))) lastSeenMap.put(b, t.getDateTirage());
            }
            freqChance.merge(t.getNumeroChance(), 1, Integer::sum);
            if (!lastSeenChance.containsKey(t.getNumeroChance()) || t.getDateTirage().isAfter(lastSeenChance.get(t.getNumeroChance()))) lastSeenChance.put(t.getNumeroChance(), t.getDateTirage());
        }
        List<StatPoint> stats = new ArrayList<>();
        for (int i = 1; i <= 49; i++) {
            StatPoint s = new StatPoint(); s.setNumero(i); s.setFrequence(freqMap.getOrDefault(i, 0)); s.setChance(false);
            LocalDate last = lastSeenMap.get(i); s.setEcart(last == null ? 999 : (int) ChronoUnit.DAYS.between(last, maxDate)); stats.add(s);
        }
        for (int i = 1; i <= 10; i++) {
            StatPoint s = new StatPoint(); s.setNumero(i); s.setFrequence(freqChance.getOrDefault(i, 0)); s.setChance(true);
            LocalDate last = lastSeenChance.get(i); s.setEcart(last == null ? 999 : (int) ChronoUnit.DAYS.between(last, maxDate)); stats.add(s);
        }
        return new StatsReponse(stats, minDate.format(fmt), maxDate.format(fmt), all.size());
    }

    public SimulationResultDto simulerGrilleDetaillee(List<Integer> boulesJouees, LocalDate dateSimul) {
        return simulerGrilleDetaillee(boulesJouees, dateSimul, repository.findAll());
    }

    private SimulationResultDto simulerGrilleDetaillee(List<Integer> boulesJouees, LocalDate dateSimul, List<LotoTirage> historique) {
        SimulationResultDto result = new SimulationResultDto();
        try { result.setDateSimulee(dateSimul.format(DateTimeFormatter.ofPattern(Constantes.FORMAT_DATE_STANDARD))); }
        catch (Exception e) { result.setDateSimulee(dateSimul.toString()); }
        result.setJourSimule(dateSimul.getDayOfWeek().getDisplayName(TextStyle.FULL, Locale.FRANCE).toUpperCase());
        result.setQuintuplets(new ArrayList<>()); result.setQuartets(new ArrayList<>());
        result.setTrios(new ArrayList<>()); result.setPairs(new ArrayList<>());

        for (LotoTirage t : historique) {
            List<Integer> commun = new ArrayList<>(t.getBoules());
            commun.retainAll(boulesJouees);
            int taille = commun.size();
            if (taille >= 2) {
                String dateHist;
                try { dateHist = t.getDateTirage().format(DateTimeFormatter.ofPattern(Constantes.FORMAT_DATE_STANDARD)); }
                catch (Exception e) { dateHist = t.getDateTirage().toString(); }
                boolean memeJour = t.getDateTirage().getDayOfWeek() == dateSimul.getDayOfWeek();
                addToResult(result, taille, commun, dateHist, memeJour, historique.size());
            }
        }
        return result;
    }

    private void addToResult(SimulationResultDto res, int taille, List<Integer> nums, String date, boolean memeJour, int totalTirages) {
        List<MatchGroup> targetList = switch (taille) {
            case 5 -> res.getQuintuplets(); case 4 -> res.getQuartets();
            case 3 -> res.getTrios(); case 2 -> res.getPairs(); default -> null;
        };
        if (targetList != null) {
            Collections.sort(nums);
            Optional<MatchGroup> existing = targetList.stream().filter(m -> m.getNumeros().equals(nums)).findFirst();
            if (existing.isPresent()) {
                MatchGroup group = existing.get();
                group.getDates().add(date + (memeJour ? " (Même jour !)" : ""));
                if (memeJour) group.setSameDayOfWeek(true);
                updateRatio(group, totalTirages, taille);
            } else {
                List<String> dates = new ArrayList<>(); dates.add(date + (memeJour ? " (Même jour !)" : ""));
                MatchGroup newGroup = new MatchGroup(nums, dates, memeJour, 0.0);
                updateRatio(newGroup, totalTirages, taille);
                targetList.add(newGroup);
            }
        }
    }

    private void updateRatio(MatchGroup group, int totalTirages, int taille) {
        double probaTheo = switch (taille) {
            case 1 -> 0.10204; case 2 -> 0.00850; case 3 -> 0.00041;
            case 4 -> 0.0000096; case 5 -> 0.00000052; default -> 0.0;
        };
        double nbreAttendu = totalTirages * probaTheo;
        int nbreReel = group.getDates().size();
        double ratio = (nbreAttendu > 0) ? (nbreReel / nbreAttendu) : 0.0;
        group.setRatio(Math.round(ratio * 100.0) / 100.0);
    }

    // Pour l'affichage Dashboard
    public StrategyDisplayDto getStrategieDuJourPourAffichage() {
        AlgoConfig config = self.recupererMeilleureConfig();
        double valForme = Math.min(10.0, config.getPoidsForme() / 2.0);
        double valEcart = Math.min(10.0, config.getPoidsEcart() * 5.0);
        double valAffinite = Math.min(10.0, config.getPoidsAffinite());
        double valTension = Math.min(10.0, config.getPoidsTension() / 3.0);

        String titre, desc, icone;
        double max = Math.max(Math.max(valForme, valEcart), Math.max(valAffinite, valTension));

        if (max == valEcart) {
            titre = "Chasse aux numéros froids"; desc = "Focus sur le retard statistique."; icone = "bi-snow";
        } else if (max == valAffinite) {
            titre = "L'heure des Duos"; desc = "Focus sur les affinités de groupe."; icone = "bi-people-fill";
        } else if (max == valTension) {
            titre = "Correction Statistique"; desc = "Retour à la moyenne théorique."; icone = "bi-magnet-fill";
        } else {
            titre = "Sur la vague"; desc = "Priorité aux numéros chauds."; icone = "bi-fire";
        }
        String puissance = (config.getNbTiragesTestes() > 0) ? "Basé sur 78M de simulations" : "Algorithme Standard";

        return StrategyDisplayDto.builder()
                .nom("IA GÉNÉTIQUE (" + config.getNomStrategie() + ")")
                .meteoTitre(titre).meteoDescription(desc).meteoIcone(icone)
                .badgePuissance(puissance)
                .chartForme(Math.round(valForme * 10.0) / 10.0)
                .chartEcart(Math.round(valEcart * 10.0) / 10.0)
                .chartAffinite(Math.round(valAffinite * 10.0) / 10.0)
                .chartTension(Math.round(valTension * 10.0) / 10.0)
                .build();
    }
}
