package com.analyseloto.loto.dto;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDate;

@Data
public class TirageManuelDto implements Serializable {
    @Serial private static final long serialVersionUID = 1L;

    private LocalDate dateTirage;
    private int boule1;
    private int boule2;
    private int boule3;
    private int boule4;
    private int boule5;
    private int numeroChance;
}
