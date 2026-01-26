package com.analyseloto.loto.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.time.LocalDate;

@Entity
@Data
@NoArgsConstructor
@Table(name = "user_bilan", uniqueConstraints = {
        @UniqueConstraint(columnNames = {"user_id", "date_bilan"}) // Un seul bilan par user et par jour
})
public class UserBilan {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "date_bilan", nullable = false)
    private LocalDate dateBilan;

    // --- DONNÉES CUMULÉES JUSQU'À CETTE DATE ---
    private double totalDepense;
    private double totalGains;
    private double solde;      // = totalGains - totalDepense
    private double roi;        // En pourcentage
    private int nbGrillesJouees;
    private int nbGrillesGagnantes;

    public UserBilan(User user, LocalDate dateBilan) {
        this.user = user;
        this.dateBilan = dateBilan;
    }
}
