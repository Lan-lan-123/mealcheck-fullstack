package com.example.mealcheck.service;

import com.example.mealcheck.config.AppProperties;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.security.core.context.SecurityContextHolder;
import com.example.mealcheck.security.UserPrincipal;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.*;

@Service
public class AiBudgetService {
    private final AppProperties properties;
    private final AiBudgetStore store;
    private final MeterRegistry metrics;
    private final BackgroundTaskDispatcher background;
    private final java.util.concurrent.atomic.AtomicReference<java.util.Map<String,Object>> recoverySnapshot=new java.util.concurrent.atomic.AtomicReference<>(java.util.Map.of());
    private final java.util.concurrent.atomic.AtomicInteger monitorHealthy=new java.util.concurrent.atomic.AtomicInteger();
    public AiBudgetService(AppProperties properties,AiBudgetStore store,MeterRegistry metrics,BackgroundTaskDispatcher background) {
        this.properties=properties;this.store=store;this.metrics=metrics;this.background=background;
        metrics.counter("mealcheck.ai.budget.rejected");
        metrics.counter("mealcheck.ai.budget.settlement.deferred");
        metrics.counter("mealcheck.ai.budget.unavailable");
        io.micrometer.core.instrument.Gauge.builder("mealcheck.ai.budget.enabled",properties,p->p.getAiBudget().isEnabled()?1:0).register(metrics);
        io.micrometer.core.instrument.Gauge.builder("mealcheck.ai.budget.monitor.healthy",monitorHealthy,java.util.concurrent.atomic.AtomicInteger::get).register(metrics);
        for (String field:java.util.List.of("unsettled","stale","oldest_seconds"))
            io.micrometer.core.instrument.Gauge.builder("mealcheck.ai.budget.recovery."+field,recoverySnapshot,
                    state->((Number)state.get().getOrDefault(field,0)).doubleValue()).register(metrics);
    }
    @jakarta.annotation.PostConstruct
    public void validateConfiguration() {
        var config=properties.getAiBudget();
        ZoneId.of(config.getZone());
        if (config.getDailyCalls()<1 || config.getSystemDailyCalls()<1 || config.getDailyCost().signum()<0
                || config.getSystemDailyCost().signum()<0 || config.getReservationCost().signum()<=0
                || config.getOperationTimeoutSeconds()<1 || config.getOperationTimeoutSeconds()>300
                || properties.getRemoteResilience().getTotalTimeoutSeconds()<1 || properties.getRemoteResilience().getTotalTimeoutSeconds()>300
                || config.getMaxOutputTokens()<1 || config.getMaxOutputTokens()>32768
                || properties.getAi().getInputCostPerMillion().signum()<0 || properties.getAi().getOutputCostPerMillion().signum()<0)
            throw new IllegalArgumentException("Invalid AI deadline or budget configuration");
    }
    public AiBudgetStore.Reservation reserve(BigDecimal estimate) {
        var config=properties.getAiBudget();
        if (!config.isEnabled()) return null;
        Long userId=currentUser();
        var now=ZonedDateTime.now(ZoneId.of(config.getZone()));
        try {
            return store.reserve(key(userId),userId,now.toLocalDate(),callLimit(userId),costLimit(userId),
                    money(estimate.max(config.getReservationCost())),retryAfter(now));
        } catch (BudgetExceededException exceeded) {
            metrics.counter("mealcheck.ai.budget.rejected").increment(); throw exceeded;
        } catch (org.springframework.dao.DataAccessException unavailable) {
            metrics.counter("mealcheck.ai.budget.unavailable").increment();
            throw new BudgetUnavailableException();
        }
    }
    public void dispatched(AiBudgetStore.Reservation reservation) {
        if (reservation != null) try { store.dispatched(reservation); }
        catch (org.springframework.dao.DataAccessException unavailable) { throw new BudgetUnavailableException(); }
    }
    public void settle(AiBudgetStore.Reservation reservation,BigDecimal actual) {
        if (reservation==null) return;
        try { store.settle(reservation,actual==null?null:money(actual)); }
        catch (org.springframework.dao.DataAccessException unavailable) {
            // Persisted holds remain counted; recovery conservatively charges dispatched attempts.
            metrics.counter("mealcheck.ai.budget.settlement.deferred").increment();
        }
    }
    public java.util.Map<String,Object> usage(Long userId) {
        var config=properties.getAiBudget();
        var now=ZonedDateTime.now(ZoneId.of(config.getZone()));
        var result=new java.util.LinkedHashMap<String,Object>();
        result.put("enabled",config.isEnabled());result.put("day",now.toLocalDate());
        result.put("resetAt",now.toLocalDate().plusDays(1).atStartOfDay(now.getZone()).toInstant());
        result.put("callLimit",callLimit(userId));result.put("costLimit",costLimit(userId));
        var usage=config.isEnabled()?store.usage(key(userId),now.toLocalDate()):java.util.Map.<String,Object>of("attempts",0,"spent",BigDecimal.ZERO,"reserved",BigDecimal.ZERO);
        result.putAll(usage);
        result.put("remainingCalls",Math.max(0,callLimit(userId)-((Number)usage.get("attempts")).intValue()));
        return result;
    }
    public java.util.Map<String,Object> monitoring() {
        var result=new java.util.LinkedHashMap<String,Object>();
        result.put("enabled",properties.getAiBudget().isEnabled());
        result.put("monitorHealthy",monitorHealthy.get()==1);
        if (monitorHealthy.get()==1) result.putAll(recoverySnapshot.get());
        result.put("rejected",metrics.counter("mealcheck.ai.budget.rejected").count());
        result.put("settlementDeferred",metrics.counter("mealcheck.ai.budget.settlement.deferred").count());
        result.put("unavailable",metrics.counter("mealcheck.ai.budget.unavailable").count());
        if (properties.getAiBudget().isEnabled()) {
            var day=ZonedDateTime.now(ZoneId.of(properties.getAiBudget().getZone())).toLocalDate();
            result.put("globalUsage",store.usage("global",day));
        }
        return result;
    }
    @Scheduled(fixedDelayString="${mealcheck.ai-budget.recovery-ms:60000}")
    public void tick() {
        if (properties.getAiBudget().isEnabled()) background.submit("ai-budget-recovery",()->{
            try {
                store.stale().forEach(store::recover);store.retain();
                recoverySnapshot.set(store.recoveryStats());monitorHealthy.set(1);
            } catch (RuntimeException unavailable) { monitorHealthy.set(0);throw unavailable; }
        });
    }
    private Long currentUser() {
        var context=AiCallContext.capture();
        if (context!=null) return context.userId();
        var auth=SecurityContextHolder.getContext().getAuthentication();
        return auth!=null && auth.getPrincipal() instanceof UserPrincipal user ? user.getId() : null;
    }
    private String key(Long userId) { return userId==null?"system":"user:"+userId; }
    private int callLimit(Long userId) { return Math.max(1,userId==null?properties.getAiBudget().getSystemDailyCalls():properties.getAiBudget().getDailyCalls()); }
    private BigDecimal costLimit(Long userId) { return userId==null?properties.getAiBudget().getSystemDailyCost():properties.getAiBudget().getDailyCost(); }
    public static BigDecimal money(BigDecimal value) { return value.max(BigDecimal.ZERO).setScale(6,RoundingMode.CEILING); }
    private long retryAfter(ZonedDateTime now) { return Math.max(1,Duration.between(now,now.toLocalDate().plusDays(1).atStartOfDay(now.getZone())).toSeconds()); }
    public static class BudgetExceededException extends RemoteCallGuard.NonRetryableRemoteCallException {
        private final int limit;private final long retryAfter;
        public BudgetExceededException(int limit,long retryAfter) { super("今日 AI 调用预算已用尽，请在额度重置后再试。");this.limit=limit;this.retryAfter=retryAfter; }
        public int limit() { return limit; } public long retryAfter() { return retryAfter; }
    }
    public static class BudgetUnavailableException extends RemoteCallGuard.NonRetryableRemoteCallException {
        public BudgetUnavailableException() { super("AI 预算服务暂时不可用，请稍后重试。"); }
    }
}
