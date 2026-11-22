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
    @org.springframework.beans.factory.annotation.Value("${mealcheck.ai-budget.min-start-interval-millis:400}")
    private long minStartIntervalMillis=400;
    @org.springframework.beans.factory.annotation.Value("${mealcheck.ai-budget.max-admission-waiters:1024}")
    private int maxAdmissionWaiters=1024;
    @jakarta.annotation.PostConstruct
    public void validateRate() {
        if (maxStartsPerMinute<1 || interactiveReservedPermits<0 || minStartIntervalMillis<0 || minStartIntervalMillis>60000 || maxAdmissionWaiters<1 || maxAdmissionWaiters>10000)
            throw new IllegalArgumentException("Invalid AI admission limits");
    }
    public AiCallPermitStore(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    @Transactional(propagation = Propagation.REQUIRES_NEW, timeout = 3)
    public UUID acquire(int limit, long leaseMillis) {
        return tryAcquire(limit, leaseMillis, false).permit();
    }
    public record Admission(UUID permit, String reason, long retryMillis) { }
    @Transactional(propagation = Propagation.REQUIRES_NEW, timeout = 3)
    public UUID enqueue(boolean background,long waitMillis) {
        jdbc.queryForObject("SELECT pg_advisory_xact_lock(742019002)", (rs,n)->0);
        jdbc.update("DELETE FROM ai_call_waiters WHERE expires_at<=clock_timestamp()");
        if(jdbc.queryForObject("SELECT count(*) FROM ai_call_waiters",Long.class)>=maxAdmissionWaiters)
            throw new RemoteCallGuard.RemoteCallRejectedException("AI admission queue is full");
        UUID id=UUID.randomUUID();
        jdbc.update("INSERT INTO ai_call_waiters(id,background,expires_at) VALUES (?,?,clock_timestamp()+(? * INTERVAL '1 millisecond'))",id,background,Math.max(1,waitMillis));
        return id;
    }
    @Transactional(propagation = Propagation.REQUIRES_NEW, timeout = 3)
    public Admission tryAcquireQueued(int limit,long leaseMillis,boolean background,UUID ticket) {
        return tryAcquireInternal(limit,leaseMillis,background,ticket);
    }
    @Transactional(propagation = Propagation.REQUIRES_NEW, timeout = 3)
    public void cancelWaiter(UUID ticket) { jdbc.update("DELETE FROM ai_call_waiters WHERE id=?",ticket); }
    @Transactional(propagation = Propagation.REQUIRES_NEW, timeout = 3)
    public Admission tryAcquire(int limit, long leaseMillis, boolean background) {
        return tryAcquireInternal(limit,leaseMillis,background,null);
    }
    private Admission tryAcquireInternal(int limit,long leaseMillis,boolean background,UUID ticket) {
        jdbc.queryForObject("SELECT pg_advisory_xact_lock(742019002)", (rs, n) -> 0);
        jdbc.update("DELETE FROM ai_call_waiters WHERE expires_at<=clock_timestamp()");
        jdbc.update("DELETE FROM ai_call_permits WHERE expires_at <= clock_timestamp()");
        long used = jdbc.queryForObject("SELECT count(*) FROM ai_call_permits", Long.class);
        int backgroundLimit=limit-Math.min(interactiveReservedPermits,Math.max(0,limit-1));
        var heads=jdbc.query("SELECT id FROM ai_call_waiters WHERE (NOT background OR ?) ORDER BY sequence LIMIT 1",
                (rs,n)->rs.getObject(1,UUID.class),used<backgroundLimit);
        if(ticket!=null && jdbc.queryForObject("SELECT count(*) FROM ai_call_waiters WHERE id=?",Long.class,ticket)==0)
            throw new RemoteCallGuard.LocalAdmissionDeadlineException("AI admission ticket expired");
        if(!heads.isEmpty() && !heads.get(0).equals(ticket)) return new Admission(null,"queued",100);
        if(background && used>=backgroundLimit) return new Admission(null,"background",100);
        if (minStartIntervalMillis>0) {
            long wait=jdbc.queryForObject("""
                SELECT greatest(0,coalesce(ceil(extract(epoch FROM
                  (max(started_at)+(? * INTERVAL '1 millisecond')-clock_timestamp()))*1000)::bigint,0))
                FROM ai_call_starts
                """,Long.class,minStartIntervalMillis);
            // Only the oldest eligible waiter reaches the paced-start check.
            if (wait>0) return new Admission(null,"pacing",wait);
        }
        jdbc.update("DELETE FROM ai_call_starts WHERE started_at <= CURRENT_TIMESTAMP-INTERVAL '60 seconds'");
        int availableLimit = background ? limit - Math.min(interactiveReservedPermits, Math.max(0, limit-1)) : limit;
        if (used >= availableLimit) return new Admission(null, background ? "background" : "concurrency", 0);
        var starts=jdbc.queryForMap("SELECT count(*) AS used, coalesce(ceil(extract(epoch from (min(started_at)+INTERVAL '60 seconds'-CURRENT_TIMESTAMP))*1000),0)::bigint AS wait FROM ai_call_starts");
        if (((Number)starts.get("used")).longValue() >= maxStartsPerMinute)
            return new Admission(null,"rate",Math.max(1,((Number)starts.get("wait")).longValue()));
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO ai_call_permits(id,expires_at) VALUES (?,CURRENT_TIMESTAMP + (? * INTERVAL '1 millisecond'))", id, leaseMillis);
        // Count admission conservatively even if the personal/daily budget later blocks dispatch.
        jdbc.update("INSERT INTO ai_call_starts(id,started_at) VALUES (?,clock_timestamp())",id);
        if(ticket!=null)jdbc.update("DELETE FROM ai_call_waiters WHERE id=?",ticket);
        return new Admission(id,"accepted",0);
    }
    @Transactional(propagation = Propagation.REQUIRES_NEW, timeout = 3)
    public void release(UUID id) { jdbc.update("DELETE FROM ai_call_permits WHERE id=?", id); }
}
