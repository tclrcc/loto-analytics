package com.analyseloto.loto.service;

import com.analyseloto.loto.entity.JobLog;
import com.analyseloto.loto.repository.JobLogRepository;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class JobMonitorService {
    // Repositories
    private final JobLogRepository jobLogRepository;

    // Cron expressions depuis properties
    @Value("${loto.jobs.cron.fdj-recovery}") private String cronFdjRecovery;
    @Value("${loto.jobs.cron.gen-pronos}") private String cronGenPronos;
    @Value("${loto.jobs.cron.send-emails}") private String cronSendEmails;
    @Value("${loto.jobs.cron.budget-alert}") private String cronBudgetAlert;
    @Value("${loto.jobs.cron.user-cleanup}") private String cronUserCleanup;
    @Value("${loto.jobs.cron.db-cleanup}") private String cronDbCleanup;

    public List<JobScheduleDto> getUpcomingJobs() {
        List<JobScheduleDto> jobs = new ArrayList<>();

        jobs.add(createJob("Récupération FDJ", cronFdjRecovery));
        jobs.add(createJob("Génération Pronostics IA", cronGenPronos));
        jobs.add(createJob("Envoi Emails Joueurs", cronSendEmails));
        jobs.add(createJob("Alerte Budget Hebdo", cronBudgetAlert));
        jobs.add(createJob("Nettoyage Comptes Inactifs", cronUserCleanup));
        jobs.add(createJob("Maintenance BDD", cronDbCleanup));

        // On trie pour afficher le plus proche en premier
        jobs.sort(Comparator.comparing(JobScheduleDto::getNextExecution));

        return jobs;
    }

    public List<JobLog> getRecentJobs(int hours) {
        // Définition date début = 48h avant
        LocalDateTime since = LocalDateTime.now().minusHours(hours);

        // retourne les jobs depuis cette date
        return jobLogRepository.findByStartTimeAfterOrderByStartTimeDesc(since);
    }

    private JobScheduleDto createJob(String name, String cron) {
        // Calcul de la prochaine date basée sur le CRON et la Zone Paris
        LocalDateTime next = CronExpression.parse(cron)
                .next(LocalDateTime.now(ZoneId.of("Europe/Paris")));

        assert next != null;
        return new JobScheduleDto(name, cron, next);
    }

    @Data
    public static class JobScheduleDto {
        private String name;
        private String cronExpression;
        private LocalDateTime nextExecution;
        private boolean isToday;

        public JobScheduleDto(String name, String cronExpression, LocalDateTime nextExecution) {
            this.name = name;
            this.cronExpression = cronExpression;
            this.nextExecution = nextExecution;
            this.isToday = nextExecution.toLocalDate().equals(LocalDateTime.now(ZoneId.of("Europe/Paris")).toLocalDate());
        }
    }

    /**
     * Enregistrement log job
     * @param name nom job
     * @return JobLog
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
    public List<JobLog> getHistory() {
        return jobLogRepository.findAll();
    }


}
