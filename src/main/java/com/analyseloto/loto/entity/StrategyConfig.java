package com.analyseloto.loto.entity;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;

@Entity
@Data
@Table(name = "strategy_history", indexes = {
        @Index(name = "idx_date_calcul", columnList = "dateCalcul")
})
public class StrategyConfig {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private LocalDateTime dateCalcul;

    // Paramètres de l'algo
    private double poidsFreqJour;
    private double poidsForme;
    private double poidsEcart;
    private double poidsTension;
    private double poidsMarkov;
    private double poidsAffinite;

    // Résultats du Backtest (pour savoir pourquoi cette config a gagné)
    private double bilanEstime; // ex: -350.50
    private int nbTiragesTestes; // ex: 50
    private String nomStrategie; // ex: "TEST_86"

    @Column(name = "nb_grilles_par_test")
    private Integer nbGrillesParTest;

    @Column(name = "roi")
    private Double roi;

    // Flag pour identifier le leader du batch
    private boolean leader;
}
