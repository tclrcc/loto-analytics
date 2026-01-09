package com.analyseloto.loto.entity;

import com.analyseloto.loto.enums.LotoRank;
import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.ToString;

@Data
@Entity
@NoArgsConstructor
@Table(name = "loto_tirage_ranks")
public class LotoTirageRank {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private int rankNumber;   // ex: 1 pour le Jackpot, 2 pour 5 bons numéros...
    private int winners;      // Nombre de gagnants
    private double prize;     // Gain par gagnant (ex: 2000000.00)

    // Relation vers le Tirage Parent
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "loto_tirage_id")
    @ToString.Exclude // Important pour éviter boucle infinie
    private LotoTirage lotoTirage;

    public LotoTirageRank(int rankNumber, int winners, double prize) {
        this.rankNumber = rankNumber;
        this.winners = winners;
        this.prize = prize;
    }

    public String getDescription() {
        LotoRank rank = LotoRank.fromPosition(this.rankNumber);
        return rank != null ? rank.getDescription() : "Rang inconnu";
    }
}
