package com.example.mealcheck.repository;

import com.example.mealcheck.entity.WeeklyReportCoordinationRun;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;

public interface WeeklyReportCoordinationRunRepository extends
        JpaRepository<WeeklyReportCoordinationRun, Long>,
        JpaSpecificationExecutor<WeeklyReportCoordinationRun> {
    Optional<WeeklyReportCoordinationRun> findByRunId(String runId);

    List<WeeklyReportCoordinationRun> findTop50ByOrderByStartedAtDesc();

    List<WeeklyReportCoordinationRun> findByStatusAndUpdatedAtBefore(
            String status, LocalDateTime updatedAt);

    long countByStatus(String status);

    @Query("""
            select min(run.startedAt) from WeeklyReportCoordinationRun run
            where run.status = 'RUNNING'
            """)
    LocalDateTime findOldestRunningStartedAt();

    @Modifying
    @Transactional
    @Query("""
            update WeeklyReportCoordinationRun run
            set run.updatedAt = :heartbeatAt
            where run.status = 'RUNNING' and run.runId in :runIds
            """)
    int heartbeat(@Param("runIds") Set<String> runIds,
                  @Param("heartbeatAt") LocalDateTime heartbeatAt);

    @Modifying
    @Transactional
    @Query("""
            update WeeklyReportCoordinationRun run
            set run.status = :status,
                run.generationMode = :generationMode,
                run.reviewStatus = :reviewStatus,
                run.revisionCount = :revisionCount,
                run.stepCount = :stepCount,
                run.degradedStepCount = :degradedStepCount,
                run.failedStepCount = :failedStepCount,
                run.elapsedMs = :elapsedMs,
                run.errorMessage = :errorMessage,
                run.finishedAt = :finishedAt,
                run.updatedAt = :finishedAt
            where run.runId = :runId and run.status = 'RUNNING'
            """)
    int finish(@Param("runId") String runId,
               @Param("status") String status,
               @Param("generationMode") String generationMode,
               @Param("reviewStatus") String reviewStatus,
               @Param("revisionCount") int revisionCount,
               @Param("stepCount") int stepCount,
               @Param("degradedStepCount") int degradedStepCount,
               @Param("failedStepCount") int failedStepCount,
               @Param("elapsedMs") long elapsedMs,
               @Param("errorMessage") String errorMessage,
               @Param("finishedAt") LocalDateTime finishedAt);

    @Modifying
    @Transactional
    @Query("""
            update WeeklyReportCoordinationRun run
            set run.status = 'INTERRUPTED',
                run.generationMode = 'INTERRUPTED',
                run.reviewStatus = 'INTERRUPTED',
                run.stepCount = :stepCount,
                run.degradedStepCount = :degradedStepCount,
                run.failedStepCount = :failedStepCount,
                run.elapsedMs = :elapsedMs,
                run.errorMessage = :errorMessage,
                run.finishedAt = :finishedAt,
                run.updatedAt = :finishedAt
            where run.runId = :runId
              and run.status = 'RUNNING'
              and run.updatedAt < :staleBefore
            """)
    int interruptStale(@Param("runId") String runId,
                       @Param("staleBefore") LocalDateTime staleBefore,
                       @Param("stepCount") int stepCount,
                       @Param("degradedStepCount") int degradedStepCount,
                       @Param("failedStepCount") int failedStepCount,
                       @Param("elapsedMs") long elapsedMs,
                       @Param("errorMessage") String errorMessage,
                       @Param("finishedAt") LocalDateTime finishedAt);
}
