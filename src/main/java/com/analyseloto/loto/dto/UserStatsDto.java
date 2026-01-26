package com.analyseloto.loto.dto;

import com.analyseloto.loto.entity.UserBilan;
import lombok.Data;

import java.util.List;
import java.util.Map;

@Data
public class UserStatsDto {
    private int totalGrilles;
    private double depenseTotale;
    private double gainTotal;

    // Stats analytiques
    private double moyenneSomme; // La somme moyenne de ses grilles
    private String pariteMoyenne; // Ex: "3 Pairs / 2 Impairs"
    private int totalPairsJoues;
    private int totalImpairsJoues;

    // Top Fréquences
    private List<StatNumero> topBoules; // Ses numéros préférés
    private List<StatNumero> topChance; // Ses numéros chance préférés
    private List<Integer> numJamaisJoues; // Ce qu'il ne joue jamais

    // Performances du joueur selon le jour
    private Map<String, DayPerformance> performanceParJour;

    private List<UserBilan> historiqueFinancier;

    @Data
    public static class StatNumero {
        private int numero;
        private int count;
        public StatNumero(int n, int c) { this.numero = n; this.count = c; }
    }

    @Data
    public static class DayPerformance {
        private String jourNom; // "Lundi", "Mercredi"...
        private int nbJeux;
        private double depense;
        private double gains;

        public DayPerformance(String jourNom) {
            this.jourNom = jourNom;
            this.nbJeux = 0;
            this.depense = 0.0;
            this.gains = 0.0;
        }

        // Helper pour Thymeleaf
        public double getSolde() {
            return gains - depense;
        }
    }
}
