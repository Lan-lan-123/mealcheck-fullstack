package com.example.mealcheck.repository;

import com.example.mealcheck.entity.WeeklyReportJob;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

public interface WeeklyReportJobRepository extends JpaRepository<WeeklyReportJob, Long> {
    @Modifying
    @Query(value = """
            INSERT INTO weekly_report_jobs
                (user_id, report_week, days, status, attempts, next_retry_at, created_at, updated_at, version)
            VALUES (:userId, :reportWeek, :days, 'PENDING', 0, :now, :now, :now, 0)
            ON CONFLICT (user_id, report_week, days) DO NOTHING
            """, nativeQuery = true)
    int enqueue(@Param("userId") Long userId,
                @Param("reportWeek") LocalDate reportWeek,
                @Param("days") int days,
                @Param("now") LocalDateTime now);

    @Query(value = """
            SELECT * FROM weekly_report_jobs
            WHERE status IN ('PENDING', 'RETRY')
              AND next_retry_at <= :now
            ORDER BY id
            FOR UPDATE SKIP LOCKED
            LIMIT :batchSize
            """, nativeQuery = true)
    List<WeeklyReportJob> findClaimable(@Param("now") LocalDateTime now, @Param("batchSize") int batchSize);

    @Modifying
    @Query("""
            update WeeklyReportJob job
            set job.status = 'RETRY', job.nextRetryAt = :now, job.updatedAt = :now,
                job.lastError = 'Recovered after worker lease expired'
            where job.status = 'RUNNING' and job.updatedAt < :staleBefore
            """)
    int recoverStale(@Param("staleBefore") LocalDateTime staleBefore, @Param("now") LocalDateTime now);

    @Modifying
    @Query("""
            update WeeklyReportJob job
            set job.status = 'SUCCEEDED', job.lastError = null, job.updatedAt = :now
            where job.id = :jobId and job.status = 'RUNNING' and job.attempts = :attempt
            """)
    int markSucceeded(@Param("jobId") Long jobId,
                      @Param("attempt") int attempt,
                      @Param("now") LocalDateTime now);

    @Modifying
    @Query("""
            update WeeklyReportJob job
            set job.status = :status, job.lastError = :error,
                job.nextRetryAt = :nextRetryAt, job.updatedAt = :now
            where job.id = :jobId and job.status = 'RUNNING' and job.attempts = :attempt
            """)
    int markFailed(@Param("jobId") Long jobId,
                   @Param("attempt") int attempt,
                   @Param("status") String status,
                   @Param("error") String error,
                   @Param("nextRetryAt") LocalDateTime nextRetryAt,
                   @Param("now") LocalDateTime now);
}
