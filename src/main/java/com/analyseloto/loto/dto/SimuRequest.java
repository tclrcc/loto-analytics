package com.analyseloto.loto.dto;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDate;
import java.util.List;
@Data
public class SimuRequest implements Serializable {
    @Serial private static final long serialVersionUID = 1L;

    private List<Integer> boules;
    private LocalDate date;
}
