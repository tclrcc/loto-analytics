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

    // --- VARIABLES DU CACHE MANUEL (Architecture Stateful) ---
    private volatile List<AlgoConfig> cachedEliteConfigs = new ArrayList<>();
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

    @EventListener(ApplicationReadyEvent.class)
    public void initConfigFromDb() {
        log.info("🔌 Démarrage : Chargement du Conseil des Sages depuis la BDD...");

        // 1. On cherche d'abord le dernier leader pour identifier la date du dernier batch
        strategyConfigRepostiroy.findTopByLeaderTrueOrderByDateCalculDesc().ifPresentOrElse(
                lastLeader -> {
                    // 2. On récupère TOUS les experts qui ont été créés en même temps que ce leader
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
    }

    /**
     * Méthode vérification au démarrage de l'application
     */
    public void verificationAuDemarrage() {
        LocalDate todayParis = LocalDate.now(ZONE_PARIS);

        // On vérifie si la liste des experts est présente et à jour
        if (this.cachedEliteConfigs != null && !this.cachedEliteConfigs.isEmpty() && todayParis.equals(this.lastBacktestDate)) {
            log.info("✋ [WARMUP] Stratégies Ensemble ({} experts) du {} déjà en mémoire. OK.", this.cachedEliteConfigs.size(), todayParis);
            // On lance un petit calcul pour être sûr que tout est chaud
            genererMultiplesPronostics(recupererDateProchainTirage(), 5);
            return;
        }

        log.info("⚠️ [WARMUP] Stratégies obsolètes ou absentes. Lancement optimisation complète !");
        forceDailyOptimization();
    }

    public void forceDailyOptimization() {
        log.info("🌙 [CRON] Optimisation de l'Ensemble IA...");

        List<LotoTirageRepository.TirageMinimal> rawData = repository.findAllOptimized();
        List<LotoTirage> historyLight = rawData.stream().map(this::mapToLightEntity).toList();

        if (!historyLight.isEmpty()) {
            List<AlgoConfig> newConfigs = backtestService.trouverMeilleuresConfigs(historyLight);

            // On définit un timestamp unique pour tout le batch d'experts
            LocalDateTime batchTimestamp = LocalDateTime.now(ZONE_PARIS);

            this.cachedEliteConfigs = newConfigs;
            this.lastBacktestDate = batchTimestamp.toLocalDate();

            // SAUVEGARDE DE TOUT LE CONSEIL (20 experts)
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

                // Le premier de la liste (triée par fitness) est le leader
                entity.setLeader(i == 0);

                entitiesToSave.add(entity);
            }

            strategyConfigRepostiroy.saveAll(entitiesToSave);
            log.info("💾 [DB] Conseil des Sages ({} experts) sauvegardé avec succès.", entitiesToSave.size());

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

        // CALCULS PARALLÈLES DES MATRICES INVARIANTES (Lourds mais faits 1 seule fois)
        // Ces données ne dépendent pas de la config, donc on les partage entre tous les experts.
        CompletableFuture<Set<Integer>> hotFinalesFuture = CompletableFuture.supplyAsync(() -> detecterFinalesChaudes(history));
        CompletableFuture<List<List<Integer>>> triosFuture = CompletableFuture.supplyAsync(() -> getTopTriosRecents(history));
        CompletableFuture<int[][]> affFuture = CompletableFuture.supplyAsync(() -> construireMatriceAffinitesDirecte(history, dateCible.getDayOfWeek()));
        CompletableFuture<int[][]> chanceFuture = CompletableFuture.supplyAsync(() -> construireMatriceChanceDirecte(history, dateCible.getDayOfWeek()));
        CompletableFuture<double[][]> markovFuture = CompletableFuture.supplyAsync(() -> precalculerMatriceMarkov(history));
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
        Map<Set<Integer>, Integer> votesGrilles = new java.util.concurrent.ConcurrentHashMap<>();
        Map<Set<Integer>, Double> scoresCumules = new java.util.concurrent.ConcurrentHashMap<>();

        log.info("🗳️ [VOTE] Lancement de la génération parallèle avec {} experts...", eliteConfigs.size());

        // 4. GÉNÉRATION PARALLÈLE (Chaque expert propose ses grilles)
        eliteConfigs.parallelStream().forEach(config -> {
            // a. Calcul des scores spécifiques à CETTE config (Chaque expert a sa vision des poids)
            // Note: boostNumbers est vide ici car c'est de l'IA pure, pas de l'Astro
            double[] scoresBoules = calculerScoresOptimise(history, 49, dateCible.getDayOfWeek(), false, Collections.emptyList(), hotFinales, config, dernierTirage);
            double[] scoresChance = calculerScoresOptimise(history, 10, dateCible.getDayOfWeek(), true, Collections.emptyList(), Collections.emptySet(), config, null);

            // b. Buckets & Primitives
            Map<String, List<Integer>> buckets = creerBucketsOptimises(scoresBoules);
            int[] hots = buckets.getOrDefault(Constantes.BUCKET_HOT, Collections.emptyList()).stream().mapToInt(i->i).toArray();
            int[] neutrals = buckets.getOrDefault(Constantes.BUCKET_NEUTRAL, Collections.emptyList()).stream().mapToInt(i->i).toArray();
            int[] colds = buckets.getOrDefault(Constantes.BUCKET_COLD, Collections.emptyList()).stream().mapToInt(i->i).toArray();
            boolean[] isHot = new boolean[51]; boolean[] isCold = new boolean[51];
            for(int n : buckets.get(Constantes.BUCKET_HOT)) isHot[n] = true;
            for(int n : buckets.get(Constantes.BUCKET_COLD)) isCold[n] = true;

            // c. L'expert génère ses grilles (On génère ~30 grilles par expert)
            List<GrilleCandidate> proposals = executerAlgorithmeGenetique(
                    hots, neutrals, colds, isHot, isCold,
                    matriceAffinitesArr, dernierTirage, topTriosDuJour, scoresBoules, scoresChance,
                    matriceChanceArr, contraintesDuJour, config, historiqueBitMasks, matriceMarkov, etatDernierTirage
            );

            // Correction pour le stockage des votes dans LotoService
            int limitVote = Math.min(proposals.size(), 30);
            for(int i = 0; i < limitVote; i++) {
                GrilleCandidate cand = proposals.get(i);

                // On transforme le int[] trié en List<Integer> pour que le HashSet l'accepte
                List<Integer> boulesList = Arrays.stream(cand.getBoules())
                        .boxed()
                        .toList();

                Set<Integer> key = new HashSet<>(boulesList);

                votesGrilles.merge(key, 1, Integer::sum);
                scoresCumules.merge(key, cand.getFitness(), Double::sum);
            }
        });

        // 5. DÉPOUILLEMENT DU SCRUTIN (Consensus)
        List<PronosticResultDto> resultatsConsensus = new ArrayList<>();

        // SEUIL DE CONSENSUS : Une grille doit être trouvée par au moins 15% des experts
        // Avec 20 experts, il faut au moins 3 votes.
        int seuilVote = Math.max(2, eliteConfigs.size() / 6);

        for (Map.Entry<Set<Integer>, Integer> entry : votesGrilles.entrySet()) {
            int nbVotes = entry.getValue();
            if (nbVotes >= seuilVote) {
                Set<Integer> boulesSet = entry.getKey();
                List<Integer> boulesList = new ArrayList<>(boulesSet);
                Collections.sort(boulesList);

                // Score Consensus = Moyenne des fitness * Bonus de confiance (Unanimité)
                double scoreMoyen = scoresCumules.get(boulesSet) / nbVotes;
                double confiance = 1.0 + (nbVotes * 0.15); // +15% par vote supplémentaire
                double finalScore = scoreMoyen * confiance;

                // On recalcule le meilleur numéro chance pour cette combinaison spécifique
                // (On utilise la moyenne des matrices chances)
                int chanceConsensus = selectionnerChancePourConsensus(boulesList, matriceChanceArr);

                // Simulation rapide pour les infos d'affichage
                SimulationResultDto simu = simulerGrilleDetaillee(boulesList, dateCible, history);
                double maxDuo = simu.getPairs().stream().mapToDouble(MatchGroup::getRatio).max().orElse(0.0);

                resultatsConsensus.add(new PronosticResultDto(
                        boulesList, chanceConsensus,
                        Math.round(finalScore * 100.0) / 100.0,
                        maxDuo, 0.0, // Ecart max pas calculé ici pour perf
                        !simu.getQuintuplets().isEmpty(),
                        "CONSENSUS (Votes: " + nbVotes + "/" + eliteConfigs.size() + ")"
                ));
            }
        }

        // 6. TRI ET SÉLECTION FINALE
        // On trie par score consensus décroissant
        resultatsConsensus.sort((p1, p2) -> Double.compare(p2.getScoreFitness(), p1.getScoreFitness()));

        // 6. GESTION DU FALLBACK (Plan de Secours)
        if (resultatsConsensus.isEmpty()) {
            log.warn("⚠️ [FALLBACK] Aucun consensus strict (Seuil: {}). Bascule sur les meilleurs scores individuels.", seuilVote);

            // On abaisse le seuil à 1 (on accepte tout le monde)
            // Mais on va trier drastiquement par score pur
            for (Map.Entry<Set<Integer>, Integer> entry : votesGrilles.entrySet()) {
                Set<Integer> boulesSet = entry.getKey();

                // On récupère le score brut cumulé.
                // Note : Si 1 seul vote, scoreCumulé = scoreFitness de l'unique expert.
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

        cachedDailyPronosRef.set(resultatsConsensus);
        this.dateCachedPronos = dateCible;

        long duration = System.currentTimeMillis() - startTotal;
        log.info("🏁 [CONSENSUS IA] Terminé en {} ms. {} grilles 'Solidaires' retenues.", duration, resultatsConsensus.size());

        return resultatsConsensus.subList(0, Math.min(resultatsConsensus.size(), nombreGrilles));
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

        // Logique Trio (inchangée mais adaptée au buffer)
        if (trios != null && !trios.isEmpty() && rng.nextBoolean()) {
            List<Integer> trioChoisi = trios.get(rng.nextInt(trios.size()));
            for (Integer n : trioChoisi) buffer[size++] = n;
        } else {
            buffer[size++] = hots.length > 0 ? hots[rng.nextInt(hots.length)] : 1 + rng.nextInt(49);
        }

        while (size < 5) {
            int nbHot = 0, nbCold = 0;
            for (int i = 0; i < size; i++) {
                if (isHot[buffer[i]]) nbHot++;
                else if (isCold[buffer[i]]) nbCold++;
            }

            int[] targetPool = (nbHot < 2) ? hots : (nbCold < 1 ? colds : neutrals);
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

            double[][] matMarkov = precalculerMatriceMarkov(historyConnu);
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

    private double calculerScoreFitnessOptimise(int[] boules, int chance, double[] scoresBoules, double[] scoresChance, int[][] matriceAffinites, AlgoConfig config, double[][] matriceMarkov, int etatDernierTirage) {
        double score = 0.0;
        for (int b : boules) score += scoresBoules[b];
        score += scoresChance[chance];

        // Score Affinité (Boucle optimisée sur tableau)
        double scoreAffinite = 0;
        for (int i = 0; i < 5; i++) {
            for (int j = i + 1; j < 5; j++) {
                scoreAffinite += matriceAffinites[boules[i]][boules[j]];
            }
        }
        score += (scoreAffinite * config.getPoidsAffinite());

        // Somme et Parité
        int pairs = 0, somme = 0;
        for (int b : boules) {
            if ((b & 1) == 0) pairs++;
            somme += b;
        }
        if (pairs == 2 || pairs == 3) score += 15.0;
        if (somme >= 120 && somme <= 170) score += 10.0;

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
