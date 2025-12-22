package com.analyseloto.loto.dto;

import lombok.Data;

import java.time.LocalDate;

@Data
public class TirageManuelDto {
    private LocalDate dateTirage;
    private int boule1;
    private int boule2;
    private int boule3;
    private int boule4;
    private int boule5;
    private int numeroChance;
}
