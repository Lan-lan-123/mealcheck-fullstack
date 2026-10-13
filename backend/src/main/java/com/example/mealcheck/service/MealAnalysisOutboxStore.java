package com.example.mealcheck.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class MealAnalysisOutboxStore {
    private final JdbcTemplate jdbc;

    public MealAnalysisOutboxStore(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public record Dispatch(UUID eventId, UUID token) { }

    @Transactional
    public Dispatch claim() {
        // Short database lease; no database transaction is held while waiting for RabbitMQ.
        var rows = jdbc.queryForList("""
            SELECT o.event_id FROM meal_analysis_outbox o
            JOIN meal_analysis_requests r USING (user_id,request_key)
            WHERE o.next_publish_at <= CURRENT_TIMESTAMP AND r.next_attempt_at <= CURRENT_TIMESTAMP
              AND (r.input_image IS NOT NULL OR r.input_image_ref IS NOT NULL)
              AND ((r.status='PENDING' AND o.published_at IS NULL)
                   OR (r.status='PROCESSING' AND r.lease_until <= CURRENT_TIMESTAMP))
            ORDER BY o.next_publish_at,o.created_at
            LIMIT 1 FOR UPDATE OF o SKIP LOCKED
            """);
        if (rows.isEmpty()) return null;
        UUID id = (UUID) rows.get(0).get("event_id");
        UUID token = UUID.randomUUID();
        jdbc.update("""
            UPDATE meal_analysis_outbox SET publish_token=?,publish_attempts=publish_attempts+1,
              next_publish_at=CURRENT_TIMESTAMP + INTERVAL '30 seconds' WHERE event_id=?
            """, token, id);
        return new Dispatch(id, token);
    }

    public void confirmed(Dispatch dispatch) {
        // Confirmed pending messages stay in RabbitMQ, without growing duplicates during backlog.
        // Expired PROCESSING leases can still be redispatched. Empty-broker restore uses explicit replay.
        jdbc.update("""
            UPDATE meal_analysis_outbox SET published_at=CURRENT_TIMESTAMP,
              next_publish_at=CURRENT_TIMESTAMP + INTERVAL '60 seconds'
            WHERE event_id=? AND publish_token=?
            """, dispatch.eventId(), dispatch.token());
    }
}
