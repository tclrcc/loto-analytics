package com.analyseloto.loto.dto;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

@Data
public class AstroResultDto implements Serializable {
    @Serial private static final long serialVersionUID = 1L;

    private String signeSolaire;
    private int cheminDeVie;
    private String element;
    private String phraseHoroscope;
    private List<Integer> luckyNumbers;
    private int luckyChance;
}
