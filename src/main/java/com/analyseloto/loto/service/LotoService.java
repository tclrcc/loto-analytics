package com.analyseloto.loto.service;

import com.analyseloto.loto.dto.*;
import com.analyseloto.loto.entity.Tirage;
import com.analyseloto.loto.repository.TirageRepository;
import lombok.Data;
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

    private final TirageRepository repository;
    private final AstroService astroService;

    // --- CONSTANTES DE PONDÉRATION ---
    private static final double POIDS_FREQ_JOUR = 3.0;
    private static final double POIDS_FORME_RECENTE = 15.0;
    private static final double POIDS_ECART_MOYEN = 0.4;
    private static final double POIDS_TENSION_ECART = 12.0; // Nouveau : Bonus si écart > moyenne perso
    private static final double POIDS_BOOST_ASTRO = 30.0;
    private static final double POIDS_BOOST_FINALE = 8.0;
    private static final double POIDS_AFFINITE_RNG = 3.0;

    @Data
    public static class StatPoint {
        private int numero;
        private int frequence;
        private int ecart;
        private boolean isChance;
    }

    // ==================================================================================
    // 1. GÉNÉRATION DE PRONOSTICS (ALGO AMÉLIORÉ)
    // ==================================================================================

    public List<PronosticResultDto> genererMultiplesPronostics(LocalDate dateCible, int nombreGrilles) {
        return genererPronosticInterne(dateCible, nombreGrilles, null);
    }

    public List<PronosticResultDto> genererPronosticsHybrides(LocalDate dateCible, int nombreGrilles, AstroService.AstroProfileDto profil) {
        return genererPronosticInterne(dateCible, nombreGrilles, profil);
    }

    private List<PronosticResultDto> genererPronosticInterne(LocalDate dateCible, int nombreGrilles, AstroService.AstroProfileDto profilAstro) {
        List<PronosticResultDto> resultats = new ArrayList<>();
        int n = Math.min(Math.max(1, nombreGrilles), 10);

        // 1. Chargement UNIQUE de la base (Perf)
        List<Tirage> history = repository.findAll();
        if (history.isEmpty()) return new ArrayList<>();

        long graine = dateCible.toEpochDay();
        if (profilAstro != null) graine += profilAstro.getVille().toLowerCase().hashCode();
        Random rng = new Random(graine);

        // 2. Analyses Préalables
        Set<Integer> hotFinales = detecterFinalesChaudes(history);
        List<Integer> boostNumbers = (profilAstro != null) ? astroService.getLuckyNumbersOnly(profilAstro) : Collections.emptyList();

        // Calcul des scores avec la nouvelle logique "Retour à la moyenne"
        Map<Integer, Double> scoresBoules = calculerScores(history, 49, dateCible.getDayOfWeek(), false, boostNumbers, hotFinales);
        Map<Integer, Double> scoresChance = calculerScores(history, 10, dateCible.getDayOfWeek(), true, boostNumbers, Collections.emptySet());

        Map<String, List<Integer>> buckets = creerBuckets(scoresBoules);

        // Construction de la matrice dynamique (Poids temporels)
        Map<Integer, Map<Integer, Integer>> matriceAffinites = construireMatriceAffinites(history);

        // Loi du répétiteur
        List<Tirage> sortedHistory = new ArrayList<>(history);
        sortedHistory.sort(Comparator.comparing(Tirage::getDateTirage).reversed());
        List<Integer> dernierTirage = sortedHistory.get(0).getBoules();

        // 3. Boucle de génération
        for (int i = 0; i < n; i++) {
            List<Integer> boules = new ArrayList<>();
            int tentatives = 0;

            while (tentatives < 500) { // On augmente un peu les essais car les filtres sont plus stricts
                List<Integer> candidat = genererGrilleParAffinite(buckets, matriceAffinites, dernierTirage, rng);
                if (estGrilleCoherente(candidat)) {
                    boules = candidat;
                    break;
                }
                tentatives++;
                if (tentatives == 500) boules = candidat; // Fallback
            }

            int chance = selectionnerChancePonderee(scoresChance, rng);

            // 4. Simulation
            SimulationResultDto simu = simulerGrilleDetaillee(boules, dateCible, history);

            double maxDuo = simu.getPairs().stream().mapToDouble(MatchGroup::getRatio).max().orElse(0.0);
            double maxTrio = simu.getTrios().stream().mapToDouble(MatchGroup::getRatio).max().orElse(0.0);
            boolean fullMatch = !simu.getQuintuplets().isEmpty();
            double avgDuo = simu.getPairs().stream().mapToDouble(MatchGroup::getRatio).average().orElse(0.0);

            resultats.add(new PronosticResultDto(
                    boules.stream().sorted().collect(Collectors.toList()),
                    chance,
                    Math.round(avgDuo * 100.0) / 100.0,
                    maxDuo,
                    maxTrio,
                    fullMatch
            ));
        }

        resultats.sort((a, b) -> Double.compare(b.getScoreGlobal(), a.getScoreGlobal()));
        return resultats;
    }

    // --- LOGIQUE INTERNE AMÉLIORÉE ---

    private List<Integer> genererGrilleParAffinite(Map<String, List<Integer>> buckets,
                                                   Map<Integer, Map<Integer, Integer>> matrice,
                                                   List<Integer> dernierTirage,
                                                   Random rng) {
        List<Integer> selection = new ArrayList<>();

        // Graine
        if (!dernierTirage.isEmpty() && rng.nextBoolean()) {
            selection.add(dernierTirage.get(rng.nextInt(dernierTirage.size())));
        } else {
            List<Integer> hots = buckets.get("HOT");
            if (!hots.isEmpty()) selection.add(hots.get(rng.nextInt(hots.size())));
            else selection.add(1 + rng.nextInt(49));
        }

        // Remplissage magnétique
        while (selection.size() < 5) {
            String targetBucket = determinerBucketCible(selection, buckets);
            List<Integer> pool = new ArrayList<>(buckets.getOrDefault(targetBucket, new ArrayList<>()));
            pool.removeAll(selection);

            if (pool.isEmpty()) {
                pool = new ArrayList<>(buckets.getOrDefault("HOT", new ArrayList<>()));
                pool.removeAll(selection);
            }

            if (pool.isEmpty()) {
                int n = 1 + rng.nextInt(49);
                while(selection.contains(n)) n = 1 + rng.nextInt(49);
                selection.add(n);
            } else {
                Integer elu = selectionnerParAffinite(pool, selection, matrice, rng);
                selection.add(elu);
            }
        }
        return selection;
    }

    private Integer selectionnerParAffinite(List<Integer> candidats, List<Integer> selectionActuelle, Map<Integer, Map<Integer, Integer>> matrice, Random rng) {
        Map<Integer, Double> scoresCandidats = new HashMap<>();
        for (Integer candidat : candidats) {
            double scoreLien = 1.0;
            for (Integer dejaPris : selectionActuelle) {
                int coOccurrences = matrice.getOrDefault(dejaPris, Collections.emptyMap()).getOrDefault(candidat, 0);
                scoreLien += coOccurrences;
            }
            scoreLien += (rng.nextDouble() * POIDS_AFFINITE_RNG);
            scoresCandidats.put(candidat, scoreLien);
        }
        return scoresCandidats.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse(candidats.get(0));
    }

    private String determinerBucketCible(List<Integer> selection, Map<String, List<Integer>> buckets) {
        long nbHot = selection.stream().filter(n -> buckets.getOrDefault("HOT", Collections.emptyList()).contains(n)).count();
        long nbCold = selection.stream().filter(n -> buckets.getOrDefault("COLD", Collections.emptyList()).contains(n)).count();
        if (nbHot < 2) return "HOT";
        if (nbCold < 2) return "COLD";
        return "NEUTRAL";
    }

    private Set<Integer> detecterFinalesChaudes(List<Tirage> history) {
        return history.stream()
                .sorted(Comparator.comparing(Tirage::getDateTirage).reversed())
                .limit(20)
                .flatMap(t -> t.getBoules().stream())
                .map(b -> b % 10)
                .collect(Collectors.groupingBy(f -> f, Collectors.counting()))
                .entrySet().stream()
                .sorted((e1, e2) -> Long.compare(e2.getValue(), e1.getValue()))
                .limit(2)
                .map(Map.Entry::getKey)
                .collect(Collectors.toSet());
    }

    // --- SCORING AVANCÉ (Mean Reversion) ---

    private Map<Integer, Double> calculerScores(List<Tirage> history, int maxNum, DayOfWeek jourCible, boolean isChance, List<Integer> boostNumbers, Set<Integer> hotFinales) {
        Map<Integer, Double> scores = new HashMap<>();
        long totalTirages = history.size();

        List<Tirage> histJour = history.stream().filter(t -> t.getDateTirage().getDayOfWeek() == jourCible).toList();
        List<Tirage> histSorted = history.stream().sorted(Comparator.comparing(Tirage::getDateTirage).reversed()).toList();

        for (int i = 1; i <= maxNum; i++) {
            final int num = i;
            double score = 10.0;

            // 1. Fréquence Jour
            long freqJour = histJour.stream().filter(t -> isChance ? t.getNumeroChance() == num : t.getBoules().contains(num)).count();
            score += (freqJour * POIDS_FREQ_JOUR);

            // 2. Ecart Actuel
            int idxLast = -1;
            for(int k=0; k<histSorted.size(); k++) {
                if (isChance ? histSorted.get(k).getNumeroChance() == num : histSorted.get(k).getBoules().contains(num)) {
                    idxLast = k; break;
                }
            }
            long ecartActuel = (idxLast == -1) ? totalTirages : idxLast;
            if (ecartActuel > 10 && ecartActuel < 40) score += (ecartActuel * POIDS_ECART_MOYEN);
            if (ecartActuel == 0) score -= 5.0;

            // 3. Forme Récente
            long sortiesRecentes = histSorted.stream().limit(15).filter(t -> isChance ? t.getNumeroChance() == num : t.getBoules().contains(num)).count();
            if (sortiesRecentes >= 2) score += POIDS_FORME_RECENTE;

            // 4. Boost Astral
            if (boostNumbers.contains(num)) score += POIDS_BOOST_ASTRO;

            // 5. Boost Finales
            if (!isChance && hotFinales != null && hotFinales.contains(num % 10)) {
                score += POIDS_BOOST_FINALE;
            }

            // --- NOUVEAU : 6. Retour à la Moyenne (Tension) ---
            if (!isChance) {
                List<Tirage> tiragesAvecCeNum = history.stream()
                        .filter(t -> t.getBoules().contains(num))
                        .sorted(Comparator.comparing(Tirage::getDateTirage))
                        .toList();

                if (tiragesAvecCeNum.size() > 5) { // Il faut un peu d'historique
                    double moyenneEcarts = 0;
                    for (int k = 1; k < tiragesAvecCeNum.size(); k++) {
                        long diff = ChronoUnit.DAYS.between(tiragesAvecCeNum.get(k-1).getDateTirage(), tiragesAvecCeNum.get(k).getDateTirage());
                        moyenneEcarts += diff;
                    }
                    // Conversion approximative jours -> tirages (env. 2.33 tirages/semaine)
                    double avgDrawGap = (moyenneEcarts / (tiragesAvecCeNum.size() - 1)) / 2.33;

                    // Si l'écart actuel est anormalement élevé par rapport à l'habitude de ce numéro
                    if (ecartActuel > (avgDrawGap * 1.5)) {
                        score += POIDS_TENSION_ECART;
                    }
                }
            }

            scores.put(num, score);
        }
        return scores;
    }

    // --- MATRICE DYNAMIQUE (Poids temporels) ---

    private Map<Integer, Map<Integer, Integer>> construireMatriceAffinites(List<Tirage> history) {
        Map<Integer, Map<Integer, Integer>> matrix = new HashMap<>();
        for (int i = 1; i <= 49; i++) matrix.put(i, new HashMap<>());

        // Tri du plus récent au plus vieux
        List<Tirage> triagesTries = new ArrayList<>(history);
        triagesTries.sort(Comparator.comparing(Tirage::getDateTirage).reversed());

        int count = 0;
        for (Tirage t : triagesTries) {
            count++;
            List<Integer> b = t.getBoules();

            // Poids dynamique : Récent = 3 pts, Moyen = 2 pts, Vieux = 1 pt
            int poids = (count <= 50) ? 3 : (count <= 150 ? 2 : 1);

            for (int i = 0; i < b.size(); i++) {
                for (int j = i + 1; j < b.size(); j++) {
                    int n1 = b.get(i);
                    int n2 = b.get(j);
                    matrix.get(n1).merge(n2, poids, Integer::sum);
                    matrix.get(n2).merge(n1, poids, Integer::sum);
                }
            }
        }
        return matrix;
    }

    private Map<String, List<Integer>> creerBuckets(Map<Integer, Double> scores) {
        List<Map.Entry<Integer, Double>> list = new ArrayList<>(scores.entrySet());
        list.sort((e1, e2) -> e2.getValue().compareTo(e1.getValue()));
        Map<String, List<Integer>> buckets = new HashMap<>();

        if (list.size() >= 24) {
            buckets.put("HOT", list.stream().limit(12).map(Map.Entry::getKey).collect(Collectors.toList()));
            buckets.put("COLD", list.stream().skip(list.size() - 12).map(Map.Entry::getKey).collect(Collectors.toList()));
            buckets.put("NEUTRAL", list.stream().skip(12).limit(list.size() - 24).map(Map.Entry::getKey).collect(Collectors.toList()));
        } else {
            buckets.put("HOT", list.stream().limit(list.size()/2).map(Map.Entry::getKey).collect(Collectors.toList()));
            buckets.put("COLD", list.stream().skip(list.size()/2).map(Map.Entry::getKey).collect(Collectors.toList()));
            buckets.put("NEUTRAL", new ArrayList<>());
        }
        return buckets;
    }

    private int selectionnerChancePonderee(Map<Integer, Double> scores, Random rng) {
        double totalWeight = scores.values().stream().mapToDouble(Double::doubleValue).sum();
        double randomValue = rng.nextDouble() * totalWeight;
        double currentWeight = 0;
        for (Map.Entry<Integer, Double> entry : scores.entrySet()) {
            currentWeight += entry.getValue();
            if (randomValue <= currentWeight) return entry.getKey();
        }
        return 1;
    }

    // --- FILTRES STRUCTURELS STRICTS ---

    private boolean estGrilleCoherente(List<Integer> boules) {
        if (boules == null || boules.size() != 5) return false;

        List<Integer> sorted = boules.stream().sorted().toList();

        // 1. Somme (Resserrée : 95-180)
        int somme = boules.stream().mapToInt(Integer::intValue).sum();
        if (somme < 95 || somme > 180) return false;

        // 2. Pairs / Impairs
        long nbPairs = boules.stream().filter(n -> n % 2 == 0).count();
        if (nbPairs == 0 || nbPairs == 5) return false;

        // 3. Pas plus de 2 numéros consécutifs (ex: 32, 33, 34 interdit)
        for (int i = 0; i < sorted.size() - 2; i++) {
            if (sorted.get(i+1) == sorted.get(i) + 1 && sorted.get(i+2) == sorted.get(i) + 2) return false;
        }

        // 4. Répartition spatiale (Pas de trous > 25)
        int maxEcartInterne = 0;
        for (int i = 0; i < sorted.size() - 1; i++) {
            maxEcartInterne = Math.max(maxEcartInterne, sorted.get(i+1) - sorted.get(i));
        }
        if (maxEcartInterne > 25) return false;

        // 5. Dizaines variées (au moins 3 différentes)
        long nbDizaines = boules.stream().map(n -> n / 10).distinct().count();
        if (nbDizaines < 3) return false;

        return true;
    }

    // ==================================================================================
    // 2. SIMULATION & ANALYSE (OPTIMISÉE)
    // ==================================================================================

    public SimulationResultDto simulerGrilleDetaillee(List<Integer> boulesJouees, LocalDate dateSimul) {
        return simulerGrilleDetaillee(boulesJouees, dateSimul, repository.findAll());
    }

    private SimulationResultDto simulerGrilleDetaillee(List<Integer> boulesJouees, LocalDate dateSimul, List<Tirage> historique) {
        SimulationResultDto result = new SimulationResultDto();
        result.setDateSimulee(dateSimul.format(DateTimeFormatter.ofPattern("dd/MM/yyyy")));
        result.setJourSimule(dateSimul.getDayOfWeek().getDisplayName(TextStyle.FULL, Locale.FRANCE).toUpperCase());
        result.setQuintuplets(new ArrayList<>()); result.setQuartets(new ArrayList<>());
        result.setTrios(new ArrayList<>()); result.setPairs(new ArrayList<>());

        for (Tirage t : historique) {
            List<Integer> commun = new ArrayList<>(t.getBoules());
            commun.retainAll(boulesJouees);
            int taille = commun.size();
            if (taille >= 2) {
                String dateHist = t.getDateTirage().format(DateTimeFormatter.ofPattern("dd/MM/yyyy"));
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
                if(memeJour) group.setSameDayOfWeek(true);
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
            case 1 -> 0.10204; case 2 -> 0.00850; case 3 -> 0.00041; case 4 -> 0.0000096; case 5 -> 0.00000052; default -> 0.0;
        };
        double nbreAttendu = totalTirages * probaTheo;
        int nbreReel = group.getDates().size();
        double ratio = (nbreAttendu > 0) ? (nbreReel / nbreAttendu) : 0.0;
        group.setRatio(Math.round(ratio * 100.0) / 100.0);
    }

    // ==================================================================================
    // 3. GESTION DES DONNÉES (IMPORT ROBUSTE)
    // ==================================================================================

    public void importCsv(MultipartFile file) throws IOException {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(file.getInputStream()))) {
            List<String> lines = reader.lines().toList();
            DateTimeFormatter fmtStandard = DateTimeFormatter.ofPattern("dd/MM/yyyy");
            DateTimeFormatter fmtIso = DateTimeFormatter.ofPattern("yyyy-MM-dd");

            for (String line : lines) {
                if (line.trim().isEmpty() || line.startsWith("annee_numero") || line.startsWith("Tirage")) continue;
                try {
                    String[] row;
                    LocalDate date = null;
                    int b1=0, b2=0, b3=0, b4=0, b5=0, chance=0;

                    if (line.contains(";")) { // Format FDJ CSV Standard
                        row = line.split(";");
                        if (row.length < 10) continue;
                        try { date = LocalDate.parse(row[2], fmtStandard); } catch (Exception e) { continue; }
                        b1 = Integer.parseInt(row[4]); b2 = Integer.parseInt(row[5]);
                        b3 = Integer.parseInt(row[6]); b4 = Integer.parseInt(row[7]);
                        b5 = Integer.parseInt(row[8]); chance = Integer.parseInt(row[9]);
                    } else { // Format Texte / Copier-coller
                        row = line.trim().split("\\s+");
                        if (row.length < 8) continue;
                        try { date = LocalDate.parse(row[6], fmtIso); }
                        catch (Exception e) { try { date = LocalDate.parse(row[6], fmtStandard); } catch(Exception ex) { continue; } }
                        b1 = Integer.parseInt(row[1]); b2 = Integer.parseInt(row[2]);
                        b3 = Integer.parseInt(row[3]); b4 = Integer.parseInt(row[4]);
                        b5 = Integer.parseInt(row[5]); chance = Integer.parseInt(row[7]);
                    }

                    if (date != null && !repository.existsByDateTirage(date)) {
                        Tirage t = new Tirage();
                        t.setDateTirage(date);
                        t.setBoule1(b1); t.setBoule2(b2); t.setBoule3(b3);
                        t.setBoule4(b4); t.setBoule5(b5);
                        t.setNumeroChance(chance);
                        repository.save(t);
                    }
                } catch (Exception e) {
                    log.error("Erreur import ligne: {}", line);
                }
            }
        }
    }

    public void ajouterTirageManuel(TirageManuelDto dto) {
        if (repository.existsByDateTirage(dto.getDateTirage())) throw new RuntimeException("Tirage existant");
        Set<Integer> unique = new HashSet<>(Arrays.asList(dto.getBoule1(), dto.getBoule2(), dto.getBoule3(), dto.getBoule4(), dto.getBoule5()));
        if (unique.size() < 5) throw new RuntimeException("Doublons");
        Tirage t = new Tirage();
        t.setDateTirage(dto.getDateTirage());
        t.setBoule1(dto.getBoule1()); t.setBoule2(dto.getBoule2()); t.setBoule3(dto.getBoule3());
        t.setBoule4(dto.getBoule4()); t.setBoule5(dto.getBoule5()); t.setNumeroChance(dto.getNumeroChance());
        repository.save(t);
    }

    public StatsReponse getStats(String jourFiltre) {
        List<Tirage> all = repository.findAll();
        if (jourFiltre != null && !jourFiltre.isEmpty()) {
            try { all = all.stream().filter(t -> t.getDateTirage().getDayOfWeek() == DayOfWeek.valueOf(jourFiltre.toUpperCase())).toList(); } catch (Exception e) {}
        }
        if (all.isEmpty()) return new StatsReponse(new ArrayList<>(), "-", "-", 0);
        LocalDate minDate = all.stream().map(Tirage::getDateTirage).min(LocalDate::compareTo).orElse(LocalDate.now());
        LocalDate maxDate = all.stream().map(Tirage::getDateTirage).max(LocalDate::compareTo).orElse(LocalDate.now());
        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("dd/MM/yyyy");
        Map<Integer, Integer> freqMap = new HashMap<>(); Map<Integer, LocalDate> lastSeenMap = new HashMap<>();
        Map<Integer, Integer> freqChance = new HashMap<>(); Map<Integer, LocalDate> lastSeenChance = new HashMap<>();

        for (Tirage t : all) {
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
}