package com.example.mealcheck.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.util.UUID;

/** Shared by every AI transport and every backend connected to this database. */
@Service
public class AiCallPermitStore {
    private final JdbcTemplate jdbc;
    @org.springframework.beans.factory.annotation.Value("${mealcheck.ai-budget.max-starts-per-minute:120}")
    private int maxStartsPerMinute=120;
    @jakarta.annotation.PostConstruct
    public void validateRate() {
        if (maxStartsPerMinute<1) throw new IllegalArgumentException("AI starts per minute must be positive");
    }
    public AiCallPermitStore(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    @Transactional(propagation = Propagation.REQUIRES_NEW, timeout = 3)
    public UUID acquire(int limit, long leaseMillis) {
        jdbc.queryForObject("SELECT pg_advisory_xact_lock(742019002)", (rs, n) -> 0);
        jdbc.update("DELETE FROM ai_call_permits WHERE expires_at <= CURRENT_TIMESTAMP");
        jdbc.update("DELETE FROM ai_call_starts WHERE started_at <= CURRENT_TIMESTAMP-INTERVAL '60 seconds'");
        if (jdbc.queryForObject("SELECT count(*) FROM ai_call_permits", Long.class) >= limit) return null;
        if (jdbc.queryForObject("SELECT count(*) FROM ai_call_starts", Long.class) >= maxStartsPerMinute) return null;
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO ai_call_permits(id,expires_at) VALUES (?,CURRENT_TIMESTAMP + (? * INTERVAL '1 millisecond'))", id, leaseMillis);
        // Count admission conservatively even if the personal/daily budget later blocks dispatch.
        jdbc.update("INSERT INTO ai_call_starts(id) VALUES (?)",id);
        return id;
    }
    @Transactional(propagation = Propagation.REQUIRES_NEW, timeout = 3)
    public void release(UUID id) { jdbc.update("DELETE FROM ai_call_permits WHERE id=?", id); }
}
