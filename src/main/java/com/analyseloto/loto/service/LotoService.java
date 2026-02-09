package com.analyseloto.loto.service;

import com.analyseloto.loto.dto.*;
import com.analyseloto.loto.entity.*;
import com.analyseloto.loto.repository.LotoTirageRepository;
import com.analyseloto.loto.repository.StrategyConfigRepostiroy;
import com.analyseloto.loto.service.calcul.BitMaskService;
import com.analyseloto.loto.util.Constantes;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.Sort;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.multipart.MultipartFile;

import java.io.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

@Slf4j
@Service
@RequiredArgsConstructor
public class LotoService {
    // Repositories
    private final LotoTirageRepository repository;
    private final StrategyConfigRepostiroy strategyConfigRepostiroy;
    // Services
    private final BacktestService backtestService;
    private final BitMaskService bitMaskService;
    private final WheelingService wheelingService;
    private final ScoringService scoringService;

    private final RedisTemplate<String, Object> redisTemplate;
    private final RestTemplate restTemplate;

    @Value("${loto.ai.url:http://localhost:8000/predict}")
    private String pythonApiUrl;

    // --- VARIABLES DU CACHE MANUEL (Architecture Stateful) ---
    private volatile List<AlgoConfig> cachedEliteConfigs = new ArrayList<>();
    private volatile LocalDate lastBacktestDate = null;
    private volatile StatsReponse cachedGlobalStats = null;
    private final AtomicReference<List<PronosticResultDto>> cachedDailyPronosRef = new AtomicReference<>();
    private volatile LocalDate dateCachedPronos = null;


    // Constantes
    private static final String FIELD_DATE_TIRAGE = "dateTirage";
    private static final ZoneId ZONE_PARIS = ZoneId.of("Europe/Paris");
    // Liste des nombres premiers entre 1 et 49
    private static final Set<Integer> NOMBRES_PREMIERS = Set.of(
            2, 3, 5, 7, 11, 13, 17, 19, 23, 29, 31, 37, 41, 43, 47
    );

    // ==================================================================================
    // CLASSES INTERNES (DTOs & CONFIG)
    // ==================================================================================

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
        private double bilanEstime;
        private int nbTiragesTestes;
        private int nbGrillesParTest;
        private double roiEstime;

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
            AlgoConfig config = new AlgoConfig(
                    "ULTRA_PERFORMANT_LEGACY",
                    1.559, 17.302, 1.984, 16.207, 4.148, 6.001, false
            );
            config.setBilanEstime(-11481.40);
            config.setNbTiragesTestes(350);
            config.setNbGrillesParTest(400);
            config.setRoiEstime(-3.7);
            return config;
        }
    }

    @Data
    @AllArgsConstructor
    public static class DynamicConstraints implements Serializable {
        @Serial private static final long serialVersionUID = 1L;
        private int minPairs;
        private int maxPairs;
        private boolean allowSuites;
        private Set<Integer> forbiddenNumbers;
    }

    @AllArgsConstructor
    @Data
    private static class GrilleCandidate implements Serializable {
        @Serial private static final long serialVersionUID = 1L;
        int[] boules;
        int chance;
        double fitness;
    }

    @Data
    @AllArgsConstructor
    public static class ScenarioSimulation implements Serializable {
        @Serial private static final long serialVersionUID = 1L;
        private LotoTirage tirageReel;
        private List<Integer> dernierTirageConnu;
        private int[][] matriceAffinites;
        private int[][] matriceChance;
        private double[][] matriceMarkov;
        private int etatDernierTirage;
        private RawStatData[] rawStatsBoulesArr;
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
    // 1. INITIALISATION & CONFIG
    // ==================================================================================

    // DANS LotoService.java

    @SuppressWarnings("unchecked") // On garantit à Java que le cast est maîtrisé
    public void initConfigFromDb() {
        log.info("🔌 Démarrage : Chargement du Conseil des Sages...");
        String cacheKey = "LOTO_ELITE_CONFIGS_V1";

        // 1. TENTATIVE REDIS (Architecture Stateless)
        try {
            if (redisTemplate != null) {
                // On récupère d'abord l'objet générique
                Object cachedObject = redisTemplate.opsForValue().get(cacheKey);

                if (cachedObject instanceof List<?>) {
                    // On cast explicitement avec la suppression de l'avertissement
                    List<AlgoConfig> cached = (List<AlgoConfig>) cachedObject;

                    if (!cached.isEmpty()) {
                        this.cachedEliteConfigs = cached;
                        // Si tu as stocké la date, récupère-la, sinon utilise aujourd'hui
                        this.lastBacktestDate = LocalDate.now(ZONE_PARIS);
                        log.info("⚡ [REDIS HIT] {} experts chargés depuis le cache distribué.", cached.size());
                        return; // On sort, pas besoin d'aller en BDD
                    }
                }
            }
        } catch (Exception e) {
            log.warn("⚠️ Redis indisponible ou erreur de désérialisation : {}", e.getMessage());
        }

        // 2. FALLBACK BDD (Si Redis vide ou échoué)
        strategyConfigRepostiroy.findTopByLeaderTrueOrderByDateCalculDesc().ifPresentOrElse(
                lastLeader -> {
                    List<StrategyConfig> batch = strategyConfigRepostiroy.findAllByDateCalcul(lastLeader.getDateCalcul());

                    this.cachedEliteConfigs = batch.stream().map(s -> {
                        AlgoConfig cfg = new AlgoConfig(
                                s.getNomStrategie(),
                                s.getPoidsFreqJour(), s.getPoidsForme(), s.getPoidsEcart(),
                                s.getPoidsTension(), s.getPoidsMarkov(), s.getPoidsAffinite(),
                                false
                        );
                        cfg.setRoiEstime(s.getRoi());
                        cfg.setBilanEstime(s.getBilanEstime());
                        return cfg;
                    }).collect(Collectors.toList());

                    this.lastBacktestDate = lastLeader.getDateCalcul().toLocalDate();
                    log.info("✅ Ensemble chargé : {} experts récupérés (Date: {}).", this.cachedEliteConfigs.size(), this.lastBacktestDate);
                },
                () -> log.warn("⚠️ Aucune stratégie en base. Un calcul initial est requis.")
        );

        // 3. SAUVEGARDE DANS REDIS APRÈS CHARGEMENT DB
        if (!this.cachedEliteConfigs.isEmpty() && redisTemplate != null) {
            try {
                redisTemplate.opsForValue().set(cacheKey, this.cachedEliteConfigs, Duration.ofHours(24));
                log.info("💾 [REDIS SAVE] Conseil des Sages sauvegardé dans le cache.");
            } catch (Exception e) {
                log.error("❌ Impossible d'écrire dans Redis", e);
            }
        }
    }

    /**
     * Méthode vérification au démarrage de l'application
     */
    public void verificationAuDemarrage() {
        LocalDate todayParis = LocalDate.now(ZONE_PARIS);

        // On considère que le conseil est complet s'il y a au moins 10 experts (plus robuste)
        boolean conseilIncomplet = this.cachedEliteConfigs.size() < 10;

        if (!conseilIncomplet && todayParis.equals(this.lastBacktestDate)) {
            log.info("✋ [WARMUP] Conseil des Sages ({} experts) déjà opérationnel. OK.", this.cachedEliteConfigs.size());
            return;
        }

        log.info("⚠️ [WARMUP] Conseil incomplet ou obsolète. Optimisation IA requise.");
        forceDailyOptimization();
    }

    // Dans LotoService.java

    public void forceDailyOptimization() {
        log.info("🌙 [CRON] Optimisation de l'Ensemble IA...");

        List<LotoTirageRepository.TirageMinimal> rawData = repository.findAllOptimized();
        List<LotoTirage> historyLight = rawData.stream().map(this::mapToLightEntity).toList();

        if (!historyLight.isEmpty()) {
            // 1. Appel du BacktestService (qui calcule maintenant le ROI pur pour chaque expert)
            List<AlgoConfig> newConfigs = backtestService.trouverMeilleuresConfigs(historyLight);

            LocalDateTime batchTimestamp = LocalDateTime.now(ZONE_PARIS);
            this.cachedEliteConfigs = newConfigs;
            this.lastBacktestDate = batchTimestamp.toLocalDate();

            // 2. LOG DU LEADER : On identifie le meilleur expert du batch
            if (!newConfigs.isEmpty()) {
                AlgoConfig leader = newConfigs.get(0);
                log.info("🥇 LEADER IDENTIFIÉ : {} | ROI Estimé : {}%",
                        leader.getNomStrategie(),
                        String.format("%.2f", leader.getRoiEstime())); // Affiche le ROI pur calculé
            }

            // 3. SAUVEGARDE DE TOUT LE CONSEIL
            List<StrategyConfig> entitiesToSave = new ArrayList<>();
            for (int i = 0; i < newConfigs.size(); i++) {
                AlgoConfig config = newConfigs.get(i);
                StrategyConfig entity = new StrategyConfig();

                entity.setDateCalcul(batchTimestamp);
                entity.setNomStrategie(config.getNomStrategie());
                entity.setPoidsForme(config.getPoidsForme());
                entity.setPoidsEcart(config.getPoidsEcart());
                entity.setPoidsAffinite(config.getPoidsAffinite());
                entity.setPoidsMarkov(config.getPoidsMarkov());
                entity.setPoidsTension(config.getPoidsTension());
                entity.setPoidsFreqJour(config.getPoidsFreqJour());
                entity.setBilanEstime(config.getBilanEstime());
                entity.setNbTiragesTestes(config.getNbTiragesTestes());
                entity.setNbGrillesParTest(config.getNbGrillesParTest());
                entity.setRoi(config.getRoiEstime());

                entity.setLeader(i == 0);
                entitiesToSave.add(entity);
            }

            strategyConfigRepostiroy.saveAll(entitiesToSave);

            // Log de fin de processus plus détaillé
            log.info("💾 [DB] Conseil des Sages ({} experts) sauvegardé avec succès. Leader : {} ({}%)",
                    entitiesToSave.size(),
                    newConfigs.get(0).getNomStrategie(),
                    newConfigs.get(0).getRoiEstime());

            // Préchauffage...
            genererMultiplesPronostics(recupererDateProchainTirage(), 10);
        }
    }

    private LotoTirage mapToLightEntity(LotoTirageRepository.TirageMinimal projection) {
        LotoTirage t = new LotoTirage();
        t.setDateTirage(projection.getDateTirage());
        t.setBoule1(projection.getBoule1());
        t.setBoule2(projection.getBoule2());
        t.setBoule3(projection.getBoule3());
        t.setBoule4(projection.getBoule4());
        t.setBoule5(projection.getBoule5());
        t.setNumeroChance(projection.getNumeroChance());
        return t;
    }


    /**
     * GÉNÉRATEUR V6 PRO : HYBRIDE (STATISTIQUE + DEEP LEARNING + ENTROPIE)
     */
    public List<PronosticResultDto> genererGrillesPro(LocalDate dateCible, int budgetMaxGrilles) {
        log.info("🚀 [V6 PRO] Démarrage du Moteur Hybride (Java + Python + Shannon)...");

        // 1. Chargement Historique
        List<LotoTirageRepository.TirageMinimal> rawData = repository.findAllOptimized();
        List<LotoTirage> history = rawData.stream().map(this::mapToLightEntity).toList();

        // 2. FUSION DES INTELLIGENCES (Double Scoring)
        // A. Score Statistique (Java) : Analyse le passé (Fréquence, Ecart, Forme)
        Map<Integer, Double> scoresJava = scoringService.calculerScores(history, dateCible);

        // B. Score Prédictif (Python) : Analyse les séquences temporelles (LSTM)
        double[] weightsPython = getDeepLearningWeights(); // Peut renvoyer des 0.0 si API down

        // C. Mixage Pondéré (60% Stat / 40% AI)
        Map<Integer, Double> scoresFinaux = new HashMap<>();
        for (int i = 1; i <= 49; i++) {
            double sJava = scoresJava.getOrDefault(i, 0.0);
            // Sécurité : Si Python HS ou boule hors range, on fallback sur Java
            double sPy = (weightsPython != null && i < weightsPython.length) ? weightsPython[i] : sJava;

            // Normalisation empirique : le score Python est souvent brut, on le lisse
            double scoreMixte = (sJava * 0.60) + (sPy * 0.40);
            scoresFinaux.put(i, scoreMixte);
        }

        // 3. POOL ADAPTATIF "SMART SIZE" (Correction Warning & Optimisation)
        // On trie les numéros par puissance décroissante (Score Mixte)
        List<Integer> classement = scoresFinaux.entrySet().stream()
                .sorted(Map.Entry.<Integer, Double>comparingByValue().reversed())
                .map(Map.Entry::getKey)
                .toList();

        // Calcul du Delta de Confiance (Écart de score entre le 10ème et le 15ème numéro)
        // Plus cet écart est grand, plus la "cassure" est nette : les favoris se détachent.
        double scoreRank10 = scoresFinaux.get(classement.get(9));
        double scoreRank15 = scoresFinaux.get(classement.get(14));
        double delta = scoreRank10 - scoreRank15; // ex: 0.08 ou 0.01

        // Formule V6 : Interpolation Linéaire Inverse
        // Si delta ~ 0.00 (Flou total) -> On veut 18 numéros (Large)
        // Si delta >= 0.08 (IA Confiante) -> On veut 12 numéros (Ciblé)
        // Le facteur 75.0 est calibré pour convertir 0.08 en ~6 unités (18 - 6 = 12)
        int tailleCalculee = 18 - (int)(delta * 75.0);

        // Le "Garde-fou" devient maintenant INDISPENSABLE et le warning disparaît
        // car 'tailleCalculee' est variable.
        int taillePool = Math.max(12, Math.min(tailleCalculee, 18));

        List<Integer> pool = classement.subList(0, taillePool);

        log.info("🎯 [V6 SMART] Delta: {} -> Pool ajusté à {} numéros.",
                String.format("%.4f", delta), taillePool);

        // 4. WHEELING SYSTEM (Mathématique)
        // Garantie 3/5 : Si 5 numéros gagnants sont dans le pool, on a FORCÉMENT une grille à 3 bons numéros.
        List<int[]> grillesBrutes = wheelingService.genererSystemeReducteur(pool, 3);
        log.info("⚙️ [WHEELING] {} combinaisons générées avant filtrage.", grillesBrutes.size());

        // 5. CONSTRUCTION & FILTRAGE AVANCÉ
        List<PronosticResultDto> candidats = new ArrayList<>();
        List<Integer> topChances = getTopChanceNumbers(history, dateCible.getDayOfWeek());

        for (int i = 0; i < grillesBrutes.size(); i++) {
            int[] g = grillesBrutes.get(i);

            // A. Filtre "Rentabilité" (Somme, Dizaines) + NOUVEAU : ENTROPIE DE SHANNON
            if (!estGrilleRentablePro(g)) continue;

            // B. Rotation Intelligente du Numéro Chance
            int chance = topChances.get(i % topChances.size());

            // C. Score "Fitness" de la grille (Somme des scores des boules)
            double fitness = 0.0;
            for (int b : g) fitness += scoresFinaux.get(b);

            candidats.add(new PronosticResultDto(
                    Arrays.stream(g).boxed().sorted().toList(),
                    chance,
                    fitness,
                    0.0, 0.0, false, // Placeholder ratios
                    "V6_PRO"
            ));
        }

        // 6. SÉLECTION FINALE (Top Score)
        List<PronosticResultDto> selectionFinale = candidats.stream()
                .sorted(Comparator.comparingDouble(PronosticResultDto::getScoreFitness).reversed())
                .limit(budgetMaxGrilles)
                .collect(Collectors.toList());

        // Si les filtres "Bio-Mimétiques" ont tout tué, on récupère les grilles brutes du Wheeling
        // car la garantie mathématique est prioritaire sur l'esthétique statistique.
        if (selectionFinale.isEmpty() && !grillesBrutes.isEmpty()) {
            log.warn("⚠️ [V6 PRO] Filtres trop stricts ! Activation du Fallback sur les grilles brutes.");

            for (int[] g : grillesBrutes) {
                // On recalcule juste le score fitness basique
                double fitness = 0.0;
                for (int b : g) fitness += scoresFinaux.get(b);

                // On crée le DTO sans passer par le filtre strict
                selectionFinale.add(new PronosticResultDto(
                        Arrays.stream(g).boxed().sorted().toList(),
                        topChances.get(0), // On prend la meilleure chance par défaut
                        fitness,
                        0.0, 0.0, false,
                        "V6_FALLBACK (Garantie Math)" // Marqueur pour l'IHM
                ));
            }
            // On limite au budget
            if (selectionFinale.size() > budgetMaxGrilles) {
                selectionFinale = selectionFinale.subList(0, budgetMaxGrilles);
            }
        }

        // 7. BACKTEST SIMULÉ (Indicateurs visuels)
        selectionFinale.forEach(dto -> {
            SimulationResultDto simu = simulerGrilleDetaillee(dto.getBoules(), dateCible, history);
            double maxDuo = simu.getPairs().stream().mapToDouble(MatchGroup::getRatio).max().orElse(0.0);
            dto.setMaxRatioDuo(maxDuo);
            dto.setDejaSortie(!simu.getQuintuplets().isEmpty());
        });

        log.info("✅ [V6 PRO] Terminé : {} grilles 'Élite' retenues.", selectionFinale.size());
        return selectionFinale;
    }

    /**
     * V7+ : FILTRE "STRUCTUREL" AVANCÉ
     * Vérifie l'intégrité statistique d'une grille selon 5 dimensions.
     */
    private boolean estGrilleRentablePro(int[] boules) {
        // Trier pour faciliter l'analyse
        int[] sorted = Arrays.copyOf(boules, 5);
        Arrays.sort(sorted);

        // 1. Filtre Somme (Courbe de Gauss)
        // Les tirages extrêmes (somme < 100 ou > 200) représentent < 10% des cas réels.
        int somme = 0;
        for (int b : sorted) somme += b;
        if (somme < 100 || somme > 200) return false;

        // 2. Filtre "Finales" (Diversité des terminaisons)
        // Ex: 11, 21, 31, 41 (4 finales en 1) -> Très rare.
        if (!checkDiversiteFinales(sorted)) return false;

        // 3. Filtre "Suites" (Consécutifs)
        // Ex: 12, 13, 14 (3 à la suite) -> Arrive moins de 2% du temps.
        if (!checkSuitesLogiques(sorted)) return false;

        // 4. Filtre "Premiers" (Densité Mathématique)
        // Un tirage sans aucun nombre premier ou avec que des premiers est une anomalie.
        if (!checkEquilibrePremiers(sorted)) return false;

        // 5. Filtre "Entropie de Shannon" (Chaos)
        // On garde ton excellent filtre V6
        return calculerEntropieShannon(sorted) >= 1.2;
    }

    /**
     * Vérifie qu'on n'a pas plus de 2 numéros terminant par le même chiffre.
     * Ex: [12, 22, 32, 45, 48] -> Rejeté (3 finales '2')
     */
    private boolean checkDiversiteFinales(int[] sorted) {
        int[] finales = new int[10]; // Pour stocker les comptes des finales 0-9
        for (int b : sorted) {
            finales[b % 10]++;
        }
        for (int count : finales) {
            if (count > 2) return false; // Trop de répétition, grille suspecte
        }
        return true;
    }

    /**
     * Vérifie les suites consécutives.
     * Autorisé : 0 suite (1, 5, 9...) ou 1 paire (12, 13).
     * Interdit : Triplé (12, 13, 14) ou Double paire (12, 13, 25, 26).
     */
    private boolean checkSuitesLogiques(int[] sorted) {
        int suites = 0;
        for (int i = 0; i < sorted.length - 1; i++) {
            if (sorted[i] + 1 == sorted[i+1]) {
                suites++;
                // Si on détecte une suite de 3 (ex: i et i+1 se suivent, et i+1 et i+2 se suivent)
                if (i < sorted.length - 2 && sorted[i+1] + 1 == sorted[i+2]) {
                    return false; // Triplé interdit
                }
            }
        }
        return suites <= 1; // Max 1 paire consécutive autorisée
    }

    /**
     * Vérifie l'équilibre des Nombres Premiers.
     * Statistiquement, 85% des tirages contiennent entre 1 et 3 nombres premiers.
     */
    private boolean checkEquilibrePremiers(int[] sorted) {
        int countPremiers = 0;
        for (int b : sorted) {
            if (NOMBRES_PREMIERS.contains(b)) countPremiers++;
        }
        // On rejette si 0 premier (trop artificiel) ou 5 premiers (trop improbable)
        return countPremiers >= 1 && countPremiers <= 4;
    }

    /**
     * Calcule l'Entropie de Shannon normalisée de la grille.
     * @return valeur entre 0.0 (Ordre total) et ~1.6 (Chaos maximal pour 5 items)
     */
    private double calculerEntropieShannon(int[] boules) {
        // On regarde la distribution des écarts entre les boules triées
        int[] sorted = Arrays.copyOf(boules, 5);
        Arrays.sort(sorted);

        Map<Integer, Integer> ecartsMap = new HashMap<>();
        int totalEcarts = 4; // 5 boules = 4 intervalles

        for (int i = 0; i < 4; i++) {
            int diff = sorted[i+1] - sorted[i];
            // On regroupe les écarts par "classes" pour lisser (Petit, Moyen, Grand)
            int classeEcart = (diff <= 2) ? 1 : (diff <= 6) ? 2 : 3;
            ecartsMap.merge(classeEcart, 1, Integer::sum);
        }

        double entropy = 0.0;
        for (int count : ecartsMap.values()) {
            double p = (double) count / totalEcarts;
            if (p > 0) {
                entropy -= p * Math.log(p); // Logarithme naturel
            }
        }
        return entropy;
    }

    /**
     * Calcule dynamiquement la taille du pool en fonction de la confiance de l'IA.
     * Si les poids sont très concentrés (IA sûre), on prend moins de numéros.
     * Si les poids sont diffus (IA incertaine), on élargit le filet.
     */
    private List<Integer> determinerPoolAdaptatif(double[] weights) {
        // 1. Convertir en liste d'indices triés par score décroissant
        List<Integer> sortedIndices = IntStream.range(1, 50)
                .boxed()
                .sorted((a, b) -> Double.compare(weights[b], weights[a]))
                .collect(Collectors.toList());

        // 2. Calculer moyenne et écart-type (rapide) du Top 20
        double sum = 0;
        for(int i=0; i<20; i++) sum += weights[sortedIndices.get(i)];
        double avg = sum / 20.0;

        // 3. Logique de seuil dynamique
        // Si le 10ème meilleur est bien au-dessus de la moyenne, l'IA est confiante.
        boolean iaConfiante = weights[sortedIndices.get(9)] > (avg * 1.1);

        int taillePool;
        if (iaConfiante) {
            taillePool = 12; // Focus agressif (Coût réduit, ROI potentiel max)
        } else {
            taillePool = 16; // Prudence (On couvre plus large)
        }

        // Sécurité : Bornes min/max pour le Wheeling
        taillePool = Math.max(10, Math.min(taillePool, 18));

        return sortedIndices.subList(0, taillePool);
    }

    /**
     * Récupère les 3 meilleurs numéros Chance basés sur l'algorithme "Primitive"
     * combiné aux statistiques récentes.
     */
    private List<Integer> getTopChanceNumbers(List<LotoTirage> history, DayOfWeek jourCible) {
        // On utilise la logique existante pour scorer les 10 numéros chance
        // Note: on passe null pour algoConfig car on veut du standard statistique ici
        double[] scoresChance = calculerScoresOptimise(
                history, 10, jourCible, true,
                Collections.emptyList(), Collections.emptySet(),
                AlgoConfig.defaut(), null, null
        );

        // On retourne les indices des 3 meilleurs scores
        return IntStream.range(1, 11)
                .boxed()
                .sorted((c1, c2) -> Double.compare(scoresChance[c2], scoresChance[c1])) // Décroissant
                .limit(3)
                .collect(Collectors.toList());
    }

    public double[] getDeepLearningWeights() {
        double[] weights = new double[50]; // Index 0 vide, 1-49 utilisés
        Arrays.fill(weights, 0.0);

        try {
            log.info("📡 [IA V4] Connexion au Neural Engine sur : {}", pythonApiUrl);

            // 1. Récupérer l'historique (Les 60 derniers tirages suffisent pour le contexte)
            List<LotoTirageRepository.TirageMinimal> rawHistory = repository.findAllOptimized();
            int limit = Math.min(rawHistory.size(), 60);

            // Convertir en format compatible Python (Liste d'objets JSON)
            List<Map<String, Object>> historyJson = new ArrayList<>();

            // Attention : findAllOptimized renvoie souvent du plus récent au plus vieux.
            // Python a besoin de l'ordre CHRONOLOGIQUE (Vieux -> Récent) pour le LSTM.
            for (int i = limit - 1; i >= 0; i--) {
                LotoTirageRepository.TirageMinimal t = rawHistory.get(i);
                Map<String, Object> draw = new HashMap<>();
                draw.put("date", t.getDateTirage().toString());
                draw.put("b1", t.getBoule1());
                draw.put("b2", t.getBoule2());
                draw.put("b3", t.getBoule3());
                draw.put("b4", t.getBoule4());
                draw.put("b5", t.getBoule5());
                draw.put("chance", t.getNumeroChance());
                historyJson.add(draw);
            }

            // 2. Préparer la requête
            Map<String, Object> requestBody = Map.of("history", historyJson);

            // 3. Appel API (Ultra rapide)
            @SuppressWarnings("unchecked")
            Map<String, Double> response = restTemplate.postForObject(pythonApiUrl, requestBody, Map.class);

            // 4. Intégration des poids
            if (response != null && !response.isEmpty()) {
                response.forEach((k, v) -> {
                    try {
                        if (k.equals("error")) return;
                        int boule = Integer.parseInt(k);
                        if (boule >= 1 && boule <= 49) {
                            weights[boule] = v;
                        }
                    } catch (NumberFormatException e) {
                        // Ignorer les clés non numériques
                    }
                });
                log.info("✅ [IA V4] Prédictions reçues avec succès.");
            } else {
                log.warn("⚠️ [IA V4] Réponse vide de l'API.");
            }

        } catch (Exception e) {
            log.error("❌ [IA V4] Le serveur d'inférence ne répond pas : {}", e.getMessage());
            // Fallback : On continue sans l'IA (poids à 0) pour ne pas bloquer l'app
        }

        return weights;
    }

    private double calculerPoidsBayesien(double roiEstime, int nbTiragesTestes) {
        // Décalage pour gérer les ROI négatifs (ex: -100% devient 0)
        // On accorde un crédit de base pour encourager la diversité
        double baseScore = Math.max(0, roiEstime + 100.0);

        // Facteur de confiance logarithmique :
        // Un expert ayant testé 1000 tirages est plus crédible qu'un expert à 10 tirages,
        // mais pas 100 fois plus (rendement décroissant).
        double confiance = Math.log10(nbTiragesTestes + 10);

        // Le poids final est le produit de la performance et de la crédibilité
        return Math.max(0.001, baseScore * confiance);
    }

    // ==================================================================================
    // 2. GÉNÉRATION DE PRONOSTICS (Optimisée)
    // ==================================================================================

    public List<PronosticResultDto> genererMultiplesPronostics(LocalDate dateCible, int nombreGrilles) {
        long startTotal = System.currentTimeMillis();

        // 1. GESTION DU CACHE
        List<PronosticResultDto> cached = cachedDailyPronosRef.get();
        if (cached != null && dateCible.equals(dateCachedPronos) && cached.size() >= nombreGrilles) {
            log.info("⚡ [CACHE] Pronostics (Consensus) récupérés instantanément en {} ms.", (System.currentTimeMillis() - startTotal));
            return cached.subList(0, nombreGrilles);
        }

        log.info("⚙️ [CONSENSUS IA] Démarrage du Conseil des Sages pour le {}...", dateCible);

        // 2. PRÉPARATION DES DONNÉES COMMUNES (Une seule fois pour tout le monde !)
        // On charge l'historique une fois
        List<LotoTirageRepository.TirageMinimal> rawData = repository.findAllOptimized();
        List<LotoTirage> history = rawData.stream().map(this::mapToLightEntity).toList();
        if (history.isEmpty()) return new ArrayList<>();

        List<Integer> dernierTirage = history.get(0).getBoules();
        int etatDernierTirage = calculerEtatAbstrait(dernierTirage);

        // On récupère les poids neuronaux AVANT de lancer les experts
        double[] deepWeights = getDeepLearningWeights();

        // CALCULS PARALLÈLES DES MATRICES INVARIANTES (Lourds mais faits 1 seule fois)
        // Ces données ne dépendent pas de la config, donc on les partage entre tous les experts.
        CompletableFuture<Set<Integer>> hotFinalesFuture = CompletableFuture.supplyAsync(() -> detecterFinalesChaudes(history));
        CompletableFuture<List<List<Integer>>> triosFuture = CompletableFuture.supplyAsync(() -> getTopTriosRecents(history));
        CompletableFuture<int[][]> affFuture = CompletableFuture.supplyAsync(() -> construireMatriceAffinitesDirecte(history, dateCible.getDayOfWeek()));
        CompletableFuture<int[][]> chanceFuture = CompletableFuture.supplyAsync(() -> construireMatriceChanceDirecte(history, dateCible.getDayOfWeek()));
        CompletableFuture<double[][]> markovFuture = CompletableFuture.supplyAsync(() -> precalculerMatriceMarkovParJour(history, dateCible.getDayOfWeek()));
        CompletableFuture<DynamicConstraints> contraintesFuture = CompletableFuture.supplyAsync(() -> analyserContraintesDynamiques(history));

        // On attend que tout soit prêt
        CompletableFuture.allOf(hotFinalesFuture, triosFuture, affFuture, chanceFuture, markovFuture, contraintesFuture).join();

        // Récupération des résultats invariants
        Set<Integer> hotFinales = hotFinalesFuture.join();
        List<List<Integer>> topTriosDuJour = triosFuture.join();
        int[][] matriceAffinitesArr = affFuture.join();
        int[][] matriceChanceArr = chanceFuture.join();
        double[][] matriceMarkov = markovFuture.join();
        DynamicConstraints contraintesDuJour = contraintesFuture.join();
        Set<Long> historiqueBitMasks = new HashSet<>(history.size());

        for(int i=0; i<Math.min(history.size(), 300); i++) {
            historiqueBitMasks.add(bitMaskService.calculerBitMask(history.get(i).getBoules()));
        }

        List<AlgoConfig> eliteConfigs;
        // Si on a déjà calculé les configs aujourd'hui, on utilise la RAM (Instant)
        if (this.cachedEliteConfigs != null && !this.cachedEliteConfigs.isEmpty() && LocalDate.now(ZONE_PARIS).equals(this.lastBacktestDate)) {
            eliteConfigs = this.cachedEliteConfigs;
            log.info("⚡ [CACHE] Utilisation des {} experts en mémoire RAM.", eliteConfigs.size());
        } else {
            // Sinon (premier lancement ou reboot), on calcule (Lourd)
            log.info("⚠️ [CACHE MISS] Calcul des stratégies nécessaire...");
            eliteConfigs = backtestService.trouverMeilleuresConfigs(history);
            this.cachedEliteConfigs = eliteConfigs; // On met à jour le cache
            this.lastBacktestDate = LocalDate.now(ZONE_PARIS);
        }

        // Structures pour le vote (Thread-Safe, car remplies en parallèle)
        Map<Set<Integer>, Double> votesPonderes = new java.util.concurrent.ConcurrentHashMap<>();
        Map<Set<Integer>, Double> scoresCumules = new java.util.concurrent.ConcurrentHashMap<>();

        log.info("🗳️ [VOTE BMA] Lancement de la génération avec pondération Bayésienne...");

        // 4. GÉNÉRATION PARALLÈLE (Chaque expert propose ses grilles)
        eliteConfigs.parallelStream().forEach(config -> {
            // a. Calcul des scores spécifiques à CETTE config (Chaque expert a sa vision des poids)
            // Note: boostNumbers est vide ici car c'est de l'IA pure, pas de l'Astro
            // Pour les boules (1-49), on passe les poids de l'IA
            double[] scoresBoules = calculerScoresOptimise(history, 49, dateCible.getDayOfWeek(), false, Collections.emptyList(), hotFinales, config, dernierTirage, deepWeights);

            // Pour le numéro chance (1-10), on passe null (l'IA ne prédit pas la chance ici)
            double[] scoresChance = calculerScoresOptimise(history, 10, dateCible.getDayOfWeek(), true, Collections.emptyList(), Collections.emptySet(), config, null, null);

            // b. Buckets & Primitives
            Map<String, List<Integer>> buckets = creerBucketsOptimises(scoresBoules);
            int[] hots = buckets.getOrDefault(Constantes.BUCKET_HOT, Collections.emptyList()).stream().mapToInt(i->i).toArray();
            int[] neutrals = buckets.getOrDefault(Constantes.BUCKET_NEUTRAL, Collections.emptyList()).stream().mapToInt(i->i).toArray();
            int[] colds = buckets.getOrDefault(Constantes.BUCKET_COLD, Collections.emptyList()).stream().mapToInt(i->i).toArray();
            boolean[] isHot = new boolean[51]; boolean[] isCold = new boolean[51];
            for(int n : buckets.get(Constantes.BUCKET_HOT)) isHot[n] = true;
            for(int n : buckets.get(Constantes.BUCKET_COLD)) isCold[n] = true;

            // 2. CALCUL DU POIDS DE L'EXPERT (NOUVEAU)
            double expertWeight = calculerPoidsBayesien(config.getRoiEstime(), config.getNbTiragesTestes());

            // c. L'expert génère ses grilles (On génère ~30 grilles par expert)
            List<GrilleCandidate> proposals = executerAlgorithmeGenetique(
                    hots, neutrals, colds, isHot, isCold,
                    matriceAffinitesArr, dernierTirage, topTriosDuJour, scoresBoules, scoresChance,
                    matriceChanceArr, contraintesDuJour, config, historiqueBitMasks, matriceMarkov, etatDernierTirage
            );

            // 4. Vote Pondéré
            int limitVote = Math.min(proposals.size(), 30);
            for(int i=0; i<limitVote; i++) {
                GrilleCandidate cand = proposals.get(i);
                List<Integer> boulesList = Arrays.stream(cand.getBoules()).boxed().toList();
                Set<Integer> key = new HashSet<>(boulesList);

                // Au lieu de +1, on ajoute le POIDS de l'expert
                votesPonderes.merge(key, expertWeight, Double::sum);
                // Le fitness est aussi pondéré par la crédibilité de l'expert
                scoresCumules.merge(key, cand.getFitness() * expertWeight, Double::sum);
            }
        });

        // 5. DÉPOUILLEMENT DU SCRUTIN (Consensus)
        List<PronosticResultDto> resultatsConsensus = new ArrayList<>();

        // Seuil dynamique : Il faut accumuler assez de "poids" pour être élu.
        // On peut dire arbitrairement qu'il faut l'équivalent de "2 experts moyens"
        double poidsMoyen = eliteConfigs.stream()
                .mapToDouble(c -> calculerPoidsBayesien(c.getRoiEstime(), c.getNbTiragesTestes()))
                .average().orElse(1.0);
        double seuilVote = poidsMoyen * 2.5;

        for (Map.Entry<Set<Integer>, Double> entry : votesPonderes.entrySet()) {
            double poidsTotal = entry.getValue();
            if (poidsTotal >= seuilVote) {
                Set<Integer> boulesSet = entry.getKey();
                List<Integer> boulesList = new ArrayList<>(boulesSet);
                Collections.sort(boulesList);

                double scorePondereTotal = scoresCumules.get(boulesSet);
                // Score final normalisé
                double finalScore = scorePondereTotal / poidsTotal;
                // Bonus de consensus (plus le poids total est grand, plus on booste)
                finalScore *= (1.0 + Math.log10(poidsTotal));

                int chanceConsensus = selectionnerChancePourConsensus(boulesList, matriceChanceArr);
                SimulationResultDto simu = simulerGrilleDetaillee(boulesList, dateCible, history);
                double maxDuo = simu.getPairs().stream().mapToDouble(MatchGroup::getRatio).max().orElse(0.0);

                resultatsConsensus.add(new PronosticResultDto(
                        boulesList, chanceConsensus,
                        Math.round(finalScore * 100.0) / 100.0,
                        maxDuo, 0.0,
                        !simu.getQuintuplets().isEmpty(),
                        "CONSENSUS BMA (Poids: " + String.format("%.1f", poidsTotal) + ")"
                ));
            }
        }

        // 6. TRI ET SÉLECTION FINALE
        // On trie par score consensus décroissant
        resultatsConsensus.sort((p1, p2) -> Double.compare(p2.getScoreFitness(), p1.getScoreFitness()));

        // 6. GESTION DU FALLBACK (Plan de Secours)
        if (resultatsConsensus.isEmpty()) {
            log.warn("⚠️ [FALLBACK] Aucun consensus strict (Seuil: {}). Bascule sur les meilleurs scores individuels.", seuilVote);

            // CORRECTION ICI : On boucle sur votesPonderes (Double) et non votesGrilles (qui n'existe plus)
            for (Map.Entry<Set<Integer>, Double> entry : votesPonderes.entrySet()) {
                Set<Integer> boulesSet = entry.getKey();

                // On récupère le score brut cumulé (fitness * poids expert)
                double rawScore = scoresCumules.get(boulesSet);

                List<Integer> boulesList = new ArrayList<>(boulesSet);
                Collections.sort(boulesList);

                // On recalcule le numéro chance
                int chanceConsensus = selectionnerChancePourConsensus(boulesList, matriceChanceArr);

                // Simulation rapide
                SimulationResultDto simu = simulerGrilleDetaillee(boulesList, dateCible, history);
                double maxDuo = simu.getPairs().stream().mapToDouble(MatchGroup::getRatio).max().orElse(0.0);

                resultatsConsensus.add(new PronosticResultDto(
                        boulesList, chanceConsensus,
                        Math.round(rawScore * 100.0) / 100.0,
                        maxDuo, 0.0,
                        !simu.getQuintuplets().isEmpty(),
                        "TOP_INDIVIDUEL (Score: " + String.format("%.1f", rawScore) + ")"
                ));
            }
        }

        // 7. TRI ET SÉLECTION FINALE (Commun aux deux modes)
        // On trie par score décroissant pour garder la crème de la crème
        // Si on est en mode Consensus : les bonus de confiance feront remonter les grilles votées.
        // Si on est en mode Fallback : le score brut fera remonter les meilleures grilles individuelles.
        resultatsConsensus.sort((p1, p2) -> Double.compare(p2.getScoreFitness(), p1.getScoreFitness()));

        // Sécurité ultime : Si vraiment vide (0 expert n'a généré 0 grille ??? Impossible mais bon)
        if (resultatsConsensus.isEmpty()) {
            log.error("🚨 [CRITIQUE] Panne sèche des experts. Génération d'urgence.");
            // Appel d'urgence à la méthode simple non-optimisée ou retour vide
            return new ArrayList<>();
        }

        cachedDailyPronosRef.set(resultatsConsensus);
        this.dateCachedPronos = dateCible;

        long duration = System.currentTimeMillis() - startTotal;
        log.info("🏁 [CONSENSUS IA] Terminé en {} ms. {} grilles 'Solidaires' retenues.", duration, resultatsConsensus.size());



        return resultatsConsensus.subList(0, Math.min(resultatsConsensus.size(), nombreGrilles));
    }

    /**
     * FILTRE DE DIVERSITÉ (Smart Covering)
     * Élimine les grilles trop similaires pour maximiser la couverture du champ des possibles.
     */
    private List<PronosticResultDto> appliquerSmartCovering(List<PronosticResultDto> candidats, int nombreFinal) {
        List<PronosticResultDto> selectionFinale = new ArrayList<>();

        // On parcourt les candidats triés par score décroissant
        for (PronosticResultDto candidat : candidats) {
            if (selectionFinale.size() >= nombreFinal) break;

            boolean tropSimilaire = false;
            Set<Integer> boulesCandidat = new HashSet<>(candidat.getBoules());

            for (PronosticResultDto elu : selectionFinale) {
                // Calculer l'intersection
                Set<Integer> intersection = new HashSet<>(elu.getBoules());
                intersection.retainAll(boulesCandidat);

                // Si la grille a 4 ou 5 numéros en commun avec une grille déjà choisie, on la rejette.
                // Pourquoi ? Car si l'autre grille perd, celle-ci perdra aussi probablement.
                // On veut maximiser la "surface d'attaque".
                if (intersection.size() >= 4) {
                    tropSimilaire = true;
                    break;
                }
            }

            if (!tropSimilaire) {
                selectionFinale.add(candidat);
            }
        }

        // Si le filtrage a été trop agressif et qu'on n'a pas assez de grilles
        if (selectionFinale.size() < nombreFinal) {
            // On comble avec les meilleures restantes même si elles sont similaires
            for (PronosticResultDto c : candidats) {
                if (selectionFinale.size() >= nombreFinal) break;
                if (!selectionFinale.contains(c)) {
                    selectionFinale.add(c);
                }
            }
        }

        return selectionFinale;
    }

    // Helper pour le numéro chance en mode consensus
    private int selectionnerChancePourConsensus(List<Integer> boules, int[][] matriceChance) {
        int bestC = 1;
        int maxScore = -1;
        for(int c=1; c<=10; c++) {
            int score = 0;
            for(int b : boules) score += matriceChance[b][c];
            if(score > maxScore) { maxScore = score; bestC = c; }
        }
        return bestC;
    }

    /**
     * CŒUR DU RÉACTEUR - CORRECTION DEADLOCK
     * On retire le .parallel() ici car la méthode est DÉJÀ appelée en parallèle par les 20 experts.
     */
    private List<GrilleCandidate> executerAlgorithmeGenetique(
            int[] hots, int[] neutrals, int[] colds, boolean[] isHot, boolean[] isCold,
            int[][] matriceAffinites, List<Integer> dernierTirage, List<List<Integer>> topTrios,
            double[] scoresBoules, double[] scoresChance, int[][] matriceChance,
            DynamicConstraints contraintes, AlgoConfig config, Set<Long> historiqueBitMasks,
            double[][] matriceMarkov, int etatDernierTirage) {

        long tStart = System.currentTimeMillis();
        int taillePopulationCible = 50_000;

        List<GrilleCandidate> population = IntStream.range(0, taillePopulationCible * 2)
                .mapToObj(i -> genererGrilleOptimiseePrimitive(hots, neutrals, colds, isHot, isCold, matriceAffinites, dernierTirage, topTrios))
                .filter(boules -> estGrilleCoherenteOptimisee(boules, dernierTirage, contraintes))
                .filter(boules -> !historiqueBitMasks.contains(bitMaskService.calculerBitMask(boules)))
                .limit(taillePopulationCible)
                .map(boules -> {
                    int chance = selectionnerChanceRapidePrimitive(boules, scoresChance, matriceChance);
                    double fitness = calculerScoreFitnessOptimise(boules, chance, scoresBoules, scoresChance, matriceAffinites, config, matriceMarkov, etatDernierTirage);
                    return new GrilleCandidate(boules, chance, fitness);
                })
                .sorted((g1, g2) -> Double.compare(g2.fitness, g1.fitness))
                .collect(Collectors.toList());

        // Log uniquement si ça prend du temps (> 1s), sinon c'est du spam
        long duration = System.currentTimeMillis() - tStart;
        if (duration > 1000) {
            log.info("✅ [EXPERT] Terminé en {} ms. {} grilles analysées.", duration, population.size());
        }

        // Fallback
        if (population.isEmpty()) {
            int[] secours = genererGrilleOptimisee(hots, neutrals, colds, isHot, isCold, matriceAffinites, dernierTirage, topTrios);
            Arrays.sort(secours);
            population.add(new GrilleCandidate(secours, 1, 50.0));
        }

        return population;
    }

    // Ajouter cette méthode dans LotoService.java

    /**
     * Version ultra-performante pour le Backtesting (Zéro Allocation / Primitives)
     * Retourne une liste de int[] où [0-4] sont les boules et [5] est le numéro chance.
     */
    public List<int[]> genererGrillesDepuisScenarioOptimise(ScenarioSimulation sc, AlgoConfig config, int nbGrilles) {
        List<int[]> resultats = new ArrayList<>(nbGrilles);

        // 1. Pré-calcul des scores de boules selon la config de l'expert
        double[] scoresBoules = new double[50];
        for (int i = 1; i <= 49; i++) {
            scoresBoules[i] = appliquerPoids(sc.getRawStatsBoulesArr()[i], config);
            if (sc.getDernierTirageConnu().contains(i)) scoresBoules[i] -= 10.0;
        }

        // 2. Préparation des buckets et scores de chance
        Map<String, List<Integer>> buckets = creerBucketsOptimises(scoresBoules);
        int[] hots = buckets.getOrDefault(Constantes.BUCKET_HOT, Collections.emptyList()).stream().mapToInt(i->i).toArray();
        int[] neutrals = buckets.getOrDefault(Constantes.BUCKET_NEUTRAL, Collections.emptyList()).stream().mapToInt(i->i).toArray();
        int[] colds = buckets.getOrDefault(Constantes.BUCKET_COLD, Collections.emptyList()).stream().mapToInt(i->i).toArray();

        boolean[] isHot = new boolean[51]; boolean[] isCold = new boolean[51];
        for(int n : hots) isHot[n] = true;
        for(int n : colds) isCold[n] = true;

        double[] scoresChance = new double[11];
        for(int i=1; i<=10; i++) scoresChance[i] = appliquerPoids(sc.getRawStatsChance().get(i), config);

        // 3. Boucle de génération
        int essais = 0; int maxEssais = nbGrilles * 10;
        while(resultats.size() < nbGrilles && essais < maxEssais) {
            essais++;
            // Utilisation de la méthode primitive
            int[] boules = genererGrilleOptimiseePrimitive(hots, neutrals, colds, isHot, isCold, sc.getMatriceAffinites(), sc.getDernierTirageConnu(), sc.getTopTriosPrecalcules());

            if (estGrilleCoherenteOptimisee(boules, sc.getDernierTirageConnu(), sc.getContraintes())) {
                // Sélection du numéro chance optimisée
                int chance = selectionnerChanceRapidePrimitive(boules, scoresChance, sc.getMatriceChance());

                // On crée un tableau de 6 pour stocker toute la grille
                int[] grilleComplete = new int[6];
                System.arraycopy(boules, 0, grilleComplete, 0, 5);
                grilleComplete[5] = chance;

                resultats.add(grilleComplete);
            }
        }
        return resultats;
    }

    private int[] genererGrilleOptimiseePrimitive(int[] hots, int[] neutrals, int[] colds, boolean[] isHot, boolean[] isCold, int[][] matrice, List<Integer> dernierTirage, List<List<Integer>> trios) {
        int[] buffer = new int[5];
        int size = 0;
        ThreadLocalRandom rng = ThreadLocalRandom.current();

        // 1. LOGIQUE TRIO (Utilisation intelligente du dernier tirage)
        if (trios != null && !trios.isEmpty() && rng.nextBoolean()) {
            // On tente de trouver un trio qui n'est pas déjà trop présent dans le dernier tirage
            for (int attempt = 0; true; attempt++) {
                List<Integer> trioChoisi = trios.get(rng.nextInt(trios.size()));
                int communsDernier = 0;
                if (dernierTirage != null) {
                    for (Integer n : trioChoisi) if (dernierTirage.contains(n)) communsDernier++;
                }
                // Si le trio a 2 ou 3 numéros déjà sortis, on réessaye (rare mais à éviter)
                if (communsDernier < 2 || attempt == 2) {
                    for (Integer n : trioChoisi) buffer[size++] = n;
                    break;
                }
            }
        }

        // 2. INITIALISATION DE LA BASE (Si pas de trio ou échec)
        if (size == 0) {
            int base = hots.length > 0 ? hots[rng.nextInt(hots.length)] : 1 + rng.nextInt(49);
            buffer[size++] = base;
        }

        // 3. REMPLISSAGE PAR AFFINITÉ AVEC CONTRÔLE DE REDONDANCE
        while (size < 5) {
            // On calcule combien de numéros du dernier tirage on a déjà intégré
            int countCommuns = 0;
            if (dernierTirage != null) {
                for (int i = 0; i < size; i++) if (dernierTirage.contains(buffer[i])) countCommuns++;
            }

            // Stratégie de Pool
            int nbHot = 0, nbCold = 0;
            for (int i = 0; i < size; i++) {
                if (isHot[buffer[i]]) nbHot++;
                else if (isCold[buffer[i]]) nbCold++;
            }

            int[] targetPool = (nbHot < 2) ? hots : (nbCold < 1 ? colds : neutrals);
            int elu = selectionnerParAffiniteFastPrimitive(targetPool, buffer, size, matrice);

            // NOUVEAU : Si l'élu fait partie du dernier tirage et qu'on en a déjà 2, on rejette
            // (Statistiquement, plus de 2 numéros communs entre deux tirages successifs est rare)
            if (elu != -1 && dernierTirage != null && dernierTirage.contains(elu) && countCommuns >= 2) {
                elu = -1; // Force la sélection aléatoire hors pool ou hors dernier tirage
            }

            if (elu == -1) {
                int n;
                int security = 0;
                do {
                    n = 1 + rng.nextInt(49);
                    security++;
                    // On évite les doublons et on limite les communs avec le dernier tirage
                } while ((containsPrimitive(buffer, size, n) || (dernierTirage != null && dernierTirage.contains(n) && countCommuns >= 2)) && security < 20);
                buffer[size++] = n;
            } else {
                buffer[size++] = elu;
            }
        }

        Arrays.sort(buffer);
        return buffer;
    }

    private int selectionnerChanceRapidePrimitive(int[] boules, double[] scoresChanceArr, int[][] matriceChance) {
        int meilleurChance = 1;
        double meilleurScore = -Double.MAX_VALUE;
        for (int c = 1; c <= 10; c++) {
            double score = scoresChanceArr[c];
            for (int b : boules) score += (matriceChance[b][c] * 2.0);
            if (score > meilleurScore) { meilleurScore = score; meilleurChance = c; }
        }
        return meilleurChance;
    }

    // ==================================================================================
    // 4. BACKTESTING & HELPERS
    // ==================================================================================

    public List<ScenarioSimulation> preparerScenariosBacktest(List<LotoTirage> historiqueComplet, int depthBacktest, int limit) {
        List<ScenarioSimulation> scenarios = new ArrayList<>();
        int startIdx = 0; int count = 0;

        while (count < limit && (startIdx + depthBacktest + 50) < historiqueComplet.size()) {
            LotoTirage cible = historiqueComplet.get(startIdx);
            List<LotoTirage> historyConnu = historiqueComplet.subList(startIdx + 1, startIdx + 1 + depthBacktest);
            if (historyConnu.isEmpty()) { startIdx++; continue; }

            List<Integer> dernierTirage = historyConnu.get(0).getBoules();

            // Utilisation des méthodes directes int[][]
            int[][] matAffArr = construireMatriceAffinitesDirecte(historyConnu, cible.getDateTirage().getDayOfWeek());
            int[][] matChanceArr = construireMatriceChanceDirecte(historyConnu, cible.getDateTirage().getDayOfWeek());

            double[][] matMarkov = precalculerMatriceMarkovParJour(historyConnu, cible.getDateTirage().getDayOfWeek());
            int etatDernier = calculerEtatAbstrait(dernierTirage);
            DynamicConstraints contraintes = analyserContraintesDynamiques(historyConnu);
            List<List<Integer>> topTrios = getTopTriosRecents(historyConnu);
            Set<Integer> hotFinales = detecterFinalesChaudes(historyConnu);

            RawStatData[] rawBoulesArr = extraireStatsBrutesArray(historyConnu, cible.getDateTirage().getDayOfWeek(), hotFinales);
            Map<Integer, RawStatData> rawChance = extraireStatsBrutes(historyConnu, 10, cible.getDateTirage().getDayOfWeek(), true, Collections.emptySet());

            scenarios.add(new ScenarioSimulation(cible, dernierTirage, matAffArr, matChanceArr, matMarkov, etatDernier, rawBoulesArr, rawChance, contraintes, topTrios));
            startIdx++; count++;
        }
        return scenarios;
    }

    // ------------------------------------------------------------------------
    // OPTIMISATION "ZERO ALLOCATION" : Utilisation de tableaux primitifs int[]
    // ------------------------------------------------------------------------

    private int[] genererGrilleOptimisee(int[] hots, int[] neutrals, int[] colds, boolean[] isHot,
            boolean[] isCold, int[][] matrice, List<Integer> dernierTirage, List<List<Integer>> trios) {
        // 1. Buffer Primitif (évite de créer une ArrayList et des objets Integer inutilement)
        int[] buffer = new int[5];
        int size = 0;
        ThreadLocalRandom rng = ThreadLocalRandom.current();

        // 1. Trio
        if (trios != null && !trios.isEmpty() && rng.nextBoolean()) {
            for (int tryTrio = 0; tryTrio < 3; tryTrio++) {
                List<Integer> trioChoisi = trios.get(rng.nextInt(trios.size()));
                int communs = 0;
                if (dernierTirage != null) for (Integer n : trioChoisi) if (dernierTirage.contains(n)) communs++;
                if (communs < 2) {
                    for (Integer n : trioChoisi) buffer[size++] = n;
                    break;
                }
            }
        }

        // 2. Base
        if (size == 0) {
            if (hots.length > 0) {
                int h = hots[rng.nextInt(hots.length)];
                if (dernierTirage == null || !dernierTirage.contains(h)) buffer[size++] = h;
                else buffer[size++] = 1 + rng.nextInt(49);
            } else {
                buffer[size++] = 1 + rng.nextInt(49);
            }
        }

        // 3. Remplissage avec tableaux primitifs
        while (size < 5) {
            int nbHot = 0; int nbCold = 0;
            for (int i = 0; i < size; i++) {
                int n = buffer[i];
                if (isHot[n]) nbHot++; else if (isCold[n]) nbCold++;
            }

            int[] targetPool = (nbHot < 2) ? hots : (nbCold < 1 ? colds : neutrals);
            if (targetPool.length == 0) targetPool = hots;

            int elu = selectionnerParAffiniteFastPrimitive(targetPool, buffer, size, matrice);

            if (elu == -1) {
                int n; do { n = 1 + rng.nextInt(49); } while (containsPrimitive(buffer, size, n));
                buffer[size++] = n;
            } else {
                buffer[size++] = elu;
            }
        }

        Arrays.sort(buffer);
        return buffer;
    }

    /**
     * Version optimisée de selectionnerParAffinite qui lit un int[] au lieu d'une List
     */
    private int selectionnerParAffiniteFastPrimitive(int[] candidats, int[] selectionActuelle, int currentSize, int[][] matriceAffinites) {
        int meilleurCandidat = -1;
        double meilleurScore = -Double.MAX_VALUE;

        // Boucle sur tableau primitif (Ultra rapide, zéro garbage)
        for (int candidat : candidats) {
            if (containsPrimitive(selectionActuelle, currentSize, candidat))
                continue;

            double scoreLien = 1.0;
            for (int j = 0; j < currentSize; j++) {
                scoreLien += matriceAffinites[selectionActuelle[j]][candidat];
            }

            if (scoreLien > meilleurScore) {
                meilleurScore = scoreLien;
                meilleurCandidat = candidat;
            }
        }
        return meilleurCandidat;
    }

    /**
     * Vérifie si une valeur existe dans le tableau primitif (remplace List.contains)
     */
    private boolean containsPrimitive(int[] arr, int size, int val) {
        for (int i = 0; i < size; i++) if (arr[i] == val) return true;
        return false;
    }

    private double appliquerPoids(RawStatData raw, AlgoConfig cfg) {
        if(raw == null) return 0.0;
        double s = 10.0;
        s += (raw.getFreqJour() * cfg.getPoidsFreqJour());
        if (raw.getEcart() > 40) s -= 5.0; else if (raw.getEcart() > 10) s += (raw.getEcart() * cfg.getPoidsEcart());
        if (raw.isForme()) s += cfg.getPoidsForme();
        if (raw.isTresForme()) s += 25.0;
        if (raw.isHotFinale()) s += 8.0;
        if (raw.isTension()) s += cfg.getPoidsTension();
        return s;
    }

    // ==================================================================================
    // 3. STATS & ANALYSE
    // ==================================================================================

    public StatsReponse getStats(String jourFiltre) {
        StatsReponse localCache = this.cachedGlobalStats;
        if (jourFiltre == null && localCache != null) {
            return localCache;
        }

        log.info("⚙️ [DB] Calcul lourd des statistiques pour : {}", jourFiltre);
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

        StatsReponse reponse = new StatsReponse(stats, minDate.format(fmt), maxDate.format(fmt), all.size());
        if (jourFiltre == null) {
            this.cachedGlobalStats = reponse;
        }
        return reponse;
    }

    @Cacheable(value = "statsGlobales", key = "'MATRICE_GRAPHE_PUBLIC'")
    public Map<Integer, Map<Integer, Integer>> getMatriceAffinitesPublic() {
        // COMPATIBILITÉ : Récupère int[][] et convertit en Map pour le Front
        List<LotoTirage> history = repository.findAll();
        int[][] matrice = construireMatriceAffinitesDirecte(history, LocalDate.now().getDayOfWeek());

        Map<Integer, Map<Integer, Integer>> resultat = new HashMap<>();
        for (int i = 1; i <= 49; i++) {
            Map<Integer, Integer> ligne = new HashMap<>();
            for (int j = 1; j <= 49; j++) {
                if (i != j && matrice[i][j] > 0) ligne.put(j, matrice[i][j]);
            }
            if (!ligne.isEmpty()) resultat.put(i, ligne);
        }
        return resultat;
    }

    // --- Helpers et Imports ---

    public double calculerGainSimule(UserBet bet, LotoTirage tirage) {
        if (tirage == null || bet == null) return 0.0;
        if (bet.getCodeLoto() != null && !bet.getCodeLoto().isEmpty()) {
            String userCode = bet.getCodeLoto().replaceAll("\\s", "").toUpperCase();
            List<String> winningCodes = tirage.getWinningCodes();
            if (winningCodes != null && winningCodes.contains(userCode)) return 20000.0;
        }
        List<Integer> tirageBoules = tirage.getBoules();
        if (tirageBoules == null || tirageBoules.isEmpty()) {
            tirageBoules = List.of(tirage.getBoule1(), tirage.getBoule2(), tirage.getBoule3(), tirage.getBoule4(), tirage.getBoule5());
        }
        int matches = 0;
        if (tirageBoules.contains(bet.getB1())) matches++;
        if (tirageBoules.contains(bet.getB2())) matches++;
        if (tirageBoules.contains(bet.getB3())) matches++;
        if (tirageBoules.contains(bet.getB4())) matches++;
        if (tirageBoules.contains(bet.getB5())) matches++;
        boolean chanceMatch = (bet.getChance() == tirage.getNumeroChance());
        int rankPosition = 0;
        if (matches == 5 && chanceMatch) rankPosition = 1;
        else if (matches == 5) rankPosition = 2;
        else if (matches == 4 && chanceMatch) rankPosition = 3;
        else if (matches == 4) rankPosition = 4;
        else if (matches == 3 && chanceMatch) rankPosition = 5;
        else if (matches == 3) rankPosition = 6;
        else if (matches == 2 && chanceMatch) rankPosition = 7;
        else if (matches == 2) rankPosition = 8;
        else if (matches == 0 && chanceMatch) rankPosition = 9;

        if (rankPosition > 0) {
            int finalRankPos = rankPosition;
            if (tirage.getRanks() != null && !tirage.getRanks().isEmpty()) {
                return tirage.getRanks().stream().filter(r -> r.getRankNumber() == finalRankPos).findFirst().map(LotoTirageRank::getPrize).orElseGet(() -> (finalRankPos == 9) ? 2.20 : 0.0);
            }
            return (rankPosition == 9) ? 2.20 : 0.0;
        }
        return 0.0;
    }

    @CacheEvict(value = {"statsGlobales", "pronosticsIA"}, allEntries = true)
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
                        b1=Integer.parseInt(row[4]); b2=Integer.parseInt(row[5]); b3=Integer.parseInt(row[6]); b4=Integer.parseInt(row[7]); b5=Integer.parseInt(row[8]); c=Integer.parseInt(row[9]);
                    } else {
                        row = line.trim().split("\\s+"); if(row.length<8) continue;
                        try{date=LocalDate.parse(row[6],fmt2);}catch(Exception e){try{date=LocalDate.parse(row[6],fmt1);}catch(Exception ex){continue;}}
                        b1=Integer.parseInt(row[1]); b2=Integer.parseInt(row[2]); b3=Integer.parseInt(row[3]); b4=Integer.parseInt(row[4]); b5=Integer.parseInt(row[5]); c=Integer.parseInt(row[7]);
                    }
                    if (!repository.existsByDateTirage(date)) {
                        LotoTirage t = new LotoTirage();
                        t.setDateTirage(date); t.setBoule1(b1); t.setBoule2(b2); t.setBoule3(b3); t.setBoule4(b4); t.setBoule5(b5); t.setNumeroChance(c);
                        repository.save(t);
                    }
                } catch(Exception e) { log.error("Erreur ligne: {}", line); }
            }
            this.cachedGlobalStats = null;
            cachedDailyPronosRef.set(null);
        }
    }

    @CacheEvict(value = {"statsGlobales", "pronosticsIA"}, allEntries = true)
    public LotoTirage ajouterTirageManuel(TirageManuelDto dto) {
        if (repository.existsByDateTirage(dto.getDateTirage())) throw new RuntimeException("Ce tirage existe déjà");
        LotoTirage t = new LotoTirage();
        t.setDateTirage(dto.getDateTirage()); t.setBoule1(dto.getBoule1()); t.setBoule2(dto.getBoule2()); t.setBoule3(dto.getBoule3()); t.setBoule4(dto.getBoule4()); t.setBoule5(dto.getBoule5()); t.setNumeroChance(dto.getNumeroChance());
        repository.save(t);
        this.cachedGlobalStats = null;
        this.cachedDailyPronosRef.set(null);
        return t;
    }

    public SimulationResultDto simulerGrilleDetaillee(List<Integer> boulesJouees, LocalDate dateSimul) {
        return simulerGrilleDetaillee(boulesJouees, dateSimul, repository.findAll());
    }

    private SimulationResultDto simulerGrilleDetaillee(List<Integer> boulesJouees, LocalDate dateSimul, List<LotoTirage> historique) {
        SimulationResultDto result = new SimulationResultDto();
        try { result.setDateSimulee(dateSimul.format(DateTimeFormatter.ofPattern(Constantes.FORMAT_DATE_STANDARD))); } catch (Exception e) { result.setDateSimulee(dateSimul.toString()); }
        result.setJourSimule(dateSimul.getDayOfWeek().getDisplayName(TextStyle.FULL, Locale.FRANCE).toUpperCase());
        result.setQuintuplets(new ArrayList<>()); result.setQuartets(new ArrayList<>()); result.setTrios(new ArrayList<>()); result.setPairs(new ArrayList<>());
        for (LotoTirage t : historique) {
            List<Integer> commun = new ArrayList<>(t.getBoules()); commun.retainAll(boulesJouees);
            int taille = commun.size();
            if (taille >= 2) {
                String dateHist;
                try { dateHist = t.getDateTirage().format(DateTimeFormatter.ofPattern(Constantes.FORMAT_DATE_STANDARD)); } catch (Exception e) { dateHist = t.getDateTirage().toString(); }
                boolean memeJour = t.getDateTirage().getDayOfWeek() == dateSimul.getDayOfWeek();
                addToResult(result, taille, commun, dateHist, memeJour, historique.size());
            }
        }
        return result;
    }

    private void addToResult(SimulationResultDto res, int taille, List<Integer> nums, String date, boolean memeJour, int totalTirages) {
        List<MatchGroup> targetList = switch (taille) {
            case 5 -> res.getQuintuplets(); case 4 -> res.getQuartets(); case 3 -> res.getTrios(); case 2 -> res.getPairs(); default -> null;
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
        double probaTheo = switch (taille) { case 1 -> 0.10204; case 2 -> 0.00850; case 3 -> 0.00041; case 4 -> 0.0000096; case 5 -> 0.00000052; default -> 0.0; };
        double nbreAttendu = totalTirages * probaTheo;
        int nbreReel = group.getDates().size();
        double ratio = (nbreAttendu > 0) ? (nbreReel / nbreAttendu) : 0.0;
        group.setRatio(Math.round(ratio * 100.0) / 100.0);
    }

    public LocalDate recupererDateProchainTirage() {
        ZoneId zoneParis = ZoneId.of("Europe/Paris");
        ZonedDateTime maintenant = ZonedDateTime.now(zoneParis);
        LocalDate dateCandidate = maintenant.toLocalDate();
        LocalTime heureActuelle = maintenant.toLocalTime();
        Set<DayOfWeek> joursTirage = Set.of(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY, DayOfWeek.SATURDAY);

        boolean estJourTirage = joursTirage.contains(dateCandidate.getDayOfWeek());
        if (estJourTirage && heureActuelle.isAfter(LocalTime.of(20, 15))) {
            dateCandidate = dateCandidate.plusDays(1);
        }
        while (!joursTirage.contains(dateCandidate.getDayOfWeek())) {
            dateCandidate = dateCandidate.plusDays(1);
        }
        return dateCandidate;
    }

    // --- Private Calculation Helpers ---

    private double[] calculerScoresOptimise(List<LotoTirage> history, int maxNum, DayOfWeek jourCible, boolean isChance, List<Integer> boostNumbers, Set<Integer> hotFinales, AlgoConfig config, List<Integer> dernierTirage, double[] deepWeights) {
        double[] scores = new double[maxNum + 1]; Arrays.fill(scores, 10.0);
        int[] freqJour = new int[maxNum + 1]; int[] lastSeenIndex = new int[maxNum + 1]; Arrays.fill(lastSeenIndex, -1);
        int[] sortiesRecentes = new int[maxNum + 1]; int[] sortiesTresRecentes = new int[maxNum + 1]; int[] totalSorties = new int[maxNum + 1];
        int totalHistory = history.size();
        for (int i = 0; i < totalHistory; i++) {
            LotoTirage t = history.get(i); boolean isJourCible = (t.getDateTirage().getDayOfWeek() == jourCible);
            List<Integer> numsToCheck = isChance ? List.of(t.getNumeroChance()) : t.getBoules();
            for (int n : numsToCheck) {
                if (n > maxNum || n < 1) continue;
                totalSorties[n]++;
                if (isJourCible) freqJour[n]++;
                if (lastSeenIndex[n] == -1) lastSeenIndex[n] = i;
                if (i < 15) sortiesRecentes[n]++;
                if (i < 10) sortiesTresRecentes[n]++;
            }
        }
        for (int num = 1; num <= maxNum; num++) {
            double s = scores[num];
            s += (freqJour[num] * config.getPoidsFreqJour());
            long ecart = (lastSeenIndex[num] == -1) ? totalHistory : lastSeenIndex[num];
            if (ecart > 40) s -= 5.0; else if (ecart > 10) s += (ecart * config.getPoidsEcart());
            if (sortiesRecentes[num] >= 2) s += config.getPoidsForme();
            if (sortiesTresRecentes[num] >= 2) s += 25.0;
            if (!isChance && totalSorties[num] > 5) s += config.getPoidsTension();
            if (boostNumbers.contains(num)) s += 30.0;
            if (!isChance && hotFinales != null && hotFinales.contains(num % 10)) s += 8.0;
            if (!isChance && dernierTirage != null && dernierTirage.contains(num)) s -= 10.0;

            // On vérifie que le tableau existe et que l'index "num" est valide
            if (deepWeights != null && num < deepWeights.length) {
                // On ajoute le poids neuronal au score classique
                s += deepWeights[num];
            }

            scores[num] = s;
        }
        return scores;
    }

    private double calculerScoreFitnessOptimise(int[] boules, int chance, double[] scoresBoules, double[] scoresChance, int[][] matriceAffinites, AlgoConfig config, double[][] matriceMarkov, int etatDernierTirage) {
        double score = 0.0;

        // 1. Scores individuels (Fréquence, Forme, Ecart, etc.)
        for (int b : boules) score += scoresBoules[b];
        score += scoresChance[chance];

        // 2. Score Affinité (Boucles sur tableaux primitifs)
        double scoreAffinite = 0;
        for (int i = 0; i < 5; i++) {
            for (int j = i + 1; j < 5; j++) {
                scoreAffinite += matriceAffinites[boules[i]][boules[j]];
            }
        }
        score += (scoreAffinite * config.getPoidsAffinite());

        // 3. NOUVEAU : Score Markov (Transitions d'états)
        // On calcule l'état de la grille candidate (en utilisant la logique de calculerEtatAbstrait)
        int sommeCandidate = 0;
        for (int b : boules) sommeCandidate += b;

        int etatCandidat;
        if (sommeCandidate < 100) etatCandidat = 1;
        else if (sommeCandidate <= 125) etatCandidat = 2;
        else if (sommeCandidate <= 150) etatCandidat = 3;
        else if (sommeCandidate <= 175) etatCandidat = 4;
        else etatCandidat = 5;

        // On récupère la probabilité de transition depuis le dernier tirage
        // etatDernierTirage est l'index de ligne, etatCandidat l'index de colonne
        if (etatDernierTirage > 0 && etatDernierTirage <= 5) {
            double probabiliteTransition = matriceMarkov[etatDernierTirage][etatCandidat];
            // On multiplie par un facteur de 100 pour mettre la probabilité à l'échelle des autres scores
            score += (probabiliteTransition * 100.0 * config.getPoidsMarkov());
        }

        // 4. Somme et Parité (Optimisation visuelle)
        int pairs = 0;
        for (int b : boules) {
            if ((b & 1) == 0) pairs++;
        }

        // Bonus pour l'équilibre statistique (2 ou 3 paires sont les cas les plus fréquents)
        if (pairs == 2 || pairs == 3) score += 15.0;

        // Bonus pour la zone de somme "centrale" statistiquement la plus probable
        if (sommeCandidate >= 120 && sommeCandidate <= 170) score += 10.0;

        // NOUVEAU : Bonus d'Entropie
        // On favorise les grilles qui ne sont pas des suites simples ou des patterns trop réguliers
        double entropie = calculerEntropieShannon(boules);
        score += (entropie * 12.0);

        return score;
    }

    private RawStatData[] extraireStatsBrutesArray(List<LotoTirage> history, DayOfWeek jour, Set<Integer> hotFinales) {
        RawStatData[] arr = new RawStatData[49 + 1];
        Map<Integer, RawStatData> map = extraireStatsBrutes(history, 49, jour, false, hotFinales);
        for(int i = 1; i<= 49; i++) arr[i] = map.get(i);
        return arr;
    }

    private Map<Integer, RawStatData> extraireStatsBrutes(List<LotoTirage> history, int maxNum, DayOfWeek jour, boolean isChance, Set<Integer> hotFinales) {
        Map<Integer, RawStatData> map = new HashMap<>(); int totalHistory = history.size();
        int[] freqJour = new int[maxNum + 1]; int[] lastSeenIndex = new int[maxNum + 1]; Arrays.fill(lastSeenIndex, -1);
        int[] sortiesRecentes = new int[maxNum + 1]; int[] sortiesTresRecentes = new int[maxNum + 1]; int[] totalSorties = new int[maxNum + 1];
        for (int i = 0; i < totalHistory; i++) {
            LotoTirage t = history.get(i); boolean isJour = (t.getDateTirage().getDayOfWeek() == jour);
            List<Integer> nums = isChance ? List.of(t.getNumeroChance()) : t.getBoules();
            for(int n : nums) {
                if(n > maxNum) continue; totalSorties[n]++;
                if(isJour) freqJour[n]++;
                if(lastSeenIndex[n] == -1) lastSeenIndex[n] = i;
                if(i < 15) sortiesRecentes[n]++;
                if(i < 10) sortiesTresRecentes[n]++;
            }
        }
        for (int i = 1; i <= maxNum; i++) {
            long ecart = (lastSeenIndex[i] == -1) ? totalHistory : lastSeenIndex[i];
            boolean isHotF = !isChance && hotFinales != null && hotFinales.contains(i % 10);
            boolean isTen = !isChance && totalSorties[i] > 5;
            map.put(i, new RawStatData(freqJour[i], ecart, sortiesRecentes[i]>=2, sortiesTresRecentes[i]>=2, false, isHotF, isTen));
        }
        return map;
    }

    private int calculerEtatAbstrait(List<Integer> boules) {
        int somme = 0; for(int b : boules) somme += b;
        if (somme < 100) return 1;
        if (somme <= 125) return 2;
        if (somme <= 150) return 3;
        if (somme <= 175) return 4;
        return 5;
    }

    private double[][] precalculerMatriceMarkovParJour(List<LotoTirage> history, DayOfWeek jourCible) {
        double[][] matrix = new double[6][6];
        int[] totalTransitions = new int[6];

        // On ne filtre que les tirages du même jour de la semaine
        List<LotoTirage> historyJour = history.stream()
                .filter(t -> t.getDateTirage().getDayOfWeek() == jourCible)
                .toList();

        int limit = Math.min(historyJour.size(), 150); // Moins de données, donc on limite la profondeur
        for (int i = 0; i < limit - 1; i++) {
            // Transition d'un Lundi vers le Lundi précédent
            int etatHier = calculerEtatAbstrait(historyJour.get(i+1).getBoules());
            int etatAuj = calculerEtatAbstrait(historyJour.get(i).getBoules());

            matrix[etatHier][etatAuj]++;
            totalTransitions[etatHier]++;
        }

        // Normalisation
        for (int i = 1; i <= 5; i++) {
            if (totalTransitions[i] > 0) {
                for (int j = 1; j <= 5; j++) matrix[i][j] /= totalTransitions[i];
            }
        }
        return matrix;
    }

    private Map<String, List<Integer>> creerBucketsOptimises(double[] scores) {
        List<Integer> indices = new ArrayList<>(49); for(int i=1; i<=49; i++) indices.add(i);
        indices.sort((a, b) -> Double.compare(scores[b], scores[a]));
        int taille = indices.size(); int q = taille / 4;
        Map<String, List<Integer>> b = new HashMap<>();
        b.put(Constantes.BUCKET_HOT, new ArrayList<>(indices.subList(0, q)));
        b.put(Constantes.BUCKET_NEUTRAL, new ArrayList<>(indices.subList(q, taille - q)));
        b.put(Constantes.BUCKET_COLD, new ArrayList<>(indices.subList(taille - q, taille)));
        return b;
    }

    /**
     * Vérification de la cohérence de la grille
     * @param boules 5 numéros
     * @param dernierTirage dernier tirage
     * @param rules contraintes à appliquer
     * @return true si cohérente, false sinon
     */
    public boolean estGrilleCoherenteOptimisee(int[] boules, List<Integer> dernierTirage, DynamicConstraints rules) {
        // 1. EXTRACTION DIRECTE
        int b0 = boules[0], b1 = boules[1], b2 = boules[2], b3 = boules[3], b4 = boules[4];

        // AJOUT ANALYST : Filtre d'Entropie
        // Une entropie < 1.5 signifie que les nombres sont très groupés (ex: 12, 13, 14, 15, 18)
        // Statistiquement, le Loto favorise la dispersion (Entropie élevée).
        if (calculerEntropieShannon(boules) < 1.5) {
            return false;
        }

        // Calcul des Deltas (Astuce de l'analyse précédente intégrée)
        int d1 = b1 - b0, d2 = b2 - b1, d3 = b3 - b2, d4 = b4 - b3;
        if (d1 > 30 || d2 > 30 || d3 > 30 || d4 > 30) return false;

        int somme = b0 + b1 + b2 + b3 + b4;
        if (somme < 85 || somme > 210) return false;

        // Bitwise pour parité et dizaines
        int pairs = 0, dizainesMask = 0;
        for (int b : boules) {
            if ((b & 1) == 0) pairs++;
            dizainesMask |= (1 << (b / 10));
        }

        if (pairs < rules.getMinPairs() || pairs > rules.getMaxPairs()) return false;
        if (Integer.bitCount(dizainesMask) < 3) return false;

        // Comparaison dernier tirage (Boxing minimal ici)
        if (dernierTirage != null) {
            int communs = 0;
            for (int b : boules) if (dernierTirage.contains(b)) communs++;
            return communs < 4;
        }
        return true;
    }

    /**
     * Méthode de détection des finales (fin numéro) sorties récemment
     * @param history historique
     * @return Set des finales
     */
    private Set<Integer> detecterFinalesChaudes(List<LotoTirage> history) {
        if (history == null || history.isEmpty()) return Collections.emptySet();

        // 1. Tableau compteur (Index 0 = Finale 0, Index 9 = Finale 9)
        // Pas d'allocation de Map complexe.
        int[] counts = new int[10];

        // 2. Limite stricte
        int limit = Math.min(history.size(), 20);

        // 3. Boucle primitive rapide (Pas de Stream, pas d'Iterator)
        for (int i = 0; i < limit; i++) {
            LotoTirage t = history.get(i);
            // Accès direct aux champs pour éviter la création de List<Integer> via getBoules()
            // Note : On assume que les getters renvoient des int primitifs ou sont inlinés par la JVM
            counts[t.getBoule1() % 10]++;
            counts[t.getBoule2() % 10]++;
            counts[t.getBoule3() % 10]++;
            counts[t.getBoule4() % 10]++;
            counts[t.getBoule5() % 10]++;
        }

        // 4. Recherche des 2 meilleurs (Algorithme "King of the Hill" en un seul passage)
        int bestFinale1 = -1;
        int bestFinale2 = -1;
        int maxCount1 = -1;
        int maxCount2 = -1;

        for (int f = 0; f < 10; f++) {
            int c = counts[f];
            if (c > maxCount1) {
                // Le nouveau est le n°1, l'ancien n°1 devient n°2
                maxCount2 = maxCount1;
                bestFinale2 = bestFinale1;

                maxCount1 = c;
                bestFinale1 = f;
            } else if (c > maxCount2) {
                // Le nouveau est le n°2
                maxCount2 = c;
                bestFinale2 = f;
            }
        }

        // 5. Construction du résultat final (Léger Set)
        Set<Integer> result = new HashSet<>();
        if (bestFinale1 != -1) result.add(bestFinale1);
        if (bestFinale2 != -1) result.add(bestFinale2);

        return result;
    }

    /**
     * Définition des contraintes dynamiques
     * @param history historique des tirages
     * @return contraintes à appliquer
     */
    private DynamicConstraints analyserContraintesDynamiques(List<LotoTirage> history) {
        // 1. Paires/Impaires : On ouvre la fenêtre pour couvrir ~85% des cas réels.
        // Au lieu de restreindre dynamiquement à [2,3] ou [1,2], on autorise une plage large.
        int minP = 1;
        int maxP = 4;

        // 2. Suites (Ex : 12, 13) : On les autorise TOUJOURS.
        boolean allowSuites = true;

        // 3. Numéros Interdits (Blacklist) : On vide la liste.
        // On n'interdit plus aucun numéro "en dur".
        Set<Integer> forbidden = new HashSet<>();

        // On peut interdire un numéro seulement s'il est sorti TROIS fois de suite (très rare).
        if (history.size() >= 3) {
            Map<Integer, Integer> compteurs = new HashMap<>();
            // On regarde les 3 derniers tirages
            for (int i = 0; i < 3; i++) {
                for (Integer b : history.get(i).getBoules()) {
                    compteurs.merge(b, 1, Integer::sum);
                }
            }

            // Si un numéro est sorti 3 fois sur les 3 derniers tirages, on le blacklist pour aujourd'hui
            for (Map.Entry<Integer, Integer> entry : compteurs.entrySet()) {
                if (entry.getValue() >= 3) {
                    forbidden.add(entry.getKey());
                }
            }
        }

        return new DynamicConstraints(minP, maxP, allowSuites, forbidden);
    }

    /**
     * Renvoie le top 10 des trios sortis récemment
     * @param history historique
     * @return liste des 10 top trios
     */
    private List<List<Integer>> getTopTriosRecents(List<LotoTirage> history) {
        // 1. Map optimisée : Clé = BitMask (Long), Valeur = Fréquence
        // On pré-dimensionne pour éviter le resizing (100 tirages * 10 trios = 1000 max)
        Map<Long, Integer> frequencyMap = new HashMap<>(1024);

        int limit = Math.min(history.size(), 100);

        // 2. Buffer primitif réutilisable pour éviter les getBoules() qui créent des Listes
        int[] b = new int[5];

        for (int i = 0; i < limit; i++) {
            LotoTirage t = history.get(i);

            // Extraction directe (évite l'allocation de listes intermédiaires)
            b[0] = t.getBoule1();
            b[1] = t.getBoule2();
            b[2] = t.getBoule3();
            b[3] = t.getBoule4();
            b[4] = t.getBoule5();

            // 3. Triple boucle déroulée (C'est O(1) car toujours 10 itérations précises)
            // Génération des trios sans créer d'objets Set ni List
            for (int x = 0; x < 3; x++) {
                for (int y = x + 1; y < 4; y++) {
                    for (int z = y + 1; z < 5; z++) {
                        // On encode le trio en un seul Long unique
                        long mask = (1L << b[x]) | (1L << b[y]) | (1L << b[z]);
                        frequencyMap.merge(mask, 1, Integer::sum);
                    }
                }
            }
        }

        // 4. Tri et Conversion finale (seulement pour le Top 10)
        return frequencyMap.entrySet().stream()
                .sorted((e1, e2) -> e2.getValue().compareTo(e1.getValue())) // Tri décroissant
                .limit(10)
                .map(e -> bitMaskService.decodeBitMask(e.getKey())) // On ne recrée les listes qu'à la toute fin
                .collect(Collectors.toList());
    }

    /**
     * Méthode construction matrice d'affinités
     * @param history historique
     * @param jourCible jour tirage
     * @return tableau primitif int
     */
    private int[][] construireMatriceAffinitesDirecte(List<LotoTirage> history, DayOfWeek jourCible) {
        int[][] matrice = new int[50][50];
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
                    matrice[n1][n2] += poids;
                    matrice[n2][n1] += poids;
                }
            }
        }
        return matrice;
    }

    /**
     * Méthode construction matrice de chance
     * @param history historique
     * @param jourCible jour tirage
     * @return tableau primitif int
     */
    private int[][] construireMatriceChanceDirecte(List<LotoTirage> history, DayOfWeek jourCible) {
        int[][] matrice = new int[50][11];
        int limit = Math.min(history.size(), 350);
        for (int i = 0; i < limit; i++) {
            LotoTirage t = history.get(i);
            int poids = (t.getDateTirage().getDayOfWeek() == jourCible) ? 6 : 1;
            int chance = t.getNumeroChance();
            if (chance > 10 || chance < 1) continue;
            for (Integer boule : t.getBoules()) {
                matrice[boule][chance] += poids;
            }
        }
        return matrice;
    }
}
