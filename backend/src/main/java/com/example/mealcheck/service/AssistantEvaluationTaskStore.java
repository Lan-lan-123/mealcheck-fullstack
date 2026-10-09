package com.example.mealcheck.service;

import com.example.mealcheck.dto.AssistantBenchmarkResponse;
import com.example.mealcheck.util.AppTime;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.Optional;
import java.util.UUID;

/** Database owns admission, progress and execution rights; JVM flags only bound local dispatch. */
@Service
public class AssistantEvaluationTaskStore {
    private final JdbcTemplate jdbc;
    private final AssistantBenchmarkHistoryService history;
    private final int leaseSeconds;
    private final String instance = UUID.randomUUID().toString();

    public AssistantEvaluationTaskStore(JdbcTemplate jdbc, AssistantBenchmarkHistoryService history,
            @Value("${mealcheck.assistant-evaluation.lease-seconds:90}") int leaseSeconds) {
        this.jdbc = jdbc; this.history = history; this.leaseSeconds = Math.max(30, leaseSeconds);
    }

    public record Work(String runId, UUID token, String definitionJson, String startedBy) {}

    @Transactional
    public AssistantBenchmarkResponse submit(AssistantBenchmarkResponse pending, String definition, String startedBy) {
        jdbc.execute("SELECT pg_advisory_xact_lock(72419024)");
        recoverExpired();
        var active = jdbc.query("SELECT run_id FROM assistant_evaluation_tasks WHERE status IN ('PENDING','RUNNING')",
                (rs, n) -> rs.getString(1));
        if (!active.isEmpty()) return history.detail(active.get(0));
        history.saveSnapshot(pending, startedBy);
        jdbc.update("INSERT INTO assistant_evaluation_tasks(run_id,status,definition_json,started_by) VALUES (?,'PENDING',?,?)",
                pending.runId(), definition, startedBy);
        return pending;
    }

    @Transactional
    public Work claim() {
        recoverExpired();
        var rows = jdbc.query("SELECT run_id,definition_json,started_by FROM assistant_evaluation_tasks "
                + "WHERE status='PENDING' ORDER BY created_at LIMIT 1 FOR UPDATE SKIP LOCKED",
                (rs, n) -> new Work(rs.getString(1), UUID.randomUUID(), rs.getString(2), rs.getString(3)));
        if (rows.isEmpty()) return null;
        Work work = rows.get(0);
        jdbc.update("UPDATE assistant_evaluation_tasks SET status='RUNNING',owner_token=?,owner_instance=?,"
                + "lease_until=clock_timestamp()+(? * INTERVAL '1 second') WHERE run_id=?",
                work.token(), instance, leaseSeconds, work.runId());
        jdbc.update("UPDATE assistant_evaluation_runs SET status='RUNNING',message='评测任务已领取' WHERE run_id=?", work.runId());
        return work;
    }

    public boolean renew(Work work) {
        return jdbc.update("UPDATE assistant_evaluation_tasks SET lease_until=clock_timestamp()+(? * INTERVAL '1 second') "
                + "WHERE run_id=? AND owner_token=? AND status='RUNNING' AND lease_until>clock_timestamp()",
                leaseSeconds, work.runId(), work.token()) == 1;
    }

    public boolean owns(Work work) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM assistant_evaluation_tasks "
                + "WHERE run_id=? AND owner_token=? AND status='RUNNING' AND lease_until>clock_timestamp())",
                Boolean.class, work.runId(), work.token()));
    }

    @Transactional
    public boolean publish(Work work, AssistantBenchmarkResponse response) {
        if (!work.runId().equals(response.runId())) throw new IllegalArgumentException("评测任务标识不匹配");
        var owned = jdbc.query("SELECT run_id FROM assistant_evaluation_tasks WHERE run_id=? AND owner_token=? "
                + "AND status='RUNNING' AND lease_until>clock_timestamp() FOR UPDATE",
                (rs,n) -> rs.getString(1), work.runId(), work.token());
        if (owned.isEmpty()) return false;
        history.saveSnapshot(response, work.startedBy());
        if (!"RUNNING".equals(response.status())) {
            jdbc.update("UPDATE assistant_evaluation_tasks SET status=?,owner_token=NULL,owner_instance=NULL,lease_until=NULL WHERE run_id=?",
                    response.status(), work.runId());
        }
        return true;
    }

    @Transactional
    public boolean fail(Work work, String message) {
        var owned = jdbc.query("SELECT run_id FROM assistant_evaluation_tasks WHERE run_id=? AND owner_token=? "
                + "AND status='RUNNING' AND lease_until>clock_timestamp() FOR UPDATE",
                (rs,n) -> rs.getString(1), work.runId(), work.token());
        if (owned.isEmpty()) return false;
        jdbc.update("UPDATE assistant_evaluation_tasks SET status='FAILED',owner_token=NULL,owner_instance=NULL,lease_until=NULL WHERE run_id=?", work.runId());
        jdbc.update("UPDATE assistant_evaluation_runs SET status='FAILED',verdict='INCOMPLETE',message=?,finished_at=?,updated_at=? WHERE run_id=?",
                message, AppTime.now(), AppTime.now(), work.runId());
        return true;
    }

    @Transactional
    public void releaseUndispatched(Work work) {
        int updated = jdbc.update("UPDATE assistant_evaluation_tasks SET status='PENDING',owner_token=NULL,owner_instance=NULL,lease_until=NULL "
                + "WHERE run_id=? AND owner_token=? AND status='RUNNING' AND lease_until>clock_timestamp()", work.runId(), work.token());
        if (updated == 1) jdbc.update("UPDATE assistant_evaluation_runs SET status='PENDING',message='等待空闲评测执行器' WHERE run_id=?", work.runId());
    }

    @Transactional
    public void recoverExpired() {
        var expired = jdbc.query("SELECT run_id FROM assistant_evaluation_tasks WHERE status='RUNNING' "
                + "AND lease_until<=clock_timestamp() FOR UPDATE SKIP LOCKED", (rs,n) -> rs.getString(1));
        for (String id : expired) {
            jdbc.update("UPDATE assistant_evaluation_tasks SET status='FAILED',owner_token=NULL,owner_instance=NULL,lease_until=NULL WHERE run_id=?", id);
            jdbc.update("UPDATE assistant_evaluation_runs SET status='FAILED',verdict='INCOMPLETE',message='执行租约已过期，已保留部分结果，请显式重新运行',"
                    + "finished_at=?,updated_at=? WHERE run_id=?", AppTime.now(), AppTime.now(), id);
        }
    }

    @Transactional(readOnly = true)
    public Optional<AssistantBenchmarkResponse> current() {
        var ids = jdbc.query("SELECT run_id FROM assistant_evaluation_runs ORDER BY "
                + "CASE WHEN status IN ('PENDING','RUNNING') THEN 0 ELSE 1 END,started_at DESC,id DESC LIMIT 1", (rs,n) -> rs.getString(1));
        return ids.isEmpty() ? Optional.empty() : Optional.of(history.detail(ids.get(0)));
    }
}
