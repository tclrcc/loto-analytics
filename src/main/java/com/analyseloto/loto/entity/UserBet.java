package com.analyseloto.loto.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

@Entity
@Data
@NoArgsConstructor
@Table(name = "user_bets")
public class UserBet {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(nullable = false)
    private LocalDate dateJeu;

    // Les numéros joués
    private int b1;
    private int b2;
    private int b3;
    private int b4;
    private int b5;
    private int chance;

    @Column(nullable = false)
    private double mise; // Combien ça a coûté (ex: 2.20)

    private Double gain; // Combien ça a rapporté (Null = pas encore tiré/vérifié)

    // Helper pour savoir si le pari est "fermé"
    public boolean isChecked() {
        return gain != null;
    }
}