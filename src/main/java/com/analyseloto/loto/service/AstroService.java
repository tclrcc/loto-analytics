package com.analyseloto.loto.service;

import com.analyseloto.loto.dto.AstroProfileDto;
import com.analyseloto.loto.dto.AstroResultDto;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

import static java.util.Map.entry;

@Service
public class AstroService {

    /**
     * Renvoie uniquement les numéros chanceux (Utilisé par LotoService / Job)
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

    /**
     * Analyse complète pour l'affichage Frontend
     */
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

        LocalDate birthDate = LocalDate.parse(profil.getDateNaissance());
        LocalTime birthTime = LocalTime.parse(profil.getTimeNaissance());
        LocalDate today = LocalDate.now();

        // 1. Calcul du Chemin de Vie
        int cheminDeVie = calculerCheminDeVie(birthDate);

        // 2. Génération Déterministe
        long seed = today.toEpochDay() + birthDate.toEpochDay() + birthTime.toSecondOfDay() + profil.getVille().toLowerCase().hashCode();
        Random rng = new Random(seed);

        // 3. Sélection des Numéros
        List<Integer> luckyNumbers = new ArrayList<>();

        if (cheminDeVie <= 49) luckyNumbers.add(cheminDeVie);

        List<Integer> baseSigne = getBaseNumbersForSign(profil.getSigne());
        luckyNumbers.add(baseSigne.get(rng.nextInt(baseSigne.size())));

        while (luckyNumbers.size() < 5) {
            int n = 1 + rng.nextInt(49);
            if (!luckyNumbers.contains(n)) luckyNumbers.add(n);
        }
        Collections.sort(luckyNumbers);

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

    // --- Helpers Privés ---

    private int calculerCheminDeVie(LocalDate date) {
        String s = date.format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        int sum = 0;
        for (char c : s.toCharArray()) sum += Character.getNumericValue(c);

        while (sum > 9 && sum != 11 && sum != 22 && sum != 33) { // Petit fix pour conserver les maîtres nombres
            int temp = 0;
            String s2 = String.valueOf(sum);
            for (char c : s2.toCharArray()) temp += Character.getNumericValue(c);
            sum = temp;
        }
        return sum;
    }

    private List<Integer> getBaseNumbersForSign(String signe) {
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