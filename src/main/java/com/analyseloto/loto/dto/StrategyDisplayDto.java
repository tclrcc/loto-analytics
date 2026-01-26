package com.analyseloto.loto.dto;

import lombok.Builder;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

@Data
@Builder
public class StrategyDisplayDto implements Serializable {
    @Serial private static final long serialVersionUID = 1L;

    private String nom;              // Ex: "DEEP BLUE IA ⭐"
    private String meteoTitre;       // Ex: "Chasse aux numéros froids"
    private String meteoDescription; // Ex: "L'IA cible les numéros oubliés..."
    private String meteoIcone;       // Ex: "bi-snow"
    private String badgePuissance;   // Ex: "78 Millions de grilles simulées"

    // Données pour le graphique Radar (normalisées sur 10)
    private double chartForme;
    private double chartEcart;
    private double chartAffinite;
    private double chartTension;
}
