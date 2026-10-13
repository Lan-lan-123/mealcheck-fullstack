package com.example.mealcheck.repository;

import com.example.mealcheck.dto.JournalDtos.*;
import com.example.mealcheck.dto.TrackingDtos.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import java.time.LocalDate;
import java.util.Optional;

@Repository
public class ActionPlanRepository {
    private final JdbcTemplate jdbc;
    private final RowMapper<PlanView> mapper = (rs, n) -> new PlanView(rs.getLong("id"), rs.getString("title"), rs.getString("action_text"),
        rs.getString("source_type"), (Long) rs.getObject("source_record_id"), (Long) rs.getObject("source_message_id"),
        rs.getDate("planned_date").toLocalDate(), rs.getString("status"), rs.getString("note"), rs.getLong("version"),
        rs.getString("completed_at"), rs.getObject("source_period_start", LocalDate.class), rs.getObject("source_period_end", LocalDate.class));
    public ActionPlanRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    public Optional<PlanView> owned(long userId, long id) {
        return jdbc.query("SELECT * FROM action_plans WHERE user_id=? AND id=?", mapper, userId, id).stream().findFirst();
    }
    public Page<PlanView> page(long userId, LocalDate from, LocalDate to, int page, int size) {
        long total = jdbc.queryForObject("SELECT count(*) FROM action_plans WHERE user_id=? AND planned_date BETWEEN ? AND ?", Long.class, userId, from, to);
        var content = jdbc.query("SELECT * FROM action_plans WHERE user_id=? AND planned_date BETWEEN ? AND ? ORDER BY planned_date,id LIMIT ? OFFSET ?",
            mapper, userId, from, to, size, (long) page * size);
        return Page.of(content, total, page, size);
    }
    public boolean ownsMeal(long userId, long id) {
        return jdbc.queryForObject("SELECT count(*) FROM meal_records WHERE id=? AND user_id=?", Long.class, id, userId) == 1;
    }
    public boolean ownsCompletedAnswer(long userId, long id) {
        return jdbc.queryForObject("SELECT count(*) FROM assistant_conversation_messages m JOIN assistant_conversations c ON m.conversation_id=c.id WHERE m.id=? AND c.user_id=? AND m.role='assistant' AND m.delivery_status='COMPLETE'", Long.class, id, userId) == 1;
    }
    public long insert(long userId, Plan body) {
        return jdbc.queryForObject("INSERT INTO action_plans(user_id,title,action_text,source_type,source_record_id,source_message_id,planned_date,source_period_start,source_period_end) VALUES(?,?,?,?,?,?,?,?,?) RETURNING id",
            Long.class, userId, body.title().trim(), body.actionText().trim(), body.sourceType(), body.sourceRecordId(), body.sourceMessageId(), body.plannedDate(), body.sourcePeriodStart(), body.sourcePeriodEnd());
    }
    public void update(long userId, long id, PlanUpdate body) {
        jdbc.update("UPDATE action_plans SET title=?,action_text=?,planned_date=?,status=?,note=?,version=version+1,updated_at=CURRENT_TIMESTAMP,completed_at=CASE WHEN ?='DONE' THEN coalesce(completed_at,CURRENT_TIMESTAMP) ELSE NULL END WHERE id=? AND user_id=?",
            body.title().trim(), body.actionText().trim(), body.plannedDate(), body.status(), body.note(), body.status(), id, userId);
    }
}
