package com.analyseloto.loto.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

@Entity
@Data
@Table(name = "tirage", uniqueConstraints = {
        @UniqueConstraint(columnNames = "dateTirage")
})
public class LotoTirage {
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

    // CascadeType.ALL signifie : Si je sauvegarde le Tirage, ça sauvegarde aussi les Ranks automatiquement
    @OneToMany(mappedBy = "lotoTirage", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<LotoTirageRank> ranks = new ArrayList<>();

    @ElementCollection
    @CollectionTable(name = "loto_tirage_codes", joinColumns = @JoinColumn(name = "tirage_id"))
    @Column(name = "code")
    private List<String> winningCodes = new ArrayList<>();

    // Helper pour récupérer les boules sous forme de liste
    public List<Integer> getBoules() {
        return List.of(boule1, boule2, boule3, boule4, boule5);
    }

    public void addRank(LotoTirageRank rank) {
        ranks.add(rank);
        rank.setLotoTirage(this);
    }
}
