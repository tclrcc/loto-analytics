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
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.context.event.EventListener;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.time.temporal.ChronoUnit;
import java.util.*;
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
    private final AstroService astroService;
    private final BacktestService backtestService;

    // --- VARIABLES DU CACHE MANUEL (Architecture Stateful) ---
    private volatile AlgoConfig cachedBestConfig = null;
    private volatile LocalDate lastBacktestDate = null;
    private volatile StatsReponse cachedGlobalStats = null;
    private final AtomicReference<List<PronosticResultDto>> cachedDailyPronosRef = new AtomicReference<>();
    private volatile LocalDate dateCachedPronos = null;

    // Constantes
    private static final String FIELD_DATE_TIRAGE = "dateTirage";
    private static final ZoneId ZONE_PARIS = ZoneId.of("Europe/Paris");

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

    @EventListener(ApplicationReadyEvent.class)
    public void initConfigFromDb() {
        log.info("🔌 Démarrage : Recherche stratégie en base...");
        strategyConfigRepostiroy.findTopByOrderByDateCalculDesc().ifPresentOrElse(
                last -> {
                    this.cachedBestConfig = new AlgoConfig(
                            last.getNomStrategie(), last.getPoidsFreqJour(), last.getPoidsForme(),
                            last.getPoidsEcart(), last.getPoidsTension(), last.getPoidsMarkov(),
                            last.getPoidsAffinite(), false
                    );
                    this.cachedBestConfig.setBilanEstime(last.getBilanEstime());
                    this.lastBacktestDate = last.getDateCalcul().atZone(ZoneId.systemDefault())
                            .withZoneSameInstant(ZONE_PARIS).toLocalDate();
                    log.info("✅ Stratégie chargée (Date: {}).", this.lastBacktestDate);
                },
                () -> log.warn("⚠️ Base vide : Calcul requis.")
        );
    }

    public void verificationAuDemarrage() {
        LocalDate todayParis = LocalDate.now(ZONE_PARIS);
        if (this.cachedBestConfig != null && todayParis.equals(this.lastBacktestDate)) {
            log.info("✋ [WARMUP] Stratégie du {} déjà en mémoire. OK.", todayParis);
            genererMultiplesPronostics(recupererDateProchainTirage(), 5);
            return;
        }
        log.info("⚠️ [WARMUP] Stratégie obsolète. Lancement optimisation !");
        forceDailyOptimization();
    }

    public AlgoConfig recupererMeilleureConfig() {
        return Objects.requireNonNullElseGet(this.cachedBestConfig, AlgoConfig::defaut);
    }

    public void forceDailyOptimization() {
        log.info("🌙 [CRON] Optimisation en cours...");
        this.cachedGlobalStats = null;
        cachedDailyPronosRef.set(null);
        List<LotoTirageRepository.TirageMinimal> rawData = repository.findAllOptimized();
        List<LotoTirage> historyLight = rawData.stream().map(this::mapToLightEntity).toList();

        if (!historyLight.isEmpty()) {
            AlgoConfig newConfig = backtestService.trouverMeilleureConfig(historyLight);
            this.cachedBestConfig = newConfig;
            this.lastBacktestDate = LocalDate.now(ZONE_PARIS);

            log.info("✅ [UPDATE] Nouvelle stratégie appliquée en RAM : {}", newConfig.getNomStrategie());

            StrategyConfig entity = new StrategyConfig();
            entity.setDateCalcul(LocalDateTime.now(ZONE_PARIS));
            entity.setNomStrategie(newConfig.getNomStrategie());
            entity.setPoidsForme(newConfig.getPoidsForme());
            entity.setPoidsEcart(newConfig.getPoidsEcart());
            entity.setPoidsAffinite(newConfig.getPoidsAffinite());
            entity.setPoidsMarkov(newConfig.getPoidsMarkov());
            entity.setPoidsTension(newConfig.getPoidsTension());
            entity.setPoidsFreqJour(newConfig.getPoidsFreqJour());
            entity.setBilanEstime(newConfig.getBilanEstime());
            entity.setNbTiragesTestes(newConfig.getNbTiragesTestes());
            entity.setNbGrillesParTest(newConfig.getNbGrillesParTest());
            entity.setRoi(newConfig.getRoiEstime());
            strategyConfigRepostiroy.save(entity);

            log.info("🔥 [WARMUP] Préchauffage des pronostics...");
            genererMultiplesPronostics(recupererDateProchainTirage(), 10);
            log.info("💾 [DB] Stratégie sauvegardée. ROI: {}%", String.format("%.2f", newConfig.getRoiEstime()));
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

    // ==================================================================================
    // 2. GÉNÉRATION DE PRONOSTICS (Optimisée)
    // ==================================================================================

    public List<PronosticResultDto> genererMultiplesPronostics(LocalDate dateCible, int nombreGrilles) {
        long startTotal = System.currentTimeMillis();
        List<PronosticResultDto> cached = cachedDailyPronosRef.get();
        if (cached != null && dateCible.equals(dateCachedPronos) && cached.size() >= nombreGrilles) {
            log.info("⚡ [CACHE] Pronostics récupérés instantanément en {} ms.", (System.currentTimeMillis() - startTotal));
            return cached.subList(0, nombreGrilles);
        }

        log.info("⚙️ [CALCUL IA] Démarrage génération fraîche pour le {}...", dateCible);
        List<PronosticResultDto> newsPronos = genererPronosticAvecConfig(dateCible, Math.max(nombreGrilles, 10), null);
        cachedDailyPronosRef.set(newsPronos);
        this.dateCachedPronos = dateCible;

        long duration = System.currentTimeMillis() - startTotal;
        log.info("🏁 [CALCUL IA] Terminé en {} ms. Résultat mis en cache.", duration);
        return newsPronos.subList(0, Math.min(newsPronos.size(), nombreGrilles));
    }

    @Cacheable(value = "pronosticsAstro", key = "#dateCible.toString() + '_' + #nombreGrilles + '_' + #profil.signe")
    public List<PronosticResultDto> genererPronosticsHybrides(LocalDate dateCible, int nombreGrilles, AstroProfileDto profil) {
        return genererPronosticAvecConfig(dateCible, nombreGrilles, profil);
    }

    private List<PronosticResultDto> genererPronosticAvecConfig(LocalDate dateCible, int nombreGrilles, AstroProfileDto profilAstro) {
        long t0 = System.currentTimeMillis();
        List<LotoTirageRepository.TirageMinimal> rawData = repository.findAllOptimized();
        List<LotoTirage> history = rawData.stream().map(this::mapToLightEntity).toList();

        if (history.isEmpty()) return new ArrayList<>();
        List<Integer> dernierTirage = history.get(0).getBoules();
        AlgoConfig configOptimisee = recupererMeilleureConfig();

        log.info("🕵️ [AUDIT PRE-CALCUL] Stratégie: '{}'", configOptimisee.getNomStrategie());

        // --- PRE-CALCULS MASSIFS ---
        Set<Integer> hotFinales = detecterFinalesChaudes(history);
        List<Integer> boostNumbers = (profilAstro != null) ? astroService.getLuckyNumbersOnly(profilAstro) : Collections.emptyList();
        List<List<Integer>> topTriosDuJour = getTopTriosRecents(history);

        // OPTIMISATION : Utilisation directe de int[][] (Gain RAM/CPU énorme)
        int[][] matriceAffinitesArr = construireMatriceAffinitesDirecte(history, dateCible.getDayOfWeek());
        int[][] matriceChanceArr = construireMatriceChanceDirecte(history, dateCible.getDayOfWeek());

        double[] scoresBoules = calculerScoresOptimise(history, 49, dateCible.getDayOfWeek(), false, boostNumbers, hotFinales, configOptimisee, dernierTirage);
        double[] scoresChance = calculerScoresOptimise(history, 10, dateCible.getDayOfWeek(), true, boostNumbers, Collections.emptySet(), configOptimisee, null);

        double[][] matriceMarkov = precalculerMatriceMarkov(history);
        int etatDernierTirage = calculerEtatAbstrait(dernierTirage);
        DynamicConstraints contraintesDuJour = analyserContraintesDynamiques(history, dernierTirage);

        Map<String, List<Integer>> buckets = creerBucketsOptimises(scoresBoules);
        List<Integer> hots = buckets.getOrDefault(Constantes.BUCKET_HOT, Collections.emptyList());
        List<Integer> neutrals = buckets.getOrDefault(Constantes.BUCKET_NEUTRAL, Collections.emptyList());
        List<Integer> colds = buckets.getOrDefault(Constantes.BUCKET_COLD, Collections.emptyList());

        boolean[] isHot = new boolean[51];
        boolean[] isCold = new boolean[51];
        for(int n : hots) isHot[n] = true;
        for(int n : colds) isCold[n] = true;

        Set<Long> historiqueBitMasks = new HashSet<>(history.size());
        for(int i=0; i<Math.min(history.size(), 300); i++) historiqueBitMasks.add(calculerBitMask(history.get(i).getBoules()));

        log.info("📊 [PREP] Matrices et Scores calculés en {} ms.", (System.currentTimeMillis() - t0));

        List<GrilleCandidate> population = executerAlgorithmeGenetique(
                hots, neutrals, colds, isHot, isCold,
                matriceAffinitesArr, dernierTirage, topTriosDuJour, scoresBoules, scoresChance,
                matriceChanceArr, contraintesDuJour, configOptimisee, historiqueBitMasks, matriceMarkov, etatDernierTirage
        );

        return finaliserResultats(population, nombreGrilles, dateCible, history);
    }

    /**
     * CŒUR DU RÉACTEUR - Optimisé pour 6 vCores
     */
    private List<GrilleCandidate> executerAlgorithmeGenetique(
            List<Integer> hots, List<Integer> neutrals, List<Integer> colds, boolean[] isHot, boolean[] isCold,
            int[][] matriceAffinites, List<Integer> dernierTirage, List<List<Integer>> topTrios,
            double[] scoresBoules, double[] scoresChance, int[][] matriceChance,
            DynamicConstraints contraintes, AlgoConfig config, Set<Long> historiqueBitMasks,
            double[][] matriceMarkov, int etatDernierTirage) {

        long tStart = System.currentTimeMillis();
        // UPGRADE : Avec 12 Go RAM, on vise 50 000 grilles
        int taillePopulationCible = 50_000;

        log.info("🚀 [TURBO] Démarrage génération parallèle sur {} cœurs...", Runtime.getRuntime().availableProcessors());

        List<GrilleCandidate> population = IntStream.range(0, taillePopulationCible * 2).parallel() // UTILISATION DES 6 COEURS
                .mapToObj(i -> {
                    // Chaque thread a son propre RNG via ThreadLocalRandom dans la méthode appelée
                    List<Integer> boules = genererGrilleOptimisee(hots, neutrals, colds, isHot, isCold, matriceAffinites, dernierTirage, topTrios);
                    Collections.sort(boules);
                    return boules;
                }).filter(boules -> estGrilleCoherenteOptimisee(boules, dernierTirage, contraintes))
                .filter(boules -> !historiqueBitMasks.contains(calculerBitMask(boules))).limit(taillePopulationCible).map(boules -> {
                    int chance = selectionnerChanceRapide(boules, scoresChance, matriceChance);
                    double fitness = calculerScoreFitnessOptimise(boules, chance, scoresBoules, scoresChance, matriceAffinites, config, matriceMarkov,
                            etatDernierTirage);
                    return new GrilleCandidate(boules, chance, fitness);
                }).sorted((g1, g2) -> Double.compare(g2.fitness, g1.fitness)).collect(Collectors.toList());

        long duration = System.currentTimeMillis() - tStart;
        log.info("✅ [TURBO] Terminé en {} ms. {} grilles analysées. Meilleur score : {}",
                duration, population.size(),
                population.isEmpty() ? "N/A" : String.format("%.2f", population.get(0).fitness));

        // Fallback
        if (population.isEmpty()) {
            List<Integer> secours = genererGrilleOptimisee(hots, neutrals, colds, isHot, isCold, matriceAffinites, dernierTirage, topTrios);
            Collections.sort(secours);
            population.add(new GrilleCandidate(secours, 1, 50.0));
        }

        return population;
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

            double[][] matMarkov = precalculerMatriceMarkov(historyConnu);
            int etatDernier = calculerEtatAbstrait(dernierTirage);
            DynamicConstraints contraintes = analyserContraintesDynamiques(historyConnu, dernierTirage);
            List<List<Integer>> topTrios = getTopTriosRecents(historyConnu);
            Set<Integer> hotFinales = detecterFinalesChaudes(historyConnu);

            RawStatData[] rawBoulesArr = extraireStatsBrutesArray(historyConnu, cible.getDateTirage().getDayOfWeek(), hotFinales);
            Map<Integer, RawStatData> rawChance = extraireStatsBrutes(historyConnu, 10, cible.getDateTirage().getDayOfWeek(), true, Collections.emptySet());

            scenarios.add(new ScenarioSimulation(cible, dernierTirage, matAffArr, matChanceArr, matMarkov, etatDernier, rawBoulesArr, rawChance, contraintes, topTrios));
            startIdx++; count++;
        }
        return scenarios;
    }

    public List<List<Integer>> genererGrillesDepuisScenario(ScenarioSimulation sc, AlgoConfig config, int nbGrilles) {
        List<List<Integer>> resultats = new ArrayList<>(nbGrilles);
        double[] scoresBoules = new double[50];

        for (int i = 1; i <= 49; i++) {
            scoresBoules[i] = appliquerPoids(sc.rawStatsBoulesArr[i], config);
            if (sc.dernierTirageConnu.contains(i)) scoresBoules[i] -= 10.0;
        }

        Map<String, List<Integer>> buckets = creerBucketsOptimises(scoresBoules);
        List<Integer> hots = buckets.getOrDefault(Constantes.BUCKET_HOT, Collections.emptyList());
        List<Integer> neutrals = buckets.getOrDefault(Constantes.BUCKET_NEUTRAL, Collections.emptyList());
        List<Integer> colds = buckets.getOrDefault(Constantes.BUCKET_COLD, Collections.emptyList());

        boolean[] isHot = new boolean[51];
        boolean[] isCold = new boolean[51];
        for(int n : hots) isHot[n] = true;
        for(int n : colds) isCold[n] = true;

        int essais = 0; int maxEssais = nbGrilles * 5;
        while(resultats.size() < nbGrilles && essais < maxEssais) {
            essais++;
            List<Integer> boules = genererGrilleOptimisee(hots, neutrals, colds, isHot, isCold, sc.matriceAffinites, sc.dernierTirageConnu, sc.topTriosPrecalcules);
            Collections.sort(boules);
            if (estGrilleCoherenteOptimisee(boules, sc.dernierTirageConnu, sc.contraintes)) {
                List<Integer> grilleFinale = new ArrayList<>(boules); grilleFinale.add(1);
                resultats.add(grilleFinale);
            }
        }
        return resultats;
    }

    // ------------------------------------------------------------------------
    // OPTIMISATION "ZERO ALLOCATION" : Utilisation de tableaux primitifs int[]
    // ------------------------------------------------------------------------

    private List<Integer> genererGrilleOptimisee(List<Integer> hots, List<Integer> neutrals, List<Integer> colds, boolean[] isHot,
            boolean[] isCold, int[][] matrice, List<Integer> dernierTirage, List<List<Integer>> trios) {
        // 1. Buffer Primitif (évite de créer une ArrayList et des objets Integer inutilement)
        int[] buffer = new int[5];
        int size = 0;
        ThreadLocalRandom rng = ThreadLocalRandom.current();

        // 2. Gestion Trio (Optimisée)
        if (trios != null && !trios.isEmpty() && rng.nextBoolean()) {
            for (int tryTrio = 0; tryTrio < 3; tryTrio++) {
                List<Integer> trioChoisi = trios.get(rng.nextInt(trios.size()));

                // Vérification rapide dernier tirage
                int communs = 0;
                if (dernierTirage != null) {
                    for (Integer n : trioChoisi) {
                        if (dernierTirage.contains(n)) communs++;
                    }
                }

                // Si valide, on copie dans le buffer primitif
                if (communs < 2) {
                    for (Integer n : trioChoisi) buffer[size++] = n;
                    break;
                }
            }
        }

        // 3. Base de départ si vide
        if (size == 0) {
            if (!hots.isEmpty()) {
                int h = hots.get(rng.nextInt(hots.size()));
                if (dernierTirage == null || !dernierTirage.contains(h)) {
                    buffer[size++] = h;
                } else {
                    buffer[size++] = 1 + rng.nextInt(49);
                }
            } else {
                buffer[size++] = 1 + rng.nextInt(49);
            }
        }

        // 4. Remplissage Rapide
        while (size < 5) {
            // Comptage Hot/Cold directement sur le tableau primitif (Très rapide)
            int nbHot = 0;
            int nbCold = 0;
            for (int i = 0; i < size; i++) {
                int n = buffer[i];
                if (isHot[n]) nbHot++;
                else if (isCold[n]) nbCold++;
            }

            List<Integer> targetPool = (nbHot < 2) ? hots : (nbCold < 1 ? colds : neutrals);
            if (targetPool.isEmpty()) targetPool = hots;

            // Appel de la version primitive du sélecteur
            int elu = selectionnerParAffiniteFastPrimitive(targetPool, buffer, size, matrice);

            if (elu == -1) {
                // Fallback aléatoire avec check primitif
                int n;
                do {
                    n = 1 + rng.nextInt(49);
                } while (containsPrimitive(buffer, size, n));
                buffer[size++] = n;
            } else {
                buffer[size++] = elu;
            }
        }

        // 5. Conversion finale (La seule allocation d'objet de toute la méthode)
        List<Integer> selection = new ArrayList<>(5);
        for (int i = 0; i < 5; i++) selection.add(buffer[i]);
        return selection;
    }

    /**
     * Version optimisée de selectionnerParAffinite qui lit un int[] au lieu d'une List
     */
    private int selectionnerParAffiniteFastPrimitive(List<Integer> candidats, int[] selectionActuelle, int currentSize, int[][] matriceAffinites) {
        int meilleurCandidat = -1;
        double meilleurScore = -Double.MAX_VALUE;

        // Boucle indexée sur la liste pour éviter l'itérateur (micro-opt)
        // Unboxing automatique
        for (int candidat : candidats) {
            if (containsPrimitive(selectionActuelle, currentSize, candidat))
                continue;

            double scoreLien = 1.0;
            // Lecture tableau primitif (Accès mémoire direct)
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
        for (int i = 0; i < size; i++) {
            if (arr[i] == val) return true;
        }
        return false;
    }

    private int selectionnerChanceRapide(List<Integer> boules, double[] scoresChanceArr, int[][] matriceChance) {
        int meilleurChance = 1;
        double meilleurScore = -Double.MAX_VALUE;
        ThreadLocalRandom rng = ThreadLocalRandom.current();

        for (int c = 1; c <= 10; c++) {
            double score = scoresChanceArr[c];
            double affiniteScore = 0;
            for (Integer b : boules) affiniteScore += matriceChance[b][c];
            score += (affiniteScore * 2.0);
            double scoreFinal = score + (rng.nextDouble() * 5.0);
            if (scoreFinal > meilleurScore) { meilleurScore = scoreFinal; meilleurChance = c; }
        }
        return meilleurChance;
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

    @CacheEvict(value = {"statsGlobales", "pronosticsIA", "pronosticsAstro"}, allEntries = true)
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

    private double[] calculerScoresOptimise(List<LotoTirage> history, int maxNum, DayOfWeek jourCible, boolean isChance, List<Integer> boostNumbers, Set<Integer> hotFinales, AlgoConfig config, List<Integer> dernierTirage) {
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
            scores[num] = s;
        }
        return scores;
    }

    private double calculerScoreFitnessOptimise(List<Integer> boules, int chance, double[] scoresBoules, double[] scoresChance, int[][] matriceAffinites, AlgoConfig config, double[][] matriceMarkov, int etatDernierTirage) {
        double score = 0.0;
        for (int b : boules) score += scoresBoules[b];
        if (chance >= 0 && chance < scoresChance.length) score += scoresChance[chance];
        double scoreAffinite = 0; int size = boules.size();
        for (int i = 0; i < size; i++) {
            int b1 = boules.get(i);
            for (int j = i + 1; j < size; j++) scoreAffinite += matriceAffinites[b1][boules.get(j)];
        }
        score += (scoreAffinite * config.getPoidsAffinite());
        int pairs = 0; int somme = 0;
        for (int b : boules) { if ((b & 1) == 0) pairs++; somme += b; }
        if (pairs == 2 || pairs == 3) score += 15.0;
        if (somme >= 120 && somme <= 170) score += 10.0;
        if (config.getPoidsMarkov() > 0) {
            int etatCandidat = calculerEtatAbstrait(boules);
            score += (matriceMarkov[etatDernierTirage][etatCandidat] * config.getPoidsMarkov());
        }
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

    private List<PronosticResultDto> finaliserResultats(List<GrilleCandidate> population, int nombreGrilles, LocalDate dateCible, List<LotoTirage> history) {
        List<PronosticResultDto> resultats = new ArrayList<>();
        Set<List<Integer>> grillesRetenues = new HashSet<>();
        long couvertureGlobale = 0L;

        population.sort((g1, g2) -> Double.compare(g2.fitness, g1.fitness));

        for (GrilleCandidate cand : population) {
            if (resultats.size() >= nombreGrilles) break;

            long masqueCandidat = calculerBitMask(cand.boules);
            long nouveauxNumerosMask = masqueCandidat & ~couvertureGlobale;
            int apportDiversite = Long.bitCount(nouveauxNumerosMask);

            if (resultats.isEmpty() || apportDiversite >= 1) {
                if (grillesRetenues.add(cand.boules)) {
                    couvertureGlobale |= masqueCandidat;
                    SimulationResultDto simu = simulerGrilleDetaillee(cand.boules, dateCible, history);
                    double maxDuo = simu.getPairs().stream().mapToDouble(MatchGroup::getRatio).max().orElse(0.0);
                    String typeAlgo = (cand.fitness < 50) ? "IA_FLEXIBLE" : "IA_OPTIMAL";

                    resultats.add(new PronosticResultDto(
                            cand.boules, cand.chance,
                            Math.round(cand.fitness * 100.0) / 100.0,
                            maxDuo, 0.0,
                            !simu.getQuintuplets().isEmpty(),
                            typeAlgo
                    ));
                }
            }
        }

        log.info("🔍 [FILTRE] {} grilles retenues après filtre diversité.", resultats.size());

        if (resultats.size() < nombreGrilles) {
            log.warn("⚠️ [PLAN B] Pas assez de diversité ({} manquantes). Complétion activée.", (nombreGrilles - resultats.size()));
            for (GrilleCandidate cand : population) {
                if (resultats.size() >= nombreGrilles) break;
                if (grillesRetenues.add(cand.boules)) {
                    SimulationResultDto simu = simulerGrilleDetaillee(cand.boules, dateCible, history);
                    double maxDuo = simu.getPairs().stream().mapToDouble(MatchGroup::getRatio).max().orElse(0.0);
                    resultats.add(new PronosticResultDto(
                            cand.boules, cand.chance,
                            Math.round(cand.fitness * 100.0) / 100.0,
                            maxDuo, 0.0,
                            !simu.getQuintuplets().isEmpty(),
                            "IA_SECOURS (PLAN B)"
                    ));
                }
            }
        }
        return resultats;
    }

    private int calculerEtatAbstrait(List<Integer> boules) {
        int somme = 0; for(int b : boules) somme += b;
        if (somme < 100) return 1;
        if (somme <= 125) return 2;
        if (somme <= 150) return 3;
        if (somme <= 175) return 4;
        return 5;
    }

    private double[][] precalculerMatriceMarkov(List<LotoTirage> history) {
        double[][] matrix = new double[6][6];
        int[] totalTransitions = new int[6];
        int limit = Math.min(history.size(), 350);
        for (int i = 0; i < limit - 1; i++) {
            int etatHier = calculerEtatAbstrait(history.get(i+1).getBoules());
            int etatAuj = calculerEtatAbstrait(history.get(i).getBoules());
            matrix[etatHier][etatAuj]++; totalTransitions[etatHier]++;
        }
        for (int i = 1; i <= 5; i++) if (totalTransitions[i] > 0) for (int j = 1; j <= 5; j++) matrix[i][j] /= totalTransitions[i];
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

    // OPTIMISATION : Fail-Fast (On sort dès qu'une condition n'est pas remplie)
    public boolean estGrilleCoherenteOptimisee(List<Integer> boules, List<Integer> dernierTirage, DynamicConstraints rules) {
        int somme = 0; int pairs = 0; int dizainesMask = 0;
        int prev = -10;
        // On fusionne les boucles pour aller plus vite
        for (int i = 0; i < 5; i++) {
            int n = boules.get(i);
            somme += n;
            if ((n & 1) == 0) pairs++;
            dizainesMask |= (1 << (n / 10));
            // Check suite (Fail fast)
            if (!rules.allowSuites && n == prev + 1) return false; // Interdit
            if (n == prev + 1 && i > 1 && boules.get(i-2) == prev - 1) return false; // Pas plus de 2 consécutifs
            prev = n;
        }
        if (pairs < rules.minPairs || pairs > rules.maxPairs) return false;
        if (somme < 100 || somme > 175) return false;
        if (Integer.bitCount(dizainesMask) < 3) return false;
        if ((boules.get(4) - boules.get(0)) < 15) return false;

        // Check dernier tirage (seulement si nécessaire)
        if (dernierTirage != null) {
            int communs = 0;
            for (Integer n : boules) {
                if (dernierTirage.contains(n)) {
                    communs++;
                    if (communs >= 2) return false; // Fail fast ici aussi
                }
            }
        }
        return true;
    }

    private long calculerBitMask(List<Integer> boules) {
        long mask = 0L; for (Integer b : boules) mask |= (1L << b); return mask;
    }

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
            List<Integer> b = history.get(i).getBoules();
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

    // Méthode Optimisée Directe (Int[][]) pour affinités
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

    // Méthode Optimisée Directe (Int[][]) pour chance
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
