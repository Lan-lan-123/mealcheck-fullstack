package com.example.mealcheck.service;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.Map;

import static org.assertj.core.api.Assertions.*;

@Testcontainers
class QueryIndexMigrationDatabaseIntegrationTest {
    @Container static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

    @Test void upgradingV29PreservesLegacyFailuresAndBuildsValidConcurrentIndexes() {
        var config = Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .configuration(Map.of("flyway.postgresql.transactional.lock", "false"));
        config.target("29").load().migrate();
        var jdbc = new JdbcTemplate(new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
        jdbc.execute("""
                INSERT INTO task_failure_events(dedupe_key,task_type,task_key,phase,error_code,error_summary,recoverable)
                VALUES ('old-weekly','WEEKLY_REPORT','42','WORKER','TEST','legacy',false),
                       ('invalid-weekly','WEEKLY_REPORT','9223372036854775808','WORKER','TEST','legacy',false),
                       ('old-meal','MEAL_ANALYSIS','42','WORKER','TEST','legacy',false)
                """);
        config.target(org.flywaydb.core.api.MigrationVersion.LATEST).load().migrate();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM task_failure_events", Integer.class)).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT weekly_report_job_id FROM task_failure_events WHERE dedupe_key='old-weekly'", Long.class)).isEqualTo(42L);
        assertThat(jdbc.queryForObject("SELECT weekly_report_job_id FROM task_failure_events WHERE dedupe_key='invalid-weekly'", Long.class)).isNull();
        assertThat(jdbc.queryForObject("SELECT weekly_report_job_id FROM task_failure_events WHERE dedupe_key='old-meal'", Long.class)).isNull();
        assertThat(jdbc.queryForList("SELECT indexrelid::regclass::text FROM pg_index WHERE NOT (indisvalid AND indisready)", String.class)).isEmpty();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pg_indexes WHERE schemaname='public' AND indexname IN ('idx_users_username_lower_trgm','idx_users_display_name_lower_trgm','idx_meal_records_admin_cursor','idx_non_food_events_created','idx_assistant_user_messages_created','idx_task_failure_time','idx_task_failure_operations_failure_time')", Integer.class)).isEqualTo(7);
        assertThat(config.load().migrate().migrationsExecuted).isZero();
    }
}
