package com.analyseloto.loto.dto;

import com.analyseloto.loto.service.LotoService;
import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.List;

@Data
@AllArgsConstructor
public class StatsReponse {
    private List<LotoService.StatPoint> points; // Vos stats habituelles
    private String dateMin;
    private String dateMax;
    private int nombreTirages; // Petit bonus : le nombre total de tirages analysés
}
