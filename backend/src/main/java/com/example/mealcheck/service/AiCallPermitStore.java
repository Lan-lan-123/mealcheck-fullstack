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
    @org.springframework.beans.factory.annotation.Value("${mealcheck.ai-budget.interactive-reserved-permits:2}")
    private int interactiveReservedPermits=2;
    @jakarta.annotation.PostConstruct
    public void validateRate() {
        if (maxStartsPerMinute<1 || interactiveReservedPermits<0) throw new IllegalArgumentException("Invalid AI admission limits");
    }
    public AiCallPermitStore(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    @Transactional(propagation = Propagation.REQUIRES_NEW, timeout = 3)
    public UUID acquire(int limit, long leaseMillis) {
        return tryAcquire(limit, leaseMillis, false).permit();
    }
    public record Admission(UUID permit, String reason, long retryMillis) { }
    @Transactional(propagation = Propagation.REQUIRES_NEW, timeout = 3)
    public Admission tryAcquire(int limit, long leaseMillis, boolean background) {
        jdbc.queryForObject("SELECT pg_advisory_xact_lock(742019002)", (rs, n) -> 0);
        jdbc.update("DELETE FROM ai_call_permits WHERE expires_at <= CURRENT_TIMESTAMP");
        jdbc.update("DELETE FROM ai_call_starts WHERE started_at <= CURRENT_TIMESTAMP-INTERVAL '60 seconds'");
        long used = jdbc.queryForObject("SELECT count(*) FROM ai_call_permits", Long.class);
        int availableLimit = background ? limit - Math.min(interactiveReservedPermits, Math.max(0, limit-1)) : limit;
        if (used >= availableLimit) return new Admission(null, background ? "background" : "concurrency", 0);
        var starts=jdbc.queryForMap("SELECT count(*) AS used, coalesce(ceil(extract(epoch from (min(started_at)+INTERVAL '60 seconds'-CURRENT_TIMESTAMP))*1000),0)::bigint AS wait FROM ai_call_starts");
        if (((Number)starts.get("used")).longValue() >= maxStartsPerMinute)
            return new Admission(null,"rate",Math.max(1,((Number)starts.get("wait")).longValue()));
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO ai_call_permits(id,expires_at) VALUES (?,CURRENT_TIMESTAMP + (? * INTERVAL '1 millisecond'))", id, leaseMillis);
        // Count admission conservatively even if the personal/daily budget later blocks dispatch.
        jdbc.update("INSERT INTO ai_call_starts(id) VALUES (?)",id);
        return new Admission(id,"accepted",0);
    }
    @Transactional(propagation = Propagation.REQUIRES_NEW, timeout = 3)
    public void release(UUID id) { jdbc.update("DELETE FROM ai_call_permits WHERE id=?", id); }
}
