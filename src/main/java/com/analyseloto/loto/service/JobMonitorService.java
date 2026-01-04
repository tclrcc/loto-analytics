package com.analyseloto.loto.service;

import com.analyseloto.loto.entity.JobLog;
import com.analyseloto.loto.repository.JobLogRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class JobMonitorService {
    // Repositories
    private final JobLogRepository jobLogRepository;

    /**
     * Enregistrement log job
     * @param name nom job
     * @return
     */
    @Transactional
    public JobLog startJob(String name) {
        JobLog log = new JobLog(name);
        return jobLogRepository.save(log);
    }

    /**
     * Modification statut et message d'un job
     * @param log log
     * @param status état
     * @param message message
     */
    @Transactional
    public void endJob(JobLog log, String status, String message) {
        log.setEndTime(LocalDateTime.now());
        log.setStatus(status);
        // On tronque le message s'il est trop long pour la BDD
        if (message != null && message.length() > 990) {
            message = message.substring(0, 990) + "...";
        }
        log.setMessage(message);
        jobLogRepository.save(log);
    }

    /**
     * Renvoie liste des 50 derniers jobs
     * @return liste jobs
     */
    public List<JobLog> getHistory50Jobs() {
        return jobLogRepository.findTop50ByOrderByStartTimeDesc();
    }
}
