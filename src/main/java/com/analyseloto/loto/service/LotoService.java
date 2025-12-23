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

    // DTO pour renvoyer les stats au front
    @Data
    public static class StatPoint {
        private int numero;
        private int frequence;
        private int ecart; // Jours
        private boolean isChance;
    }

    public List<PronosticResultDto> genererMultiplesPronostics(LocalDate dateCible, int nombreGrilles) {
        List<PronosticResultDto> resultats = new ArrayList<>();
        int n = Math.min(Math.max(1, nombreGrilles), 10);

        // --- C'EST ICI QUE LA MAGIE OPÈRE ---
        // On initialise le hasard avec la date convertie en nombre (Epoch Day).
        // Pour une même date, "rng" générera TOUJOURS la même séquence de nombres.
        long graine = dateCible.toEpochDay();
        Random rng = new Random(graine);

        for (int i = 0; i < n; i++) {
            // On passe le 'rng' à nos méthodes de sélection
            List<Integer> boules = selectionnerParPonderation(repository.findAll(), 49, 5, dateCible.getDayOfWeek(), false, rng);
            List<Integer> chance = selectionnerParPonderation(repository.findAll(), 10, 1, dateCible.getDayOfWeek(), true, rng);

            // Le reste ne change pas (Analyse des performances)
            SimulationResultDto simu = simulerGrilleDetaillee(boules, dateCible);

            double maxDuo = simu.getPairs().stream().mapToDouble(MatchGroup::getRatio).max().orElse(0.0);
            double maxTrio = simu.getTrios().stream().mapToDouble(MatchGroup::getRatio).max().orElse(0.0);
            boolean fullMatch = !simu.getQuintuplets().isEmpty();
            double avgDuo = simu.getPairs().stream().mapToDouble(MatchGroup::getRatio).average().orElse(0.0);

            resultats.add(new PronosticResultDto(
                    boules.stream().sorted().collect(Collectors.toList()),
                    chance.get(0),
                    Math.round(avgDuo * 100.0) / 100.0,
                    maxDuo,
                    maxTrio,
                    fullMatch
            ));
        }

        // Le tri reste le même
        resultats.sort((a, b) -> Double.compare(b.getScoreGlobal(), a.getScoreGlobal()));

        return resultats;
    }

    // Notez l'ajout du paramètre 'Random rng' à la fin
    private List<Integer> selectionnerParPonderation(List<Tirage> history, int maxNum, int qteAFournir, DayOfWeek jourCible, boolean isChance, Random rng) {
        Map<Integer, Double> scores = new HashMap<>();
        long totalTirages = history.size();

        // 1. CALCUL DES SCORES (DÉTERMINISTE)
        // Cette partie ne change pas, car elle est basée sur les maths pures
        for (int i = 1; i <= maxNum; i++) {
            final int num = i;
            double score = 1.0; // Score de base pour éviter le 0

            // A. Poids du Jour
            long freqJour = history.stream()
                    .filter(t -> t.getDateTirage().getDayOfWeek() == jourCible)
                    .filter(t -> isChance ? t.getNumeroChance() == num : t.getBoules().contains(num))
                    .count();
            score += (freqJour * 2.0);

            // B. Poids du Retard
            Optional<Tirage> lastSortie = history.stream()
                    .sorted((t1, t2) -> t2.getDateTirage().compareTo(t1.getDateTirage()))
                    .filter(t -> isChance ? t.getNumeroChance() == num : t.getBoules().contains(num))
                    .findFirst();

            long ecart;
            if (lastSortie.isPresent()) {
                long jours = java.time.temporal.ChronoUnit.DAYS.between(lastSortie.get().getDateTirage(), LocalDate.now());
                ecart = jours;
            } else {
                ecart = totalTirages * 3; // Bonus énorme si jamais sorti
            }
            score += (ecart * 0.5);

            scores.put(num, score);
        }

        // 2. SÉLECTION PONDÉRÉE (DÉTERMINISTE GRÂCE À RNG)
        List<Integer> elus = new ArrayList<>();

        // Pour s'assurer qu'on ne boucle pas indéfiniment
        int safetyCounter = 0;

        while (elus.size() < qteAFournir && safetyCounter < 1000) {
            double totalWeight = scores.values().stream().mapToDouble(Double::doubleValue).sum();

            // ICI : On utilise rng.nextDouble() au lieu de Math.random()
            // Comme rng a été initialisé avec la date, il sortira toujours la même suite de décimales.
            double randomValue = rng.nextDouble() * totalWeight;

            double currentWeight = 0;
            for (Map.Entry<Integer, Double> entry : scores.entrySet()) {
                currentWeight += entry.getValue();
                if (randomValue <= currentWeight) {
                    if (!elus.contains(entry.getKey())) {
                        elus.add(entry.getKey());
                        scores.remove(entry.getKey()); // On l'enlève pour ne pas le repiocher
                    }
                    break;
                }
            }
            safetyCounter++;
        }
        return elus;
    }

    public void importCsv(MultipartFile file) throws IOException, CsvException {
        try (Reader reader = new InputStreamReader(file.getInputStream())) {
            CSVParser parser = new CSVParserBuilder().withSeparator(';').build();
            CSVReader csvReader = new CSVReaderBuilder(reader)
                    .withCSVParser(parser)
                    .withSkipLines(1) // sauter le header
                    .build();

            List<String[]> rows = csvReader.readAll();
            DateTimeFormatter fmt = DateTimeFormatter.ofPattern("dd/MM/yyyy");

            for (String[] row : rows) {
                // Mapping basé sur votre fichier : Date=Index 2, Boules=4-8, Chance=9
                LocalDate date = LocalDate.parse(row[2], fmt);

                // Eviter les doublons si on réimporte
                if (!repository.existsByDateTirage(date)) {
                    Tirage t = new Tirage();
                    t.setDateTirage(date);
                    t.setBoule1(Integer.parseInt(row[4]));
                    t.setBoule2(Integer.parseInt(row[5]));
                    t.setBoule3(Integer.parseInt(row[6]));
                    t.setBoule4(Integer.parseInt(row[7]));
                    t.setBoule5(Integer.parseInt(row[8]));
                    t.setNumeroChance(Integer.parseInt(row[9]));
                    repository.save(t);
                }
            }

        }
    }

    public void ajouterTirageManuel(TirageManuelDto dto) {
        if (repository.existsByDateTirage(dto.getDateTirage())) {
            throw new RuntimeException("Un tirage existe déjà pour cette date !");
        }

        List<Integer> boules = Arrays.asList(
          dto.getBoule1(), dto.getBoule2(), dto.getBoule3(), dto.getBoule4(), dto.getBoule5()
        );

        Set<Integer> uniqueBoules = new HashSet<>(boules);
        if (uniqueBoules.size() < 5) {
            throw new RuntimeException("Impossible d'avoir deux fois le même numéro dans un tirage");
        }

        Tirage t = new Tirage();
        t.setDateTirage(dto.getDateTirage());
        t.setBoule1(dto.getBoule1());
        t.setBoule2(dto.getBoule2());
        t.setBoule3(dto.getBoule3());
        t.setBoule4(dto.getBoule4());
        t.setBoule5(dto.getBoule5());
        t.setNumeroChance(dto.getNumeroChance());

        repository.save(t);
    }

    public SimulationResultDto simulerGrilleDetaillee(List<Integer> boulesJouees, LocalDate dateSimul) {
        List<Tirage> historique = repository.findAll();

        SimulationResultDto result = new SimulationResultDto();
        result.setDateSimulee(dateSimul.format(DateTimeFormatter.ofPattern("dd/MM/yyyy")));
        result.setJourSimule(dateSimul.getDayOfWeek().getDisplayName(TextStyle.FULL, Locale.FRANCE).toUpperCase());

        // Initialisation des listes
        result.setQuintuplets(new ArrayList<>());
        result.setQuartets(new ArrayList<>());
        result.setTrios(new ArrayList<>());
        result.setPairs(new ArrayList<>());

        // Pour chaque tirage de l'histoire
        for (Tirage t : historique) {
            // Calcul de l'intersection (Numéros communs)
            List<Integer> commun = new ArrayList<>(t.getBoules());
            commun.retainAll(boulesJouees); // Garde uniquement les numéros qui sont dans les deux listes

            int taille = commun.size();

            if (taille >= 2) {
                // On prépare l'objet résultat
                String dateHist = t.getDateTirage().format(DateTimeFormatter.ofPattern("dd/MM/yyyy"));
                boolean memeJour = t.getDateTirage().getDayOfWeek() == dateSimul.getDayOfWeek();

                // On ajoute l'info dans la bonne catégorie
                addToResult(result, taille, commun, dateHist, memeJour, historique.size());
            }
        }

        return result;
    }

    private void addToResult(SimulationResultDto res, int taille, List<Integer> nums, String date, boolean memeJour, int totalTirages) {
        // On cherche si ce groupe de numéros existe déjà dans la liste pour ne pas faire de doublons, mais ajouter la date
        List<MatchGroup> targetList = switch (taille) {
            case 5 -> res.getQuintuplets();
            case 4 -> res.getQuartets();
            case 3 -> res.getTrios();
            case 2 -> res.getPairs();
            default -> null;
        };

        if (targetList != null) {
            // On trie les numéros pour que [7, 21] soit pareil que [21, 7]
            Collections.sort(nums);

            // Recherche existant
            Optional<MatchGroup> existing = targetList.stream()
                    .filter(m -> m.getNumeros().equals(nums))
                    .findFirst();

            if (existing.isPresent()) {
                MatchGroup group = existing.get();
                group.getDates().add(date + (memeJour ? " (Même jour !)" : ""));
                if(memeJour) group.setSameDayOfWeek(true);

                // RECALCUL DU RATIO
                updateRatio(group, totalTirages, taille);

            } else {
                List<String> dates = new ArrayList<>();
                dates.add(date + (memeJour ? " (Même jour !)" : ""));

                MatchGroup newGroup = new MatchGroup(nums, dates, memeJour, 0.0);
                updateRatio(newGroup, totalTirages, taille); // Calcul initial
                targetList.add(newGroup);
            }
        }
    }

    private void updateRatio(MatchGroup group, int totalTirages, int taille) {
        double probaTheo = getProbabiliteTheorique(taille);
        double nbreAttendu = totalTirages * probaTheo;
        int nbreReel = group.getDates().size();

        // Si on attendait 10 sorties et qu'on en a eu 15, ratio = 1.5 (150%)
        double ratio = (nbreAttendu > 0) ? (nbreReel / nbreAttendu) : 0.0;

        // On arrondit à 2 décimales
        group.setRatio(Math.round(ratio * 100.0) / 100.0);
    }

    // Ajoutez cette méthode utilitaire pour les probabilités théoriques
    private double getProbabiliteTheorique(int tailleGroupe) {
        // Calculs basés sur C(49,5) = 1,906,884 combinaisons
        return switch (tailleGroupe) {
            case 1 -> 0.10204;       // ~10.2%
            case 2 -> 0.00850;       // ~0.85% (1 chance sur 117)
            case 3 -> 0.00041;       // ~0.04% (1 chance sur 2413)
            case 4 -> 0.0000096;     // ~0.0009%
            case 5 -> 0.00000052;    // Jackpot
            default -> 0.0;
        };
    }

    public StatsReponse getStats(String jourFiltre) {
        List<Tirage> all = repository.findAll();

        // 1. FILTRAGE (Code existant inchangé)
        if (jourFiltre != null && !jourFiltre.isEmpty()) {
            try {
                DayOfWeek targetDay = DayOfWeek.valueOf(jourFiltre.toUpperCase());
                all = all.stream().filter(t -> t.getDateTirage().getDayOfWeek() == targetDay).collect(Collectors.toList());
            } catch (IllegalArgumentException e) { /* ... */ }
        }

        if (all.isEmpty()) return new StatsReponse(new ArrayList<>(), "-", "-", 0);

        LocalDate minDate = all.stream().map(Tirage::getDateTirage).min(LocalDate::compareTo).orElse(LocalDate.now());
        LocalDate maxDate = all.stream().map(Tirage::getDateTirage).max(LocalDate::compareTo).orElse(LocalDate.now());
        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("dd/MM/yyyy");

        // 2. INITIALISATION DES MAPS
        Map<Integer, Integer> freqMap = new HashMap<>();
        Map<Integer, LocalDate> lastSeenMap = new HashMap<>();

        // NOUVEAU : Maps pour les numéros chance
        Map<Integer, Integer> freqChanceMap = new HashMap<>();
        Map<Integer, LocalDate> lastSeenChanceMap = new HashMap<>();

        // 3. REMPLISSAGE (Boucle unique)
        for (Tirage t : all) {
            // A. Boules classiques
            for (Integer b : t.getBoules()) {
                freqMap.merge(b, 1, Integer::sum);
                if (!lastSeenMap.containsKey(b) || t.getDateTirage().isAfter(lastSeenMap.get(b))) {
                    lastSeenMap.put(b, t.getDateTirage());
                }
            }
            // B. Numéro Chance (NOUVEAU)
            int chance = t.getNumeroChance();
            freqChanceMap.merge(chance, 1, Integer::sum);
            if (!lastSeenChanceMap.containsKey(chance) || t.getDateTirage().isAfter(lastSeenChanceMap.get(chance))) {
                lastSeenChanceMap.put(chance, t.getDateTirage());
            }
        }

        List<StatPoint> stats = new ArrayList<>();

        // 4. STATS BOULES (1-49)
        for (int i = 1; i <= 49; i++) {
            StatPoint s = new StatPoint();
            s.setNumero(i);
            s.setFrequence(freqMap.getOrDefault(i, 0));
            s.setChance(false); // C'est une boule normale
            LocalDate last = lastSeenMap.get(i);
            s.setEcart(last == null ? 999 : (int) ChronoUnit.DAYS.between(last, maxDate));
            stats.add(s);
        }

        // 5. STATS CHANCE (1-10) (NOUVEAU)
        for (int i = 1; i <= 10; i++) {
            StatPoint s = new StatPoint();
            s.setNumero(i);
            s.setFrequence(freqChanceMap.getOrDefault(i, 0));
            s.setChance(true); // C'est un numéro chance !
            LocalDate last = lastSeenChanceMap.get(i);
            s.setEcart(last == null ? 999 : (int) ChronoUnit.DAYS.between(last, maxDate));
            stats.add(s);
        }

        return new StatsReponse(stats, minDate.format(fmt), maxDate.format(fmt), all.size());
    }
}
