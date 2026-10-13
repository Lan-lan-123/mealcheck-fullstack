package com.example.mealcheck.service;

import com.example.mealcheck.dto.AssistantBenchmarkResponse;
import com.example.mealcheck.config.ApiTimeConfiguration;
import com.example.mealcheck.repository.MealRecordRepository;
import com.example.mealcheck.util.AppTime;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.*;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.*;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.*;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest(properties={"spring.jpa.hibernate.ddl-auto=validate","spring.flyway.baseline-on-migrate=false"})
@AutoConfigureTestDatabase(replace=AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation=Propagation.NOT_SUPPORTED)
@Import({AssistantBenchmarkHistoryService.class,AdminAnalyticsQueryService.class,AssistantEvaluationTaskDatabaseIntegrationTest.Beans.class})
@Testcontainers
class AssistantEvaluationTaskDatabaseIntegrationTest {
    @Container static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            org.testcontainers.utility.DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username",POSTGRES::getUsername);
        registry.add("spring.datasource.password",POSTGRES::getPassword);
    }
    @TestConfiguration static class Beans {
        @Bean AssistantEvaluationTaskStore assistantEvaluationTaskStore(JdbcTemplate jdbc, AssistantBenchmarkHistoryService history) {
            return new AssistantEvaluationTaskStore(jdbc, history, 90);
        }
        @Bean ObjectMapper mapper() {
            var builder = new Jackson2ObjectMapperBuilder();
            new ApiTimeConfiguration().explicitTimestampOffsets().customize(builder);
            return builder.build();
        }
        @Bean AssistantEvaluationTaskStore otherStore(JdbcTemplate jdbc, AssistantBenchmarkHistoryService history) {
            return new AssistantEvaluationTaskStore(jdbc, history, 90);
        }
    }
    @Autowired @Qualifier("assistantEvaluationTaskStore") AssistantEvaluationTaskStore first;
    @Autowired @Qualifier("otherStore") AssistantEvaluationTaskStore second;
    @Autowired AssistantBenchmarkHistoryService history;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;
    @Autowired MealRecordRepository meals;
    @Autowired AdminAnalyticsQueryService analytics;
    @BeforeEach void reset() { jdbc.execute("TRUNCATE assistant_evaluation_tasks,assistant_evaluation_runs CASCADE"); }

    AssistantBenchmarkResponse snapshot(String id, String status, int completed) throws Exception {
        return mapper.readValue("""
            {"runId":"%s","status":"%s","mode":"QUICK","verdict":"RUNNING","caseCount":10,
             "completedCount":%d,"startedAt":"2026-10-08T10:00:00Z","model":"test","message":"test"}
            """.formatted(id,status,completed), AssistantBenchmarkResponse.class);
    }

    @Test void simultaneousInstancesAdmitOneTaskAndReturnItsId() throws Exception {
        var pool=Executors.newFixedThreadPool(8);
        var gate=new CountDownLatch(1);
        try {
            List<Future<String>> results=new ArrayList<>();
            for(int i=0;i<8;i++) {
                var store=i%2==0?first:second;
                var pending=snapshot(UUID.randomUUID().toString(),"PENDING",0);
                results.add(pool.submit(() -> {gate.await();return store.submit(pending,"{}","admin").runId();}));
            }
            gate.countDown();
            Set<String> ids=new HashSet<>();
            for(var result:results) ids.add(result.get(15,TimeUnit.SECONDS));
            assertThat(ids).hasSize(1);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM assistant_evaluation_runs",Integer.class)).isEqualTo(1);
            assertThat(first.current().orElseThrow()).isEqualTo(second.current().orElseThrow());
        } finally {pool.shutdownNow();}
    }

    @Test void onlyOneInstanceCanClaimAndBothReadPersistedProgress() throws Exception {
        String id=UUID.randomUUID().toString();
        first.submit(snapshot(id,"PENDING",0),"{}","admin");
        var pool=Executors.newFixedThreadPool(2);
        try {
            var a=pool.submit(first::claim);var b=pool.submit(second::claim);
            var wa=a.get(10,TimeUnit.SECONDS);var wb=b.get(10,TimeUnit.SECONDS);
            assertThat((wa==null?0:1)+(wb==null?0:1)).isEqualTo(1);
            var work=wa==null?wb:wa;
            assertThat(first.publish(work,snapshot(id,"RUNNING",3))).isTrue();
            assertThat(second.current().orElseThrow().completedCount()).isEqualTo(3);
            second.recoverExpired();
            assertThat(history.detail(id).status()).isEqualTo("RUNNING");
            assertThat(second.renew(work)).isTrue();
        } finally {pool.shutdownNow();}
    }

    @Test void expiredWorkersCannotRenewPublishFailOrStartAnotherCase() throws Exception {
        String id=UUID.randomUUID().toString();
        first.submit(snapshot(id,"PENDING",0),"{}","admin");
        var work=first.claim();
        first.publish(work,snapshot(id,"RUNNING",2));
        jdbc.update("UPDATE assistant_evaluation_tasks SET lease_until=clock_timestamp()-INTERVAL '1 second' WHERE run_id=?",id);
        assertThat(first.renew(work)).isFalse();
        assertThat(first.owns(work)).isFalse();
        assertThat(first.publish(work,snapshot(id,"COMPLETED",10))).isFalse();
        second.recoverExpired();
        assertThat(history.detail(id).status()).isEqualTo("FAILED");
        assertThat(history.detail(id).completedCount()).isEqualTo(2);
        assertThat(history.detail(id).verdict()).isEqualTo("INCOMPLETE");
        assertThat(first.fail(work,"late failure")).isFalse();
        var next=snapshot(UUID.randomUUID().toString(),"PENDING",0);
        assertThat(second.submit(next,"{}","admin").runId()).isEqualTo(next.runId());
    }

    @Test void executorRejectionReturnsTaskToQueueAndRotatesOwnership() throws Exception {
        String id=UUID.randomUUID().toString();
        first.submit(snapshot(id,"PENDING",0),"{}","admin");
        var old=first.claim();first.releaseUndispatched(old);
        assertThat(history.detail(id).status()).isEqualTo("PENDING");
        var replacement=second.claim();
        assertThat(replacement.token()).isNotEqualTo(old.token());
        assertThat(first.publish(old,snapshot(id,"COMPLETED",10))).isFalse();
        assertThat(second.publish(replacement,snapshot(id,"COMPLETED",10))).isTrue();
        assertThat(first.current().orElseThrow().status()).isEqualTo("COMPLETED");
    }

    @Test void shanghaiMidnightGroupingAndFilteringUseTheSameUtcBoundaries() {
        jdbc.update("INSERT INTO users(username,password_hash,role,created_at) VALUES ('time-test','hash','USER',TIMESTAMP '2026-10-08')");
        long user=jdbc.queryForObject("SELECT id FROM users WHERE username='time-test'",Long.class);
        jdbc.update("INSERT INTO meal_records(user_id,score,created_at) VALUES (?,80,TIMESTAMP '2026-10-08 15:59:59'),(?,90,TIMESTAMP '2026-10-08 16:00:00')",user,user);
        var daily=meals.trendDailyScores(user,LocalDateTime.of(2026,10,8,0,0),"Asia/Shanghai");
        assertThat(daily).extracting(MealRecordRepository.DailyScore::getDay).containsExactly(LocalDate.of(2026,10,8),LocalDate.of(2026,10,9));
        int count=jdbc.queryForObject("SELECT count(*) FROM meal_records WHERE user_id=? AND created_at>=? AND created_at<?",Integer.class,
                user,AppTime.startOfDay(LocalDate.of(2026,10,9)),AppTime.startOfDay(LocalDate.of(2026,10,10)));
        assertThat(count).isEqualTo(1);
        var filtered=analytics.mealAnalytics("time-test",LocalDate.of(2026,10,9),LocalDate.of(2026,10,9),null,null);
        assertThat(filtered.getTotal()).isEqualTo(1);
        assertThat(filtered.getDailyAverageScores()).extracting(item -> item.getLabel()).containsExactly("2026-10-09");
        var uploads=analytics.uploadTrends(LocalDate.of(2026,10,8),LocalDate.of(2026,10,9));
        assertThat(uploads.getDailyNormalUploads()).extracting(item -> item.getValue()).containsExactly(1,1);
    }
}
