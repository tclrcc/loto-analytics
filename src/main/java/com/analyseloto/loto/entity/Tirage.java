package com.analyseloto.loto.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDate;
import java.util.List;

@Entity
@Data
@Table(name = "tirage", uniqueConstraints = {
        @UniqueConstraint(columnNames = "dateTirage")
})
public class Tirage {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(unique = true)
    private LocalDate dateTirage;

    // On stocke les boules séparément pour faciliter le SQL si besoin
    private int boule1;
    private int boule2;
    private int boule3;
    private int boule4;
    private int boule5;
    private int numeroChance;

    // Helper pour récupérer les boules sous forme de liste
    public List<Integer> getBoules() {
        return List.of(boule1, boule2, boule3, boule4, boule5);
    }
}
