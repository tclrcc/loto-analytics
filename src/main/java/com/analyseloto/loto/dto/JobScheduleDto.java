package com.analyseloto.loto.dto;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
public class JobScheduleDto implements Serializable {
    @Serial private static final long serialVersionUID = 1L;

    private String name;
    private String cronExpression;
    private LocalDateTime nextExecution;

    // Constructeur, Getters, Setters
    public JobScheduleDto(String name, String cronExpression) {
        this.name = name;
        this.cronExpression = cronExpression;
        // Calcul de la prochaine date
        this.nextExecution = org.springframework.scheduling.support.CronExpression
                .parse(cronExpression)
                .next(LocalDateTime.now());
    }

    // Helper pour savoir si c'est aujourd'hui (pour l'affichage)
    public boolean isToday() {
        return nextExecution.toLocalDate().equals(LocalDate.now());
    }
}
