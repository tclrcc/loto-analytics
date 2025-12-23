package com.analyseloto.loto.dto;

import lombok.Data;

import java.util.List;

@Data
public class SimulationResultDto {
    private String dateSimulee;
    private String jourSimule; // ex: "LUNDI"
    // Liste des correspondances trouvées
    private List<MatchGroup> quintuplets; // 5 numéros
    private List<MatchGroup> quartets;    // 4 numéros
    private List<MatchGroup> trios;       // 3 numéros
    private List<MatchGroup> pairs;       // 2 numéros
}
