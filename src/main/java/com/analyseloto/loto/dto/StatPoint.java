package com.analyseloto.loto.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class StatPoint implements Serializable {
    @Serial private static final long serialVersionUID = 1L;

    private int numero;
    private int frequence;
    private int ecart;
    private boolean isChance; // Assurez-vous que c'est bien "isChance" ou "chance" selon ce que vous préférez
}
