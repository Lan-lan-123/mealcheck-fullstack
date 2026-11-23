package com.example.mealcheck.service;

import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/** Sampled snapshots: Prometheus scraping never performs database or filesystem I/O. */
@Service
public class TaskLifecycleMetrics {
    private final JdbcTemplate jdbc;
    private final BackgroundTaskDispatcher background;
    private final Map<String, AtomicLong> values = new LinkedHashMap<>();
    @Value("${mealcheck.analysis.max-stored-image-bytes:536870912}")
    private long payloadBudget = 536870912L;

    public TaskLifecycleMetrics(JdbcTemplate jdbc, MeterRegistry registry, BackgroundTaskDispatcher background) {
        this.jdbc = jdbc;
        this.background = background;
        for (String name : new String[]{"payload.bytes", "payload.failed.bytes", "payload.oldest.seconds",
                "payload.failed.oldest.seconds", "payload.references", "outbox.unpublished",
                "outbox.oldest.unpublished.seconds", "recovery.pending", "monitor.healthy",
                "monitor.last.success.timestamp.seconds", "database.bytes", "database.task.tables.bytes",
                "database.dead.tuples", "database.monitor.healthy", "database.last.success.timestamp.seconds"}) {
            var value = new AtomicLong();
            values.put(name, value);
            Gauge.builder("mealcheck.lifecycle." + name, value, AtomicLong::get).register(registry);
        }
        Gauge.builder("mealcheck.lifecycle.payload.budget.bytes", this, value -> value.payloadBudget).register(registry);
        var wal = new AtomicLong();
        values.put("database.wal.bytes", wal);
        FunctionCounter.builder("mealcheck.lifecycle.database.wal.bytes", wal, AtomicLong::doubleValue).register(registry);
    }

    @Scheduled(fixedDelayString = "${mealcheck.lifecycle.metrics-ms:30000}")
    public void tick() { background.submit("lifecycle-metrics", this::sample); }

    public void sample() {
        try {
            var row = jdbc.queryForMap("""
                SELECT coalesce(sum(octet_length(input_image)),0)::bigint AS bytes,
                  coalesce(sum(octet_length(input_image)) FILTER (WHERE status='FAILED'),0)::bigint AS failed,
                  coalesce(extract(epoch FROM CURRENT_TIMESTAMP-min(created_at)
                    FILTER (WHERE input_image IS NOT NULL OR input_image_ref IS NOT NULL)),0)::bigint AS oldest,
                  coalesce(extract(epoch FROM CURRENT_TIMESTAMP-min(updated_at)
                    FILTER (WHERE status='FAILED' AND (input_image IS NOT NULL OR input_image_ref IS NOT NULL))),0)::bigint AS failed_oldest,
                  count(*) FILTER (WHERE input_image_ref IS NOT NULL) AS refs,
                  count(*) FILTER (WHERE status='PROCESSING' AND lease_until<=CURRENT_TIMESTAMP
                    AND (input_image IS NOT NULL OR input_image_ref IS NOT NULL)) AS recovery
                FROM meal_analysis_requests
                WHERE input_image IS NOT NULL OR input_image_ref IS NOT NULL
                """);
            var outbox = jdbc.queryForMap("""
                SELECT count(*) AS total, coalesce(extract(epoch FROM CURRENT_TIMESTAMP-min(o.created_at)),0)::bigint AS oldest
                FROM meal_analysis_outbox o JOIN meal_analysis_requests r USING(user_id,request_key)
                WHERE o.published_at IS NULL AND r.status IN ('PENDING','PROCESSING')
                  AND (r.input_image IS NOT NULL OR r.input_image_ref IS NOT NULL)
                """);
            set("payload.bytes", row.get("bytes")); set("payload.failed.bytes", row.get("failed"));
            set("payload.oldest.seconds", row.get("oldest")); set("payload.failed.oldest.seconds", row.get("failed_oldest"));
            set("payload.references", row.get("refs")); set("recovery.pending", row.get("recovery"));
            set("outbox.unpublished", outbox.get("total")); set("outbox.oldest.unpublished.seconds", outbox.get("oldest"));
            set("monitor.last.success.timestamp.seconds", System.currentTimeMillis() / 1000);
            set("monitor.healthy", 1);
        } catch (RuntimeException unavailable) {
            // Preserve the previous snapshot; a failed sample must not look like an empty queue.
            set("monitor.healthy", 0);
        }
    }

    @Scheduled(fixedDelayString = "${mealcheck.lifecycle.database-metrics-ms:300000}")
    public void databaseTick() { background.submit("lifecycle-database-metrics", this::sampleDatabase); }

    public void sampleDatabase() {
        try {
            var row = jdbc.queryForMap("""
                SELECT pg_database_size(current_database()) AS bytes,
                  coalesce(sum(pg_total_relation_size(relid)),0)::bigint AS tables,
                  coalesce(sum(n_dead_tup),0)::bigint AS dead
                FROM pg_stat_user_tables WHERE schemaname=current_schema()
                  AND relname IN ('meal_analysis_requests','meal_analysis_outbox','task_failure_events')
                """);
            Long wal = jdbc.queryForObject("SELECT wal_bytes::bigint FROM pg_stat_wal", Long.class);
            if (wal == null) throw new IllegalStateException("WAL statistics unavailable");
            set("database.bytes", row.get("bytes")); set("database.task.tables.bytes", row.get("tables"));
            set("database.dead.tuples", row.get("dead")); set("database.wal.bytes", wal);
            set("database.last.success.timestamp.seconds", System.currentTimeMillis() / 1000);
            set("database.monitor.healthy", 1);
        } catch (RuntimeException unavailable) { set("database.monitor.healthy", 0); }
    }

    private void set(String name, Object value) { values.get(name).set(Math.max(0, ((Number) value).longValue())); }
}
