package com.analyseloto.loto.service;

import com.analyseloto.loto.dto.*;
import com.analyseloto.loto.entity.LotoTirage;
import com.analyseloto.loto.entity.LotoTirageRank;
import com.analyseloto.loto.entity.User;
import com.analyseloto.loto.entity.UserBet;
import com.analyseloto.loto.repository.LotoTirageRepository;
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
    // Services
    private final AstroService astroService;
    private final BacktestService backtestService;
    // Variable de classe pour stocker la meilleure config en mémoire (Cache simple)
    private AlgoConfig cachedBestConfig = null;
    private LocalDate lastBacktestDate = null;

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
        private double poidsMarkov;       // NOUVEAU : Poids des chaînes de Markov
        private boolean utiliserGenetique; // NOUVEAU : Activer l'algo génétique ?

        // 1. Standard (Mix équilibré)
        public static AlgoConfig defaut() {
            return new AlgoConfig("1_STANDARD", 3.0, 15.0, 0.4, 12.0, 5.0, false);
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

    /**
     * Génération de N pronostics optimisés (sans astro)
     * @param dateCible date tirage
     * @param nombreGrilles nombre grilles à générer
     * @return liste des pronostics
     */
    public List<PronosticResultDto> genererMultiplesPronostics(LocalDate dateCible, int nombreGrilles) {
        return genererPronosticAvecConfig(dateCible, nombreGrilles, null);
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

        // 1. Initialisation optimisée (Trié par la BDD directement)
        List<LotoTirage> history = repository.findAll(Sort.by(Sort.Direction.DESC, "dateTirage"));
        if (history.isEmpty()) return new ArrayList<>();

        List<Integer> dernierTirage = history.get(0).getBoules();

        // --- OPTIMISATION INTELLIGENTE (CACHE) ---
        AlgoConfig configOptimisee;

        // 1. Si on a déjà une config du jour en cache, on l'utilise
        if (cachedBestConfig != null && LocalDate.now().equals(lastBacktestDate)) {
            configOptimisee = cachedBestConfig;
        }
        // 2. Sinon, on utilise la config par défaut (pour ne pas bloquer l'utilisateur 10s)
        else {
            // Petit log pour dire qu'on est en mode dégradé temporaire
            if (cachedBestConfig == null) {
                log.info("⏳ [ALGO] Backtest en cours ou non démarré. Utilisation Config PAR DÉFAUT en attendant.");
            } else {
                log.info("⚠️ [ALGO] Config périmée (date différente). Utilisation Config PAR DÉFAUT en attendant le CRON.");
                // Optionnel : on pourrait déclencher un refresh asynchrone ici si le CRON a raté
            }
            configOptimisee = AlgoConfig.defaut();
        }

        // --- LOG STRATÉGIQUE ---
        log.info("🎯 [ALGO] Stratégie utilisée : {}", configOptimisee.getNomStrategie());
        log.info("   ➤ Dernier tirage connu : {} (Date : {})", dernierTirage, history.get(0).getDateTirage());

        // 2. Calcul des Scores (Avec la config optimisée par l'IA)
        Set<Integer> hotFinales = detecterFinalesChaudes(history);
        List<Integer> boostNumbers = (profilAstro != null) ? astroService.getLuckyNumbersOnly(profilAstro) : Collections.emptyList();

        Map<Integer, Map<Integer, Integer>> matriceAffinites = construireMatriceAffinitesPonderee(history, dateCible.getDayOfWeek());
        Map<Integer, Map<Integer, Integer>> matriceChance = construireMatriceAffinitesChancePonderee(history, dateCible.getDayOfWeek());

        Map<Integer, Double> scoresBoules = calculerScores(history, 49, dateCible.getDayOfWeek(), false,
                boostNumbers, hotFinales, configOptimisee, dernierTirage);
        Map<Integer, Double> scoresChance = calculerScores(history, 10, dateCible.getDayOfWeek(), true,
                boostNumbers, Collections.emptySet(), configOptimisee, null);

        // 3. GÉNÉRATION DE MASSE (POOLING)
        List<GrilleCandidate> population = new ArrayList<>();
        int taillePopulation = 5000;
        Random rng = new Random();

        Map<String, List<Integer>> buckets = creerBuckets(scoresBoules);

        // Calcul des contraintes dynamiques (Pair/Impair, Suites...)
        DynamicConstraints contraintesDuJour = analyserContraintesDynamiques(history, dernierTirage);

        for (int i = 0; i < taillePopulation; i++) {
            List<Integer> boules;

            // 70% Intelligence (Buckets) / 30% Exploration (Hasard)
            if (rng.nextDouble() < 0.7) {
                boules = genererGrilleParAffinite(buckets, matriceAffinites, dernierTirage, history, rng);
            } else {
                boules = genererGrilleAleatoireSecours(rng);
            }

            // On vérifie la cohérence AVEC les contraintes dynamiques du jour
            // Optimisation : On le fait AVANT de calculer le fitness complet pour économiser du CPU
            if (estGrilleCoherente(boules, dernierTirage, contraintesDuJour)) {

                int chance = selectionnerChanceOptimisee(boules, scoresChance, matriceChance, rng);
                double fitness = calculerScoreFitness(boules, chance, scoresBoules, scoresChance, matriceAffinites, history, dernierTirage);

                population.add(new GrilleCandidate(boules, chance, fitness));
            }
        }

        // 4. SÉLECTION ÉLITISTE
        population.sort((g1, g2) -> Double.compare(g2.fitness, g1.fitness));

        // 5. CONSTRUCTION DU RÉSULTAT
        List<PronosticResultDto> resultats = new ArrayList<>();
        Set<List<Integer>> doublonsCheck = new HashSet<>();

        for (GrilleCandidate cand : population) {
            if (resultats.size() >= nombreGrilles) break;

            Collections.sort(cand.boules);
            if (doublonsCheck.contains(cand.boules)) continue;

            SimulationResultDto simu = simulerGrilleDetaillee(cand.boules, dateCible, history);
            double maxDuo = simu.getPairs().stream().mapToDouble(MatchGroup::getRatio).max().orElse(0.0);
            double maxTrio = simu.getTrios().stream().mapToDouble(MatchGroup::getRatio).max().orElse(0.0);
            boolean fullMatch = !simu.getQuintuplets().isEmpty();

            // Badge IA Dynamique
            String typeAlgo = (cand.fitness > 50.0) ? "IA_GENETIQUE ⭐" : "IA_FLEXIBLE ⚠️";

            // On loggue le top 3 des grilles retenues
            if (resultats.size() < 3) {
                log.info("🏆 [ALGO] Grille Retenue #{} : {} + {} (Fitness: {}, Algo: {})",
                        resultats.size() + 1, cand.boules, cand.chance,
                        String.format("%.2f", cand.fitness), typeAlgo);
            }

            resultats.add(new PronosticResultDto(
                    cand.boules,
                    cand.chance,
                    Math.round(cand.fitness * 100.0) / 100.0,
                    maxDuo,
                    maxTrio,
                    fullMatch,
                    typeAlgo
            ));

            doublonsCheck.add(cand.boules);
        }

        // Fallback (Rare)
        while (resultats.size() < nombreGrilles) {
            log.warn("⚠️ [ALGO] Pas assez de grilles valides trouvées ({} / {}). Passage en mode Secours (Hasard).", resultats.size(), nombreGrilles);

            // Génération de grilles aléatoires
            List<Integer> boulesSecours = genererGrilleAleatoireSecours(rng);
            Collections.sort(boulesSecours);

            // On évite les doublons même en secours
            if (!doublonsCheck.contains(boulesSecours)) {
                resultats.add(new PronosticResultDto(boulesSecours, 1, 0.0, 0.0, 0.0, false, "HASARD 🎲"));
                doublonsCheck.add(boulesSecours);
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
        // 1. Analyse Parité (Sur les 10 derniers tirages)
        // La moyenne théorique est 2.5 pairs par tirage.
        long totalPairsRecents = history.stream().limit(10)
                .flatMap(t -> t.getBoules().stream())
                .filter(n -> n % 2 == 0)
                .count();

        double moyenneRecente = totalPairsRecents / 10.0;

        int minP, maxP;
        // Si on a eu TROP de pairs récemment (> 2.8), on force les IMPAIRS
        if (moyenneRecente > 2.8) {
            minP = 1; maxP = 2; // On vise 1 ou 2 pairs max (donc 3 ou 4 impairs)
        }
        // Si on a eu TROP d'impairs (< 2.2), on force les PAIRS
        else if (moyenneRecente < 2.2) {
            minP = 3; maxP = 4; // On vise 3 ou 4 pairs
        }
        // Sinon, zone neutre équilibrée
        else {
            minP = 2; maxP = 3;
        }

        // 2. Analyse des Suites (Sur les 5 derniers tirages)
        // Est-ce qu'une suite (ex: 12-13) est sortie récemment ?
        boolean suiteRecente = false;
        for (int i = 0; i < Math.min(5, history.size()); i++) {
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

        // Si une suite est sortie récemment, on les interdit (trop rare pour sortir 2x).
        // Si aucune suite n'est sortie depuis 5 tours, on les autorise (la tension monte).
        boolean allowSuites = !suiteRecente;

        // 3. Interdiction stricte du dernier tirage (Anti-répétition immédiate)
        Set<Integer> forbidden = new HashSet<>();

        // RÈGLE : "ANTI-SURCHAUFFE"
        // Si un numéro est sorti 3 fois de suite (sur les 3 derniers tirages),
        // il est statistiquement "cramé". Il y a 99% de chance qu'il ne sorte pas une 4ème fois.
        // On l'ajoute aux interdits.
        if (history.size() >= 3) {
            List<Integer> t1 = history.get(0).getBoules(); // Dernier
            List<Integer> t2 = history.get(1).getBoules(); // Avant-dernier
            List<Integer> t3 = history.get(2).getBoules(); // Ante-pénultième

            for (Integer n : t1) {
                if (t2.contains(n) && t3.contains(n)) {
                    forbidden.add(n); // Hop, interdit de jouer ce numéro ce soir
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
            List<Integer> dernierTirage) {
        double score = 0.0;

        // 1. Somme des scores individuels
        for (Integer b : boules) {
            score += scoresBoules.getOrDefault(b, 0.0);
        }
        score += scoresChance.getOrDefault(chance, 0.0);

        // 2. Cohésion de groupe (Affinités)
        double scoreAffinite = 0;
        for (int i = 0; i < boules.size(); i++) {
            for (int j = i + 1; j < boules.size(); j++) {
                scoreAffinite += affinites.getOrDefault(boules.get(i), Map.of()).getOrDefault(boules.get(j), 0);
            }
        }
        score += (scoreAffinite * 0.5);

        // 3. BONUS / MALUS STRUCTURELS

        // Pairs / Impairs
        long pairs = boules.stream().filter(n -> n % 2 == 0).count();
        if (pairs == 2 || pairs == 3) score += 20.0;

        // Somme
        int somme = boules.stream().mapToInt(Integer::intValue).sum();
        if (somme >= 130 && somme <= 160) score += 15.0;

        // Suites
        Collections.sort(boules);
        int suites = 0;
        for(int k=0; k<boules.size()-1; k++) {
            if(boules.get(k+1) == boules.get(k) + 1) suites++;
        }
        if(suites > 1) score -= 30.0;

        // Répétition immédiate (Dernier tirage)
        long communsDernier = boules.stream().filter(dernierTirage::contains).count();
        if(communsDernier > 1) score -= 50.0;

        if (scoreAffinite < 5.0) score -= 20.0;

        // --- 4. ANALYSE HISTORIQUE (NOUVEAU) ---
        // On suppose que 'history' est trié du plus récent au plus ancien

        int profondeurVerif = Math.min(history.size(), 500); // On regarde les 500 derniers tirages pour la perf

        for (int i = 0; i < profondeurVerif; i++) {
            LotoTirage t = history.get(i);
            List<Integer> bHist = t.getBoules();

            // Compte les numéros communs entre ma grille candidate et ce vieux tirage
            long communs = boules.stream().filter(bHist::contains).count();

            // A. PÉNALITÉ DOUBLON EXACT (5 numéros)
            // Si cette grille est déjà sortie, on la tue. On cherche l'inédit.
            if (communs == 5) {
                score -= 200.0; // Disqualification quasi-totale
                break; // Pas la peine de continuer
            }

            // B. PÉNALITÉ RÉPÉTITION RÉCENTE (4 numéros)
            // Si on a 4 numéros en commun avec un tirage d'il y a moins de 20 tours
            if (communs >= 4 && i < 20) {
                score -= 100.0; // Très improbable que ça retombe si vite
            }

            // C. PÉNALITÉ RÉPÉTITION TROP FRÉQUENTE (3 numéros)
            // Si on a 3 numéros en commun avec le tirage d'il y a 2 jours
            if (communs >= 3 && i < 5) {
                score -= 20.0;
            }
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
            List<LotoTirage> history, // Historique requis pour les trios
            Random rng) {
        List<Integer> selection = new ArrayList<>();

        // --- STRATÉGIE 1 : GOLDEN TRIO (50% de chance) ---
        // On tente de démarrer directement avec 3 numéros qui vont bien ensemble
        if (rng.nextBoolean()) {
            List<List<Integer>> topTrios = getTopTriosRecents(history);

            if (!topTrios.isEmpty()) {
                // On essaie jusqu'à 3 fois de trouver un trio valide (anti-répétition)
                for (int tryTrio = 0; tryTrio < 3; tryTrio++) {
                    List<Integer> trioChoisi = topTrios.get(rng.nextInt(topTrios.size()));

                    // Vérification Anti-Répétition
                    long communs = trioChoisi.stream().filter(dernierTirage::contains).count();

                    if (communs < 2) {
                        selection.addAll(trioChoisi);
                        break;
                    }
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
     * Création des buckets Hot, Cold, Neutral pour trier les numéros
     * @param scores scores des numéros
     * @return Map des buckets
     */
    private Map<String, List<Integer>> creerBuckets(Map<Integer, Double> scores) {
        // 1. Conversion et Tri
        List<Map.Entry<Integer, Double>> list = new ArrayList<>(scores.entrySet());
        list.sort((e1, e2) -> e2.getValue().compareTo(e1.getValue())); // Tri Décroissant

        Map<String, List<Integer>> buckets = new HashMap<>();
        int total = list.size();

        // Sécurité : Si pas assez de données, on renvoie vide
        if (total < 10) {
            buckets.put(Constantes.BUCKET_HOT, new ArrayList<>());
            buckets.put(Constantes.BUCKET_NEUTRAL, new ArrayList<>());
            buckets.put(Constantes.BUCKET_COLD, new ArrayList<>());
            return buckets;
        }

        // 2. Calcul dynamique des tailles (Règle du Quartile)
        // Pour 49 numéros : taille = 12.
        int tailleHotCold = total / 4;

        // 3. Découpage avec subList (Beaucoup plus rapide et lisible)
        // HOT : Le premier quart (ex: 0 à 12)
        List<Integer> hotList = list.subList(0, tailleHotCold).stream()
                .map(Map.Entry::getKey).toList();

        // COLD : Le dernier quart (ex: 37 à 49)
        List<Integer> coldList = list.subList(total - tailleHotCold, total).stream()
                .map(Map.Entry::getKey).toList();

        // NEUTRAL : Tout le reste au milieu (ex: 12 à 37)
        List<Integer> neutralList = list.subList(tailleHotCold, total - tailleHotCold).stream()
                .map(Map.Entry::getKey).toList();

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

        return true;
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
        List<LotoTirage> all = repository.findAll();
        if (jourFiltre != null && !jourFiltre.isEmpty()) {
            try { all = all.stream().filter(t -> t.getDateTirage().getDayOfWeek() == DayOfWeek.valueOf(jourFiltre.toUpperCase())).toList(); } catch (Exception e) {}
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
        return new StatsReponse(stats, minDate.format(fmt), maxDate.format(fmt), all.size());
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
     * Construction optimisée de la matrice d'affinités pondérée
     * @param history historique des tirages
     * @param jourCible jour de la semaine cible pour le pondération
     * @return matrice d'affinités pondérée
     */
    private Map<Integer, Map<Integer, Integer>> construireMatriceAffinitesPonderee(List<LotoTirage> history, DayOfWeek jourCible) {
        // 1. Initialisation optimisée (Capacity 64 pour éviter le resizing car 49 numéros)
        Map<Integer, Map<Integer, Integer>> matrix = new HashMap<>(64);
        for (int i = 1; i <= 49; i++) {
            matrix.put(i, new HashMap<>(64));
        }

        // 2. Une seule boucle pour tout faire
        for (LotoTirage t : history) {
            // Calcul du poids dynamique : 1 par défaut, +5 bonus si c'est le jour cible
            // Donc total = 6 pour le jour cible, 1 pour les autres.
            int poids = (t.getDateTirage().getDayOfWeek() == jourCible) ? 6 : 1;

            List<Integer> b = t.getBoules();
            int nbBoules = b.size();

            // Double boucle pour les paires (ex: 5 boules = 10 paires)
            for (int i = 0; i < nbBoules; i++) {
                Integer n1 = b.get(i);
                for (int j = i + 1; j < nbBoules; j++) {
                    Integer n2 = b.get(j);

                    // Mise à jour symétrique (A vers B et B vers A)
                    matrix.get(n1).merge(n2, poids, Integer::sum);
                    matrix.get(n2).merge(n1, poids, Integer::sum);
                }
            }
        }
        return matrix;
    }

    /**
     * Construction optimisée de la matrice d'affinités entre boules et numéros chance avec pondération
     * @param history historique des tirages
     * @param jourCible jour de la semaine cible pour la pondération
     * @return matrice d'affinités entre boules et numéros chance pondérée
     */
    private Map<Integer, Map<Integer, Integer>> construireMatriceAffinitesChancePonderee(List<LotoTirage> history, DayOfWeek jourCible) {
        // 1. Initialisation optimisée
        // Capacité 64 pour les 49 boules principales
        Map<Integer, Map<Integer, Integer>> matrix = new HashMap<>(64);
        for (int i = 1; i <= 49; i++) {
            // Capacité 16 pour les 10 numéros chance (évite le resizing)
            matrix.put(i, new HashMap<>(16));
        }

        // 2. Une seule boucle pour tout traiter
        for (LotoTirage t : history) {
            // Poids dynamique : 6 si c'est le jour cible (1 base + 5 bonus), sinon 1
            int poids = (t.getDateTirage().getDayOfWeek() == jourCible) ? 6 : 1;

            int chance = t.getNumeroChance();
            List<Integer> boules = t.getBoules();

            // On associe chaque boule du tirage au numéro chance
            for (Integer boule : boules) {
                matrix.get(boule).merge(chance, poids, Integer::sum);
            }
        }

        return matrix;
    }

    /**
     * Méthode spéciale pour générer des grilles lors de simulations
     * @param historiqueSimule historique simulé
     * @param config configuration de l'algorithme
     * @param nbGrilles nombre de grilles à générer
     * @return liste des grilles générées
     */
    public List<List<Integer>> genererGrillesPourSimulation(List<LotoTirage> historiqueSimule, AlgoConfig config, int nbGrilles) {
        if (historiqueSimule.isEmpty()) return new ArrayList<>();

        // 1. On récupère le dernier tirage de cet historique simulé
        List<Integer> dernierTirage = historiqueSimule.get(0).getBoules();
        LocalDate dateVirtuelle = historiqueSimule.get(0).getDateTirage().plusDays(2); // Date approx du prochain

        // 2. On prépare les données comme dans la méthode principale
        Set<Integer> hotFinales = detecterFinalesChaudes(historiqueSimule);

        Map<Integer, Map<Integer, Integer>> matriceAffinites = construireMatriceAffinitesPonderee(historiqueSimule, dateVirtuelle.getDayOfWeek());
        Map<Integer, Map<Integer, Integer>> matriceChance = construireMatriceAffinitesChancePonderee(historiqueSimule, dateVirtuelle.getDayOfWeek());

        // 3. Scores
        Map<Integer, Double> scoresBoules = calculerScores(historiqueSimule, 49, dateVirtuelle.getDayOfWeek(), false, Collections.emptyList(), hotFinales, config, dernierTirage);
        Map<Integer, Double> scoresChance = calculerScores(historiqueSimule, 10, dateVirtuelle.getDayOfWeek(), true, Collections.emptyList(), Collections.emptySet(), config, null);

        // 4. Analyse Dynamique (Nouvelle méthode !)
        DynamicConstraints contraintes = analyserContraintesDynamiques(historiqueSimule, dernierTirage);

        // 5. Génération rapide (Copie simplifiée de la boucle de population)
        List<List<Integer>> resultats = new ArrayList<>();
        Random rng = new Random();
        Map<String, List<Integer>> buckets = creerBuckets(scoresBoules);

        int essais = 0;
        while(resultats.size() < nbGrilles && essais < 1000) {
            essais++;
            List<Integer> boules = genererGrilleParAffinite(buckets, matriceAffinites, dernierTirage, historiqueSimule, rng);

            // Validation avec règles dynamiques
            if (estGrilleCoherente(boules, dernierTirage, contraintes)) {
                // On vérifie doublon interne
                boolean doublon = resultats.stream().anyMatch(r -> new HashSet<>(r).containsAll(boules));
                if (!doublon) {
                    Collections.sort(boules);
                    resultats.add(boules);
                }
            }
        }
        return resultats;
    }

    /**
     * Méthode appelée par le scheduler pour forcer l'optimisation quotidienne
     */
    public void forceDailyOptimization() {
        log.info("🌙 [CRON] Lancement de l'optimisation nocturne des poids...");
        long start = System.currentTimeMillis();

        // On récupère l'historique complet
        List<LotoTirage> history = repository.findAll(Sort.by(Sort.Direction.DESC, "dateTirage"));

        if (!history.isEmpty()) {
            // Calcul lourd
            AlgoConfig newConfig = backtestService.trouverMeilleureConfig(history);

            // Mise à jour atomique (thread-safe)
            this.cachedBestConfig = newConfig;
            this.lastBacktestDate = LocalDate.now();

            log.info("✅ [CRON] Stratégie mise à jour en {} ms ! Nouvelle Config : {}",
                    (System.currentTimeMillis() - start), newConfig);
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
}
