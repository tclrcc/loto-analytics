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
    private final LotoTirageRepository repository;
    private final AstroService astroService;
    private final UserBetRepository betRepository;

    // --- CONFIGURATION DYNAMIQUE (A/B TESTING) ---
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

    public List<PronosticResultDto> genererMultiplesPronostics(LocalDate dateCible, int nombreGrilles, boolean exclureHasard) {
        return genererPronosticAvecConfig(dateCible, nombreGrilles, null, AlgoConfig.defaut(), exclureHasard);
    }

    public List<PronosticResultDto> genererPronosticsHybrides(LocalDate dateCible, int nombreGrilles, AstroProfileDto profil, boolean exclureHasard) {
        return genererPronosticAvecConfig(dateCible, nombreGrilles, profil, AlgoConfig.defaut(), exclureHasard);
    }

    /**
     * Cœur du réacteur : Génération massive + Sélection élitiste (Fitness)
     */
    private List<PronosticResultDto> genererPronosticAvecConfig(LocalDate dateCible, int nombreGrilles, AstroProfileDto profilAstro,
            AlgoConfig config, boolean exclureHasard) {

        // 1. Initialisation des données
        List<LotoTirage> history = repository.findAll();
        if (history.isEmpty()) return new ArrayList<>();

        // On trie l'historique (Plus récent -> Plus vieux)
        history.sort(Comparator.comparing(LotoTirage::getDateTirage).reversed());
        List<Integer> dernierTirage = history.get(0).getBoules();

        // 2. Calcul des Scores Individuels (Poids de chaque boule)
        // ON INVERSE LA LOGIQUE ICI : On favorise la FORME (ceux qui sortent) plutôt que l'ECART (ceux qui dorment)
        Set<Integer> hotFinales = detecterFinalesChaudes(history);
        List<Integer> boostNumbers = (profilAstro != null) ? astroService.getLuckyNumbersOnly(profilAstro) : Collections.emptyList();

        // Matrices
        Map<Integer, Map<Integer, Integer>> matriceAffinites = construireMatriceAffinitesPonderee(history, dateCible.getDayOfWeek());
        Map<Integer, Map<Integer, Integer>> matriceChance = construireMatriceAffinitesChancePonderee(history, dateCible.getDayOfWeek());

        // On force un poids "Forme" plus élevé pour suivre la tendance (Trend Following)
        AlgoConfig configOptimisee = new AlgoConfig(config.getNomStrategie(),
                config.getPoidsFreqJour(),
                config.getPoidsForme() * 2.0, // On double l'importance de la forme
                config.getPoidsEcart() * 0.5, // On réduit l'importance de l'écart
                config.getPoidsTension(),
                config.getPoidsMarkov(),
                config.isUtiliserGenetique());

        Map<Integer, Double> scoresBoules = calculerScores(history, 49, dateCible.getDayOfWeek(), false, boostNumbers, hotFinales, configOptimisee, dernierTirage, null);
        Map<Integer, Double> scoresChance = calculerScores(history, 10, dateCible.getDayOfWeek(), true, boostNumbers, Collections.emptySet(), configOptimisee, null, null);

        // 3. GÉNÉRATION DE MASSE (POOLING)
        // On génère 5000 grilles candidates semi-aléatoires basées sur les scores
        List<GrilleCandidate> population = new ArrayList<>();
        int taillePopulation = 5000;

        Random rng = new Random();
        Map<String, List<Integer>> buckets = creerBuckets(scoresBoules);

        for (int i = 0; i < taillePopulation; i++) {
            List<Integer> boules;

            // 70% du temps on utilise la logique de buckets (intelligente)
            // 30% du temps on fait du hasard pur pour explorer d'autres pistes (Exploration vs Exploitation)
            if (rng.nextDouble() < 0.7) {
                boules = genererGrilleParAffinite(buckets, matriceAffinites, dernierTirage, history, rng);
            } else {
                boules = genererGrilleAleatoireSecours(rng);
            }

            // On associe un numéro chance optimisé à cette grille
            int chance = selectionnerChanceOptimisee(boules, scoresChance, matriceChance, rng);

            // On évalue la grille immédiatement avec la fonction de Fitness
            double fitness = calculerScoreFitness(boules, chance, scoresBoules, scoresChance, matriceAffinites, history, dernierTirage);

            // On ajoute à la population si elle est techniquement valide (somme, parité...)
            // On est permissif ici (estGrilleCoherente basique) car le fitness fera le tri final
            if (estGrilleCoherente(boules, null)) {
                population.add(new GrilleCandidate(boules, chance, fitness));
            }
        }

        // 4. SÉLECTION DES MEILLEURES (ELITISM)
        // On trie par score de fitness décroissant (Les meilleures en haut)
        population.sort((g1, g2) -> Double.compare(g2.fitness, g1.fitness));

        // 5. CONSTRUCTION DU RÉSULTAT
        // On prend les N meilleures UNIQUES
        List<PronosticResultDto> resultats = new ArrayList<>();
        Set<List<Integer>> doublonsCheck = new HashSet<>();

        for (GrilleCandidate cand : population) {
            if (resultats.size() >= nombreGrilles) break;

            Collections.sort(cand.boules);
            if (doublonsCheck.contains(cand.boules)) continue;

            // Simulation pour l'affichage stats
            SimulationResultDto simu = simulerGrilleDetaillee(cand.boules, dateCible, history);
            double avgDuo = simu.getPairs().stream().mapToDouble(MatchGroup::getRatio).average().orElse(0.0);
            double maxDuo = simu.getPairs().stream().mapToDouble(MatchGroup::getRatio).max().orElse(0.0);
            double maxTrio = simu.getTrios().stream().mapToDouble(MatchGroup::getRatio).max().orElse(0.0);
            boolean fullMatch = !simu.getQuintuplets().isEmpty();

            // Détermination du type d'algo (Pour l'affichage frontend)
            String typeAlgo = "IA_OPTIMAL ⭐";
            if (cand.fitness < 50.0) typeAlgo = "IA_FLEXIBLE ⚠️";

            resultats.add(new PronosticResultDto(
                    cand.boules,
                    cand.chance,
                    Math.round(cand.fitness * 100.0) / 100.0, // On utilise le fitness comme score global
                    maxDuo,
                    maxTrio,
                    fullMatch,
                    typeAlgo
            ));

            doublonsCheck.add(cand.boules);
        }

        // Si on n'a pas assez de grilles (très rare avec 5000 candidats), on comble
        while (resultats.size() < nombreGrilles && !exclureHasard) {
            List<Integer> boulesSecours = genererGrilleAleatoireSecours(rng);
            Collections.sort(boulesSecours);
            if (!doublonsCheck.contains(boulesSecours)) {
                resultats.add(new PronosticResultDto(boulesSecours, 1, 0.0, 0.0, 0.0, false, "HASARD 🎲"));
                doublonsCheck.add(boulesSecours);
            }
        }

        return resultats;
    }

    // Petite classe interne pour faciliter le tri de la population
    @AllArgsConstructor
    private static class GrilleCandidate {
        List<Integer> boules;
        int chance;
        double fitness;
    }

    /**
     * Nouvelle fonction de Fitness (Score de Pertinence)
     * Note une grille de 0 à 100+ selon sa qualité statistique.
     */
    private double calculerScoreFitness(List<Integer> boules, int chance,
            Map<Integer, Double> scoresBoules,
            Map<Integer, Double> scoresChance,
            Map<Integer, Map<Integer, Integer>> affinites,
            List<LotoTirage> history,
            List<Integer> dernierTirage) {
        double score = 0.0;

        // 1. Somme des scores individuels (Est-ce que les numéros sont "Chauds" ?)
        for (Integer b : boules) {
            score += scoresBoules.getOrDefault(b, 0.0);
        }
        score += scoresChance.getOrDefault(chance, 0.0);

        // 2. Cohésion de groupe (Affinités)
        // On vérifie si ces numéros ont l'habitude de sortir ensemble
        double scoreAffinite = 0;
        for (int i = 0; i < boules.size(); i++) {
            for (int j = i + 1; j < boules.size(); j++) {
                scoreAffinite += affinites.getOrDefault(boules.get(i), Map.of()).getOrDefault(boules.get(j), 0);
            }
        }
        // On normalise un peu pour ne pas que l'affinité écrase tout
        score += (scoreAffinite * 0.5);

        // 3. BONUS / MALUS STRUCTURELS

        // Bonus : Équilibre Pair/Impair parfait (2/3 ou 3/2)
        long pairs = boules.stream().filter(n -> n % 2 == 0).count();
        if (pairs == 2 || pairs == 3) score += 20.0;

        // Bonus : Somme comprise dans la "Golden Zone" (120-170)
        int somme = boules.stream().mapToInt(Integer::intValue).sum();
        if (somme >= 130 && somme <= 160) score += 15.0;

        // Malus : Trop de suites (ex: 12, 13, 14)
        Collections.sort(boules);
        int suites = 0;
        for(int k=0; k<boules.size()-1; k++) {
            if(boules.get(k+1) == boules.get(k) + 1) suites++;
        }
        if(suites > 1) score -= 30.0; // On pénalise fortement

        // Malus : Répétition du dernier tirage (On veut éviter de rejouer 3 numéros d'hier)
        long communs = boules.stream().filter(dernierTirage::contains).count();
        if(communs > 1) score -= 50.0;

        // Malus : Numéros jamais sortis ensemble
        // Si le scoreAffinite est très bas, c'est que c'est une combinaison "Alien", on évite
        if (scoreAffinite < 5.0) score -= 20.0;

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

    private Map<Integer, Double> calculerScores(List<LotoTirage> history, int maxNum, DayOfWeek jourCible,
            boolean isChance, List<Integer> boostNumbers,
            Set<Integer> hotFinales, AlgoConfig config,
            List<Integer> dernierTirage,
            Map<Integer, Map<Integer, Integer>> matriceMarkov) {
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

            // 6. Intégration Markov (Probabilité de suite logique)
            if (!isChance && dernierTirage != null && matriceMarkov != null) {
                double scoreMarkov = 0;
                for (Integer prev : dernierTirage) {
                    scoreMarkov += matriceMarkov.getOrDefault(prev, Map.of()).getOrDefault(num, 0);
                }
                score += (scoreMarkov * config.getPoidsMarkov());
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

    private Integer selectionnerParAffinite(List<Integer> candidats, List<Integer> selectionActuelle, Map<Integer, Map<Integer, Integer>> matrice, Random rng) {
        Map<Integer, Double> scoresCandidats = new HashMap<>();
        for (Integer candidat : candidats) {
            double scoreLien = 1.0;
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
        return scoresCandidats.entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse(candidats.get(0));
    }

    private String determinerBucketCible(List<Integer> selection, Map<String, List<Integer>> buckets) {
        long nbHot = selection.stream().filter(n -> buckets.getOrDefault(Constantes.BUCKET_HOT, List.of()).contains(n)).count();
        if (nbHot < 2) return Constantes.BUCKET_HOT;
        return Constantes.BUCKET_NEUTRAL;
    }

    private Map<String, List<Integer>> creerBuckets(Map<Integer, Double> scores) {
        List<Map.Entry<Integer, Double>> list = new ArrayList<>(scores.entrySet());
        list.sort((e1, e2) -> e2.getValue().compareTo(e1.getValue()));
        Map<String, List<Integer>> buckets = new HashMap<>();
        if (list.size() >= 24) {
            buckets.put(Constantes.BUCKET_HOT, list.stream().limit(12).map(Map.Entry::getKey).toList());
            buckets.put(Constantes.BUCKET_COLD, list.stream().skip(list.size() - 12).map(Map.Entry::getKey).toList());
            buckets.put(Constantes.BUCKET_NEUTRAL, list.stream().skip(12).limit(list.size() - 24).map(Map.Entry::getKey).toList());
        } else {
            buckets.put(Constantes.BUCKET_HOT, new ArrayList<>()); buckets.put(Constantes.BUCKET_COLD, new ArrayList<>()); buckets.put(Constantes.BUCKET_NEUTRAL, new ArrayList<>());
        }
        return buckets;
    }

    private Set<Integer> detecterFinalesChaudes(List<LotoTirage> history) {
        return history.stream().sorted(Comparator.comparing(LotoTirage::getDateTirage).reversed()).limit(20)
                .flatMap(t -> t.getBoules().stream()).map(b -> b % 10)
                .collect(Collectors.groupingBy(f -> f, Collectors.counting()))
                .entrySet().stream().sorted((e1, e2) -> Long.compare(e2.getValue(), e1.getValue()))
                .limit(2).map(Map.Entry::getKey).collect(Collectors.toSet());
    }

    public Map<Integer, Map<Integer, Integer>> getMatriceAffinitesPublic() {
        return construireMatriceAffinitesPonderee(repository.findAll(), LocalDate.now().getDayOfWeek());
    }

    private boolean estGrilleCoherente(List<Integer> boules, List<Integer> dernierTirage) {
        if (boules == null || boules.size() != 5) return false;
        List<Integer> s = boules.stream().sorted().toList();

        // 1. Somme (Resserrer la courbe de Gauss)
        int sum = s.stream().mapToInt(Integer::intValue).sum();
        if (sum < 100 || sum > 175) return false;

        // 2. Parité
        long pairs = s.stream().filter(n -> n % 2 == 0).count();
        if (pairs < 2 || pairs > 3) return false;

        // 3. Dizaines (Répartition spatiale)
        long diz = s.stream().map(n -> n / 10).distinct().count();
        if (diz < 3) return false;

        // 4. Suites (Numéros consécutifs)
        int consecutiveCount = 0;
        for (int i = 0; i < s.size() - 1; i++) {
            if (s.get(i + 1) == s.get(i) + 1) {
                consecutiveCount++;
            } else {
                consecutiveCount = 0;
            }
            if (consecutiveCount >= 2) return false;
        }

        // Si 2 chiffres minimum lors du dernier tirage sont repris, on rejette aussi
        // (Seulement si dernierTirage est fourni)
        if (dernierTirage != null && !dernierTirage.isEmpty()) {
            long communs = s.stream().filter(dernierTirage::contains).count();
            if (communs >= 2) return false;
        }

        // 5. Finales
        Map<Integer, Long> finales = s.stream()
                .collect(Collectors.groupingBy(n -> n % 10, Collectors.counting()));

        return finales.values().stream().noneMatch(count -> count > 2);
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

    private Map<Integer, Map<Integer, Integer>> construireMatriceAffinitesPonderee(List<LotoTirage> history, DayOfWeek jourCible) {
        Map<Integer, Map<Integer, Integer>> matrix = new HashMap<>();
        for (int i = 1; i <= 49; i++) matrix.put(i, new HashMap<>());
        for (LotoTirage t : history) {
            List<Integer> b = t.getBoules();
            for (int i = 0; i < b.size(); i++) {
                for (int j = i + 1; j < b.size(); j++) {
                    matrix.get(b.get(i)).merge(b.get(j), 1, Integer::sum);
                    matrix.get(b.get(j)).merge(b.get(i), 1, Integer::sum);
                }
            }
        }
        List<LotoTirage> historiqueJour = history.stream().filter(t -> t.getDateTirage().getDayOfWeek() == jourCible).toList();
        for (LotoTirage t : historiqueJour) {
            List<Integer> b = t.getBoules();
            for (int i = 0; i < b.size(); i++) {
                for (int j = i + 1; j < b.size(); j++) {
                    matrix.get(b.get(i)).merge(b.get(j), 5, Integer::sum);
                    matrix.get(b.get(j)).merge(b.get(i), 5, Integer::sum);
                }
            }
        }
        return matrix;
    }

    private Map<Integer, Map<Integer, Integer>> construireMatriceAffinitesChancePonderee(List<LotoTirage> history, DayOfWeek jourCible) {
        Map<Integer, Map<Integer, Integer>> matrix = new HashMap<>();
        for (int i = 1; i <= 49; i++) matrix.put(i, new HashMap<>());
        for (LotoTirage t : history) {
            int chance = t.getNumeroChance();
            for (Integer boule : t.getBoules()) matrix.get(boule).merge(chance, 1, Integer::sum);
        }
        List<LotoTirage> historiqueJour = history.stream().filter(t -> t.getDateTirage().getDayOfWeek() == jourCible).toList();
        for (LotoTirage t : historiqueJour) {
            int chance = t.getNumeroChance();
            for (Integer boule : t.getBoules()) matrix.get(boule).merge(chance, 5, Integer::sum);
        }
        return matrix;
    }
}
