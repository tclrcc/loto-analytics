package com.analyseloto.loto.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class AstroProfileDto {
    private String dateNaissance; // YYYY-MM-DD
    private String timeNaissance; // HH:mm
    private String ville;
    private String signe; // "BELIER", "TAUREAU"...
}
