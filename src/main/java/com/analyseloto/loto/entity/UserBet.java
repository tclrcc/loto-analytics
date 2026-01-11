package com.analyseloto.loto.entity;

import com.analyseloto.loto.enums.BetType;
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

    @Enumerated(EnumType.STRING)
    private BetType type;

    // Les numéros joués
    private Integer b1;
    private Integer b2;
    private Integer b3;
    private Integer b4;
    private Integer b5;
    private Integer chance;

    @Column(name = "code_loto")
    private String codeLoto;

    @Column(nullable = false)
    private double mise; // Combien ça a coûté (ex: 2.20)

    private Double gain; // Combien ça a rapporté (Null = pas encore tiré/vérifié)

    // Helper pour savoir si le pari est "fermé"
    public boolean isChecked() {
        return gain != null;
    }
}
