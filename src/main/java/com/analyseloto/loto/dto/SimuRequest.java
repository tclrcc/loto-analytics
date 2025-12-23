package com.analyseloto.loto.dto;

import lombok.Data;

import java.time.LocalDate;
import java.util.List;
@Data
public class SimuRequest {
    private List<Integer> boules;
    private LocalDate date;
}
