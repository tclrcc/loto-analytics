package com.analyseloto.loto.service;

import com.analyseloto.loto.dto.*;
import com.analyseloto.loto.entity.Tirage;
import com.analyseloto.loto.repository.TirageRepository;
import com.opencsv.CSVParser;
import com.opencsv.CSVParserBuilder;
import com.opencsv.CSVReader;
import com.opencsv.CSVReaderBuilder;
import com.opencsv.exceptions.CsvException;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class LotoService {
    private final TirageRepository repository;

    @Data
    public static class StatPoint {
        private int numero;
        private int frequence;
        private int ecart;
        private boolean isChance;
    }

    // ==================================================================================
    // 1. MOTEUR DE GÉNÉRATION "EXPERT"
    // ==================================================================================

    public List<PronosticResultDto> genererMultiplesPronostics(LocalDate dateCible, int nombreGrilles) {
        List<PronosticResultDto> resultats = new ArrayList<>();
        int n = Math.min(Math.max(1, nombreGrilles), 10);

        // Graine déterministe basée sur la date (Même date = Même pronostic)
        long graine = dateCible.toEpochDay();
        Random rng = new Random(graine);

        // 1. ANALYSE PRÉALABLE DE TOUS LES NUMÉROS
        // On calcule le score de chaque boule une fois pour toutes pour cette date
        Map<Integer, Double> scoresBoules = calculerScores(repository.findAll(), 49, dateCible.getDayOfWeek(), false);
        Map<Integer, Double> scoresChance = calculerScores(repository.findAll(), 10, dateCible.getDayOfWeek(), true);

        // 2. CRÉATION DES "BUCKETS" (SEAUX)
        // On classe les boules en 3 catégories : CHAUD (Top 15), FROID (Bottom 15), NEUTRE (Le reste)
        Map<String, List<Integer>> buckets = creerBuckets(scoresBoules);

        // 3. RÉCUPÉRATION DU DERNIER TIRAGE (Pour la loi du Répétiteur)
        List<Tirage> history = repository.findAll();
        history.sort(Comparator.comparing(Tirage::getDateTirage).reversed());
        List<Integer> dernierTirage = history.isEmpty() ? new ArrayList<>() : history.get(0).getBoules();

        for (int i = 0; i < n; i++) {
            List<Integer> boules = new ArrayList<>();
            int tentatives = 0;

            // BOUCLE DE CONSTRUCTION INTELLIGENTE
            while (tentatives < 200) {
                // On tente de construire une grille équilibrée
                List<Integer> candidat = genererGrilleEquilibree(buckets, dernierTirage, rng);

                // On passe le candidat au "Crible Statistique"
                if (estGrilleCoherente(candidat)) {
                    boules = candidat;
                    break;
                }
                tentatives++;
                // Fallback : si on ne trouve pas de grille parfaite, on garde la dernière tentative
                if (tentatives == 200) boules = candidat;
            }

            // Sélection du numéro chance (Pondéré simple)
            int chance = selectionnerChancePonderee(scoresChance, rng);

            // BACKTESTING IMMÉDIAT
            SimulationResultDto simu = simulerGrilleDetaillee(boules, dateCible);

            // Calcul des indicateurs de performance
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

        // Tri par score global décroissant
        resultats.sort((a, b) -> Double.compare(b.getScoreGlobal(), a.getScoreGlobal()));
        return resultats;
    }

    /**
     * Etape A : Calcul des scores pour chaque numéro (Day-Centric)
     */
    private Map<Integer, Double> calculerScores(List<Tirage> history, int maxNum, DayOfWeek jourCible, boolean isChance) {
        Map<Integer, Double> scores = new HashMap<>();
        long totalTirages = history.size();

        // Historique filtré pour le jour cible
        List<Tirage> histJour = history.stream()
                .filter(t -> t.getDateTirage().getDayOfWeek() == jourCible)
                .toList();

        // Historique trié par date (récent -> vieux)
        List<Tirage> histSorted = history.stream()
                .sorted(Comparator.comparing(Tirage::getDateTirage).reversed())
                .toList();

        for (int i = 1; i <= maxNum; i++) {
            final int num = i;
            double score = 10.0;

            // 1. Fréquence Jour (Poids Fort)
            long freqJour = histJour.stream().filter(t -> isChance ? t.getNumeroChance() == num : t.getBoules().contains(num)).count();
            score += (freqJour * 3.0);

            // 2. Retard Global (Poids Moyen)
            int idxLast = -1;
            for(int k=0; k<histSorted.size(); k++) {
                Tirage t = histSorted.get(k);
                if (isChance ? t.getNumeroChance() == num : t.getBoules().contains(num)) {
                    idxLast = k; break;
                }
            }
            long ecart = (idxLast == -1) ? totalTirages : idxLast;

            // Bonus pour retard "mûr" (entre 10 et 40)
            if (ecart > 10 && ecart < 40) score += (ecart * 0.4);
            // Petit malus pour les numéros sortis au dernier tirage (pour éviter de tous les rejouer)
            if (ecart == 0) score -= 5.0;

            // 3. Forme Récente (Poids Fort) : Est-il sorti souvent récemment ?
            long sortiesRecentes = histSorted.stream().limit(15)
                    .filter(t -> isChance ? t.getNumeroChance() == num : t.getBoules().contains(num)).count();
            if (sortiesRecentes >= 2) score += 15.0;

            scores.put(num, score);
        }
        return scores;
    }

    /**
     * Etape B : Classification en Seaux (Hot / Cold / Neutral)
     */
    private Map<String, List<Integer>> creerBuckets(Map<Integer, Double> scores) {
        // Tri des numéros par score décroissant
        List<Map.Entry<Integer, Double>> list = new ArrayList<>(scores.entrySet());
        list.sort((e1, e2) -> e2.getValue().compareTo(e1.getValue()));

        Map<String, List<Integer>> buckets = new HashMap<>();

        // HOT : Les 12 meilleurs scores
        buckets.put("HOT", list.stream().limit(12).map(Map.Entry::getKey).collect(Collectors.toList()));

        // COLD : Les 12 pires scores (Souvent les gros retards ou les jamais sortis)
        buckets.put("COLD", list.stream().skip(list.size() - 12).map(Map.Entry::getKey).collect(Collectors.toList()));

        // NEUTRAL : Le reste (Ventre mou)
        buckets.put("NEUTRAL", list.stream().skip(12).limit(list.size() - 24).map(Map.Entry::getKey).collect(Collectors.toList()));

        return buckets;
    }

    /**
     * Etape C : Construction de la grille (Stratégie 2 Hot + 2 Cold + 1 Neutral + Répétiteur)
     */
    private List<Integer> genererGrilleEquilibree(Map<String, List<Integer>> buckets, List<Integer> dernierTirage, Random rng) {
        Set<Integer> selection = new HashSet<>();

        // 1. LOI DU RÉPÉTITEUR (Optionnel mais recommandé)
        // On essaie de prendre 1 numéro du tirage précédent (50% de chance que ça arrive)
        if (!dernierTirage.isEmpty() && rng.nextBoolean()) {
            selection.add(dernierTirage.get(rng.nextInt(dernierTirage.size())));
        }

        // Helper pour piocher dans une liste sans doublon
        Runnable piocherDansHot = () -> piocher(buckets.get("HOT"), selection, rng);
        Runnable piocherDansCold = () -> piocher(buckets.get("COLD"), selection, rng);
        Runnable piocherDansNeutral = () -> piocher(buckets.get("NEUTRAL"), selection, rng);

        // 2. REMPLISSAGE STRATÉGIQUE (On vise 5 numéros)
        // On veut idéalement : 2 HOT, 2 COLD, 1 NEUTRAL (modulo le répétiteur déjà choisi)

        while (selection.size() < 5) {
            int currentSize = selection.size();

            // Priorité à l'équilibre
            if (currentSize < 2) piocherDansHot.run();       // On assure 1 ou 2 Hot
            else if (currentSize < 4) piocherDansCold.run(); // On assure 1 ou 2 Cold
            else piocherDansNeutral.run();                   // On complète avec du Neutre

            // Sécurité boucle infinie (si un bucket est vide ou épuisé)
            if (selection.size() == currentSize) {
                // Si on arrive pas à piocher dans le bucket visé, on pioche au hasard dans HOT (le plus sûr)
                piocherDansHot.run();
            }
        }

        return new ArrayList<>(selection);
    }

    private void piocher(List<Integer> source, Set<Integer> destination, Random rng) {
        if (source.isEmpty()) return;
        // On essaie 10 fois de trouver un numéro pas encore pris
        for (int i=0; i<10; i++) {
            int num = source.get(rng.nextInt(source.size()));
            if (!destination.contains(num)) {
                destination.add(num);
                break;
            }
        }
    }

    private int selectionnerChancePonderee(Map<Integer, Double> scores, Random rng) {
        // Sélection type "Roulette Wheel"
        double totalWeight = scores.values().stream().mapToDouble(Double::doubleValue).sum();
        double randomValue = rng.nextDouble() * totalWeight;
        double currentWeight = 0;
        for (Map.Entry<Integer, Double> entry : scores.entrySet()) {
            currentWeight += entry.getValue();
            if (randomValue <= currentWeight) return entry.getKey();
        }
        return 1; // Fallback
    }

    /**
     * Etape D : Filtres Structurels (Somme, Parité, Finales, Suites)
     */
    private boolean estGrilleCoherente(List<Integer> boules) {
        if (boules == null || boules.size() != 5) return false;

        // 1. Somme (Gaussienne : 100-175)
        int somme = boules.stream().mapToInt(Integer::intValue).sum();
        if (somme < 90 || somme > 185) return false; // Légèrement élargi

        // 2. Parité (Pas de 5/0)
        long nbPairs = boules.stream().filter(n -> n % 2 == 0).count();
        if (nbPairs == 0 || nbPairs == 5) return false;

        // 3. Dizaines (Pas de 4 dans la même dizaine)
        Map<Integer, Long> parDizaine = boules.stream().collect(Collectors.groupingBy(n -> n / 10, Collectors.counting()));
        if (parDizaine.values().stream().anyMatch(count -> count >= 4)) return false;

        // 4. Suites (Pas de suite de 3 numéros : 1,2,3)
        List<Integer> sorted = boules.stream().sorted().toList();
        int suite = 0;
        for (int i = 0; i < sorted.size() - 1; i++) {
            if (sorted.get(i) + 1 == sorted.get(i+1)) suite++; else suite = 0;
            if (suite >= 2) return false; // Rejet si 3 numéros consécutifs (suite=2)
        }

        // 5. Finales (Nouveau !) : On évite 5 finales différentes
        // Ex: 1, 12, 23, 34, 45 -> finales 1,2,3,4,5. C'est rare.
        // On préfère avoir au moins une finale commune (ex: 13 et 43)
        long nbFinalesUniques = boules.stream().map(n -> n % 10).distinct().count();
        if (nbFinalesUniques == 5) return false; // Trop dispersé

        return true;
    }

    // ==================================================================================
    // 2. SIMULATION & ANALYSE (Code inchangé mais essentiel)
    // ==================================================================================

    public SimulationResultDto simulerGrilleDetaillee(List<Integer> boulesJouees, LocalDate dateSimul) {
        List<Tirage> historique = repository.findAll();
        SimulationResultDto result = new SimulationResultDto();
        result.setDateSimulee(dateSimul.format(DateTimeFormatter.ofPattern("dd/MM/yyyy")));
        result.setJourSimule(dateSimul.getDayOfWeek().getDisplayName(TextStyle.FULL, Locale.FRANCE).toUpperCase());
        result.setQuintuplets(new ArrayList<>());
        result.setQuartets(new ArrayList<>());
        result.setTrios(new ArrayList<>());
        result.setPairs(new ArrayList<>());

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
        double probaTheo = getProbabiliteTheorique(taille);
        double nbreAttendu = totalTirages * probaTheo;
        int nbreReel = group.getDates().size();
        double ratio = (nbreAttendu > 0) ? (nbreReel / nbreAttendu) : 0.0;
        group.setRatio(Math.round(ratio * 100.0) / 100.0);
    }

    private double getProbabiliteTheorique(int tailleGroupe) {
        return switch (tailleGroupe) {
            case 1 -> 0.10204; case 2 -> 0.00850; case 3 -> 0.00041; case 4 -> 0.0000096; case 5 -> 0.00000052; default -> 0.0;
        };
    }

    // ==================================================================================
    // 3. GESTION DES DONNÉES (CRUD)
    // ==================================================================================

    public void importCsv(MultipartFile file) throws IOException, CsvException {
        try (Reader reader = new InputStreamReader(file.getInputStream())) {
            CSVParser parser = new CSVParserBuilder().withSeparator(';').build();
            CSVReader csvReader = new CSVReaderBuilder(reader).withCSVParser(parser).withSkipLines(1).build();
            List<String[]> rows = csvReader.readAll();
            DateTimeFormatter fmt = DateTimeFormatter.ofPattern("dd/MM/yyyy");
            for (String[] row : rows) {
                LocalDate date = LocalDate.parse(row[2], fmt);
                if (!repository.existsByDateTirage(date)) {
                    Tirage t = new Tirage();
                    t.setDateTirage(date);
                    t.setBoule1(Integer.parseInt(row[4])); t.setBoule2(Integer.parseInt(row[5]));
                    t.setBoule3(Integer.parseInt(row[6])); t.setBoule4(Integer.parseInt(row[7]));
                    t.setBoule5(Integer.parseInt(row[8])); t.setNumeroChance(Integer.parseInt(row[9]));
                    repository.save(t);
                }
            }
        }
    }

    public void ajouterTirageManuel(TirageManuelDto dto) {
        if (repository.existsByDateTirage(dto.getDateTirage())) throw new RuntimeException("Tirage existant");
        Set<Integer> unique = new HashSet<>(Arrays.asList(dto.getBoule1(), dto.getBoule2(), dto.getBoule3(), dto.getBoule4(), dto.getBoule5()));
        if (unique.size() < 5) throw new RuntimeException("Doublons interdits");
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