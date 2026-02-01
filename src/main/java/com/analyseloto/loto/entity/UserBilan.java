package com.analyseloto.loto.entity;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonIgnore;
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

    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd")
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
