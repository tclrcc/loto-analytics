package com.analyseloto.loto.repository;

import com.analyseloto.loto.entity.JobLog;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface JobLogRepository extends JpaRepository<JobLog, Long> {
    List<JobLog> findByStartTimeAfterOrderByStartTimeDesc(java.time.LocalDateTime time);
    List<JobLog> findAllOrderByStartTimeDesc();
}
