package com.analyseloto.loto.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Data
@NoArgsConstructor
public class JobLog {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String jobName;
    private LocalDateTime startTime;
    private LocalDateTime endTime;

    private String status;         // "SUCCESS", "FAILURE", "WARNING"

    @Column(length = 1000)         // Pour stocker un message d'erreur si besoin
    private String message;

    public long getDurationInSeconds() {
        if (startTime == null || endTime == null) return 0;
        return java.time.Duration.between(startTime, endTime).toSeconds();
    }

    public JobLog(String jobName) {
        this.jobName = jobName;
        this.startTime = LocalDateTime.now();
        this.status = "RUNNING";
    }
}