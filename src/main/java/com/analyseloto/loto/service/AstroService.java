package com.analyseloto.loto.service;

import lombok.Data;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

import static java.util.Map.entry;

@Service
public class AstroService {

    @Data
    public static class AstroProfileDto {
        private String dateNaissance; // YYYY-MM-DD
        private String timeNaissance; // HH:mm
        private String ville;
        private String signe; // "BELIER", "TAUREAU"...
    }

    @Data
    public static class AstroResultDto {
        private String signeSolaire;
        private int cheminDeVie;
        private String element; // Feu, Terre, Air, Eau
        private String phraseHoroscope;
        private List<Integer> luckyNumbers; // 5 numéros
        private int luckyChance; // 1 numéro
    }

    /**
     * Renvoie les numéros chanceux selon le profil astral
     * @param profil
     * @return
     */
    public List<Integer> getLuckyNumbersOnly(AstroProfileDto profil) {
        LocalDate birthDate = LocalDate.parse(profil.getDateNaissance());

        // 1. Numéro Chemin de Vie
        int cheminDeVie = calculerCheminDeVie(birthDate);

        // 2. Numéros du Signe
        List<Integer> baseSigne = getBaseNumbersForSign(profil.getSigne());

        // On fusionne tout ça
        Set<Integer> lucky = new HashSet<>(baseSigne);
        if (cheminDeVie <= 49) lucky.add(cheminDeVie);

        return new ArrayList<>(lucky);
    }

    public AstroResultDto analyserProfil(AstroProfileDto profil) {
        Map<String, String> ELEMENTS = Map.ofEntries(
                entry("BELIER", "FEU"),
                entry("LION", "FEU"),
                entry("SAGITTAIRE", "FEU"),
                entry("TAUREAU", "TERRE"),
                entry("VIERGE", "TERRE"),
                entry("CAPRICORNE", "TERRE"),
                entry("GEMEAUX", "AIR"),
                entry("BALANCE", "AIR"),
                entry("VERSEAU", "AIR"),
                entry("CANCER", "EAU"),
                entry("SCORPION", "EAU"),
                entry("POISSONS", "EAU")
        );

        LocalDate birthDate = LocalDate.parse(profil.dateNaissance);
        LocalTime birthTime = LocalTime.parse(profil.timeNaissance);
        LocalDate today = LocalDate.now();

        // 1. Calcul du Chemin de Vie (Numérologie)
        int cheminDeVie = calculerCheminDeVie(birthDate);

        // 2. Génération Déterministe (Basée sur la personne + la date du jour)
        // La "graine" combine la date du jour, la date de naissance, l'heure et la ville.
        // Cela garantit que pour une journée donnée, le conseil reste le même (pas de random qui change à chaque clic).
        long seed = today.toEpochDay() + birthDate.toEpochDay() + birthTime.toSecondOfDay() + profil.ville.toLowerCase().hashCode();
        Random rng = new Random(seed);

        // 3. Sélection des Numéros
        List<Integer> luckyNumbers = new ArrayList<>();

        // A. Le numéro du chemin de vie est souvent un porte-bonheur (s'il est < 49)
        if (cheminDeVie <= 49) luckyNumbers.add(cheminDeVie);

        // B. Numéros basés sur le signe (Préférences fixes)
        List<Integer> baseSigne = getBaseNumbersForSign(profil.getSigne());
        luckyNumbers.add(baseSigne.get(rng.nextInt(baseSigne.size())));

        // C. Compléter avec des numéros "astraux" générés par la graine
        while (luckyNumbers.size() < 5) {
            int n = 1 + rng.nextInt(49);
            if (!luckyNumbers.contains(n)) luckyNumbers.add(n);
        }
        Collections.sort(luckyNumbers);

        // D. Numéro Chance (1-10)
        int luckyChance = 1 + rng.nextInt(10);

        // 4. Construction de la réponse
        AstroResultDto result = new AstroResultDto();
        result.setSigneSolaire(profil.getSigne());
        result.setCheminDeVie(cheminDeVie);
        result.setElement(ELEMENTS.getOrDefault(profil.getSigne().toUpperCase(), "MYSTÈRE"));
        result.setLuckyNumbers(luckyNumbers);
        result.setLuckyChance(luckyChance);
        result.setPhraseHoroscope(genererPhrase(result.getElement(), rng));

        return result;
    }

    /**
     * Calcul le numéro de chemin de vie de la personne
     * @param date
     * @return
     */
    private int calculerCheminDeVie(LocalDate date) {
        // Somme de tous les chiffres : 1990-12-05 -> 1+9+9+0+1+2+0+5 = 27 -> 2+7 = 9
        String s = date.format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        int sum = 0;
        for (char c : s.toCharArray()) sum += Character.getNumericValue(c);

        // Réduction tant que > 9 (sauf 11, 22, 33 maîtres nombres)
        while (sum > 9) {
            int temp = 0;
            String s2 = String.valueOf(sum);
            for (char c : s2.toCharArray()) temp += Character.getNumericValue(c);
            sum = temp;
        }
        return sum;
    }

    private List<Integer> getBaseNumbersForSign(String signe) {
        // Numéros traditionnellement associés aux signes
        return switch (signe.toUpperCase()) {
            case "BELIER" -> List.of(9, 18, 27, 36, 45, 1, 10);
            case "TAUREAU" -> List.of(6, 15, 24, 33, 42, 2, 11);
            case "GEMEAUX" -> List.of(5, 14, 23, 32, 41, 3, 12);
            case "CANCER" -> List.of(2, 11, 20, 29, 38, 47, 7);
            case "LION" -> List.of(1, 10, 19, 28, 37, 46, 5);
            case "VIERGE" -> List.of(5, 14, 23, 32, 41, 6, 15);
            case "BALANCE" -> List.of(6, 15, 24, 33, 42, 9, 18);
            case "SCORPION" -> List.of(9, 18, 27, 36, 45, 8, 17);
            case "SAGITTAIRE" -> List.of(3, 12, 21, 30, 39, 48, 9);
            case "CAPRICORNE" -> List.of(8, 17, 26, 35, 44, 4, 13);
            case "VERSEAU" -> List.of(4, 13, 22, 31, 40, 49, 8);
            case "POISSONS" -> List.of(7, 16, 25, 34, 43, 2, 11);
            default -> List.of(1, 7, 13);
        };
    }

    private String genererPhrase(String element, Random rng) {
        List<String> phrases = switch (element) {
            case "FEU" -> List.of("L'énergie est avec vous, osez les numéros chauds !", "Mars booste votre intuition aujourd'hui.");
            case "TERRE" -> List.of("Misez sur la stabilité et les numéros en retard.", "Saturne conseille la prudence.");
            case "AIR" -> List.of("Le hasard vous sourit, tentez une combinaison inédite.", "Mercure favorise les échanges... et les gains ?");
            case "EAU" -> List.of("Écoutez votre petite voix intérieure.", "La Lune influence vos choix, suivez votre instinct.");
            default -> List.of("Les astres sont mystérieux aujourd'hui...");
        };
        return phrases.get(rng.nextInt(phrases.size()));
    }
}