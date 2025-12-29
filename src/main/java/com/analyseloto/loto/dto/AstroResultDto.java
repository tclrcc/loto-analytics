package com.analyseloto.loto.dto;

import lombok.Data;

import java.util.List;

@Data
public class AstroResultDto {
    private String signeSolaire;
    private int cheminDeVie;
    private String element;
    private String phraseHoroscope;
    private List<Integer> luckyNumbers;
    private int luckyChance;
}
