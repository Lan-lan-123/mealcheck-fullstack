package com.example.mealcheck.service;

import com.example.mealcheck.dto.TaskFailureDtos.*;
import com.example.mealcheck.security.UserPrincipal;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.*;

@Service
public class TaskFailureManagementService {
    private static final String SELECT = """
        SELECT f.*,m.status AS meal_status,m.attempt_token AS meal_token,m.input_image IS NOT NULL AS image_present,
               m.manual_retry_count AS meal_retries,o.event_id AS current_event,
               j.status AS weekly_status,j.execution_token AS weekly_token,j.manual_retry_count AS weekly_retries
        FROM task_failure_events f
        LEFT JOIN meal_analysis_requests m ON f.task_type='MEAL_ANALYSIS' AND m.user_id=f.user_id AND m.request_key=f.task_key
        LEFT JOIN meal_analysis_outbox o ON o.user_id=m.user_id AND o.request_key=m.request_key
        LEFT JOIN weekly_report_jobs j ON f.task_type='WEEKLY_REPORT' AND j.user_id=f.user_id AND j.id::text=f.task_key
        """;
    private final JdbcTemplate jdbc;
    private final TaskFailureRecorder recorder;
    private final AdminAuditService audit;
    private final int maxManualRetries;
    public TaskFailureManagementService(JdbcTemplate jdbc, TaskFailureRecorder recorder, AdminAuditService audit,
            @Value("${mealcheck.failure-management.max-manual-retries:3}") int maxManualRetries) {
        this.jdbc = jdbc; this.recorder = recorder; this.audit = audit;
        this.maxManualRetries = Math.max(0, Math.min(20, maxManualRetries));
    }

    @Transactional(readOnly = true)
    public Page list(String type, String status, String code, Long userId, LocalDateTime from, LocalDateTime to, int page, int size) {
        if (page < 0 || page > 100000 || size < 1 || size > 100) throw new IllegalArgumentException("分页范围不正确");
        if (from != null && to != null && from.isAfter(to)) throw new IllegalArgumentException("时间范围不正确");
        List<Object> args = new ArrayList<>();
        StringBuilder where = new StringBuilder(" WHERE 1=1");
        filter(where, args, "f.task_type", type, Set.of("MEAL_ANALYSIS","WEEKLY_REPORT","BROKER"));
        filter(where, args, "f.status", status, Set.of("OPEN","AUTO_RETRY","RETRYING","RETRY_FAILED","RECOVERED","CLOSED"));
        if (code != null && !code.isBlank()) { where.append(" AND f.error_code=?"); args.add(code); }
        if (userId != null) { where.append(" AND f.user_id=?"); args.add(userId); }
        if (from != null) { where.append(" AND f.failed_at>=?"); args.add(from); }
        if (to != null) { where.append(" AND f.failed_at<=?"); args.add(to); }
        long count = jdbc.queryForObject("SELECT count(*) FROM task_failure_events f" + where, Long.class, args.toArray());
        args.add(size); args.add((long)page * size);
        var rows = jdbc.queryForList(SELECT + where + " ORDER BY f.failed_at DESC,f.id DESC LIMIT ? OFFSET ?", args.toArray());
        return new Page(rows.stream().map(this::item).toList(), count, (int)((count + size - 1) / size), page, size);
    }

    @Transactional(readOnly = true)
    public Summary summary() {
        var row = jdbc.queryForMap("""
            SELECT count(*) FILTER(WHERE status='OPEN') AS open,
                   count(*) FILTER(WHERE status='RETRYING') AS retrying,
                   coalesce(extract(epoch FROM CURRENT_TIMESTAMP-min(failed_at) FILTER(WHERE status='OPEN')),0)::bigint AS oldest,
                   count(*) FILTER(WHERE status='OPEN' AND recoverable=true) AS actionable,
                   coalesce(extract(epoch FROM CURRENT_TIMESTAMP-min(failed_at) FILTER(WHERE status='OPEN' AND recoverable=true)),0)::bigint AS actionable_oldest
            FROM task_failure_events
            """);
        return new Summary(number(row,"open"),number(row,"retrying"),Math.max(0,number(row,"oldest")),
                number(row,"actionable"),Math.max(0,number(row,"actionable_oldest")));
    }

    @Transactional(readOnly = true)
    public Detail detail(long id) {
        var row = load(id);
        var history = row.get("user_id") == null ? List.of(row) : jdbc.queryForList(SELECT +
                " WHERE f.task_type=? AND f.user_id=? AND f.task_key=? ORDER BY f.id DESC LIMIT 100",
                row.get("task_type"),row.get("user_id"),row.get("task_key"));
        var operations = jdbc.queryForList("""
            SELECT op.* FROM task_failure_operations op JOIN task_failure_events f ON f.id=op.failure_id
            WHERE f.id=? OR (f.task_type=? AND f.user_id=? AND f.task_key=?)
            ORDER BY op.created_at DESC LIMIT 100
            """, id,row.get("task_type"),row.get("user_id"),row.get("task_key"));
        return new Detail(item(row), history.stream().map(this::item).toList(), operations.stream().map(op ->
                new Operation(op.get("operation_id").toString(),str(op,"action"),number(op,"admin_id"),str(op,"reason"),time(op,"created_at"))).toList());
    }

    @Transactional
    public Detail retry(long id, Action action, UserPrincipal admin) {
        var initial = load(id);
        if (initial.get("user_id") == null) throw conflict("死信未关联有效任务，无法直接重新执行");
        // The same account -> task -> failure lock order is used by user retry, completion and deletion.
        if (jdbc.queryForList("SELECT id FROM users WHERE id=? FOR UPDATE",initial.get("user_id")).isEmpty()) throw conflict("账号已删除");
        if ("MEAL_ANALYSIS".equals(initial.get("task_type")))
            jdbc.queryForList("SELECT user_id FROM meal_analysis_requests WHERE user_id=? AND request_key=? FOR UPDATE",initial.get("user_id"),initial.get("task_key"));
        else jdbc.queryForList("SELECT id FROM weekly_report_jobs WHERE id::text=? AND user_id=? FOR UPDATE",initial.get("task_key"),initial.get("user_id"));
        jdbc.queryForList("SELECT id FROM task_failure_events WHERE id=? FOR UPDATE", id);
        if (existingOperation(id,action,admin,"RETRY")) return detail(id);
        var row = load(id);
        checkVersion(row,action);
        String blocked = blocked(row);
        if (!blocked.isEmpty()) throw conflict(blocked);
        if (!insertOperation(id,action,admin,"RETRY")) {
            existingOperation(id,action,admin,"RETRY");
            return detail(id);
        }
        Long userId = number(row,"user_id");
        String key = str(row,"task_key");
        if ("MEAL_ANALYSIS".equals(row.get("task_type"))) {
            jdbc.update("""
                UPDATE meal_analysis_requests SET status='PENDING',attempt_token=?,attempts=0,last_error=NULL,
                    lease_until=CURRENT_TIMESTAMP,next_attempt_at=CURRENT_TIMESTAMP,manual_retry_count=manual_retry_count+1,updated_at=CURRENT_TIMESTAMP
                WHERE user_id=? AND request_key=?
                """,UUID.randomUUID().toString(),userId,key);
            jdbc.update("""
                INSERT INTO meal_analysis_outbox(event_id,user_id,request_key) VALUES (?,?,?)
                ON CONFLICT(user_id,request_key) DO UPDATE SET event_id=EXCLUDED.event_id,publish_token=NULL,
                    published_at=NULL,publish_attempts=0,next_publish_at=CURRENT_TIMESTAMP,created_at=CURRENT_TIMESTAMP
                """,UUID.randomUUID(),userId,key);
        } else {
            jdbc.update("""
                UPDATE weekly_report_jobs SET status='PENDING',attempts=0,execution_token=?,last_error=NULL,
                    next_retry_at=created_at,manual_retry_count=manual_retry_count+1,updated_at=CURRENT_TIMESTAMP,version=version+1
                WHERE id::text=? AND user_id=?
                """,UUID.randomUUID().toString(),key,userId);
            // created_at was written by JPA and is already in the past. Reuse it as the due time,
            // avoiding a session-timezone conversion difference between JDBC NOW() and Hibernate.
        }
        recorder.retrying(str(row,"task_type"),userId,key);
        jdbc.update("UPDATE task_failure_events SET handling_note=? WHERE id=?",action.reason().trim(),id);
        audit.log(admin,"RETRY_FAILED_TASK","task_failure",id,"operationId=" + action.operationId() + "; " + action.reason().trim());
        return detail(id);
    }

    @Transactional
    public Detail close(long id, Action action, UserPrincipal admin) {
        var initial = load(id);
        if (initial.get("user_id") != null) jdbc.queryForList("SELECT id FROM users WHERE id=? FOR UPDATE",initial.get("user_id"));
        jdbc.queryForList("SELECT id FROM task_failure_events WHERE id=? FOR UPDATE",id);
        if (existingOperation(id,action,admin,"CLOSE")) return detail(id);
        var row = load(id);
        checkVersion(row,action);
        if (!Set.of("OPEN","RETRY_FAILED").contains(str(row,"status"))) throw conflict("当前事件状态无法关闭，请刷新详情");
        if (!insertOperation(id,action,admin,"CLOSE")) {
            existingOperation(id,action,admin,"CLOSE");
            return detail(id);
        }
        jdbc.update("UPDATE task_failure_events SET status='CLOSED',handling_note=?,version=version+1,updated_at=CURRENT_TIMESTAMP WHERE id=?",
                action.reason().trim(),id);
        audit.log(admin,"CLOSE_TASK_FAILURE","task_failure",id,"operationId=" + action.operationId() + "; " + action.reason().trim());
        return detail(id);
    }

    private boolean existingOperation(long id, Action action, UserPrincipal admin, String kind) {
        var rows = jdbc.queryForList("SELECT * FROM task_failure_operations WHERE operation_id=?",action.operationId());
        if (rows.isEmpty()) return false;
        var row = rows.get(0);
        if (number(row,"failure_id") != id || number(row,"admin_id") != admin.getId()
                || !kind.equals(row.get("action")) || number(row,"expected_version") != action.expectedVersion()
                || !action.reason().trim().equals(row.get("reason"))) throw conflict("该操作标识已用于其他操作");
        return true;
    }
    private boolean insertOperation(long id, Action action, UserPrincipal admin, String kind) {
        return jdbc.update("""
            INSERT INTO task_failure_operations(operation_id,failure_id,action,admin_id,expected_version,reason)
            VALUES (?,?,?,?,?,?) ON CONFLICT(operation_id) DO NOTHING
            """,action.operationId(),id,kind,admin.getId(),action.expectedVersion(),action.reason().trim()) == 1;
    }
    private Map<String,Object> load(long id) {
        var rows = jdbc.queryForList(SELECT + " WHERE f.id=?",id);
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"失败事件不存在或关联账号已删除");
        return rows.get(0);
    }
    private String blocked(Map<String,Object> row) {
        if (!"OPEN".equals(row.get("status"))) return "该事件已处理或仍在自动恢复，请刷新详情";
        if (!Boolean.TRUE.equals(row.get("recoverable"))) return "输入或消息不符合要求，需要修正输入或排查派发来源";
        if (row.get("user_id") == null) return "死信未关联有效任务，无法直接重新执行";
        String type = str(row,"task_type");
        boolean meal = "MEAL_ANALYSIS".equals(type);
        String status = str(row,meal ? "meal_status" : "weekly_status");
        if (status.isEmpty()) return "任务已清理，无法重新执行";
        if (!Objects.equals(row.get("execution_token"),row.get(meal ? "meal_token" : "weekly_token"))) return "该事件属于旧执行，请查看最新失败记录";
        if (meal && !Boolean.TRUE.equals(row.get("image_present"))) return "原图已清理，需要用户重新上传";
        if (number(row,meal ? "meal_retries" : "weekly_retries") >= maxManualRetries) return "已达到人工重试次数上限";
        if (meal && "PENDING".equals(status) && "CLAIM".equals(row.get("phase"))
                && Boolean.TRUE.equals(row.get("dead_lettered")) && Objects.equals(row.get("event_id"),row.get("current_event"))) return "";
        if ((meal && "FAILED".equals(status)) || (!meal && "DEAD".equals(status))) return "";
        return "任务正在处理、已完成或等待租约恢复，不能重新执行";
    }
    private Item item(Map<String,Object> row) {
        boolean meal = "MEAL_ANALYSIS".equals(row.get("task_type"));
        String blocked = blocked(row);
        return new Item(number(row,"id"),str(row,"task_type"),row.get("user_id") == null ? null : number(row,"user_id"),
                str(row,"task_key"),str(row,"event_id"),str(row,"phase"),str(row,"error_code"),str(row,"error_summary"),
                str(row,"status"),(int)number(row,"attempts"),Boolean.TRUE.equals(row.get("dead_lettered")),number(row,"version"),
                time(row,"failed_at"),time(row,"updated_at"),str(row,meal ? "meal_status" : "weekly_status"),
                (int)number(row,meal ? "meal_retries" : "weekly_retries"),blocked.isEmpty(),blocked,str(row,"handling_note"));
    }
    private static void filter(StringBuilder where,List<Object> args,String column,String value,Set<String> allowed) {
        if (value == null || value.isBlank() || "ALL".equals(value)) return;
        if (!allowed.contains(value)) throw new IllegalArgumentException("筛选条件不正确");
        where.append(" AND ").append(column).append("=?"); args.add(value);
    }
    private static void checkVersion(Map<String,Object> row,Action action) {
        if (number(row,"version") != action.expectedVersion()) throw conflict("任务已被其他操作更新，请刷新后重试");
    }
    private static ResponseStatusException conflict(String message) { return new ResponseStatusException(HttpStatus.CONFLICT,message); }
    private static String str(Map<String,Object> row,String key) { return row.get(key) == null ? "" : row.get(key).toString(); }
    private static long number(Map<String,Object> row,String key) { return row.get(key) == null ? 0 : ((Number)row.get(key)).longValue(); }
    private static LocalDateTime time(Map<String,Object> row,String key) {
        Object value = row.get(key); return value instanceof Timestamp timestamp ? timestamp.toLocalDateTime() : (LocalDateTime)value;
    }
}
