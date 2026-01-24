    package com.analyseloto.loto.service;
    
    import com.analyseloto.loto.dto.*;
    import com.analyseloto.loto.entity.*;
    import com.analyseloto.loto.repository.LotoTirageRepository;
    import com.analyseloto.loto.repository.StrategyConfigRepostiroy;
    import com.analyseloto.loto.repository.UserBetRepository;
    import com.analyseloto.loto.util.Constantes;
    import lombok.AllArgsConstructor;
    import lombok.Data;
    import lombok.NoArgsConstructor;
    import lombok.RequiredArgsConstructor;
    import lombok.extern.slf4j.Slf4j;
    import org.springframework.data.domain.Sort;
    import org.springframework.stereotype.Service;
    import org.springframework.web.multipart.MultipartFile;
    
    import java.io.BufferedReader;
    import java.io.IOException;
    import java.io.InputStreamReader;
    import java.time.DayOfWeek;
    import java.time.LocalDate;
    import java.time.LocalDateTime;
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
        private final UserBetRepository betRepository;
        private final StrategyConfigRepostiroy strategyConfigRepostiroy;
        // Services
        private final AstroService astroService;
        private final BacktestService backtestService;
        // Variable de classe pour stocker la meilleure config en mémoire (Cache simple)
        private AlgoConfig cachedBestConfig = null;
        private LocalDate lastBacktestDate = null;
        // Cache pour les Stats Globales
        private StatsReponse cachedGlobalStats = null;
        // Cache pour les Pronostics du Jour
        private List<PronosticResultDto> cachedDailyPronos = null;
        private LocalDate dateCachedPronos = null;
    
        /**
         * Configuration dynamique de l'algorithme de génération
         */
        @Data
        @AllArgsConstructor
        @NoArgsConstructor
        public static class AlgoConfig {
            private String nomStrategie;
            private double poidsFreqJour;
            private double poidsForme;
            private double poidsEcart;
            private double poidsTension;
            private double poidsMarkov;
            private double poidsAffinite;
            private boolean utiliserGenetique; // NOUVEAU : Activer l'algo génétique ?

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
    
            // 1. Standard (Mix équilibré)
            public static AlgoConfig defaut() {
                return new AlgoConfig("1_STANDARD", 3.0, 15.0, 0.4, 12.0, 5.0, 1.0, false);
            }
        }
    
        /**
         * Contraintes dynamiques pour la génération de grilles
         */
        @Data
        @AllArgsConstructor
        public static class DynamicConstraints {
            private int minPairs;      // Minimum de nombres pairs requis
            private int maxPairs;      // Maximum de nombres pairs requis
            private boolean allowSuites; // Autorise-t-on les suites (ex: 12, 13) ?
            private Set<Integer> forbiddenNumbers; // Numéros interdits (ex: ceux sortis hier)
        }
    
        /**
         * Grille candidate pour l'algorithme génétique
         */
        @AllArgsConstructor
        private static class GrilleCandidate {
            List<Integer> boules;
            int chance;
            double fitness;
        }
    
        @Data
        @AllArgsConstructor
        public static class ScenarioSimulation {
            private LotoTirage tirageReel; // Pour vérifier le gain
            private List<Integer> dernierTirageConnu; // Pour les filtres
    
            // Données pré-calculées (Lourdes)
            private Map<Integer, Map<Integer, Integer>> matriceAffinites;
            private Map<Integer, Map<Integer, Integer>> matriceChance;
            private Map<Integer, RawStatData> rawStatsBoules; // Stats brutes par boule
            private Map<Integer, RawStatData> rawStatsChance;
    
            private DynamicConstraints contraintes;
            private List<List<Integer>> topTriosPrecalcules;
        }
    
        @Data
        @AllArgsConstructor
        public static class RawStatData {
            private long freqJour;
            private long ecart;
            private boolean isForme;
            private boolean isTresForme; // Hot streak
            private boolean isBoostAstro; // Toujours false ici sauf si on personnalise
            private boolean isHotFinale;
            private boolean isTension;
            // On ne stocke pas le score final car il dépend des poids !
        }
    
        /**
         * Génération de N pronostics optimisés (sans astro)
         * @param dateCible date tirage
         * @param nombreGrilles nombre grilles à générer
         * @return liste des pronostics
         */
        public List<PronosticResultDto> genererMultiplesPronostics(LocalDate dateCible, int nombreGrilles) {
            // Si on demande les pronos pour la date déjà en cache, on renvoie le cache !
            if (cachedDailyPronos != null && dateCible.equals(dateCachedPronos) && cachedDailyPronos.size() >= nombreGrilles) {
                log.debug("🚀 [CACHE] Retour des pronostics en mémoire (pas de recalcul)");
                // On renvoie une copie ou une sous-liste si on en veut moins
                return cachedDailyPronos.subList(0, nombreGrilles);
            }
    
            // Sinon, on calcule (c'est le cas lent, une seule fois par jour)
            log.info("⚙️ [CALCUL] Génération fraîche des pronostics pour le {}", dateCible);
            List<PronosticResultDto> newsPronos = genererPronosticAvecConfig(dateCible, nombreGrilles, null);
    
            // On met en cache
            this.cachedDailyPronos = newsPronos;
            this.dateCachedPronos = dateCible;
    
            return newsPronos;
        }
    
        /**
         * Génération de N pronostics hybrides (avec astro)
         * @param dateCible date tirage
         * @param nombreGrilles nombre grilles à générer
         * @param profil profil astral
         * @return liste des pronostics
         */
        public List<PronosticResultDto> genererPronosticsHybrides(LocalDate dateCible, int nombreGrilles, AstroProfileDto profil) {
            return genererPronosticAvecConfig(dateCible, nombreGrilles, profil);
        }
    
        /**
         * Algorithme sélections grilles pronostics optimisées
         * @param dateCible date tirage
         * @param nombreGrilles nombre grilles à générer
         * @param profilAstro profil astral
         * @return liste des pronostics
         */
        private List<PronosticResultDto> genererPronosticAvecConfig(LocalDate dateCible, int nombreGrilles, AstroProfileDto profilAstro) {
            // 1. Chargement Historique
            List<LotoTirage> history = repository.findAll(Sort.by(Sort.Direction.DESC, "dateTirage"));
            if (history.isEmpty()) return new ArrayList<>();
            List<Integer> dernierTirage = history.get(0).getBoules();

            // 2. Choix de la Config (Sécurisé)
            AlgoConfig configOptimisee;
            if (cachedBestConfig != null) {
                configOptimisee = cachedBestConfig;
                if (lastBacktestDate != null && lastBacktestDate.isBefore(LocalDate.now().minusDays(7))) {
                    log.warn("⚠️ [ALGO] Config périmée (>7 jours). Vérifiez le CRON.");
                }
            } else {
                log.info("⏳ [ALGO] Pas de config en cache. Utilisation DÉFAUT.");
                configOptimisee = AlgoConfig.defaut();
            }

            log.info("🎯 [ALGO] Stratégie : {} (Bilan Backtest: {} €)",
                    configOptimisee.getNomStrategie(), String.format("%.2f", configOptimisee.getBilanEstime()));

            // 3. Préparations des Données (Optimisé hors boucle)
            Set<Integer> hotFinales = detecterFinalesChaudes(history);
            List<Integer> boostNumbers = (profilAstro != null) ? astroService.getLuckyNumbersOnly(profilAstro) : Collections.emptyList();

            List<List<Integer>> topTriosDuJour = getTopTriosRecents(history);
            Map<Integer, Map<Integer, Integer>> matriceAffinites = construireMatriceAffinitesPonderee(history, dateCible.getDayOfWeek());
            Map<Integer, Map<Integer, Integer>> matriceChance = construireMatriceAffinitesChancePonderee(history, dateCible.getDayOfWeek());

            // Calcul des scores unitaires avec les poids de l'IA
            Map<Integer, Double> scoresBoules = calculerScores(history, 49, dateCible.getDayOfWeek(), false, boostNumbers, hotFinales, configOptimisee, dernierTirage);
            Map<Integer, Double> scoresChance = calculerScores(history, 10, dateCible.getDayOfWeek(), true, boostNumbers, Collections.emptySet(), configOptimisee, null);

            DynamicConstraints contraintesDuJour = analyserContraintesDynamiques(history, dernierTirage);
            Map<String, List<Integer>> buckets = creerBuckets(scoresBoules);

            // --- OPTIMISATION PERFORMANCE : Cache des grilles passées pour vérification rapide ---
            Set<Long> historiqueBitMasks = new HashSet<>();
            int limitHistoryCheck = Math.min(history.size(), 300);
            for(int i=0; i<limitHistoryCheck; i++) {
                historiqueBitMasks.add(calculerBitMask(history.get(i).getBoules()));
            }

            Random rng = new Random();

            // ---------------------------------------------------------
            // 4. GÉNÉRATION : MOTEUR GÉNÉTIQUE IA
            // ---------------------------------------------------------
            int taillePopulation = 1000;
            int nbGenerations = 15;

            // C'est LUI qui fait tout le travail maintenant (génération, mutation, sélection) !
            List<GrilleCandidate> population = executerAlgorithmeGenetique(
                    taillePopulation, nbGenerations, buckets, matriceAffinites, dernierTirage,
                    topTriosDuJour, scoresBoules, scoresChance, matriceChance,
                    history, contraintesDuJour, configOptimisee,
                    historiqueBitMasks, rng
            );

            // ---------------------------------------------------------
            // 5. CONSTRUCTION DU RÉSULTAT FINAL (AVEC FILTRE DE DIVERSITÉ)
            // ---------------------------------------------------------

            List<PronosticResultDto> resultats = new ArrayList<>();
            List<List<Integer>> grillesRetenues = new ArrayList<>();

            // Objectif : Maximiser la couverture des numéros de l'élite.
            // On représente les numéros déjà "couverts" par nos grilles retenues via un BitMask.
            long couvertureGlobale = 0L;

            for (GrilleCandidate cand : population) {
                if (resultats.size() >= nombreGrilles) break;
                Collections.sort(cand.boules);

                // Création du masque binaire de la grille candidate
                long masqueCandidat = calculerBitMask(cand.boules);

                // --- ALGORITHME GLOUTON DE COUVERTURE ---
                // On regarde combien de NOUVEAUX numéros cette grille apporte par rapport à ce qu'on a déjà.
                // Opération bitwise : (Masque Candidat) ET NON (Couverture Globale)
                long nouveauxNumerosMask = masqueCandidat & ~couvertureGlobale;

                // Long.bitCount() compte le nombre de '1' (numéros uniques) apportés.
                int apportDiversite = Long.bitCount(nouveauxNumerosMask);

                // Règle Gloutonne : On n'accepte la grille que si elle apporte au moins 2 nouveaux numéros non couverts,
                // OU si c'est la toute première grille (l'absolue meilleure).
                if (resultats.isEmpty() || apportDiversite >= 2) {

                    // On met à jour notre couverture globale (OU binaire)
                    couvertureGlobale |= masqueCandidat;
                    grillesRetenues.add(cand.boules);

                    // Stats et DTO
                    SimulationResultDto simu = simulerGrilleDetaillee(cand.boules, dateCible, history);
                    double maxDuo = simu.getPairs().stream().mapToDouble(MatchGroup::getRatio).max().orElse(0.0);

                    // Badge Marketing
                    String typeAlgo = "IA_GÉNÉTIQUE + MARKOV ⭐";
                    if(cand.fitness < 50) typeAlgo = "IA_FLEXIBLE";

                    resultats.add(new PronosticResultDto(
                            cand.boules, cand.chance,
                            Math.round(cand.fitness * 100.0) / 100.0,
                            maxDuo, 0.0, !simu.getQuintuplets().isEmpty(),
                            typeAlgo
                    ));
                }
            }

            // Fallback (Au cas où le filtre est trop strict)
            while (resultats.size() < nombreGrilles) {
                List<Integer> b = genererGrilleAleatoireSecours(rng);
                Collections.sort(b);

                boolean existeDeja = grillesRetenues.stream().anyMatch(g -> g.equals(b));
                if(!existeDeja){
                    resultats.add(new PronosticResultDto(b, 1, 0.0, 0.0, 0.0, false, "HASARD_SECOURS"));
                    grillesRetenues.add(b);
                }
            }

            return resultats;
        }
    
        /**
         * Analyse des contraintes dynamiques basées sur l'historique
         * @param history historique tirages
         * @param dernierTirage dernier tirage
         * @return contraintes dynamiques
         */
        private DynamicConstraints analyserContraintesDynamiques(List<LotoTirage> history, List<Integer> dernierTirage) {
            // Sécurité : Si l'historique est vide, on renvoie des contraintes par défaut
            if (history.isEmpty()) return new DynamicConstraints(2, 3, true, new HashSet<>());
    
            // 1. Analyse Parité (Sur les 10 derniers tirages)
            long totalPairsRecents = history.stream().limit(10)
                    .flatMap(t -> t.getBoules().stream())
                    .filter(n -> n % 2 == 0)
                    .count();
    
            double moyenneRecente = totalPairsRecents / 10.0;
    
            int minP, maxP;
            if (moyenneRecente > 2.8) {
                minP = 1; maxP = 2; // Trop de pairs récemment → on vise Impair
            } else if (moyenneRecente < 2.2) {
                minP = 3; maxP = 4; // Trop d'impairs récemment → on vise Pair
            } else {
                minP = 2; maxP = 3; // Zone neutre
            }
    
            // 2. Analyse des Suites (Sur les 5 derniers tirages)
            boolean suiteRecente = false;
            for (int i = 0; i < Math.min(5, history.size()); i++) {
                // Optimisation : On évite de trier la liste originale du tirage, on copie
                List<Integer> b = new ArrayList<>(history.get(i).getBoules());
                Collections.sort(b);
                for (int k = 0; k < b.size() - 1; k++) {
                    if (b.get(k+1) == b.get(k) + 1) {
                        suiteRecente = true;
                        break;
                    }
                }
                if (suiteRecente) break;
            }
            boolean allowSuites = !suiteRecente;
    
            // 3. RÈGLE "ANTI-SURCHAUFFE" (Utilisation correcte de dernierTirage)
            Set<Integer> forbidden = new HashSet<>();
    
            if (history.size() >= 3 && dernierTirage != null) {
                List<Integer> t2 = history.get(1).getBoules(); // Avant-dernier
                List<Integer> t3 = history.get(2).getBoules(); // Ante-pénultième
    
                for (Integer n : dernierTirage) {
                    // Si le numéro est présent dans les 3 derniers tirages consécutifs
                    if (t2.contains(n) && t3.contains(n)) {
                        forbidden.add(n); // Il est "cramé", on l'interdit pour le prochain
                    }
                }
            }
    
            return new DynamicConstraints(minP, maxP, allowSuites, forbidden);
        }
    
        /**
         * Calcul du score de fitness d'une grille
         * @param boules numéros normaux
         * @param chance numéro chance
         * @param scoresBoules scores numéros
         * @param scoresChance scores chance
         * @param affinites matrice affinités
         * @param history historique tirages
         * @param dernierTirage dernier tirage
         * @return score fitness
         */
        private double calculerScoreFitness(List<Integer> boules, int chance,
                Map<Integer, Double> scoresBoules,
                Map<Integer, Double> scoresChance,
                Map<Integer, Map<Integer, Integer>> affinites,
                List<LotoTirage> history,
                List<Integer> dernierTirage,
                AlgoConfig config) {

            double score = 0.0;

            // 1. Somme des scores individuels
            for (Integer b : boules) score += scoresBoules.getOrDefault(b, 0.0);
            score += scoresChance.getOrDefault(chance, 0.0);

            // 2. Cohésion de groupe (Affinités)
            double scoreAffinite = 0;
            for (int i = 0; i < boules.size(); i++) {
                for (int j = i + 1; j < boules.size(); j++) {
                    scoreAffinite += affinites.getOrDefault(boules.get(i), Map.of()).getOrDefault(boules.get(j), 0);
                }
            }
            score += (scoreAffinite * config.getPoidsAffinite());

            // 3. Bonus/Malus Structurels (Non optimisés par l'IA mais bons sens mathématique)
            // Pairs / Impairs (Equilibre)
            long pairs = boules.stream().filter(n -> n % 2 == 0).count();
            if (pairs == 2 || pairs == 3) score += 15.0; // Bonus équilibre

            // Somme (Courbe de Gauss)
            int somme = boules.stream().mapToInt(Integer::intValue).sum();
            if (somme >= 120 && somme <= 170) score += 10.0;

            // Suites (Pénalité si trop)
            Collections.sort(boules);
            int suites = 0;
            for(int k=0; k<boules.size()-1; k++) {
                if(boules.get(k+1) == boules.get(k) + 1) suites++;
            }
            if(suites > 1) score -= 30.0;

            // --- 4. ANALYSE HISTORIQUE RAPIDE ---
            // On a déjà vérifié le "Doublon Exact" via le Set dans la méthode appelante.
            // Ici, on vérifie juste les "presque doublons" (4/5 numéros) sur le court terme.
            if (dernierTirage != null) {
                long communsDernier = boules.stream().filter(dernierTirage::contains).count();
                if (communsDernier > 0) {
                    score -= 50.0; // Malus dissuasif pour favoriser la nouveauté totale
                }
            }

            // On limite à 20 tirages pour la performance (suffisant pour la répétition)
            int depthFastCheck = Math.min(history.size(), 20);

            for (int i = 0; i < depthFastCheck; i++) {
                List<Integer> bHist = history.get(i).getBoules();
                long communs = boules.stream().filter(bHist::contains).count();

                // Si 4 numéros communs avec un tirage très récent → Pénalité
                if (communs >= 4) {
                    score -= 100.0;
                    break; // Inutile de continuer
                }
                // Si 3 numéros communs avec le tirage d'hier ou avant-hier → Pénalité
                if (communs >= 3 && i < 3) {
                    score -= 20.0;
                }
            }

            // --- 5. FILTRE MARKOV ---
            if (config.getPoidsMarkov() > 0 && dernierTirage != null) {
                double probaMarkov = calculerScoreMarkov(boules, dernierTirage, history);
                // On ajoute la probabilité multipliée par le poids (ex: 0.3 * 10 = +3.0 pts)
                score += (probaMarkov * config.getPoidsMarkov());
            }

            return score;
        }
    
        // Méthode de secours ultime : 5 chiffres au hasard
        private List<Integer> genererGrilleAleatoireSecours(Random rng) {
            Set<Integer> b = new HashSet<>();
            while (b.size() < 5) {
                b.add(rng.nextInt(49) + 1);
            }
            return new ArrayList<>(b);
        }
    
        // ==================================================================================
        // 5. FONCTIONS DE SCORE & UTILITAIRES (Mises à jour)
        // ==================================================================================
    
        /**
         * Calcul des scores pour chaque numéro
         * @param history historique des tirages
         * @param maxNum numéro maximum
         * @param jourCible jour cible
         * @param isChance est un numéro chance ?
         * @param boostNumbers numéros chauds
         * @param hotFinales finales chaudes
         * @param config configuration algo
         * @param dernierTirage dernier tirage
         * @return map numéro -> score
         */
        private Map<Integer, Double> calculerScores(List<LotoTirage> history, int maxNum, DayOfWeek jourCible,
                boolean isChance, List<Integer> boostNumbers,
                Set<Integer> hotFinales, AlgoConfig config,
                List<Integer> dernierTirage) {
            // Initialisation
            Map<Integer, Double> scores = new HashMap<>();
            long totalTirages = history.size();
    
            // Filtrage par jour
            List<LotoTirage> histJour = history.stream()
                    .filter(t -> t.getDateTirage().getDayOfWeek() == jourCible)
                    .toList();
    
            // Tri décroissant (Du plus récent au plus vieux)
            List<LotoTirage> histSorted = history.stream()
                    .sorted(Comparator.comparing(LotoTirage::getDateTirage).reversed())
                    .toList();
    
            for (int i = 1; i <= maxNum; i++) {
                final int num = i;
                double score = 10.0; // Score de départ
    
                // 1. Fréquence Jour (Habitudes du Lundi/Mercredi/Samedi)
                long freqJour = histJour.stream()
                        .filter(t -> isChance ? t.getNumeroChance() == num : t.getBoules().contains(num))
                        .count();
                score += (freqJour * config.getPoidsFreqJour());
    
                // 2. Calcul de l'Ecart (Dernière sortie)
                int idxLast = -1;
                for(int k=0; k < histSorted.size(); k++) {
                    if (isChance ? histSorted.get(k).getNumeroChance() == num : histSorted.get(k).getBoules().contains(num)) {
                        idxLast = k; break;
                    }
                }
                long ecartActuel = (idxLast == -1) ? totalTirages : idxLast;
    
                // --- STRATÉGIE OPTIMISÉE (Trend Following) ---
                if (ecartActuel > 40) {
                    // PENALITÉ : Si le numéro dort depuis trop longtemps (>40 tirages), il est statistiquement "froid".
                    score -= 5.0;
                } else if (ecartActuel > 10) {
                    // On garde un boost modéré pour l'écart moyen
                    score += (ecartActuel * config.getPoidsEcart());
                }
    
                // 3. Forme (Activité récente)
                // A. Forme Standard (15 derniers tirages)
                long sortiesRecentes = histSorted.stream().limit(15)
                        .filter(t -> isChance ? t.getNumeroChance() == num : t.getBoules().contains(num))
                        .count();
    
                if (sortiesRecentes >= 2) {
                    score += config.getPoidsForme();
                }
    
                // B. --- NOUVEAU : Forme "Brûlante" (10 derniers tirages) ---
                // On booste fortement les numéros qui sont dans une série actuelle (Hot Streak)
                long sortiesTresRecentes = histSorted.stream().limit(10)
                        .filter(t -> isChance ? t.getNumeroChance() == num : t.getBoules().contains(num))
                        .count();
    
                if (sortiesTresRecentes >= 2) {
                    score += 25.0; // BOOST MAJEUR pour capter la tendance immédiate
                }
    
                // 4. Boosts Externes (Astro & Finales)
                if (boostNumbers.contains(num)) score += 30.0;
                if (!isChance && hotFinales != null && hotFinales.contains(num % 10)) score += 8.0;
    
                // 5. Tension (Si le numéro est dû mathématiquement)
                if (!isChance && tiragesSuffisants(history, num)) {
                    score += config.getPoidsTension();
                }
    
                // 7. Pénalité de répétition immédiate
                if (!isChance && dernierTirage != null && dernierTirage.contains(num)) {
                    score -= 10.0;
                }
    
                scores.put(num, score);
            }
            return scores;
        }
    
        private boolean tiragesSuffisants(List<LotoTirage> history, int num) {
            return history.stream().filter(t -> t.getBoules().contains(num)).count() > 5;
        }
    
        // --- GENERATEUR CLASSIQUE (BUCKETS) ---
    
        private List<Integer> genererGrilleParAffinite(Map<String, List<Integer>> buckets,
                Map<Integer, Map<Integer, Integer>> matrice,
                List<Integer> dernierTirage,
                List<List<Integer>> triosDisponibles, // Historique requis pour les trios
                Random rng) {
            List<Integer> selection = new ArrayList<>();
    
            if (triosDisponibles != null && !triosDisponibles.isEmpty() && rng.nextBoolean()) {
    
                for (int tryTrio = 0; tryTrio < 3; tryTrio++) {
                    List<Integer> trioChoisi = triosDisponibles.get(rng.nextInt(triosDisponibles.size()));
    
                    // Vérification Anti-Répétition
                    long communs = trioChoisi.stream().filter(dernierTirage::contains).count();
    
                    if (communs < 2) {
                        selection.addAll(trioChoisi);
                        break;
                    }
                }
            }
    
            // --- STRATÉGIE 2 : DÉMARRAGE CLASSIQUE (Fallback) ---
            if (selection.isEmpty()) {
                List<Integer> hots = buckets.getOrDefault(Constantes.BUCKET_HOT, new ArrayList<>());
                List<Integer> hotsJouables = hots.stream()
                        .filter(n -> !dernierTirage.contains(n))
                        .toList();
    
                if (!hotsJouables.isEmpty()) {
                    selection.add(hotsJouables.get(rng.nextInt(hotsJouables.size())));
                } else {
                    int n = 1 + rng.nextInt(49);
                    while (dernierTirage.contains(n)) n = 1 + rng.nextInt(49);
                    selection.add(n);
                }
            }
    
            // --- COMPLÉTION DE LA GRILLE ---
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
    
        /**
         * Sélectionne un candidat basé sur l'affinité avec la sélection actuelle
         * @param candidats liste des candidats
         * @param selectionActuelle sélection actuelle
         * @param matrice matrice d'affinité
         * @param rng générateur aléatoire
         * @return candidat sélectionné
         */
        private Integer selectionnerParAffinite(List<Integer> candidats, List<Integer> selectionActuelle, Map<Integer, Map<Integer, Integer>> matrice, Random rng) {
            Map<Integer, Double> scoresCandidats = new HashMap<>();
    
            // On parcourt tous les candidats
            for (Integer candidat : candidats) {
                // Par défaut score = 1.0
                double scoreLien = 1.0;
                // On calcule le score d'affinité avec la sélection actuelle
                for (Integer dejaPris : selectionActuelle) {
                    int affinite = matrice.getOrDefault(dejaPris, Map.of()).getOrDefault(candidat, 0);
                    if (affinite > 12) {
                        scoreLien += (affinite * affinite) / 5.0;
                    } else {
                        scoreLien += affinite;
                    }
                }
                scoreLien += (rng.nextDouble() * 3.0);
                scoresCandidats.put(candidat, scoreLien);
            }
    
            // On sélectionne le candidat avec le score le plus élevé
            return scoresCandidats.entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse(candidats.get(0));
        }
    
        /**
         * Détermine le bucket cible pour la sélection actuelle
         * @param selection sélection actuelle
         * @param buckets buckets disponibles
         * @return bucket cible
         */
        private String determinerBucketCible(List<Integer> selection, Map<String, List<Integer>> buckets) {
            // On compte ce qu'on a déjà
            long nbHot = selection.stream()
                    .filter(n -> buckets.getOrDefault(Constantes.BUCKET_HOT, List.of()).contains(n)).count();
            long nbCold = selection.stream()
                    .filter(n -> buckets.getOrDefault(Constantes.BUCKET_COLD, List.of()).contains(n)).count();
    
            // Logique de priorité (2 Hot, 1 Cold, 2 Neutral)
            // Priorité 1 : 2 numéros hots
            if (nbHot < 2) return Constantes.BUCKET_HOT;
    
            // Priorité 2 : 1 numéro cold
            if (nbCold < 1) return Constantes.BUCKET_COLD;
    
            // Le reste : Neutral
            return Constantes.BUCKET_NEUTRAL;
        }
    
        /**
         * OPTIMISÉE : Version ultra-rapide sans Streams complexes pour le Backtest
         */
        private Map<String, List<Integer>> creerBuckets(Map<Integer, Double> scores) {
            // 1. Conversion rapide Map -> List
            List<Map.Entry<Integer, Double>> list = new ArrayList<>(scores.entrySet());
    
            // 2. Tri optimisé (Java utilise Timsort, très rapide pour les listes partiellement triées)
            list.sort(Map.Entry.<Integer, Double>comparingByValue().reversed());
    
            int total = list.size();
    
            // Si pas assez de données (début historique), on renvoie des listes vides
            if (total < 10) {
                return Map.of(
                        Constantes.BUCKET_HOT, new ArrayList<>(),
                        Constantes.BUCKET_NEUTRAL, new ArrayList<>(),
                        Constantes.BUCKET_COLD, new ArrayList<>()
                );
            }
    
            // 3. Découpage par index (SubList est une vue, donc 0 coût mémoire)
            int tailleQuart = total / 4; // Environ 12 numéros pour 49
    
            // HOT : Le Top 25%
            List<Integer> hotList = new ArrayList<>(tailleQuart);
            for (int i = 0; i < tailleQuart; i++) hotList.add(list.get(i).getKey());
    
            // COLD : Le Flop 25%
            List<Integer> coldList = new ArrayList<>(tailleQuart);
            for (int i = total - tailleQuart; i < total; i++) coldList.add(list.get(i).getKey());
    
            // NEUTRAL : Le milieu (50%)
            List<Integer> neutralList = new ArrayList<>(total - (2 * tailleQuart));
            for (int i = tailleQuart; i < total - tailleQuart; i++) neutralList.add(list.get(i).getKey());
    
            // 4. Construction Map résultat
            Map<String, List<Integer>> buckets = new HashMap<>(4);
            buckets.put(Constantes.BUCKET_HOT, hotList);
            buckets.put(Constantes.BUCKET_NEUTRAL, neutralList);
            buckets.put(Constantes.BUCKET_COLD, coldList);
    
            return buckets;
        }
    
        /**
         * Détection des finales chaudes dans les 20 derniers tirages
         * @param history historique des tirages
         * @return set des finales chaudes
         */
        private Set<Integer> detecterFinalesChaudes(List<LotoTirage> history) {
            // Sécurité : si la liste est vide
            if (history == null || history.isEmpty()) return Collections.emptySet();
    
            return history.stream()
                    .limit(20) // On prend directement les 20 derniers (les plus récents)
                    .flatMap(t -> t.getBoules().stream())
                    .map(b -> b % 10) // Calcul de la finale (ex: 42 -> 2)
                    .collect(Collectors.groupingBy(f -> f, Collectors.counting())) // Fréquence
                    .entrySet().stream()
                    .sorted((e1, e2) -> Long.compare(e2.getValue(), e1.getValue())) // Tri des fréquences
                    .limit(2) // On garde le TOP 2 des finales
                    .map(Map.Entry::getKey)
                    .collect(Collectors.toSet());
        }
    
        public Map<Integer, Map<Integer, Integer>> getMatriceAffinitesPublic() {
            return construireMatriceAffinitesPonderee(repository.findAll(), LocalDate.now().getDayOfWeek());
        }
    
        private boolean estGrilleCoherente(List<Integer> boules, List<Integer> dernierTirage, DynamicConstraints rules) {
            if (boules == null || boules.size() != 5) return false;
    
            Collections.sort(boules);
    
            int somme = 0;
            int pairs = 0;
            int dizainesMask = 0;
            int consecutiveCount = 0;
            boolean aUneSuite = false;
    
            for (int i = 0; i < 5; i++) {
                int n = boules.get(i);
                somme += n;
    
                if ((n & 1) == 0) pairs++;
                dizainesMask |= (1 << (n / 10));
    
                // Détection suites
                if (i > 0 && boules.get(i) == boules.get(i - 1) + 1) {
                    consecutiveCount++;
                    aUneSuite = true;
                } else {
                    consecutiveCount = 0;
                }
                // Rejet immédiat si suite de 3 nombres (ex: 1,2,3) - Toujours interdit
                if (consecutiveCount >= 2) return false;
            }
    
            // --- APPLICATION DES REGLES DYNAMIQUES ---
    
            // 1. Parité Dynamique
            if (pairs < rules.minPairs || pairs > rules.maxPairs) return false;
    
            // 2. Gestion des Suites
            if (!rules.allowSuites && aUneSuite) return false; // Si interdit, on jette
    
            // 3. Règles fixes (structurelles)
            if (somme < 100 || somme > 175) return false;
            if (Integer.bitCount(dizainesMask) < 3) return false;
    
            // 4. Dernier Tirage
            if (dernierTirage != null) {
                int communs = 0;
                for (Integer n : boules) if (dernierTirage.contains(n)) communs++;
                return communs < 2;
            }
    
            // 5. Filtre des Dizaines (Répartition Spatiale)
            // Une grille ne doit jamais avoir 4 numéros dans la même dizaine (ex: 10, 12, 15, 19, 45)
            // C'est statistiquement rarissime.
            int[] dizaines = new int[5];
            for(int n : boules) dizaines[n/10]++;
            for(int d : dizaines) if(d >= 4) return false; // REJET IMMÉDIAT
    
            // 6. Filtre du "Grand Ecart"
            // La différence entre le plus grand et le plus petit numéro est souvent > 20
            if ((boules.get(4) - boules.get(0)) < 15) return false; // Trop compact
    
            // 7. Filtre "Somme des Finales"
            // La somme des derniers chiffres (ex: 12 -> 2, 45 -> 5) est souvent entre 15 et 35
            int sommeFinales = boules.stream().mapToInt(n -> n % 10).sum();
    
            return sommeFinales >= 10 && sommeFinales <= 40;
        }
    
        // ==================================================================================
        // 5. MÉTHODES DE SIMULATION & CALCULS DE RATIO
        // ==================================================================================
    
        public SimulationResultDto simulerGrilleDetaillee(List<Integer> boulesJouees, LocalDate dateSimul) {
            return simulerGrilleDetaillee(boulesJouees, dateSimul, repository.findAll());
        }
    
        private SimulationResultDto simulerGrilleDetaillee(List<Integer> boulesJouees, LocalDate dateSimul, List<LotoTirage> historique) {
            SimulationResultDto result = new SimulationResultDto();
            try {
                result.setDateSimulee(dateSimul.format(DateTimeFormatter.ofPattern(Constantes.FORMAT_DATE_STANDARD)));
            } catch (Exception e) {
                result.setDateSimulee(dateSimul.toString());
            }
            result.setJourSimule(dateSimul.getDayOfWeek().getDisplayName(TextStyle.FULL, Locale.FRANCE).toUpperCase());
            result.setQuintuplets(new ArrayList<>());
            result.setQuartets(new ArrayList<>());
            result.setTrios(new ArrayList<>());
            result.setPairs(new ArrayList<>());
    
            for (LotoTirage t : historique) {
                List<Integer> commun = new ArrayList<>(t.getBoules());
                commun.retainAll(boulesJouees);
    
                int taille = commun.size();
                if (taille >= 2) {
                    String dateHist;
                    try {
                        dateHist = t.getDateTirage().format(DateTimeFormatter.ofPattern(Constantes.FORMAT_DATE_STANDARD));
                    } catch (Exception e) {
                        dateHist = t.getDateTirage().toString();
                    }
                    boolean memeJour = t.getDateTirage().getDayOfWeek() == dateSimul.getDayOfWeek();
                    addToResult(result, taille, commun, dateHist, memeJour, historique.size());
                }
            }
            return result;
        }
    
        private void addToResult(SimulationResultDto res, int taille, List<Integer> nums, String date, boolean memeJour, int totalTirages) {
            List<MatchGroup> targetList = switch (taille) {
                case 5 -> res.getQuintuplets();
                case 4 -> res.getQuartets();
                case 3 -> res.getTrios();
                case 2 -> res.getPairs();
                default -> null;
            };
    
            if (targetList != null) {
                Collections.sort(nums);
                Optional<MatchGroup> existing = targetList.stream()
                        .filter(m -> m.getNumeros().equals(nums))
                        .findFirst();
    
                if (existing.isPresent()) {
                    MatchGroup group = existing.get();
                    group.getDates().add(date + (memeJour ? " (Même jour !)" : ""));
                    if (memeJour) group.setSameDayOfWeek(true);
                    updateRatio(group, totalTirages, taille);
                } else {
                    List<String> dates = new ArrayList<>();
                    dates.add(date + (memeJour ? " (Même jour !)" : ""));
                    MatchGroup newGroup = new MatchGroup(nums, dates, memeJour, 0.0);
                    updateRatio(newGroup, totalTirages, taille);
                    targetList.add(newGroup);
                }
            }
        }
    
        private void updateRatio(MatchGroup group, int totalTirages, int taille) {
            double probaTheo = switch (taille) {
                case 1 -> 0.10204;
                case 2 -> 0.00850;
                case 3 -> 0.00041;
                case 4 -> 0.0000096;
                case 5 -> 0.00000052;
                default -> 0.0;
            };
            double nbreAttendu = totalTirages * probaTheo;
            int nbreReel = group.getDates().size();
            double ratio = (nbreAttendu > 0) ? (nbreReel / nbreAttendu) : 0.0;
            group.setRatio(Math.round(ratio * 100.0) / 100.0);
        }
    
        public void importCsv(MultipartFile file) throws IOException {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(file.getInputStream()))) {
                List<String> lines = reader.lines().toList();
                DateTimeFormatter fmt1 = DateTimeFormatter.ofPattern(Constantes.FORMAT_DATE_STANDARD);
                DateTimeFormatter fmt2 = DateTimeFormatter.ofPattern(Constantes.FORMAT_DATE_STANDARD_INVERSE);
    
                for (String line : lines) {
                    if (line.trim().isEmpty() || line.startsWith("annee") || line.startsWith("Tirage")) continue;
                    try {
                        String[] row;
                        LocalDate date;
                        int b1, b2, b3, b4, b5, c;
    
                        if (line.contains(Constantes.DELIMITEUR_POINT_VIRGULE)) {
                            row = line.split(Constantes.DELIMITEUR_POINT_VIRGULE);
                            if(row.length<10) continue;
                            try{
                                date=LocalDate.parse(row[2],fmt1);
                            } catch(Exception e) { continue; }
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
    
        public double calculerGainSimule(UserBet bet, LotoTirage tirage) {
            if (tirage == null || bet == null) return 0.0;
            if (bet.getCodeLoto() != null && !bet.getCodeLoto().isEmpty()) {
                String userCode = bet.getCodeLoto().replaceAll("\\s", "").toUpperCase();
                List<String> winningCodes = tirage.getWinningCodes();
                if (winningCodes != null && winningCodes.contains(userCode)) return 20000.0;
                return 0.0;
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
                return tirage.getRanks().stream()
                        .filter(r -> r.getRankNumber() == finalRankPos)
                        .findFirst()
                        .map(LotoTirageRank::getPrize)
                        .orElse(rankPosition == 9 ? 2.20 : 0.0);
            }
            return 0.0;
        }
    
        public LotoTirage ajouterTirageManuel(TirageManuelDto dto) {
            if (repository.existsByDateTirage(dto.getDateTirage())) throw new RuntimeException("Ce tirage existe déjà");
            LotoTirage t = new LotoTirage();
            t.setDateTirage(dto.getDateTirage());
            t.setBoule1(dto.getBoule1()); t.setBoule2(dto.getBoule2()); t.setBoule3(dto.getBoule3());
            t.setBoule4(dto.getBoule4()); t.setBoule5(dto.getBoule5()); t.setNumeroChance(dto.getNumeroChance());
            repository.save(t);
            return t;
        }
    
        public StatsReponse getStats(String jourFiltre) {
            // Si pas de filtre jour (cas de la page d'accueil) et cache dispo
            if (jourFiltre == null && cachedGlobalStats != null) {
                return cachedGlobalStats;
            }
    
            // Récupération des tirages triés par ordre décroissant de date
            List<LotoTirage> all = repository.findAll(Sort.by(Sort.Direction.DESC, "dateTirage"));
    
            if (jourFiltre != null && !jourFiltre.isEmpty()) {
                try {
                    DayOfWeek d = DayOfWeek.valueOf(jourFiltre.toUpperCase());
                    all = all.stream().filter(t -> t.getDateTirage().getDayOfWeek() == d).toList();
                } catch (Exception e) {
                    log.error("Erreur filtre jour: {}", jourFiltre);
                    throw new RuntimeException(e);
                }
            }
    
            if (all.isEmpty()) return new StatsReponse(new ArrayList<>(), "-", "-", 0);
    
            LocalDate minDate = all.stream().map(LotoTirage::getDateTirage).min(LocalDate::compareTo).orElse(LocalDate.now());
            LocalDate maxDate = all.stream().map(LotoTirage::getDateTirage).max(LocalDate::compareTo).orElse(LocalDate.now());
            DateTimeFormatter fmt = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    
            Map<Integer, Integer> freqMap = new HashMap<>();
            Map<Integer, LocalDate> lastSeenMap = new HashMap<>();
            Map<Integer, Integer> freqChance = new HashMap<>();
            Map<Integer, LocalDate> lastSeenChance = new HashMap<>();
    
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
    
            // Construction de l'objet contenant la réponse des stats
            StatsReponse response = new StatsReponse(stats, minDate.format(fmt), maxDate.format(fmt), all.size());
    
            // Si c'était le calcul global (sans filtre), on le garde en mémoire !
            if (jourFiltre == null) {
                this.cachedGlobalStats = response;
            }
            return response;
        }
    
        public UserStatsDto calculerStatistiquesJoueur(User user) {
            UserStatsDto stats = new UserStatsDto();
            List<UserBet> bets = betRepository.findByUserOrderByDateJeuDesc(user);
            stats.setTotalGrilles(bets.size());
            stats.setDepenseTotale(bets.stream().mapToDouble(UserBet::getMise).sum());
            stats.setGainTotal(bets.stream().filter(b -> b.getGain() != null).mapToDouble(UserBet::getGain).sum());
    
            if (bets.isEmpty()) return stats;
    
            Map<Integer, Integer> freqBoules = new HashMap<>();
            Map<Integer, Integer> freqChance = new HashMap<>();
            long totalSomme = 0;
            int countPairs = 0;
            int totalNumerosJoues = 0;
    
            Map<String, UserStatsDto.DayPerformance> dayStats = new LinkedHashMap<>();
            dayStats.put("MONDAY", new UserStatsDto.DayPerformance("Lundi"));
            dayStats.put("WEDNESDAY", new UserStatsDto.DayPerformance("Mercredi"));
            dayStats.put("SATURDAY", new UserStatsDto.DayPerformance("Samedi"));
    
            for (UserBet bet : bets) {
                boolean isGrille = bet.getB1() != null;
                if (isGrille) {
                    List<Integer> gr = List.of(bet.getB1(), bet.getB2(), bet.getB3(), bet.getB4(), bet.getB5());
                    totalSomme += gr.stream().mapToInt(Integer::intValue).sum();
                    for (Integer n : gr) {
                        freqBoules.merge(n, 1, Integer::sum);
                        if (n % 2 == 0) countPairs++;
                        totalNumerosJoues++;
                    }
                    if (bet.getChance() != null) freqChance.merge(bet.getChance(), 1, Integer::sum);
                }
                String dayKey = bet.getDateJeu().getDayOfWeek().name();
                if (dayStats.containsKey(dayKey)) {
                    UserStatsDto.DayPerformance p = dayStats.get(dayKey);
                    p.setNbJeux(p.getNbJeux() + 1);
                    p.setDepense(p.getDepense() + bet.getMise());
                    if (bet.getGain() != null) p.setGains(p.getGains() + bet.getGain());
                }
            }
            stats.setPerformanceParJour(dayStats);
            long nbGrillesReelles = bets.stream().filter(b -> b.getB1() != null).count();
            if (nbGrillesReelles > 0) stats.setMoyenneSomme(Math.round((double) totalSomme / nbGrillesReelles));
            else stats.setMoyenneSomme(0);
    
            stats.setTotalPairsJoues(countPairs);
            stats.setTotalImpairsJoues(totalNumerosJoues - countPairs);
            if (totalNumerosJoues > 0) {
                double ratioPair = (double) countPairs / totalNumerosJoues;
                int p = (int) Math.round(ratioPair * 5);
                stats.setPariteMoyenne(p + " Pairs / " + (5 - p) + " Impairs");
            } else {
                stats.setPariteMoyenne("N/A");
            }
    
            stats.setTopBoules(freqBoules.entrySet().stream()
                    .sorted((e1, e2) -> e2.getValue().compareTo(e1.getValue()))
                    .limit(5).map(e -> new UserStatsDto.StatNumero(e.getKey(), e.getValue())).toList());
    
            stats.setTopChance(freqChance.entrySet().stream()
                    .sorted((e1, e2) -> e2.getValue().compareTo(e1.getValue()))
                    .limit(3).map(e -> new UserStatsDto.StatNumero(e.getKey(), e.getValue())).toList());
    
            List<Integer> jamais = new ArrayList<>();
            for(int i=1; i<=49; i++) { if(!freqBoules.containsKey(i)) jamais.add(i); }
            stats.setNumJamaisJoues(jamais);
            return stats;
        }
    
        private int selectionnerChanceOptimisee(List<Integer> boules, Map<Integer, Double> scoresChanceBase,
                Map<Integer, Map<Integer, Integer>> affinitesChance, Random rng) {
            Map<Integer, Double> scoreFinalChance = new HashMap<>();
            for (int c = 1; c <= 10; c++) {
                double score = scoresChanceBase.getOrDefault(c, 10.0);
                double affiniteScore = 0;
                for (Integer b : boules) {
                    int count = affinitesChance.getOrDefault(b, Map.of()).getOrDefault(c, 0);
                    affiniteScore += count;
                }
                score += (affiniteScore * 2.0);
                scoreFinalChance.put(c, score);
            }
            return scoreFinalChance.entrySet().stream()
                    .max(Comparator.comparingDouble(e -> e.getValue() + rng.nextDouble() * 5.0))
                    .map(Map.Entry::getKey).orElse(1);
        }
    
        private List<List<Integer>> getTopTriosRecents(List<LotoTirage> history) {
            Map<Set<Integer>, Integer> trioFrequency = new HashMap<>();
            List<LotoTirage> recents = history.stream()
                    .sorted(Comparator.comparing(LotoTirage::getDateTirage).reversed()).limit(100).toList();
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
    
        /**
         * OPTIMISÉE : Utilise des tableaux primitifs int[][] pour le calcul (x10 plus rapide)
         * et convertit en Map à la fin pour la compatibilité.
         */
        private Map<Integer, Map<Integer, Integer>> construireMatriceAffinitesPonderee(List<LotoTirage> history, DayOfWeek jourCible) {
            // 1. UTILISATION D'UN TABLEAU PRIMITIF (Zone mémoire contiguë = CPU heureux)
            // [50][50] car les boules vont de 1 à 49 (on ignore l'index 0 pour simplifier)
            int[][] matriceTemp = new int[50][50];
    
            // 2. Limite d'analyse : On ne regarde que les 350 derniers tirages (~2 ans et demi)
            // C'est le "Sweet Spot" : assez long pour être fiable, assez court pour capter la tendance actuelle.
            int limit = Math.min(history.size(), 350);
    
            for (int i = 0; i < limit; i++) {
                LotoTirage t = history.get(i);
    
                // Poids dynamique
                int poids = (t.getDateTirage().getDayOfWeek() == jourCible) ? 6 : 1;
    
                List<Integer> boules = t.getBoules();
                int nbBoules = boules.size(); // Devrait être 5
    
                // Boucle critique : Accès tableau direct (Nanosecondes) vs HashMap (Microsecondes)
                for (int k = 0; k < nbBoules; k++) {
                    int n1 = boules.get(k);
                    for (int m = k + 1; m < nbBoules; m++) {
                        int n2 = boules.get(m);
    
                        // Mise à jour symétrique ultra-rapide
                        matriceTemp[n1][n2] += poids;
                        matriceTemp[n2][n1] += poids;
                    }
                }
            }
    
            // 3. CONVERSION FINALE EN MAP (On ne le fait qu'une seule fois)
            // On remet ça dans le format que le reste de votre code attend
            Map<Integer, Map<Integer, Integer>> matrixResult = new HashMap<>(64);
    
            for (int i = 1; i <= 49; i++) {
                Map<Integer, Integer> ligne = new HashMap<>(64);
                for (int j = 1; j <= 49; j++) {
                    if (i == j) continue; // Pas d'affinité avec soi-même
                    if (matriceTemp[i][j] > 0) {
                        ligne.put(j, matriceTemp[i][j]);
                    }
                }
                matrixResult.put(i, ligne);
            }
    
            return matrixResult;
        }
    
        /**
         * Construction optimisée de la matrice d'affinités entre boules et numéros chance avec pondération
         * @param history historique des tirages
         * @param jourCible jour de la semaine cible pour la pondération
         * @return matrice d'affinités entre boules et numéros chance pondérée
         */
        private Map<Integer, Map<Integer, Integer>> construireMatriceAffinitesChancePonderee(List<LotoTirage> history, DayOfWeek jourCible) {
            // [50] boules x [11] chances (0-10)
            int[][] matriceTemp = new int[50][11];
    
            int limit = Math.min(history.size(), 350);
    
            for (int i = 0; i < limit; i++) {
                LotoTirage t = history.get(i);
                int poids = (t.getDateTirage().getDayOfWeek() == jourCible) ? 6 : 1;
    
                int chance = t.getNumeroChance();
                // Sécurité si chance > 10 (changement de règles ancien loto)
                if (chance > 10 || chance < 1) continue;
    
                for (Integer boule : t.getBoules()) {
                    matriceTemp[boule][chance] += poids;
                }
            }
    
            // Conversion
            Map<Integer, Map<Integer, Integer>> matrixResult = new HashMap<>(64);
            for (int i = 1; i <= 49; i++) {
                Map<Integer, Integer> ligne = new HashMap<>(16);
                for (int c = 1; c <= 10; c++) {
                    if (matriceTemp[i][c] > 0) {
                        ligne.put(c, matriceTemp[i][c]);
                    }
                }
                matrixResult.put(i, ligne);
            }
            return matrixResult;
        }
    
        @jakarta.annotation.PostConstruct
        public void initConfigFromDb() {
            log.info("🔌 Démarrage : Recherche d'une stratégie existante en base...");
    
            strategyConfigRepostiroy.findTopByOrderByDateCalculDesc().ifPresentOrElse(
                    lastStrategy -> {
                        // On convertit l'entité BDD en objet de config RAM
                        this.cachedBestConfig = new AlgoConfig(
                                lastStrategy.getNomStrategie(),
                                lastStrategy.getPoidsFreqJour(),
                                lastStrategy.getPoidsForme(),
                                lastStrategy.getPoidsEcart(),
                                lastStrategy.getPoidsTension(),
                                lastStrategy.getPoidsMarkov(),
                                lastStrategy.getPoidsAffinite(),
                                false
                        );
                        // On considère que le cache est valide pour aujourd'hui
                        this.lastBacktestDate = lastStrategy.getDateCalcul().toLocalDate();
                        log.info("✅ Stratégie chargée depuis la BDD (Date: {}). Prêt immédiat !", lastStrategy.getDateCalcul());
                    },
                    () -> log.warn("⚠️ Aucune stratégie en base. Le premier utilisateur déclenchera le calcul.")
            );
        }
    
        public void verificationAuDemarrage() {
            // Si une config est déjà chargée (via @PostConstruct) et qu'elle date d'aujourd'hui
            if (this.cachedBestConfig != null && LocalDate.now().equals(this.lastBacktestDate)) {
                log.info("✋ [WARMUP] Stratégie du jour déjà présente en mémoire/BDD. Calcul inutile.");
    
                // On peut regénérer les pronostics du jour ici si le cache est vide,
                // c'est rapide (0ms) et ça préchauffe le cache "DailyPronos"
                genererMultiplesPronostics(recupererDateProchainTirage(), 5);
                return;
            }
    
            // Sinon, c'est que la base est vide ou date d'hier : on lance le calcul
            log.info("⚠️ [WARMUP] Aucune stratégie valide pour ce jour. Lancement du calcul...");
            forceDailyOptimization();
        }
    
        /**
         * Méthode appelée par le scheduler pour forcer l'optimisation quotidienne
         */
        public void forceDailyOptimization() {
            log.info("🌙 [CRON] Optimisation et Nettoyage des caches...");
    
            // 1. Vidage des caches
            this.cachedGlobalStats = null;
            this.cachedDailyPronos = null;
    
            long start = System.currentTimeMillis();
            List<LotoTirage> history = repository.findAll(Sort.by(Sort.Direction.DESC, "dateTirage"));
    
            if (!history.isEmpty()) {
                // 2. Calcul de la meilleure configuration
                AlgoConfig newConfig = backtestService.trouverMeilleureConfig(history);
    
                // 3. Mise à jour RAM (Immédiat)
                this.cachedBestConfig = newConfig;
                this.lastBacktestDate = LocalDate.now();
    
                // 4. SAUVEGARDE BDD (Pour le futur/redémarrage)
                StrategyConfig entity = new StrategyConfig();
                entity.setDateCalcul(LocalDateTime.now());
                entity.setNomStrategie(newConfig.getNomStrategie());
                entity.setPoidsForme(newConfig.getPoidsForme());
                entity.setPoidsEcart(newConfig.getPoidsEcart());
                entity.setPoidsAffinite(newConfig.getPoidsAffinite());
                entity.setPoidsMarkov(newConfig.getPoidsMarkov());
                entity.setPoidsTension(newConfig.getPoidsTension());
                entity.setPoidsFreqJour(newConfig.getPoidsFreqJour());

                // Enregistrement des perfs
                entity.setBilanEstime(newConfig.getBilanEstime());
                entity.setNbTiragesTestes(newConfig.getNbTiragesTestes());
    
                strategyConfigRepostiroy.save(entity);

                log.info("✅ [CRON] Stratégie sauvegardée (Bilan: {} €) en {} ms.",
                        String.format("%.2f", entity.getBilanEstime()), (System.currentTimeMillis() - start));
            }
        }
    
        /**
         * Récupère la date du prochain tirage (Lundi, Mercredi, Samedi)
         * @return date du prochain tirage
         */
        public LocalDate recupererDateProchainTirage() {
            LocalDate date = LocalDate.now();
    
            // Si on est un jour de tirage (1, 3, 6) MAIS qu'il est tard (> 20h15),
            // alors le tirage du jour est "fini", on cherche le suivant.
            boolean estJourTirage = (date.getDayOfWeek().getValue() == 1 ||
                    date.getDayOfWeek().getValue() == 3 ||
                    date.getDayOfWeek().getValue() == 6);
    
            if (estJourTirage && java.time.LocalTime.now().isAfter(java.time.LocalTime.of(20, 15))) {
                date = date.plusDays(1);
            }
    
            // On avance jusqu'au prochain Lundi (1), Mercredi (3) ou Samedi (6)
            while (date.getDayOfWeek().getValue() != 1 &&
                    date.getDayOfWeek().getValue() != 3 &&
                    date.getDayOfWeek().getValue() != 6) {
                date = date.plusDays(1);
            }
            return date;
        }
    
        /**
         * ÉTAPE 1 : Pré-calcul massif des contextes historiques.
         * Cette méthode est lente mais n'est exécutée qu'une seule fois.
         */
        public List<ScenarioSimulation> preparerScenariosBacktest(List<LotoTirage> historiqueComplet, int nbTirages, int historyDepth) {
            List<ScenarioSimulation> scenarios = new ArrayList<>();
    
            // Sécurité
            if (historiqueComplet.size() < nbTirages + historyDepth) return scenarios;
    
            for (int i = 0; i < nbTirages; i++) {
                LotoTirage cible = historiqueComplet.get(i);
    
                // On coupe l'historique "comme si on y était"
                int end = Math.min(i + 1 + historyDepth, historiqueComplet.size());
                List<LotoTirage> historyConnu = historiqueComplet.subList(i + 1, end);
    
                if(historyConnu.isEmpty()) continue;
    
                // 1. Calculs Invariants (Matrices, Contraintes...)
                List<Integer> dernierTirage = historyConnu.get(0).getBoules();
                Set<Integer> hotFinales = detecterFinalesChaudes(historyConnu);
                DynamicConstraints contraintes = analyserContraintesDynamiques(historyConnu, dernierTirage);
                // Pré-calcul des trios
                List<List<Integer>> topTrios = getTopTriosRecents(historyConnu);
    
                // Matrices (Optimisées int[][])
                Map<Integer, Map<Integer, Integer>> matAff = construireMatriceAffinitesPonderee(historyConnu, cible.getDateTirage().getDayOfWeek());
                Map<Integer, Map<Integer, Integer>> matChance = construireMatriceAffinitesChancePonderee(historyConnu, cible.getDateTirage().getDayOfWeek());
    
                // 2. Extraction des Stats Brutes (SANS appliquer les poids)
                Map<Integer, RawStatData> rawBoules = extraireStatsBrutes(historyConnu, 49, cible.getDateTirage().getDayOfWeek(), false, hotFinales);
                Map<Integer, RawStatData> rawChance = extraireStatsBrutes(historyConnu, 10, cible.getDateTirage().getDayOfWeek(), true, Collections.emptySet());
    
                scenarios.add(new ScenarioSimulation(cible, dernierTirage, matAff, matChance, rawBoules, rawChance, contraintes, topTrios));
            }
            return scenarios;
        }
    
        /**
         * Helper pour extraire les stats brutes (Frequency, Ecart...) sans les pondérer
         */
        private Map<Integer, RawStatData> extraireStatsBrutes(List<LotoTirage> history, int maxNum, DayOfWeek jour, boolean isChance, Set<Integer> hotFinales) {
            Map<Integer, RawStatData> map = new HashMap<>();
            // Note: On pourrait optimiser encore en évitant les streams ici, mais c'est fait 1 fois
            List<LotoTirage> histJour = history.stream().filter(t -> t.getDateTirage().getDayOfWeek() == jour).toList();
    
            for (int i = 1; i <= maxNum; i++) {
                int num = i;
                long freqJour = histJour.stream().filter(t -> isChance ? t.getNumeroChance() == num : t.getBoules().contains(num)).count();
    
                // Ecart
                int idxLast = -1;
                for(int k=0; k < history.size(); k++) {
                    if (isChance ? history.get(k).getNumeroChance() == num : history.get(k).getBoules().contains(num)) {
                        idxLast = k; break;
                    }
                }
                long ecart = (idxLast == -1) ? history.size() : idxLast;
    
                // Forme
                long sorties15 = history.stream().limit(15).filter(t -> isChance ? t.getNumeroChance() == num : t.getBoules().contains(num)).count();
                long sorties10 = history.stream().limit(10).filter(t -> isChance ? t.getNumeroChance() == num : t.getBoules().contains(num)).count();
    
                boolean isTension = !isChance && tiragesSuffisants(history, num);
                boolean isHotFinale = !isChance && hotFinales.contains(num % 10);
    
                map.put(num, new RawStatData(freqJour, ecart, sorties15 >= 2, sorties10 >= 2, false, isHotFinale, isTension));
            }
            return map;
        }
    
        /**
         * ÉTAPE 2 : Génération Ultra-Rapide
         * Applique la Config (Poids) sur les Données Pré-calculées (Scenario)
         */
        public List<List<Integer>> genererGrillesDepuisScenario(ScenarioSimulation sc, AlgoConfig config, int nbGrilles) {
            List<List<Integer>> resultats = new ArrayList<>();
            Random rng = new Random();
    
            // 1. Calcul des Scores (Multiplication simple Poids * RawData)
            Map<Integer, Double> scoresBoules = sc.rawStatsBoules.entrySet().stream().collect(Collectors.toMap(
                    Map.Entry::getKey,
                    e -> calculerScoreFinal(e.getValue(), config)
            ));
    
            Map<Integer, Double> scoresChance = sc.rawStatsChance.entrySet().stream().collect(Collectors.toMap(
                    Map.Entry::getKey,
                    e -> calculerScoreFinal(e.getValue(), config)
            ));
    
            // 2. Buckets
            Map<String, List<Integer>> buckets = creerBuckets(scoresBoules);
    
            // 3. Génération (Identique à avant, mais utilisant les matrices pré-calculées du scénario)
            int essais = 0;
            while(resultats.size() < nbGrilles && essais < 200) { // Limite essais pour vitesse
                essais++;
                // On réutilise votre méthode genererGrilleParAffinite qui prend déjà les maps en entrée !
                List<Integer> boules = genererGrilleParAffinite(buckets, sc.matriceAffinites, sc.dernierTirageConnu, sc.topTriosPrecalcules, rng); // null pour history car trios ignorés en mode rapide ou adapter
    
                if (estGrilleCoherente(boules, sc.dernierTirageConnu, sc.contraintes)) {
                    // Fitness Check rapide
                    int chance = selectionnerChanceOptimisee(boules, scoresChance, sc.matriceChance, rng);
    
                    Collections.sort(boules);
                    boules.add(chance);
                    resultats.add(boules);
                }
            }
            return resultats;
        }
    
        private double calculerScoreFinal(RawStatData raw, AlgoConfig cfg) {
            double s = 10.0;
            s += (raw.freqJour * cfg.getPoidsFreqJour());
    
            if (raw.ecart > 40) s -= 5.0;
            else if (raw.ecart > 10) s += (raw.ecart * cfg.getPoidsEcart());
    
            if (raw.isForme) s += cfg.getPoidsForme();
            if (raw.isTresForme) s += 25.0; // Boost fixe
            if (raw.isHotFinale) s += 8.0;
            if (raw.isTension) s += cfg.getPoidsTension();
    
            return s;
        }

        /**
         * VRAI ALGORITHME GÉNÉTIQUE : Évolution par Sélection, Croisement et Mutation
         */
        private List<GrilleCandidate> executerAlgorithmeGenetique(
                int taillePopulation, int generations,
                Map<String, List<Integer>> buckets,
                Map<Integer, Map<Integer, Integer>> matriceAffinites,
                List<Integer> dernierTirage,
                List<List<Integer>> topTrios,
                Map<Integer, Double> scoresBoules,
                Map<Integer, Double> scoresChance,
                Map<Integer, Map<Integer, Integer>> matriceChance,
                List<LotoTirage> history,
                DynamicConstraints contraintes,
                AlgoConfig config,
                Set<Long> historiqueBitMasks,
                Random rng) {

            List<GrilleCandidate> population = new ArrayList<>();

            // -------------------------------------------------------------
            // 1. POPULATION INITIALE (Génération 0)
            // -------------------------------------------------------------
            while (population.size() < taillePopulation) {
                List<Integer> boules = genererGrilleParAffinite(buckets, matriceAffinites, dernierTirage, topTrios, rng);
                if (estGrilleCoherente(boules, dernierTirage, contraintes) && !historiqueBitMasks.contains(calculerBitMask(boules))) {
                    int chance = selectionnerChanceOptimisee(boules, scoresChance, matriceChance, rng);
                    double fitness = calculerScoreFitness(boules, chance, scoresBoules, scoresChance, matriceAffinites, history, dernierTirage, config);
                    population.add(new GrilleCandidate(boules, chance, fitness));
                }
            }

            // -------------------------------------------------------------
            // 2. ÉVOLUTION (Boucle des Générations)
            // -------------------------------------------------------------
            for (int gen = 1; gen <= generations; gen++) {
                // Tri des individus du meilleur au moins bon
                population.sort((g1, g2) -> Double.compare(g2.fitness, g1.fitness));

                List<GrilleCandidate> nouvelleGeneration = new ArrayList<>();

                // A. ELITISME (On garde les 15% meilleurs intacts)
                int nbElites = (int) (taillePopulation * 0.15);
                for (int i = 0; i < nbElites; i++) {
                    nouvelleGeneration.add(population.get(i));
                }

                // B. CROSSOVER (Reproduction : 70% de la population)
                int nbEnfants = (int) (taillePopulation * 0.85); // 15% élite + 70% enfants = 85%
                while (nouvelleGeneration.size() < nbEnfants) {
                    // Sélection des parents par "Tournoi" (On prend 2 parents parmi les 30% meilleurs)
                    GrilleCandidate maman = population.get(rng.nextInt(taillePopulation / 3));
                    GrilleCandidate papa = population.get(rng.nextInt(taillePopulation / 3));

                    // L'enfant hérite de 3 gènes de maman et 2 de papa
                    Set<Integer> genesEnfant = new HashSet<>(maman.boules.subList(0, 3));
                    for (Integer numPapa : papa.boules) {
                        if (genesEnfant.size() < 5) genesEnfant.add(numPapa);
                    }

                    // Si maman et papa avaient des numéros en commun, il manque des boules à l'enfant. On comble.
                    while (genesEnfant.size() < 5) {
                        genesEnfant.add(rng.nextInt(49) + 1);
                    }

                    List<Integer> boulesEnfant = new ArrayList<>(genesEnfant);
                    Collections.sort(boulesEnfant);

                    // C. MUTATION (15% de chance de modifier un numéro aléatoirement)
                    if (rng.nextDouble() < 0.15) {
                        int idxToMutate = rng.nextInt(5);
                        int mutation = rng.nextInt(49) + 1;
                        while (boulesEnfant.contains(mutation)) mutation = rng.nextInt(49) + 1;
                        boulesEnfant.set(idxToMutate, mutation);
                        Collections.sort(boulesEnfant);
                    }

                    // D. VALIDATION DE L'ENFANT
                    if (estGrilleCoherente(boulesEnfant, dernierTirage, contraintes) && !historiqueBitMasks.contains(calculerBitMask(boulesEnfant))) {
                        int chance = rng.nextBoolean() ? maman.chance : papa.chance; // Héritage du numéro chance
                        double fitness = calculerScoreFitness(boulesEnfant, chance, scoresBoules, scoresChance, matriceAffinites, history, dernierTirage, config);
                        nouvelleGeneration.add(new GrilleCandidate(boulesEnfant, chance, fitness));
                    }
                }

                // E. IMMIGRATION (15% de sang neuf pour éviter la consanguinité/blocage local)
                while (nouvelleGeneration.size() < taillePopulation) {
                    List<Integer> b = genererGrilleParAffinite(buckets, matriceAffinites, dernierTirage, topTrios, rng);
                    if (estGrilleCoherente(b, dernierTirage, contraintes) && !historiqueBitMasks.contains(calculerBitMask(b))) {
                        int c = selectionnerChanceOptimisee(b, scoresChance, matriceChance, rng);
                        double f = calculerScoreFitness(b, c, scoresBoules, scoresChance, matriceAffinites, history, dernierTirage, config);
                        nouvelleGeneration.add(new GrilleCandidate(b, c, f));
                    }
                }

                population = nouvelleGeneration; // La nouvelle génération remplace l'ancienne
            }

            // Tri final après la dernière génération
            population.sort((g1, g2) -> Double.compare(g2.fitness, g1.fitness));
            return population;
        }

        /**
         * Prépare les données de la stratégie pour l'affichage utilisateur (Dashboard)
         */
        public StrategyDisplayDto getStrategieDuJourPourAffichage() {
            AlgoConfig config = (this.cachedBestConfig != null) ? this.cachedBestConfig : AlgoConfig.defaut();

            // 1. Normalisation pour le Radar Chart (Note sur 10 pour que ce soit joli)
            // Forme (max ~20) -> on divise par 2
            double valForme = Math.min(10.0, config.getPoidsForme() / 2.0);
            // Ecart (max ~2.0) -> on multiplie par 5
            double valEcart = Math.min(10.0, config.getPoidsEcart() * 5.0);
            // Affinité (max ~10) -> tel quel
            double valAffinite = Math.min(10.0, config.getPoidsAffinite());
            // Tension (max ~30) -> on divise par 3
            double valTension = Math.min(10.0, config.getPoidsTension() / 3.0);

            // 2. Détermination de la "Météo" (Le point fort de la stratégie)
            String titre, desc, icone;
            double max = Math.max(Math.max(valForme, valEcart), Math.max(valAffinite, valTension));

            if (max == valEcart) {
                titre = "Chasse aux numéros froids";
                desc = "L'IA parie sur la loi des grands nombres : les numéros qui ne sont pas sortis depuis longtemps sont privilégiés ce soir.";
                icone = "bi-snow";
            } else if (max == valAffinite) {
                titre = "L'heure des Duos Historiques";
                desc = "L'algorithme a détecté des paires de numéros inséparables. Focus maximal sur les affinités de groupe.";
                icone = "bi-people-fill";
            } else if (max == valTension) {
                titre = "Correction Statistique";
                desc = "L'IA force le destin sur les numéros qui sont en 'retard' par rapport à leur moyenne théorique de sortie.";
                icone = "bi-magnet-fill";
            } else {
                titre = "Sur la vague de la tendance";
                desc = "L'IA suit le courant : priorité aux numéros chauds qui ont dominé les derniers tirages.";
                icone = "bi-fire";
            }

            // 3. Le Badge de Puissance
            // Si c'est notre config "Deep Blue" (nbTiragesTestes = 350)
            String puissance = (config.getNbTiragesTestes() > 0)
                    ? "Basé sur 78 millions de simulations"
                    : "Algorithme Standard FDJ";

            return StrategyDisplayDto.builder()
                    .nom("IA GÉNÉTIQUE (" + config.getNomStrategie() + ")")
                    .meteoTitre(titre)
                    .meteoDescription(desc)
                    .meteoIcone(icone)
                    .badgePuissance(puissance)
                    .chartForme(Math.round(valForme * 10.0) / 10.0)
                    .chartEcart(Math.round(valEcart * 10.0) / 10.0)
                    .chartAffinite(Math.round(valAffinite * 10.0) / 10.0)
                    .chartTension(Math.round(valTension * 10.0) / 10.0)
                    .build();
        }

        // ==================================================================================
        // 6. MODULE MARKOV (PRÉDICTION STRUCTURELLE)
        // ==================================================================================

        /**
         * Définit l'état abstrait d'un tirage basé sur la somme des boules.
         * TRES_BAS (<100), BAS (100-125), MOYEN (126-150), HAUT (151-175), TRES_HAUT (>175)
         */
        private int calculerEtatAbstrait(List<Integer> boules) {
            int somme = boules.stream().mapToInt(Integer::intValue).sum();
            if (somme < 100) return 1;
            if (somme <= 125) return 2;
            if (somme <= 150) return 3;
            if (somme <= 175) return 4;
            return 5;
        }

        /**
         * Calcule la probabilité de transition de l'état d'hier vers l'état candidat aujourd'hui.
         */
        private double calculerScoreMarkov(List<Integer> grilleCandidate, List<Integer> dernierTirage, List<LotoTirage> history) {
            if (dernierTirage == null || history.size() < 200) return 0.0;

            int etatPrecedent = calculerEtatAbstrait(dernierTirage);
            int etatCandidat = calculerEtatAbstrait(grilleCandidate);

            // On compte dans l'historique combien de fois 'etatPrecedent' a été suivi par 'etatCandidat'
            int totalTransitions = 0;
            int transitionsCibles = 0;

            // On parcourt l'historique (history[0] est le plus récent)
            for (int i = 0; i < history.size() - 1; i++) {
                List<Integer> tirageJourJ = history.get(i).getBoules();
                List<Integer> tirageHier = history.get(i+1).getBoules();

                if (calculerEtatAbstrait(tirageHier) == etatPrecedent) {
                    totalTransitions++;
                    if (calculerEtatAbstrait(tirageJourJ) == etatCandidat) {
                        transitionsCibles++;
                    }
                }
            }

            if (totalTransitions == 0) return 0.0;

            // Renvoie une probabilité (ex: 0.25 si cet enchaînement arrive 1 fois sur 4)
            return (double) transitionsCibles / totalTransitions;
        }

        /**
         * ULTRA-RAPIDE : Convertit une grille en un masque de 64 bits (long)
         * Numéro 1 = Bit 1, Numéro 49 = Bit 49.
         */
        private long calculerBitMask(List<Integer> boules) {
            long mask = 0L;
            for (Integer b : boules) {
                mask |= (1L << b); // Allume le bit correspondant au numéro
            }
            return mask;
        }
    }
