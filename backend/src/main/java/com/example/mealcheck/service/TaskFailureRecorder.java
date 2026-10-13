package com.example.mealcheck.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.util.UUID;

@Service
@Transactional(propagation = Propagation.MANDATORY)
public class TaskFailureRecorder {
    private final JdbcTemplate jdbc;
    private final org.springframework.beans.factory.ObjectProvider<io.micrometer.core.instrument.MeterRegistry> registries;
    public TaskFailureRecorder(JdbcTemplate jdbc, org.springframework.beans.factory.ObjectProvider<io.micrometer.core.instrument.MeterRegistry> registries) {
        this.jdbc = jdbc; this.registries = registries;
    }

    public void record(String type, Long userId, String key, UUID eventId, String token, int attempts,
            String phase, TaskFailureClassifier.Failure failure, boolean terminal) {
        if (terminal) jdbc.update("""
            UPDATE task_failure_events SET status='RETRY_FAILED',version=version+1,updated_at=CURRENT_TIMESTAMP
            WHERE task_type=? AND user_id=? AND task_key=? AND status='RETRYING'
            """, type, userId, key);
        int inserted = jdbc.update("""
            INSERT INTO task_failure_events(dedupe_key,task_type,user_id,task_key,event_id,execution_token,
                attempts,phase,error_code,error_summary,recoverable,status)
            VALUES (?,?,?,?,?,?,?,?,?,?,?,?) ON CONFLICT(dedupe_key) DO NOTHING
            """, type + ":" + userId + ":" + key + ":" + token, type, userId, key, eventId, token,
                attempts, phase, failure.code(), failure.summary(), failure.recoverable(), terminal ? "OPEN" : "AUTO_RETRY");
        if (inserted == 1) afterCommit(() -> registries.ifAvailable(metrics -> metrics.counter("mealcheck.failure.events",
                "task_type", type, "error_code", failure.code(), "terminal", Boolean.toString(terminal)).increment()));
    }

    public void mealFailed(Long userId, String key, String token, String phase, Throwable error) {
        var rows = jdbc.queryForList("""
            SELECT r.attempts,o.event_id FROM meal_analysis_requests r
            LEFT JOIN meal_analysis_outbox o USING(user_id,request_key)
            WHERE r.user_id=? AND r.request_key=? AND r.attempt_token=? AND r.status='FAILED'
            """, userId, key, token);
        if (rows.isEmpty()) return;
        var row = rows.get(0);
        var failure = TaskFailureClassifier.classify(error, phase);
        jdbc.update("UPDATE meal_analysis_requests SET last_error=? WHERE user_id=? AND request_key=? AND attempt_token=?",
                failure.code(), userId, key, token);
        record("MEAL_ANALYSIS", userId, key, (UUID) row.get("event_id"), token,
                ((Number) row.get("attempts")).intValue(), phase, failure, true);
    }

    public void retrying(String type, Long userId, String key) {
        int changed = jdbc.update("""
            UPDATE task_failure_events SET status='RETRYING',version=version+1,updated_at=CURRENT_TIMESTAMP
            WHERE task_type=? AND user_id=? AND task_key=? AND status='OPEN'
            """, type, userId, key);
        if (changed > 0) afterCommit(() -> registries.ifAvailable(metrics -> metrics.counter("mealcheck.failure.retries","outcome","started","task_type",type).increment()));
    }

    public void recovered(String type, Long userId, String key) {
        int changed = jdbc.update("""
            UPDATE task_failure_events SET status='RECOVERED',version=version+1,updated_at=CURRENT_TIMESTAMP
            WHERE task_type=? AND user_id=? AND task_key=? AND status IN ('OPEN','RETRYING','AUTO_RETRY')
            """, type, userId, key);
        if (changed > 0) afterCommit(() -> registries.ifAvailable(metrics -> metrics.counter("mealcheck.failure.retries","outcome","recovered","task_type",type).increment()));
    }

    public void recoverExpiredWeekly(java.time.LocalDateTime staleBefore, int maxAttempts) {
        // Called before the repository's recovery UPDATE, in the same transaction. Lock its candidates.
        var candidates = jdbc.queryForList("SELECT id,user_id FROM weekly_report_jobs WHERE status='RUNNING' AND updated_at<? ORDER BY user_id,id", staleBefore);
        for (var candidate : candidates) {
            if (jdbc.queryForList("SELECT id FROM users WHERE id=? FOR UPDATE", candidate.get("user_id")).isEmpty()) continue;
            var rows = jdbc.queryForList("SELECT * FROM weekly_report_jobs WHERE id=? AND status='RUNNING' AND updated_at<? FOR UPDATE",
                    candidate.get("id"), staleBefore);
            if (rows.isEmpty()) continue;
            var row = rows.get(0);
            record("WEEKLY_REPORT", ((Number) row.get("user_id")).longValue(), row.get("id").toString(),
                null, row.get("execution_token").toString(), ((Number) row.get("attempts")).intValue(), "WORKER",
                new TaskFailureClassifier.Failure("WORKER_INTERRUPTED", "执行租约过期，Worker 未完成任务", true),
                ((Number) row.get("attempts")).intValue() >= maxAttempts);
        }
    }
    private static void afterCommit(Runnable action) {
        org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                new org.springframework.transaction.support.TransactionSynchronization() {
                    @Override public void afterCommit() { action.run(); }
                });
    }
}
