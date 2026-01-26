package com.analyseloto.loto.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

@Data
@AllArgsConstructor
public class PronosticResultDto implements Serializable {
    @Serial private static final long serialVersionUID = 1L;

    private List<Integer> boules;
    private int numeroChance;
    private double scoreGlobal; // Un score de "qualité" de la grille
    private double maxRatioDuo; // La perf de la meilleure paire
    private double maxRatioTrio; // La perf du meilleur trio
    private boolean dejaSortie; // Est-ce que la grille complète existe déjà ?
    private String typeAlgo; // ex: "OPTIMAL", "FLEXIBLE", "HASARD"
}
