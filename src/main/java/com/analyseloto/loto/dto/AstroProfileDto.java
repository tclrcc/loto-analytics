package com.analyseloto.loto.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class AstroProfileDto implements Serializable {
    @Serial private static final long serialVersionUID = 1L;

    private String dateNaissance; // YYYY-MM-DD
    private String timeNaissance; // HH:mm
    private String ville;
    private String signe; // "BELIER", "TAUREAU"...
}
