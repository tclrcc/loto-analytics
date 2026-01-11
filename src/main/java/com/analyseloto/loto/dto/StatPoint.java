package com.analyseloto.loto.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class StatPoint {
    private int numero;
    private int frequence;
    private int ecart;
    private boolean isChance; // Assurez-vous que c'est bien "isChance" ou "chance" selon ce que vous préférez
}
