package com.analyseloto.loto.service;

import com.analyseloto.loto.dto.StatsReponse;
import com.analyseloto.loto.dto.TirageManuelDto;
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
