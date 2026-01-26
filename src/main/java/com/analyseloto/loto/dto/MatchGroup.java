package com.analyseloto.loto.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

@Data
@AllArgsConstructor
public class MatchGroup implements Serializable {
    @Serial private static final long serialVersionUID = 1L;

    private List<Integer> numeros; // Les numéros en commun (ex: 7, 21)
    private List<String> dates;    // Liste des dates où c'est arrivé
    private boolean sameDayOfWeek; // Est-ce que c'est arrivé le même jour (ex: Lundi) ?
    private double ratio; // Nouveau champ : 1.0 = Normal, 2.0 = 2x plus que la moyenne
}
