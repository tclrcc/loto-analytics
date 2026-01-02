package com.analyseloto.loto.dto;

import lombok.Data;

import java.util.List;

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

    @Data
    public static class StatNumero {
        private int numero;
        private int count;
        public StatNumero(int n, int c) { this.numero = n; this.count = c; }
    }
}
