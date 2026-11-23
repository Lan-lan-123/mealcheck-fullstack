package com.example.mealcheck.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;
import java.util.List;
import java.util.ArrayList;

@Service
public class MealAnalysisOutboxStore {
    private final JdbcTemplate jdbc;

    public MealAnalysisOutboxStore(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public record Dispatch(UUID eventId, UUID token) { }

    @Transactional
    public Dispatch claim() {
        var batch = claimBatch(1);
        return batch.isEmpty() ? null : batch.get(0);
    }

    @Transactional
    public List<Dispatch> claimBatch(int limit) {
        if (limit < 1 || limit > 20) throw new IllegalArgumentException("Outbox window must be between 1 and 20");
        // Short database lease; no database transaction is held while waiting for RabbitMQ.
        var rows = jdbc.queryForList("""
            SELECT o.event_id FROM meal_analysis_outbox o
            JOIN meal_analysis_requests r USING (user_id,request_key)
            WHERE o.next_publish_at <= CURRENT_TIMESTAMP AND r.next_attempt_at <= CURRENT_TIMESTAMP
              AND (r.input_image IS NOT NULL OR r.input_image_ref IS NOT NULL)
              AND ((r.status='PENDING' AND o.published_at IS NULL)
                   OR (r.status='PROCESSING' AND r.lease_until <= CURRENT_TIMESTAMP))
            ORDER BY o.next_publish_at,o.created_at
            LIMIT ? FOR UPDATE OF o SKIP LOCKED
            """, limit);
        if (rows.isEmpty()) return List.of();
        var dispatches = new ArrayList<Dispatch>(rows.size());
        var parameters = new ArrayList<Object[]>(rows.size());
        for (var row : rows) {
            var dispatch = new Dispatch((UUID) row.get("event_id"), UUID.randomUUID());
            dispatches.add(dispatch);
            parameters.add(new Object[]{dispatch.token(), dispatch.eventId()});
        }
        jdbc.batchUpdate("""
            UPDATE meal_analysis_outbox SET publish_token=?,publish_attempts=publish_attempts+1,
              next_publish_at=CURRENT_TIMESTAMP + INTERVAL '30 seconds' WHERE event_id=?
            """, parameters);
        return List.copyOf(dispatches);
    }

    public boolean confirmed(Dispatch dispatch) {
        // Confirmed pending messages stay in RabbitMQ, without growing duplicates during backlog.
        // Expired PROCESSING leases can still be redispatched. Empty-broker restore uses explicit replay.
        return jdbc.update("""
            UPDATE meal_analysis_outbox SET published_at=CURRENT_TIMESTAMP,
              next_publish_at=CURRENT_TIMESTAMP + INTERVAL '60 seconds'
            WHERE event_id=? AND publish_token=? AND next_publish_at > CURRENT_TIMESTAMP
            """, dispatch.eventId(), dispatch.token()) == 1;
    }
}
