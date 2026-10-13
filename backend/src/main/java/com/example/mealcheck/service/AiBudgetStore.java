package com.example.mealcheck.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

@Service
public class AiBudgetStore {
    private final JdbcTemplate jdbc;
    @org.springframework.beans.factory.annotation.Value("${mealcheck.ai-budget.global-daily-calls:100000}")
    private int globalDailyCalls = 100000;
    @org.springframework.beans.factory.annotation.Value("${mealcheck.ai-budget.global-daily-cost:0}")
    private BigDecimal globalDailyCost = BigDecimal.ZERO;
    @jakarta.annotation.PostConstruct
    public void validateGlobalBudget() {
        if (globalDailyCalls < 1 || globalDailyCost.signum() < 0)
            throw new IllegalArgumentException("Invalid global AI budget");
    }
    public AiBudgetStore(JdbcTemplate jdbc) { this.jdbc=jdbc; }
    public record Reservation(UUID id, String key, LocalDate day, BigDecimal amount) { }

    @Transactional(propagation=Propagation.REQUIRES_NEW, timeout=3)
    public Reservation reserve(String key, Long userId, LocalDate day, int callLimit, BigDecimal costLimit,
                               BigDecimal amount, long retryAfter) {
        // Acquire global before personal quota everywhere, including settlement.
        debit("global",null,day,globalDailyCalls,globalDailyCost,amount,retryAfter);
        debit(key,userId,day,callLimit,costLimit,amount,retryAfter);
        UUID globalId=UUID.randomUUID();
        jdbc.update("INSERT INTO ai_budget_reservations(id,quota_key,usage_day,amount,status) VALUES (?,'global',?,?,'RESERVED')",globalId,day,amount);
        UUID id=UUID.randomUUID();
        jdbc.update("INSERT INTO ai_budget_reservations(id,quota_key,usage_day,amount,status,global_reservation_id) VALUES (?,?,?,?,'RESERVED',?)",id,key,day,amount,globalId);
        return new Reservation(id,key,day,amount);
    }
    private void debit(String key,Long userId,LocalDate day,int callLimit,BigDecimal costLimit,BigDecimal amount,long retryAfter) {
        jdbc.update("INSERT INTO ai_budget_daily(quota_key,usage_day,user_id) VALUES (?,?,?) ON CONFLICT DO NOTHING", key,day,userId);
        int changed = jdbc.update("""
                UPDATE ai_budget_daily SET attempts=attempts+1,reserved=reserved+?
                WHERE quota_key=? AND usage_day=? AND attempts<? AND (?<=0 OR spent+reserved+?<=?)
                """,amount,key,day,callLimit,costLimit,amount,costLimit);
        if (changed != 1) throw new AiBudgetService.BudgetExceededException(callLimit,retryAfter);
    }

    @Transactional(propagation=Propagation.REQUIRES_NEW, timeout=3)
    public void dispatched(Reservation reservation) {
        var rows=jdbc.queryForList("SELECT global_reservation_id FROM ai_budget_reservations WHERE id=? AND status='RESERVED' FOR UPDATE",reservation.id());
        if (rows.isEmpty()) throw new IllegalStateException("AI budget reservation is no longer active");
        if (rows.get(0).get("global_reservation_id") instanceof UUID globalId
                && jdbc.update("UPDATE ai_budget_reservations SET status='SENT' WHERE id=? AND status='RESERVED'",globalId)!=1)
            throw new IllegalStateException("Global AI budget reservation is no longer active");
        if (jdbc.update("UPDATE ai_budget_reservations SET status='SENT' WHERE id=? AND status='RESERVED'",reservation.id()) != 1)
            throw new IllegalStateException("AI budget reservation is no longer active");
    }

    @Transactional(propagation=Propagation.REQUIRES_NEW, timeout=3)
    public void settle(Reservation reservation, BigDecimal actualCost) {
        finish(reservation.id(),actualCost,false);
    }

    @Transactional(propagation=Propagation.REQUIRES_NEW, timeout=3)
    public void recover(UUID id) { finish(id,null,true); }

    private void finish(UUID id, BigDecimal actualCost, boolean recovery) {
        var rows=jdbc.queryForList("SELECT * FROM ai_budget_reservations WHERE id=? AND status IN ('RESERVED','SENT') FOR UPDATE",id);
        if (rows.isEmpty()) return;
        var row=rows.get(0);
        if (recovery && !Boolean.TRUE.equals(jdbc.queryForObject("SELECT created_at < CURRENT_TIMESTAMP-INTERVAL '10 minutes' FROM ai_budget_reservations WHERE id=?",Boolean.class,id))) return;
        if (row.get("global_reservation_id") instanceof UUID globalId) finish(globalId,actualCost,recovery);
        boolean sent="SENT".equals(row.get("status"));
        BigDecimal amount=(BigDecimal)row.get("amount");
        BigDecimal charged=sent ? (actualCost==null ? amount : actualCost) : BigDecimal.ZERO;
        jdbc.update("UPDATE ai_budget_daily SET reserved=reserved-?,spent=spent+?,attempts=attempts-? WHERE quota_key=? AND usage_day=?",
                amount,charged,sent?0:1,row.get("quota_key"),row.get("usage_day"));
        jdbc.update("UPDATE ai_budget_reservations SET status=?,charged=? WHERE id=?",sent?"SETTLED":"RELEASED",charged,id);
    }

    public java.util.List<UUID> stale() {
        return jdbc.query("SELECT id FROM ai_budget_reservations WHERE status IN ('RESERVED','SENT') AND created_at<CURRENT_TIMESTAMP-INTERVAL '10 minutes' ORDER BY created_at LIMIT 100",(rs,n)->rs.getObject(1,UUID.class));
    }
    public java.util.Map<String,Object> recoveryStats() {
        return jdbc.queryForMap("""
                SELECT count(*) AS unsettled,
                  count(*) FILTER(WHERE created_at<CURRENT_TIMESTAMP-INTERVAL '10 minutes') AS stale,
                  coalesce(EXTRACT(EPOCH FROM (CURRENT_TIMESTAMP-min(created_at))),0) AS oldest_seconds
                FROM ai_budget_reservations WHERE status IN ('RESERVED','SENT')
                """);
    }
    @Transactional(propagation=Propagation.REQUIRES_NEW, timeout=3)
    public void retain() {
        // Drain settled rows first: deleting a daily parent could cascade millions of children.
        jdbc.update("""
                DELETE FROM ai_budget_reservations WHERE id IN (
                  SELECT r.id FROM ai_budget_reservations r
                  WHERE r.usage_day<CURRENT_DATE-30 AND r.status IN ('SETTLED','RELEASED')
                  ORDER BY r.created_at,r.id LIMIT 1000)
                """);
        jdbc.update("""
                DELETE FROM ai_budget_daily d WHERE (quota_key,usage_day) IN (
                  SELECT quota_key,usage_day FROM ai_budget_daily old WHERE usage_day<CURRENT_DATE-30
                    AND NOT EXISTS(SELECT 1 FROM ai_budget_reservations r WHERE r.quota_key=old.quota_key AND r.usage_day=old.usage_day)
                  ORDER BY usage_day,quota_key LIMIT 100)
                """);
    }
    public java.util.Map<String,Object> globalUsage(LocalDate day) {
        var result=new java.util.LinkedHashMap<String,Object>(usage("global",day));
        result.put("callLimit",globalDailyCalls);result.put("costLimit",globalDailyCost);
        result.put("remainingCalls",Math.max(0,globalDailyCalls-((Number)result.get("attempts")).longValue()));
        return result;
    }
    public java.util.Map<String,Object> usage(String key, LocalDate day) {
        var rows=jdbc.queryForList("SELECT attempts,spent,reserved FROM ai_budget_daily WHERE quota_key=? AND usage_day=?",key,day);
        return rows.isEmpty() ? java.util.Map.of("attempts",0,"spent",BigDecimal.ZERO,"reserved",BigDecimal.ZERO) : rows.get(0);
    }
}
