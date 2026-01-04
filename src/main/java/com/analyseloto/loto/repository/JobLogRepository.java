package com.analyseloto.loto.repository;

import com.analyseloto.loto.entity.JobLog;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface JobLogRepository extends JpaRepository<JobLog, Long> {
    /**
     * Récupére les 50 derniers Job selon la date de démarrage
     * @return liste jobs
     */
    List<JobLog> findTop50ByOrderByStartTimeDesc();
}
