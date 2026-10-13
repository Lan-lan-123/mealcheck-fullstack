package com.example.mealcheck.service;

import com.example.mealcheck.dto.AdminMealRecordResponse;
import com.example.mealcheck.util.AppTime;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@DataJpaTest(properties = {"spring.jpa.hibernate.ddl-auto=validate", "spring.flyway.baseline-on-migrate=false",
        "spring.jpa.properties.hibernate.generate_statistics=true"})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import({AdminMealQueryService.class, AdminAnalyticsQueryService.class, AdminQueryOptimizationDatabaseIntegrationTest.Beans.class})
@Testcontainers
class AdminQueryOptimizationDatabaseIntegrationTest {
    @Container static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }
    @TestConfiguration static class Beans {
        @Bean ObjectMapper objectMapper() { return new ObjectMapper(); }
    }
    @Autowired AdminMealQueryService meals;
    @Autowired AdminAnalyticsQueryService analytics;
    @SpyBean JdbcTemplate jdbc;
    @Autowired EntityManagerFactory entityManagerFactory;
    long alice, other;
    final LocalDate day = LocalDate.of(2026, 10, 9);

    @BeforeEach void setup() {
        jdbc.execute("TRUNCATE users CASCADE");
        alice = user("Alice-Query");
        other = user("Other-Query");
        entityManagerFactory.unwrap(SessionFactory.class).getStatistics().clear();
    }

    @Test void numberedPageKeepsFiltersAndExcludesNextBusinessDayWithoutLoadingEntities() {
        meal(alice, LocalDateTime.of(2026, 10, 8, 15, 59, 59), 80, "[]");
        long first = meal(alice, LocalDateTime.of(2026, 10, 8, 16, 0), 80, "[{\"name\":\"米饭\"},{\"name\":\"\"}]");
        long second = meal(alice, LocalDateTime.of(2026, 10, 9, 15, 59, 59), 90, "[]");
        meal(alice, LocalDateTime.of(2026, 10, 9, 16, 0), 90, "[]");
        meal(other, LocalDateTime.of(2026, 10, 9, 10, 0), 90, "[]");
        meal(alice, LocalDateTime.of(2026, 10, 9, 10, 0), 50, null);
        var page = meals.page(0, 1, " ALICE ", day, day, 80, 90);
        assertThat(page.getTotalElements()).isEqualTo(2);
        assertThat(page.getTotalPages()).isEqualTo(2);
        assertThat(page.getContent()).extracting(AdminMealRecordResponse::getId).containsExactly(second);
        var next = meals.page(1, 1, "alice", day, day, 80, 90);
        assertThat(next.getContent()).extracting(AdminMealRecordResponse::getId).containsExactly(first);
        assertThat(next.getContent().get(0).getFoodNames()).containsExactly("米饭");
        assertThat(entityManagerFactory.unwrap(SessionFactory.class).getStatistics().getEntityLoadCount()).isZero();
    }

    @Test void cursorRemainsStableAcrossTimestampTiesAndNewerInsertions() {
        LocalDateTime time = AppTime.startOfDay(day).plusHours(1);
        List<Long> ids = new ArrayList<>();
        for (int i = 0; i < 5; i++) ids.add(meal(alice, time, 80, "[]"));
        meal(other, time, 80, "[]");
        var first = meals.cursor(2, "alice", day, day, 80, 80, null, null);
        assertThat(first.content()).extracting(AdminMealRecordResponse::getId).containsExactly(ids.get(4), ids.get(3));
        assertThat(first.hasMore()).isTrue();
        meal(alice, time.plusSeconds(1), 80, "[]");
        var second = meals.cursor(2, "alice", day, day, 80, 80, first.nextBeforeTime(), first.nextBeforeId());
        assertThat(second.content()).extracting(AdminMealRecordResponse::getId).containsExactly(ids.get(2), ids.get(1));
        var last = meals.cursor(2, "alice", day, day, 80, 80, second.nextBeforeTime(), second.nextBeforeId());
        assertThat(last.content()).extracting(AdminMealRecordResponse::getId).containsExactly(ids.get(0));
        assertThat(last.hasMore()).isFalse();
        assertThat(last.nextBeforeId()).isNull();
        assertThat(last.nextBeforeTime()).isNull();
    }

    @Test void emptyResultsMissingFoodsAndInvalidCursorsHaveDefinedBehavior() {
        assertThat(meals.page(-1, 1000, null, null, null, null, null).getSize()).isEqualTo(100);
        assertThat(meals.cursor(0, null, null, null, null, null, null, null).content()).isEmpty();
        meal(alice, AppTime.startOfDay(day), 0, null);
        var row = meals.cursor(20, null, null, null, null, null, null, null).content().get(0);
        assertThat(row.getScore()).isZero();
        assertThat(row.getFoodNames()).isEmpty();
        assertThatThrownBy(() -> meals.cursor(20, null, null, null, null, null, AppTime.startOfDay(day), null))
                .isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> meals.cursor(20, null, null, null, null, null, AppTime.startOfDay(day), 0L))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test void uploadTrendsAggregateOncePerSourceAndPreserveZeroDaysAndDistinctBlockedUsers() {
        meal(alice, AppTime.startOfDay(day).minusSeconds(1), 80, "[]");
        meal(alice, AppTime.startOfDay(day), 80, "[]");
        meal(alice, AppTime.startOfDay(day.plusDays(1)), 80, "[]");
        event("alice", true, AppTime.startOfDay(day));
        event("alice", true, AppTime.startOfDay(day).plusHours(1));
        event("other", false, AppTime.startOfDay(day));
        event("outside", true, AppTime.startOfDay(day.plusDays(1)));
        var trends = analytics.uploadTrends(day.minusDays(2), day);
        assertThat(trends.getDailyNormalUploads()).extracting(item -> item.getValue()).containsExactly(0, 1, 1);
        assertThat(trends.getDailyAbnormalUploads()).extracting(item -> item.getValue()).containsExactly(0, 0, 3);
        assertThat(trends.getDailyAlertTriggers()).extracting(item -> item.getValue()).containsExactly(0, 0, 2);
        assertThat(trends.getDailyBlockedUsers()).extracting(item -> item.getValue()).containsExactly(0, 0, 1);
    }

    @Test void typedFailureReferenceRejectsMalformedAndOutOfRangeLegacyKeys() {
        List<String> keys = List.of("42", "042", "not-a-number", "9223372036854775807", "9223372036854775808", "0", "-1");
        for (String key : keys) {
            jdbc.update("INSERT INTO task_failure_events(dedupe_key,task_type,user_id,task_key,phase,error_code,error_summary,recoverable) VALUES (?,'WEEKLY_REPORT',?,?,'WORKER','TEST','test',false)",
                    key, alice, key);
        }
        assertThat(jdbc.queryForList("SELECT weekly_report_job_id FROM task_failure_events WHERE weekly_report_job_id IS NOT NULL ORDER BY weekly_report_job_id", Long.class))
                .containsExactly(42L, Long.MAX_VALUE);
        jdbc.update("UPDATE task_failure_events SET task_type='MEAL_ANALYSIS' WHERE task_key='42'");
        assertThat(jdbc.queryForObject("SELECT weekly_report_job_id FROM task_failure_events WHERE task_key='42'", Long.class)).isNull();
        assertThat(jdbc.queryForList("SELECT indexrelid::regclass::text FROM pg_index WHERE NOT indisvalid", String.class)).isEmpty();
    }

    @Test void recordsPlansForActualProjectedQueryAgainstRepresentativeWideRows() throws Exception {
        jdbc.update("""
                INSERT INTO meal_records(user_id,score,created_at,summary,detected_foods_json,advice,original_foods_json)
                SELECT ?,80,TIMESTAMP '2026-10-01'+i*INTERVAL '1 second','summary','[]',
                       repeat('long advice ',1000),'[]' FROM generate_series(1,20000) i
                """, alice);
        jdbc.execute("ANALYZE meal_records");
        clearInvocations(jdbc);
        assertThat(meals.page(0, 20, null, null, null, null, null).getContent()).hasSize(20);
        var query = mockingDetails(jdbc).getInvocations().stream()
                .filter(call -> call.getMethod().getName().equals("query") && call.getRawArguments().length == 3
                        && call.getRawArguments()[1] instanceof org.springframework.jdbc.core.RowMapper)
                .findFirst().orElseThrow();
        String sql = (String) query.getRawArguments()[0];
        Object[] args = (Object[]) query.getRawArguments()[2];
        List<String> projected = jdbc.queryForList("EXPLAIN (ANALYZE,BUFFERS) " + sql, String.class, args);
        List<String> full = jdbc.queryForList("""
                EXPLAIN (ANALYZE,BUFFERS) SELECT m.*,u.* FROM meal_records m
                LEFT JOIN users u ON u.id=m.user_id ORDER BY m.created_at DESC,m.id DESC LIMIT 20
                """, String.class);
        Files.writeString(Path.of("target/admin-query-plans.txt"),
                "Fixture: 20,000 wide meals; timings are not production capacity evidence.\n\nProjected actual query:\n"
                        + sql + "\n" + String.join("\n", projected) + "\n\nFull-row comparison:\n" + String.join("\n", full));
        assertThat(projected).anyMatch(line -> line.contains("meal_page"));
        assertThat(entityManagerFactory.unwrap(SessionFactory.class).getStatistics().getEntityLoadCount()).isZero();
    }

    private long user(String username) {
        return jdbc.queryForObject("INSERT INTO users(username,password_hash) VALUES (?,'hash') RETURNING id", Long.class, username);
    }
    private long meal(long user, LocalDateTime time, Integer score, String foods) {
        return jdbc.queryForObject("INSERT INTO meal_records(user_id,created_at,score,summary,detected_foods_json,advice,original_foods_json) VALUES (?,?,?,'summary',?,repeat('long advice ',10000),'[]') RETURNING id",
                Long.class, user, time, score, foods);
    }
    private void event(String username, boolean threshold, LocalDateTime time) {
        jdbc.update("INSERT INTO non_food_upload_events(username,window_count,window_minutes,threshold_reached,created_at) VALUES (?,1,5,?,?)", username, threshold, time);
    }
}
