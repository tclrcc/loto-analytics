package com.analyseloto.loto.service;

import com.analyseloto.loto.dto.*;
import com.analyseloto.loto.entity.*;
import com.analyseloto.loto.repository.LotoTirageRepository;
import com.analyseloto.loto.util.Constantes;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.multipart.MultipartFile;

import java.io.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

@Slf4j
@Service
@RequiredArgsConstructor
public class LotoService {

    private final LotoTirageRepository repository;
    private final WheelingService wheelingService;
    private final RestTemplate restTemplate;

    @Value("${loto.ai.url:http://localhost:8000/predict}")
    private String pythonApiUrl;

    // Cache local pour éviter de recalculer pendant la même journée
    private volatile StatsReponse cachedGlobalStats = null;
    private final AtomicReference<List<PronosticResultDto>> cachedDailyPronosRef = new AtomicReference<>();
    private volatile LocalDate dateCachedPronos = null;

    private static final String FIELD_DATE_TIRAGE = "dateTirage";
    private static final ZoneId ZONE_PARIS = ZoneId.of("Europe/Paris");

    // ==================================================================================
    // 1. MÉTHODES DE DÉMARRAGE (Désormais Stateless et Instantanées)
    // ==================================================================================

    public void initConfigFromDb() {
        // Obsolète : Plus besoin de charger les "experts" de l'algorithme génétique.
        log.info("🔌 Démarrage : Architecture V8 Stateless. Pas de cache BDD nécessaire.");
    }

    public void verificationAuDemarrage() {
        log.info("🚀 [WARMUP] Moteur V8 Value & Wheeling activé et prêt.");
    }

    public void forceDailyOptimization() {
        // Appelée par le Cron à 4h00
        log.info("🌙 [CRON] L'optimisation lourde n'est plus requise en Java. Le modèle V8 Python s'occupe de l'inférence en temps réel.");
    }

    // ==================================================================================
    // 2. NOUVEAU PIPELINE DE GÉNÉRATION (VALUE + WHEELING)
    // ==================================================================================

    public List<PronosticResultDto> genererMultiplesPronostics(LocalDate dateCible, int nombreGrilles) {
        long startTotal = System.currentTimeMillis();

        // 1. Vérification du Cache
        List<PronosticResultDto> cached = cachedDailyPronosRef.get();
        if (cached != null && dateCible.equals(dateCachedPronos) && cached.size() >= nombreGrilles) {
            log.info("⚡ [CACHE] Pronostics Value récupérés instantanément.");
            return cached.subList(0, nombreGrilles);
        }

        log.info("⚙️ [MOTEUR V8] Inférence AI et création de la Matrice de Steiner pour le {}...", dateCible);

        // 2. Appel API Python pour obtenir l'indice d'impopularité (Value) de chaque numéro
        double[] pythonScores = getDeepLearningWeights();
        boolean isApiDown = Arrays.stream(pythonScores).sum() == 0;

        if (isApiDown) {
            log.warn("⚠️ API Python injoignable, utilisation d'un fallback stratégique (Bonus aux numéros > 31).");
            for(int i=1; i<=49; i++) pythonScores[i] = (i > 31) ? 1.5 : 1.0;
        }

        // 3. Extraction de la Piscine (Top 10 ou Top 12 selon la requête IHM)
        int poolSize = (nombreGrilles >= 15) ? 12 : 10;
        List<Integer> pool = determinerPoolAdaptatif(pythonScores, poolSize);
        log.info("🎯 [POOL VALUE] {} numéros sélectionnés par l'IA : {}", poolSize, pool);

        // 4. Système Réducteur (Garantie Mathématique absolue)
        // IMPORTANT: On ne filtre JAMAIS les grilles générées par le Wheeling, sinon on casse la garantie !
        List<int[]> grillesBrutes = wheelingService.genererSystemeReducteur(pool, 3);
        log.info("⚙️ [WHEELING] {} combinaisons structurelles générées.", grillesBrutes.size());

        // 5. Numéros Chance (Basé sur les moins sortis récemment pour maximiser les gains)
        List<LotoTirage> history = repository.findAll(Sort.by(Sort.Direction.DESC, FIELD_DATE_TIRAGE));
        List<Integer> topChances = getChanceNumbersImpopulaires(history);

        // 6. Construction des DTOs pour l'affichage
        List<PronosticResultDto> resultats = new ArrayList<>();
        for (int i = 0; i < grillesBrutes.size(); i++) {
            int[] g = grillesBrutes.get(i);
            int chance = topChances.get(i % topChances.size());

            // Calcul du fitness total de la grille pour affichage
            double fitness = 0.0;
            for (int b : g) fitness += pythonScores[b];

            SimulationResultDto simu = simulerGrilleDetaillee(Arrays.stream(g).boxed().toList(), dateCible, history);
            double maxDuo = simu.getPairs().stream().mapToDouble(MatchGroup::getRatio).max().orElse(0.0);

            resultats.add(new PronosticResultDto(
                    Arrays.stream(g).boxed().sorted().toList(),
                    chance,
                    Math.round(fitness * 100.0) / 100.0,
                    maxDuo, 0.0,
                    !simu.getQuintuplets().isEmpty(),
                    "MATRICE STEINER (Garantie 3/3)"
            ));
        }

        // On trie purement pour l'esthétique de présentation (Les grilles à plus forte "Value" en premier)
        resultats.sort((p1, p2) -> Double.compare(p2.getScoreFitness(), p1.getScoreFitness()));

        cachedDailyPronosRef.set(resultats);
        this.dateCachedPronos = dateCible;

        log.info("🏁 [MOTEUR V8] Terminé en {} ms. {} grilles prêtes.", (System.currentTimeMillis() - startTotal), resultats.size());

        // On retourne la taille demandée (bien que le wheeling doive imposer sa taille de 8 ou 15)
        return resultats.subList(0, Math.min(resultats.size(), nombreGrilles));
    }

    private double[] getDeepLearningWeights() {
        double[] weights = new double[50];
        Arrays.fill(weights, 0.0);

        try {
            log.info("📡 [IA V8] Interrogation du Neural Engine Value sur : {}", pythonApiUrl);

            // Pour l'instant, on simule un jackpot à 2 millions.
            // Idéalement, tu récupéreras cette valeur dynamiquement via FdjService.
            Map<String, Object> requestBody = Map.of(
                    "history", Collections.emptyList(),
                    "current_jackpot", 2000000.0,
                    "ticket_cost", 2.20
            );

            @SuppressWarnings("unchecked")
            Map<String, Object> response = restTemplate.postForObject(pythonApiUrl, requestBody, Map.class);

            if (response != null && response.containsKey("number_scores")) {
                // --- SNIPER MODE LOGIC ---
                Boolean playAuthorized = (Boolean) response.get("play_authorized");
                Double evScore = ((Number) response.get("ev_score")).doubleValue();

                log.info("🎯 [SNIPER MODE] EV (Rentabilité) : {}", evScore);
                if (Boolean.FALSE.equals(playAuthorized)) {
                    log.warn("🛑 [SNIPER MODE] Espérance mathématique faible (EV < 1). Stratégiquement, il ne faut pas jouer ce tirage.");
                    // Note : Tu peux ajouter un return immédiat ici si tu veux bloquer
                    // purement et simplement la génération des grilles en Java.
                }

                // --- LECTURE DES POIDS ---
                @SuppressWarnings("unchecked")
                Map<String, Number> numberScores = (Map<String, Number>) response.get("number_scores");
                numberScores.forEach((k, v) -> {
                    try {
                        int boule = Integer.parseInt(k);
                        if (boule >= 1 && boule <= 49) weights[boule] = v.doubleValue();
                    } catch (NumberFormatException ignored) {}
                });
            }
        } catch (Exception e) {
            log.error("❌ [IA V8] Erreur API Python : {}", e.getMessage());
        }
        return weights;
    }

    private List<Integer> determinerPoolAdaptatif(double[] weights, int requestedPoolSize) {
        List<Integer> pool = new ArrayList<>();

        // 1. Séparation des numéros en 3 strates pour forcer un équilibre naturel
        // (On trie chaque groupe par son score d'impopularité IA)
        List<Integer> bas = IntStream.rangeClosed(1, 15).boxed()
                .sorted((a, b) -> Double.compare(weights[b], weights[a])).toList();

        List<Integer> moyen = IntStream.rangeClosed(16, 31).boxed()
                .sorted((a, b) -> Double.compare(weights[b], weights[a])).toList();

        List<Integer> haut = IntStream.rangeClosed(32, 49).boxed()
                .sorted((a, b) -> Double.compare(weights[b], weights[a])).toList();

        // 2. Échantillonnage Stratifié en fonction du Plan demandé
        if (requestedPoolSize >= 15) {
            // Plan Syndicat (Pool de 12) : 50% Hauts, 25% Moyens, 25% Bas
            pool.addAll(haut.subList(0, 6));
            pool.addAll(moyen.subList(0, 3));
            pool.addAll(bas.subList(0, 3));
        } else {
            // Plan Standard (Pool de 10) : 5 Hauts, 3 Moyens, 2 Bas
            pool.addAll(haut.subList(0, 5));
            pool.addAll(moyen.subList(0, 3));
            pool.addAll(bas.subList(0, 2));
        }

        // 3. On mélange le pool pour que la Matrice de Steiner ne crée pas
        // des grilles avec uniquement les "bas" d'un côté et les "hauts" de l'autre.
        Collections.shuffle(pool, new Random());

        // Calcul de confiance pour les logs (basé sur le Top 5 de chaque strate)
        double avgTopHaut = haut.subList(0, 5).stream().mapToDouble(i -> weights[i]).average().orElse(0);
        log.info("📊 [IA VALUE] Score moyen de rentabilité sur la strate Haute : {}", String.format("%.2f", avgTopHaut));

        return pool;
    }

    private List<Integer> getChanceNumbersImpopulaires(List<LotoTirage> history) {
        // Stratégie Value : On prend les numéros chance les MOINS sortis sur les 100 derniers tirages
        int[] freq = new int[11];
        int limit = Math.min(history.size(), 100);
        for(int i=0; i<limit; i++) {
            freq[history.get(i).getNumeroChance()]++;
        }
        return IntStream.rangeClosed(1, 10)
                .boxed()
                .sorted(Comparator.comparingInt(c -> freq[c])) // Tri Croissant (Moins fréquent d'abord)
                .limit(3)
                .toList();
    }

    // ==================================================================================
    // 3. STATISTIQUES ET SIMULATIONS
    // ==================================================================================

    public StatsReponse getStats(String jourFiltre) {
        StatsReponse localCache = this.cachedGlobalStats;
        if (jourFiltre == null && localCache != null) return localCache;

        List<LotoTirage> all = repository.findAll(Sort.by(Sort.Direction.DESC, FIELD_DATE_TIRAGE));
        if (jourFiltre != null && !jourFiltre.isEmpty()) {
            DayOfWeek d = DayOfWeek.valueOf(jourFiltre.toUpperCase());
            all = all.stream().filter(t -> t.getDateTirage().getDayOfWeek() == d).toList();
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
        if (jourFiltre == null) this.cachedGlobalStats = reponse;

        return reponse;
    }

    @Cacheable(value = "statsGlobales", key = "'MATRICE_GRAPHE_PUBLIC'")
    public Map<Integer, Map<Integer, Integer>> getMatriceAffinitesPublic() {
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

    public double calculerGainSimule(UserBet bet, LotoTirage tirage) {
        if (tirage == null || bet == null) return 0.0;

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

    public SimulationResultDto simulerGrilleDetaillee(List<Integer> boulesJouees, LocalDate dateSimul, List<LotoTirage> historique) {
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
        ZonedDateTime maintenant = ZonedDateTime.now(ZONE_PARIS);
        LocalDate dateCandidate = maintenant.toLocalDate();
        LocalTime heureActuelle = maintenant.toLocalTime();
        Set<DayOfWeek> joursTirage = Set.of(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY, DayOfWeek.SATURDAY);

        if (joursTirage.contains(dateCandidate.getDayOfWeek()) && heureActuelle.isAfter(LocalTime.of(20, 15))) {
            dateCandidate = dateCandidate.plusDays(1);
        }
        while (!joursTirage.contains(dateCandidate.getDayOfWeek())) {
            dateCandidate = dateCandidate.plusDays(1);
        }
        return dateCandidate;
    }

    // ==================================================================================
    // 4. IMPORTS & GESTION DE DONNÉES
    // ==================================================================================

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
}
