package com.example.mealcheck.repository;

import com.example.mealcheck.entity.WeeklyReportJob;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface WeeklyReportJobRepository extends JpaRepository<WeeklyReportJob, Long> {
    @Query("select j from WeeklyReportJob j where j.userId=:userId and j.reportWeek=:reportWeek and j.days=:days and j.status <> 'STALE'")
    Optional<WeeklyReportJob> findByUserIdAndReportWeekAndDays(@Param("userId") Long userId, @Param("reportWeek") LocalDate reportWeek, @Param("days") int days);
    Optional<WeeklyReportJob> findByIdAndUserId(Long id, Long userId);

    @Modifying
    @Query(value = """
            INSERT INTO weekly_report_jobs
                (user_id, report_week, days, status, attempts, next_retry_at, created_at, updated_at, version)
            VALUES (:userId, :reportWeek, :days, 'PENDING', 0, :now, :now, :now, 0)
            ON CONFLICT (user_id, report_week, days) WHERE status <> 'STALE' DO NOTHING
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
            set job.status = case when job.attempts >= :maxAttempts then 'DEAD' else 'RETRY' end,
                job.nextRetryAt = :now, job.updatedAt = :now, job.version = job.version + 1,
                job.lastError = 'Recovered after worker lease expired'
            where job.status = 'RUNNING' and job.updatedAt < :staleBefore
            """)
    int recoverStale(@Param("staleBefore") LocalDateTime staleBefore, @Param("now") LocalDateTime now,
                     @Param("maxAttempts") int maxAttempts);

    @Modifying
    @Query("""
            update WeeklyReportJob job
            set job.status = 'RETRY', job.attempts = job.attempts - 1,
                job.executionToken = :replacementToken, job.nextRetryAt = :nextRetryAt,
                job.updatedAt = :now, job.version = job.version + 1
            where job.id = :jobId and job.status = 'RUNNING'
                and job.attempts = :attempt and job.executionToken = :token and job.attempts > 0
            """)
    int releaseUndispatched(@Param("jobId") Long jobId, @Param("attempt") int attempt,
                           @Param("token") String token, @Param("nextRetryAt") LocalDateTime nextRetryAt,
                           @Param("now") LocalDateTime now, @Param("replacementToken") String replacementToken);

    @Modifying
    @Query("""
            update WeeklyReportJob job
            set job.status = 'SUCCEEDED', job.lastError = null, job.updatedAt = :now, job.version = job.version + 1
            where job.id = :jobId and job.status = 'RUNNING' and job.attempts = :attempt and job.executionToken = :token
            """)
    int markSucceeded(@Param("jobId") Long jobId,
                      @Param("attempt") int attempt,
                      @Param("token") String token,
                      @Param("now") LocalDateTime now);

    @Modifying
    @Query("""
            update WeeklyReportJob job
            set job.status = :status, job.lastError = :error,
                job.nextRetryAt = :nextRetryAt, job.updatedAt = :now, job.version = job.version + 1
            where job.id = :jobId and job.status = 'RUNNING' and job.attempts = :attempt and job.executionToken = :token
            """)
    int markFailed(@Param("jobId") Long jobId,
                   @Param("attempt") int attempt,
                   @Param("token") String token,
                   @Param("status") String status,
                   @Param("error") String error,
                   @Param("nextRetryAt") LocalDateTime nextRetryAt,
                   @Param("now") LocalDateTime now);

    @Modifying
    @Query("""
            update WeeklyReportJob job set job.status='RETRY', job.attempts=job.attempts-1,
                job.lastError=:error, job.nextRetryAt=:nextRetryAt, job.updatedAt=:now, job.version=job.version+1
            where job.id=:jobId and job.status='RUNNING' and job.attempts=:attempt
                and job.executionToken=:token and job.attempts>0
            """)
    int deferAdmission(@Param("jobId") Long jobId, @Param("attempt") int attempt, @Param("token") String token,
                       @Param("error") String error, @Param("nextRetryAt") LocalDateTime nextRetryAt,
                       @Param("now") LocalDateTime now);
}
