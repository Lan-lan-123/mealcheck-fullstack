package com.example.mealcheck.repository;

import com.example.mealcheck.entity.WeeklyReportCoordinationStep;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

public interface WeeklyReportCoordinationStepRepository extends JpaRepository<WeeklyReportCoordinationStep, Long> {
    List<WeeklyReportCoordinationStep> findByRunIdOrderByStartedAtAscIdAsc(String runId);

    long countByRunId(String runId);

    long countByRunIdAndStatus(String runId, String status);

    @Modifying
    @Transactional
    @Query("""
            update WeeklyReportCoordinationStep step
            set step.status = 'INTERRUPTED',
                step.outcome = 'INTERRUPTED',
                step.errorMessage = :errorMessage,
                step.finishedAt = :finishedAt
            where step.runId = :runId and step.status = 'RUNNING'
            """)
    int interruptRunning(@Param("runId") String runId,
                         @Param("errorMessage") String errorMessage,
                         @Param("finishedAt") LocalDateTime finishedAt);

    @Modifying
    @Transactional
    @Query("""
            update WeeklyReportCoordinationStep step
            set step.status = 'INTERRUPTED',
                step.outcome = 'INTERRUPTED',
                step.errorMessage = :errorMessage,
                step.finishedAt = :finishedAt
            where step.status = 'RUNNING'
              and step.runId in (
                  select run.runId from WeeklyReportCoordinationRun run
                  where run.status = 'INTERRUPTED'
              )
            """)
    int interruptOrphaned(@Param("errorMessage") String errorMessage,
                          @Param("finishedAt") LocalDateTime finishedAt);

    @Modifying
    @Transactional
    @Query("""
            update WeeklyReportCoordinationStep step
            set step.status = :status,
                step.outcome = :outcome,
                step.aiGenerated = :aiGenerated,
                step.fallbackUsed = :fallbackUsed,
                step.latencyMs = :latencyMs,
                step.errorMessage = :errorMessage,
                step.finishedAt = :finishedAt
            where step.id = :stepId and step.status = 'RUNNING'
            """)
    int finish(@Param("stepId") Long stepId,
               @Param("status") String status,
               @Param("outcome") String outcome,
               @Param("aiGenerated") Boolean aiGenerated,
               @Param("fallbackUsed") boolean fallbackUsed,
               @Param("latencyMs") long latencyMs,
               @Param("errorMessage") String errorMessage,
               @Param("finishedAt") LocalDateTime finishedAt);
}
