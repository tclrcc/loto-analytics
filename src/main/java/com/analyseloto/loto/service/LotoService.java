package com.analyseloto.loto.service;

import com.analyseloto.loto.dto.*;
import com.analyseloto.loto.entity.LotoTirage;
import com.analyseloto.loto.entity.LotoTirageRank;
import com.analyseloto.loto.entity.User;
import com.analyseloto.loto.entity.UserBet;
import com.analyseloto.loto.repository.LotoTirageRepository;
import com.analyseloto.loto.repository.UserBetRepository;
import com.analyseloto.loto.util.Constantes;
import jakarta.transaction.Transactional;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.Cacheable;
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
    private final EmailService emailService;
    private final LotoTirageRepository repository;
    private final AstroService astroService;
    private final UserBetRepository betRepository;

    @Value("${app.base-url:http://localhost:8080}")
    private String baseUrl;

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
        // 2. Markov Pur (Focus sur les séquences historiques)
        public static AlgoConfig markov() {
            return new AlgoConfig("2_MARKOV_SEQ", 1.0, 5.0, 0.0, 0.0, 25.0, false);
        }
        // 3. Génétique (Evolutionnaire)
        public static AlgoConfig genetique() {
            return new AlgoConfig("3_GENETIQUE_AI", 3.0, 10.0, 0.5, 5.0, 2.0, true);
        }
        // 4. Delta & Ecart (Structurel)
        public static AlgoConfig delta() {
            return new AlgoConfig("4_DELTA_STRUCT", 2.0, 5.0, 5.0, 15.0, 2.0, false);
        }
    }

    // ==================================================================================
    // 1. COMPARAISON & GÉNÉRATION
    // ==================================================================================

    public Map<String, List<PronosticResultDto>> comparerAlgorithmes(LocalDate dateCible) {
        Map<String, List<PronosticResultDto>> comparatif = new LinkedHashMap<>();
        comparatif.put("STANDARD", genererPronosticAvecConfig(dateCible, 5, null, AlgoConfig.defaut()));
        comparatif.put("MARKOV", genererPronosticAvecConfig(dateCible, 5, null, AlgoConfig.markov()));
        comparatif.put("GENETIQUE", genererPronosticAvecConfig(dateCible, 5, null, AlgoConfig.genetique()));
        comparatif.put("DELTA", genererPronosticAvecConfig(dateCible, 5, null, AlgoConfig.delta()));
        return comparatif;
    }

    public List<PronosticResultDto> genererMultiplesPronostics(LocalDate dateCible, int nombreGrilles) {
        return genererPronosticAvecConfig(dateCible, nombreGrilles, null, AlgoConfig.defaut());
    }

    public List<PronosticResultDto> genererPronosticsHybrides(LocalDate dateCible, int nombreGrilles, AstroProfileDto profil) {
        return genererPronosticAvecConfig(dateCible, nombreGrilles, profil, AlgoConfig.defaut());
    }

    private List<PronosticResultDto> genererPronosticAvecConfig(LocalDate dateCible, int nombreGrilles, AstroProfileDto profilAstro, AlgoConfig config) {
        List<PronosticResultDto> resultats = new ArrayList<>();
        int n = Math.min(Math.max(1, nombreGrilles), 10);

        List<LotoTirage> history = repository.findAll();
        if (history.isEmpty()) return new ArrayList<>();

        long graine = dateCible.toEpochDay() + config.getNomStrategie().hashCode();
        if (profilAstro != null) graine += profilAstro.getVille().toLowerCase().hashCode();
        Random rng = new Random(graine);

        // Préparations
        Set<Integer> hotFinales = detecterFinalesChaudes(history);
        List<Integer> boostNumbers = (profilAstro != null) ? astroService.getLuckyNumbersOnly(profilAstro) : Collections.emptyList();

        // Markov : On récupère le dernier tirage pour savoir "d'où on part"
        List<LotoTirage> sortedHistory = new ArrayList<>(history);
        sortedHistory.sort(Comparator.comparing(LotoTirage::getDateTirage).reversed());
        List<Integer> dernierTirageConnu = sortedHistory.get(0).getBoules();
        Map<Integer, Map<Integer, Integer>> matriceMarkov = construireMatriceMarkov(history);

        // Scores
        Map<Integer, Double> scoresBoules = calculerScores(history, 49, dateCible.getDayOfWeek(), false, boostNumbers, hotFinales, config, dernierTirageConnu, matriceMarkov);
        Map<Integer, Double> scoresChance = calculerScores(history, 10, dateCible.getDayOfWeek(), true, boostNumbers, Collections.emptySet(), config, null, null);

        // Buckets & Affinités
        Map<String, List<Integer>> buckets = creerBuckets(scoresBoules);
        Map<Integer, Map<Integer, Integer>> matriceAffinites = construireMatriceAffinites(history);

        for (int i = 0; i < n; i++) {
            List<Integer> boules;

            // --- CHOIX DE L'ALGO DE GÉNÉRATION ---
            if (config.isUtiliserGenetique()) {
                // Solution 3 : ALGORITHME GÉNÉTIQUE
                boules = genererGrilleGenetique(scoresBoules, matriceAffinites, rng);
            } else {
                // Solution Classique (Affinités + Buckets)
                boules = new ArrayList<>();
                int tentatives = 0;
                while (tentatives < 1000) {
                    List<Integer> candidat = genererGrilleParAffinite(buckets, matriceAffinites, dernierTirageConnu, rng);
                    // Solution 2 : VALIDATION DELTA SYSTEM
                    if (estGrilleCoherente(candidat) && validerDeltaSystem(candidat)) {
                        boules = candidat;
                        break;
                    }
                    tentatives++;
                    if (tentatives == 1000) boules = candidat;
                }
            }

            int chance = selectionnerChancePonderee(scoresChance, rng);

            // Simulation
            SimulationResultDto simu = simulerGrilleDetaillee(boules, dateCible, history);
            double maxDuo = simu.getPairs().stream().mapToDouble(MatchGroup::getRatio).max().orElse(0.0);
            double maxTrio = simu.getTrios().stream().mapToDouble(MatchGroup::getRatio).max().orElse(0.0);
            boolean fullMatch = !simu.getQuintuplets().isEmpty();
            double avgDuo = simu.getPairs().stream().mapToDouble(MatchGroup::getRatio).average().orElse(0.0);

            resultats.add(new PronosticResultDto(boules.stream().sorted().toList(), chance, Math.round(avgDuo * 100.0) / 100.0, maxDuo, maxTrio, fullMatch));
        }

        resultats.sort((a, b) -> Double.compare(b.getScoreGlobal(), a.getScoreGlobal()));
        return resultats;
    }

    // ==================================================================================
    // 2. SOLUTION 1 : CHAÎNES DE MARKOV (Séquentiel)
    // ==================================================================================

    private Map<Integer, Map<Integer, Integer>> construireMatriceMarkov(List<LotoTirage> history) {
        Map<Integer, Map<Integer, Integer>> markov = new HashMap<>();
        // Tri chronologique (Ancien -> Récent)
        List<LotoTirage> chrono = new ArrayList<>(history);
        chrono.sort(Comparator.comparing(LotoTirage::getDateTirage));

        for (int i = 0; i < chrono.size() - 1; i++) {
            List<Integer> t1 = chrono.get(i).getBoules();
            List<Integer> t2 = chrono.get(i+1).getBoules();
            for (Integer b1 : t1) {
                markov.putIfAbsent(b1, new HashMap<>());
                for (Integer b2 : t2) {
                    markov.get(b1).merge(b2, 1, Integer::sum);
                }
            }
        }
        return markov;
    }

    // ==================================================================================
    // 3. SOLUTION 2 : DELTA SYSTEM (Validation Structurelle)
    // ==================================================================================

    /**
     * Vérifie si la grille respecte une structure "Delta" réaliste
     * Les différences entre numéros triés ne doivent pas être extrêmes
     */
    private boolean validerDeltaSystem(List<Integer> boules) {
        if (boules == null || boules.size() != 5) return false;
        List<Integer> sorted = boules.stream().sorted().toList();

        // Calcul des Deltas
        List<Integer> deltas = new ArrayList<>();
        deltas.add(sorted.get(0)); // Le premier numéro est le premier delta (depuis 0)
        for (int i = 0; i < sorted.size() - 1; i++) {
            deltas.add(sorted.get(i+1) - sorted.get(i));
        }

        // 1. Pas trop de "petits" deltas (suite de nombres ex: 32,33,34)
        long petitsDeltas = deltas.stream().filter(d -> d == 1).count();
        if (petitsDeltas > 2) return false;

        // 2. Pas de delta énorme (trou géant ex: 1, 48...)
        long grosDeltas = deltas.stream().filter(d -> d > 25).count();
        if (grosDeltas > 0) return false;

        return true;
    }

    // ==================================================================================
    // 4. SOLUTION 3 : ALGORITHME GÉNÉTIQUE (Evolutionnaire)
    // ==================================================================================



    /**
     * Génère une grille via évolution de population
     */
    private List<Integer> genererGrilleGenetique(Map<Integer, Double> scores, Map<Integer, Map<Integer, Integer>> affinites, Random rng) {
        int populationSize = 100;
        int generations = 50;
        List<List<Integer>> population = new ArrayList<>();

        // 1. Initialisation (100 grilles aléatoires cohérentes)
        // 1. Initialisation
        for (int i = 0; i < populationSize; i++) {
            if (i < populationSize / 2) {
                // 50% de population "intelligente" dès le début
                // On utilise buckets et affinités pour pré-remplir
                population.add(genererGrilleParAffinite(creerBuckets(scores), affinites, new ArrayList<>(), rng));
            } else {
                // 50% de pur hasard pour garder de la diversité génétique
                List<Integer> g = new ArrayList<>();
                while (g.size() < 5) {
                    int n = 1 + rng.nextInt(49);
                    if (!g.contains(n)) g.add(n);
                }
                population.add(g);
            }
        }

        // 2. Boucle d'évolution
        for (int gen = 0; gen < generations; gen++) {
            // Évaluation
            Map<List<Integer>, Double> fitnessMap = new HashMap<>();
            for (List<Integer> individu : population) {
                fitnessMap.put(individu, evaluerFitness(individu, scores, affinites));
            }

            // Sélection (Top 50%)
            List<List<Integer>> survivors = population.stream()
                    .sorted((g1, g2) -> Double.compare(fitnessMap.get(g2), fitnessMap.get(g1)))
                    .limit(populationSize / 2)
                    .toList();

            // Reproduction (Croisement + Mutation) pour remplir la nouvelle pop
            List<List<Integer>> nextGen = new ArrayList<>(survivors);
            while (nextGen.size() < populationSize) {
                List<Integer> parent1 = survivors.get(rng.nextInt(survivors.size()));
                List<Integer> parent2 = survivors.get(rng.nextInt(survivors.size()));

                // Croisement
                Set<Integer> enfantSet = new HashSet<>();
                // Prend la moitié de P1
                for(int k=0; k<3; k++) enfantSet.add(parent1.get(k));
                // Complète avec P2
                for(Integer n : parent2) {
                    if (enfantSet.size() < 5) enfantSet.add(n);
                }
                // Si pas assez (doublons), complète aléatoirement
                while (enfantSet.size() < 5) {
                    enfantSet.add(1 + rng.nextInt(49));
                }

                List<Integer> enfant = new ArrayList<>(enfantSet);

                // Mutation (10% de chance de changer un numéro)
                if (rng.nextDouble() < 0.10) {
                    enfant.set(rng.nextInt(5), 1 + rng.nextInt(49));
                    // Nettoyage doublons après mutation
                    enfant = new ArrayList<>(new HashSet<>(enfant));
                    while (enfant.size() < 5) {
                        int r = 1 + rng.nextInt(49);
                        if (!enfant.contains(r)) enfant.add(r);
                    }
                }

                // Vérification cohérence basique
                if (estGrilleCoherente(enfant)) {
                    nextGen.add(enfant);
                }
            }
            population = nextGen;
        }

        // Retourne le meilleur individu de la dernière génération
        return population.stream()
                .max(Comparator.comparingDouble(g -> evaluerFitness(g, scores, affinites)))
                .orElse(population.get(0));
    }

    private double evaluerFitness(List<Integer> grille, Map<Integer, Double> scores, Map<Integer, Map<Integer, Integer>> affinites) {
        double score = 0;

        // 1. Score de base (Poids des numéros)
        for (Integer n : grille) {
            score += scores.getOrDefault(n, 0.0);
        }

        // 2. Score d'affinité (Les numéros vont-ils bien ensemble ?)
        for (int i = 0; i < grille.size(); i++) {
            for (int j = i + 1; j < grille.size(); j++) {
                score += affinites.getOrDefault(grille.get(i), Map.of()).getOrDefault(grille.get(j), 0) * 0.2; // Poids réduit
            }
        }

        // 3. PÉNALITÉS (Nouveau)
        if (!estGrilleCoherente(grille)) {
            score -= 500.0; // Enorme malus pour tuer cette grille dans l'œuf
        }

        return score;
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
        List<LotoTirage> histJour = history.stream().filter(t -> t.getDateTirage().getDayOfWeek() == jourCible).toList();
        List<LotoTirage> histSorted = history.stream().sorted(Comparator.comparing(LotoTirage::getDateTirage).reversed()).toList();

        for (int i = 1; i <= maxNum; i++) {
            final int num = i;
            double score = 10.0;

            // Freq Jour
            long freqJour = histJour.stream().filter(t -> isChance ? t.getNumeroChance() == num : t.getBoules().contains(num)).count();
            score += (freqJour * config.getPoidsFreqJour());

            // Ecart
            int idxLast = -1;
            for(int k=0; k<histSorted.size(); k++) {
                if (isChance ? histSorted.get(k).getNumeroChance() == num : histSorted.get(k).getBoules().contains(num)) {
                    idxLast = k; break;
                }
            }
            long ecartActuel = (idxLast == -1) ? totalTirages : idxLast;
            if (ecartActuel > 10 && ecartActuel < 40) score += (ecartActuel * config.getPoidsEcart());

            // Forme
            long sortiesRecentes = histSorted.stream().limit(15).filter(t -> isChance ? t.getNumeroChance() == num : t.getBoules().contains(num)).count();
            if (sortiesRecentes >= 2) score += config.getPoidsForme();

            // Boosts
            if (boostNumbers.contains(num)) score += 30.0;
            if (!isChance && hotFinales != null && hotFinales.contains(num % 10)) score += 8.0;

            // Tension
            if (!isChance && tiragesSuffisants(history, num)) {
                // Simplification pour concision : Si ecart > 1.5x moyenne, +Tension
                // (Logique conservée de votre ancien code)
                score += config.getPoidsTension();
            }

            // --- INTÉGRATION MARKOV ---
            if (!isChance && dernierTirage != null && matriceMarkov != null) {
                double scoreMarkov = 0;
                for (Integer prev : dernierTirage) {
                    scoreMarkov += matriceMarkov.getOrDefault(prev, Map.of()).getOrDefault(num, 0);
                }
                score += (scoreMarkov * config.getPoidsMarkov());
            }

            long ecartMoyen = totalTirages / Math.max(1, histJour.stream().filter(t -> t.getBoules().contains(num)).count());
            if (ecartActuel > (ecartMoyen * 3)) {
                // Si l'écart actuel est 3x supérieur à sa moyenne habituelle, c'est une anomalie statistique
                // On force le destin (ou pas, c'est le hasard, mais on le tente)
                score += 25.0;
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
                                                   List<Integer> dernierTirage, Random rng) {
        List<Integer> selection = new ArrayList<>();
        if (!dernierTirage.isEmpty() && rng.nextBoolean()) {
            selection.add(dernierTirage.get(rng.nextInt(dernierTirage.size())));
        } else {
            List<Integer> hots = buckets.getOrDefault("HOT", new ArrayList<>());
            if (!hots.isEmpty()) selection.add(hots.get(rng.nextInt(hots.size())));
            else selection.add(1 + rng.nextInt(49));
        }

        while (selection.size() < 5) {
            String targetBucket = determinerBucketCible(selection, buckets);
            List<Integer> pool = new ArrayList<>(buckets.getOrDefault(targetBucket, new ArrayList<>()));
            pool.removeAll(selection);

            if (pool.isEmpty()) pool = new ArrayList<>(buckets.getOrDefault("HOT", new ArrayList<>()));
            pool.removeAll(selection);

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
                scoreLien += matrice.getOrDefault(dejaPris, Map.of()).getOrDefault(candidat, 0);
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


    private Map<Integer, Map<Integer, Integer>> construireMatriceAffinites(List<LotoTirage> history) {
        Map<Integer, Map<Integer, Integer>> matrix = new HashMap<>();
        for (int i = 1; i <= 49; i++) matrix.put(i, new HashMap<>());

        // On trie du plus récent au plus ancien
        List<LotoTirage> sorted = new ArrayList<>(history);
        sorted.sort(Comparator.comparing(LotoTirage::getDateTirage).reversed());

        double decayFactor = 0.995; // Chaque tirage perd 0.5% d'influence par rapport au précédent
        double currentWeight = 100.0; // Poids initial arbitraire

        for (LotoTirage t : sorted) {
            // On convertit le double en int pour votre Map, ou on passe la Map en <Integer, Double> (recommandé)
            // Ici on arrondit pour garder votre signature actuelle, mais l'idée est là
            int poidsInt = (int) Math.max(1, Math.round(currentWeight));

            List<Integer> b = t.getBoules();
            for (int i = 0; i < b.size(); i++) {
                for (int j = i + 1; j < b.size(); j++) {
                    matrix.get(b.get(i)).merge(b.get(j), poidsInt, Integer::sum);
                    matrix.get(b.get(j)).merge(b.get(i), poidsInt, Integer::sum);
                }
            }
            // Le poids diminue pour les vieux tirages
            currentWeight *= decayFactor;
        }
        return matrix;
    }

    private double calculerGain(UserBet bet, LotoTirage t) {
        // Logique simplifiée des gains FDJ (À adapter selon les règles officielles si besoin)
        List<Integer> userNums = List.of(bet.getB1(), bet.getB2(), bet.getB3(), bet.getB4(), bet.getB5());
        List<Integer> drawNums = t.getBoules();

        // Compter les bons numéros
        long bonsNumeros = userNums.stream().filter(drawNums::contains).count();
        boolean bonChance = (bet.getChance() == t.getNumeroChance());

        // Grille de gains approximative (Mise à jour 2024)
        if (bonsNumeros == 5 && bonChance) return 2000000.0; // Jackpot (theorique)
        if (bonsNumeros == 5) return 100000.0;
        if (bonsNumeros == 4 && bonChance) return 1000.0;
        if (bonsNumeros == 4) return 500.0;
        if (bonsNumeros == 3 && bonChance) return 50.0;
        if (bonsNumeros == 3) return 20.0;
        if (bonsNumeros == 2 && bonChance) return 15.0;
        if (bonsNumeros == 2) return 5.0;
        if (bonChance) return 2.20; // Remboursement

        return 0.0;
    }

    private void envoyerMailGain(User user, double gain, LocalDate date) {
        String subject = "💰 BRAVO ! Gain détecté sur Loto Master AI";
        String body = "<html><body>"
                + "<h2>Félicitations " + user.getFirstName() + " !</h2>"
                + "<p>Suite au tirage du <strong>" + date + "</strong>, nous avons détecté un gain sur l'une de vos grilles.</p>"
                + "<h1 style='color:green;'>" + String.format("%.2f", gain) + " €</h1>"
                + "<p>Connectez-vous pour voir les détails.</p>"
                + "<br><a href='" + baseUrl + "/login'>Voir mon compte</a>"
                + "</body></html>";

        try {
            emailService.sendHtmlEmail(user.getEmail(), subject, body);
        } catch (Exception e) {
            log.error("Erreur envoi mail gain", e);
        }
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
        return construireMatriceAffinites(repository.findAll());
    }

    private int selectionnerChancePonderee(Map<Integer, Double> scores, Random rng) {
        double total = scores.values().stream().mapToDouble(d->d).sum();
        double r = rng.nextDouble() * total;
        double cur = 0;
        for (Map.Entry<Integer, Double> e : scores.entrySet()) {
            cur += e.getValue();
            if (r <= cur) return e.getKey();
        }
        return 1;
    }

    private boolean estGrilleCoherente(List<Integer> boules) {
        if (boules == null || boules.size() != 5) return false;
        List<Integer> s = boules.stream().sorted().toList();

        // 1. Somme (Resserrer la courbe de Gauss)
        // La majorité des tirages se situe entre 120 et 170
        int sum = s.stream().mapToInt(Integer::intValue).sum();
        if (sum < 100 || sum > 175) return false;

        // 2. Parité (On évite 5 Pairs ou 5 Impairs, mais aussi 4/1 qui est déséquilibré)
        long pairs = s.stream().filter(n -> n % 2 == 0).count();
        // On favorise l'équilibre : 2 Pairs/3 Impairs ou 3 Pairs/2 Impairs
        if (pairs < 2 || pairs > 3) return false;

        // 3. Dizaines (Répartition spatiale)
        long diz = s.stream().map(n -> n / 10).distinct().count();
        if (diz < 3) return false; // Il faut couvrir au moins 3 dizaines différentes

        // 4. Suites (Numéros consécutifs)
        // On refuse s'il y a 3 numéros qui se suivent (ex: 12, 13, 14)
        int consecutiveCount = 0;
        for (int i = 0; i < s.size() - 1; i++) {
            if (s.get(i + 1) == s.get(i) + 1) {
                consecutiveCount++;
            } else {
                consecutiveCount = 0;
            }
            if (consecutiveCount >= 2) return false; // Rejet si suite de 3 (donc 2 "sauts" de 1)
        }

        // 5. Finales (ex: 12, 22, 42 -> trois nombres finissant par 2)
        // C'est rare. On limite à max 2 nombres ayant la même finale.
        Map<Integer, Long> finales = s.stream()
                .collect(Collectors.groupingBy(n -> n % 10, Collectors.counting()));

        return finales.values().stream().noneMatch(count -> count > 2);
    }

    // ==================================================================================
    // 5. MÉTHODES DE SIMULATION & CALCULS DE RATIO
    // ==================================================================================

    /**
     * Surcharge pour utiliser l'historique complet par défaut
     */
    public SimulationResultDto simulerGrilleDetaillee(List<Integer> boulesJouees, LocalDate dateSimul) {
        return simulerGrilleDetaillee(boulesJouees, dateSimul, repository.findAll());
    }

    /**
     * Compare la grille jouée avec tout l'historique pour trouver les correspondances (2, 3, 4, 5 numéros)
     */
    private SimulationResultDto simulerGrilleDetaillee(List<Integer> boulesJouees, LocalDate dateSimul, List<LotoTirage> historique) {
        SimulationResultDto result = new SimulationResultDto();

        // Formatage de la date pour l'affichage
        try {
            result.setDateSimulee(dateSimul.format(DateTimeFormatter.ofPattern(Constantes.FORMAT_DATE_STANDARD)));
        } catch (Exception e) {
            result.setDateSimulee(dateSimul.toString());
        }

        result.setJourSimule(dateSimul.getDayOfWeek().getDisplayName(TextStyle.FULL, Locale.FRANCE).toUpperCase());

        // Initialisation des listes
        result.setQuintuplets(new ArrayList<>());
        result.setQuartets(new ArrayList<>());
        result.setTrios(new ArrayList<>());
        result.setPairs(new ArrayList<>());

        // Boucle sur tout l'historique
        for (LotoTirage t : historique) {
            List<Integer> commun = new ArrayList<>(t.getBoules());
            commun.retainAll(boulesJouees); // Garde uniquement les numéros communs

            int taille = commun.size();

            // On ne s'intéresse qu'aux combinaisons gagnantes (2 numéros ou plus)
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

    /**
     * Ajoute une correspondance trouvée dans le bon groupe (Paire, Trio...) et met à jour les stats
     */
    private void addToResult(SimulationResultDto res, int taille, List<Integer> nums, String date, boolean memeJour, int totalTirages) {
        List<MatchGroup> targetList = switch (taille) {
            case 5 -> res.getQuintuplets();
            case 4 -> res.getQuartets();
            case 3 -> res.getTrios();
            case 2 -> res.getPairs();
            default -> null;
        };

        if (targetList != null) {
            Collections.sort(nums); // On trie pour que [1,2] soit pareil que [2,1]

            // On cherche si cette combinaison précise existe déjà dans les résultats
            Optional<MatchGroup> existing = targetList.stream()
                    .filter(m -> m.getNumeros().equals(nums))
                    .findFirst();

            if (existing.isPresent()) {
                // Si oui, on ajoute juste la nouvelle date
                MatchGroup group = existing.get();
                group.getDates().add(date + (memeJour ? " (Même jour !)" : ""));
                if (memeJour) group.setSameDayOfWeek(true);
                updateRatio(group, totalTirages, taille);
            } else {
                // Si non, on crée une nouvelle entrée
                List<String> dates = new ArrayList<>();
                dates.add(date + (memeJour ? " (Même jour !)" : ""));
                MatchGroup newGroup = new MatchGroup(nums, dates, memeJour, 0.0);
                updateRatio(newGroup, totalTirages, taille);
                targetList.add(newGroup);
            }
        }
    }

    /**
     * Calcule le Ratio de Sortie (Fréquence Réelle / Probabilité Théorique)
     * Ratio > 1.0 = La combinaison sort plus souvent que prévu par les maths
     */
    private void updateRatio(MatchGroup group, int totalTirages, int taille) {
        // Probabilités théoriques approximatives au Loto (5/49) pour obtenir exactement X numéros
        double probaTheo = switch (taille) {
            case 1 -> 0.10204;
            case 2 -> 0.00850;       // ~1 chance sur 117 (approx pour combinaison partielle)
            case 3 -> 0.00041;       // ~1 chance sur 2400
            case 4 -> 0.0000096;     // ~1 chance sur 100 000
            case 5 -> 0.00000052;    // ~1 chance sur 19 millions
            default -> 0.0;
        };

        double nbreAttendu = totalTirages * probaTheo;
        int nbreReel = group.getDates().size();

        double ratio = (nbreAttendu > 0) ? (nbreReel / nbreAttendu) : 0.0;

        // On arrondit à 2 chiffres après la virgule
        group.setRatio(Math.round(ratio * 100.0) / 100.0);
    }

    /**
     * Import du fichier CSV officiel de FDJ recensant tous les tirages
     * @param file fichier
     * @throws IOException
     */
    public void importCsv(MultipartFile file) throws IOException {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(file.getInputStream()))) {
            // Récupération des lignes
            List<String> lines = reader.lines().toList();
            // Déclaration des formats de dates
            DateTimeFormatter fmt1 = DateTimeFormatter.ofPattern(Constantes.FORMAT_DATE_STANDARD);
            DateTimeFormatter fmt2 = DateTimeFormatter.ofPattern(Constantes.FORMAT_DATE_STANDARD_INVERSE);

            // On parcourt toutes les lignes du fichier
            for (String line : lines) {
                if (line.trim().isEmpty() || line.startsWith("annee") || line.startsWith("Tirage")) continue;
                try {
                    String[] row;
                    LocalDate date;
                    int b1;
                    int b2;
                    int b3;
                    int b4;
                    int b5;
                    int c;

                    if (line.contains(Constantes.DELIMITEUR_POINT_VIRGULE)) {
                        // Ligne actuelle
                        row = line.split(Constantes.DELIMITEUR_POINT_VIRGULE);
                        if(row.length<10) continue;

                        try{
                            date=LocalDate.parse(row[2],fmt1);
                        } catch(Exception e) {
                            continue;
                        }
                        // Récupération des boules et du numéro chance
                        b1=Integer.parseInt(row[4]); b2=Integer.parseInt(row[5]); b3=Integer.parseInt(row[6]);
                        b4=Integer.parseInt(row[7]); b5=Integer.parseInt(row[8]); c=Integer.parseInt(row[9]);
                    } else {
                        row = line.trim().split("\\s+"); if(row.length<8) continue;
                        try{date=LocalDate.parse(row[6],fmt2);}catch(Exception e){try{date=LocalDate.parse(row[6],fmt1);}catch(Exception ex){continue;}}
                        b1=Integer.parseInt(row[1]); b2=Integer.parseInt(row[2]); b3=Integer.parseInt(row[3]);
                        b4=Integer.parseInt(row[4]); b5=Integer.parseInt(row[5]); c=Integer.parseInt(row[7]);
                    }

                    // Vérification non-existence avant insertion
                    if (!repository.existsByDateTirage(date)) {
                        // Création et sauvegarde du tirage
                        LotoTirage t = new LotoTirage();
                        t.setDateTirage(date);
                        t.setBoule1(b1);
                        t.setBoule2(b2);
                        t.setBoule3(b3);
                        t.setBoule4(b4);
                        t.setBoule5(b5);
                        t.setNumeroChance(c);

                        repository.save(t);
                    }
                } catch(Exception e) {
                    log.error("Erreur ligne: {}", line);
                }
            }
        }
    }

    @Transactional
    public void mettreAJourGainsUtilisateur(User user, LotoTirage tirage) {
        // 1. Récupérer les grilles de l'utilisateur pour ce tirage
        List<UserBet> bets = betRepository.findByUserAndDateJeu(user, tirage.getDateTirage());

        if (bets.isEmpty()) return;

        // 2. Calculer et enregistrer les gains
        for (UserBet bet : bets) {
            double gain = calculerGainSimule(bet, tirage); // Votre méthode de calcul existante
            bet.setGain(gain);

            betRepository.save(bet);
        }

        log.info("💰 Gains mis à jour pour l'utilisateur {} sur le tirage du {}", user.getEmail(), tirage.getDateTirage());
    }

    public double calculerGainSimule(UserBet bet, LotoTirage tirage) {
        if (tirage == null || bet == null) return 0.0;

        // 1. Compter les bons numéros
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

        // 2. Déterminer le rang (Rank 1 à 9)
        int rankPosition = 0;
        if (matches == 5 && chanceMatch) rankPosition = 1;
        else if (matches == 5) rankPosition = 2;
        else if (matches == 4 && chanceMatch) rankPosition = 3;
        else if (matches == 4) rankPosition = 4;
        else if (matches == 3 && chanceMatch) rankPosition = 5;
        else if (matches == 3) rankPosition = 6;
        else if (matches == 2 && chanceMatch) rankPosition = 7;
        else if (matches == 2) rankPosition = 8;
        else if (matches == 0 && chanceMatch) rankPosition = 9; // 0 ou 1 numéro + chance = rang 9

        // 3. Récupérer le montant associé dans le tirage officiel
        if (rankPosition > 0) {
            int finalRankPos = rankPosition;
            return tirage.getRanks().stream()
                    .filter(r -> r.getRankNumber() == finalRankPos) // ou .getPosition() selon votre entité
                    .findFirst()
                    .map(LotoTirageRank::getPrize)
                    .orElse(rankPosition == 9 ? 2.20 : 0.0);
        }

        return 0.0;
    }

    /**
     * Ajout d'un tirage via un administrateur (mode manuel)
     * @param dto
     * @return
     */
    public LotoTirage ajouterTirageManuel(TirageManuelDto dto) {
        if (repository.existsByDateTirage(dto.getDateTirage())) throw new RuntimeException("Existe déjà");
        LotoTirage t = new LotoTirage(); t.setDateTirage(dto.getDateTirage());
        t.setBoule1(dto.getBoule1()); t.setBoule2(dto.getBoule2()); t.setBoule3(dto.getBoule3()); t.setBoule4(dto.getBoule4()); t.setBoule5(dto.getBoule5()); t.setNumeroChance(dto.getNumeroChance());
        repository.save(t);

        return t;
    }

    @Data public static class StatPoint {
        private int numero;
        private int frequence;
        private int ecart;
        private boolean isChance;
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

        // 1. Fréquence des Numéros (1-49) et Chance (1-10)
        Map<Integer, Integer> freqBoules = new HashMap<>();
        Map<Integer, Integer> freqChance = new HashMap<>();
        long totalSomme = 0;
        int countPairs = 0;
        int totalNumerosJoues = bets.size() * 5;

        // Initialisation de la map des performances
        Map<String, UserStatsDto.DayPerformance> dayStats = new LinkedHashMap<>();
        dayStats.put("MONDAY", new UserStatsDto.DayPerformance("Lundi"));
        dayStats.put("WEDNESDAY", new UserStatsDto.DayPerformance("Mercredi"));
        dayStats.put("SATURDAY", new UserStatsDto.DayPerformance("Samedi"));

        for (UserBet bet : bets) {
            List<Integer> gr = List.of(bet.getB1(), bet.getB2(), bet.getB3(), bet.getB4(), bet.getB5());

            // Somme
            totalSomme += gr.stream().mapToInt(Integer::intValue).sum();

            // Parité & Fréquence
            for (Integer n : gr) {
                freqBoules.merge(n, 1, Integer::sum);
                if (n % 2 == 0) countPairs++;
            }

            // Chance
            freqChance.merge(bet.getChance(), 1, Integer::sum);

            // Récupération du jour
            String dayKey = bet.getDateJeu().getDayOfWeek().name();

            // On ne traite que Lundi/Mercredi/Samedi (sécurité)
            if (dayStats.containsKey(dayKey)) {
                UserStatsDto.DayPerformance p = dayStats.get(dayKey);

                p.setNbJeux(p.getNbJeux() + 1);
                p.setDepense(p.getDepense() + bet.getMise());

                if (bet.getGain() != null) {
                    p.setGains(p.getGains() + bet.getGain());
                }
            }
        }

        stats.setPerformanceParJour(dayStats);

        // 2. Calculs Moyennes
        stats.setMoyenneSomme(Math.round((double) totalSomme / bets.size()));
        stats.setTotalPairsJoues(countPairs);
        stats.setTotalImpairsJoues(totalNumerosJoues - countPairs);

        // Parité formatée
        double ratioPair = (double) countPairs / totalNumerosJoues; // ex: 0.6
        int p = (int) Math.round(ratioPair * 5); // ex: 3
        stats.setPariteMoyenne(p + " Pairs / " + (5 - p) + " Impairs");

        // 3. Top Listes (Stream API pour trier par fréquence)
        stats.setTopBoules(freqBoules.entrySet().stream()
                .sorted((e1, e2) -> e2.getValue().compareTo(e1.getValue())) // Tri décroissant
                .limit(5)
                .map(e -> new UserStatsDto.StatNumero(e.getKey(), e.getValue()))
                .toList());

        stats.setTopChance(freqChance.entrySet().stream()
                .sorted((e1, e2) -> e2.getValue().compareTo(e1.getValue()))
                .limit(3)
                .map(e -> new UserStatsDto.StatNumero(e.getKey(), e.getValue()))
                .toList());

        // 4. Numéros jamais joués (Optionnel mais sympa)
        List<Integer> jamais = new ArrayList<>();
        for(int i=1; i<=49; i++) {
            if(!freqBoules.containsKey(i)) jamais.add(i);
        }
        stats.setNumJamaisJoues(jamais);

        return stats;
    }
}
