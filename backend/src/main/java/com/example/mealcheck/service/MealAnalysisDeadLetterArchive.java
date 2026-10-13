package com.example.mealcheck.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.UUID;

@Service
public class MealAnalysisDeadLetterArchive {
    private final JdbcTemplate jdbc;
    public MealAnalysisDeadLetterArchive(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    @Transactional
    public void archive(UUID eventId, String fingerprint) {
        // Look up an existing recorded failure before using the mutable outbox association.
        var known = eventId == null ? java.util.List.<java.util.Map<String,Object>>of() :
                jdbc.queryForList("SELECT user_id FROM task_failure_events WHERE event_id=? ORDER BY id DESC LIMIT 1",eventId);
        if (!known.isEmpty()) {
            if (known.get(0).get("user_id") != null && jdbc.queryForList("SELECT id FROM users WHERE id=? FOR UPDATE",known.get(0).get("user_id")).isEmpty()) return;
            jdbc.update("UPDATE task_failure_events SET dead_lettered=true,version=version+1,updated_at=CURRENT_TIMESTAMP WHERE event_id=? AND dead_lettered=false",eventId);
            return;
        }
        var dispatch = eventId == null ? java.util.List.<java.util.Map<String,Object>>of() :
                jdbc.queryForList("SELECT user_id,request_key FROM meal_analysis_outbox WHERE event_id=?",eventId);
        if (!dispatch.isEmpty()) {
            var ref = dispatch.get(0);
            if (jdbc.queryForList("SELECT id FROM users WHERE id=? FOR UPDATE",ref.get("user_id")).isEmpty()) return;
            if (!jdbc.queryForList("SELECT id FROM task_failure_events WHERE event_id=?",eventId).isEmpty()) {
                jdbc.update("UPDATE task_failure_events SET dead_lettered=true,version=version+1,updated_at=CURRENT_TIMESTAMP WHERE event_id=? AND dead_lettered=false",eventId);
                return;
            }
            var tasks = jdbc.queryForList("""
                SELECT r.* FROM meal_analysis_requests r JOIN meal_analysis_outbox o USING(user_id,request_key)
                WHERE o.event_id=? FOR UPDATE OF r
                """,eventId);
            if (!tasks.isEmpty()) {
                var task = tasks.get(0);
                String state = task.get("status").toString();
                jdbc.update("""
                    INSERT INTO task_failure_events(dedupe_key,task_type,user_id,task_key,event_id,execution_token,
                        attempts,phase,error_code,error_summary,recoverable,status,dead_lettered)
                    VALUES (?,'MEAL_ANALYSIS',?,?,?,?,?,'CLAIM','DISPATCH_INTERRUPTED',
                        '消息消费失败后进入死信，请检查数据库及服务日志',true,?,true)
                    ON CONFLICT(dedupe_key) DO NOTHING
                    """, "dead:" + eventId,task.get("user_id"),task.get("request_key"),eventId,task.get("attempt_token"),task.get("attempts"),
                        "SUCCEEDED".equals(state) ? "RECOVERED" : "OPEN");
                return;
            }
        }
        // Unmatched/malformed messages are visible, but never authorize a model call.
        jdbc.update("""
            INSERT INTO task_failure_events(dedupe_key,task_type,event_id,phase,error_code,error_summary,recoverable,dead_lettered)
            VALUES (?,'BROKER',?,'DISPATCH',?,?,false,true) ON CONFLICT(dedupe_key) DO NOTHING
            ""","broker:" + fingerprint,eventId,eventId == null ? "MALFORMED_DISPATCH" : "UNMATCHED_DISPATCH",
                eventId == null ? "死信消息格式不正确，请排查派发来源" : "死信未关联当前任务，可能已重试、完成或清理");
    }
}
