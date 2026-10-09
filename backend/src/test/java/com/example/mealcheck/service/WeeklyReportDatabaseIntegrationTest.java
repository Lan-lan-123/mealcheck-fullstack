package com.example.mealcheck.service;

import com.example.mealcheck.config.AppProperties;
import com.example.mealcheck.dto.WeeklyReportResponse;
import com.example.mealcheck.entity.*;
import com.example.mealcheck.repository.*;
import com.example.mealcheck.security.UserPrincipal;
import com.example.mealcheck.util.AppTime;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@DataJpaTest(properties = {"spring.jpa.hibernate.ddl-auto=validate", "spring.flyway.baseline-on-migrate=false"})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import({TaskFailureRecorder.class, WeeklyReportJobService.class, WeeklyReportJobCompletionService.class, WeeklyReportRequestService.class,
        MealRecordPersistenceService.class, MealReportInvalidationService.class, UserDietProfileService.class, ImageStorageService.class,
        WeeklyReportDatabaseIntegrationTest.Config.class})
@Testcontainers
class WeeklyReportDatabaseIntegrationTest {
    @Container static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(org.testcontainers.utility.DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));
    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }
    @TestConfiguration static class Config {
        @Bean AppProperties appProperties() { return new AppProperties(); }
        @Bean ObjectMapper objectMapper() { return new ObjectMapper().findAndRegisterModules(); }
    }
    @Autowired WeeklyReportJobService workerJobs;
    @Autowired WeeklyReportJobCompletionService completion;
    @Autowired WeeklyReportRequestService requests;
    @Autowired WeeklyReportJobRepository jobs;
    @Autowired WeeklyReportSnapshotRepository snapshots;
    @Autowired UserAccountRepository users;
    @Autowired MealRecordRepository meals;
    @Autowired MealRecordPersistenceService persistence;
    @Autowired ImageStorageService images;
    @Autowired AppProperties properties;
    @Autowired JdbcTemplate jdbc;
    @MockBean RateLimitService limits;
    @SpyBean UserDietProfileService profiles;
    @TempDir Path uploadDir;
    UserAccount user;

    @BeforeEach void setup() {
        jdbc.execute("TRUNCATE users CASCADE");
        properties.setUploadDir(uploadDir.toString());
        user = new UserAccount(); user.setUsername("alice"); user.setPasswordHash("hash");
        user = users.saveAndFlush(user);
        when(limits.consumeWeeklyReport(anyString())).thenReturn(new RateLimitService.RateLimitDecision(
                RateLimitService.RateLimitStatus.ALLOWED, 3, 2, Duration.ZERO));
    }

    @Test void concurrentSubmissionsSpendQuotaOnceAndReturnTheSameJob() throws Exception {
        var results = concurrent(() -> requests.request(new UserPrincipal(user), 7));
        assertThat(results.get(0).id()).isEqualTo(results.get(1).id());
        assertThat(jobs.count()).isEqualTo(1);
        verify(limits, times(1)).consumeWeeklyReport(user.getId().toString());
    }

    @Test void concurrentWorkersDoNotClaimTheSameJob() throws Exception {
        workerJobs.enqueue(List.of(user.getId()), 7, LocalDate.now());
        var results = concurrent(workerJobs::claimBatch);
        assertThat(results.stream().flatMap(List::stream).map(WeeklyReportJob::getId).toList()).hasSize(1);
    }

    @Test void repeatedDispatchRejectionsPreserveRetryBudgetAndFenceReleasedAttempts() {
        workerJobs.enqueue(List.of(user.getId()),7,LocalDate.now());
        for (int rejection=0; rejection<6; rejection++) {
            var claimed=workerJobs.claimBatch(1);
            assertThat(claimed).as("rejection=%s",rejection).hasSize(1);
            var job=claimed.get(0);
            assertThat(job.getAttempts()).isEqualTo(1);
            assertThat(workerJobs.releaseUndispatched(job.getId(),job.getAttempts(),job.getExecutionToken())).isTrue();
            assertThat(workerJobs.releaseUndispatched(job.getId(),job.getAttempts(),job.getExecutionToken())).isFalse();
            assertThat(workerJobs.markFailed(job.getId(),job.getAttempts(),job.getExecutionToken(),new IllegalStateException("stale"))).isFalse();
            var released=jobs.findById(job.getId()).orElseThrow();
            assertThat(released.getStatus()).isEqualTo(WeeklyReportJob.RETRY);
            assertThat(released.getAttempts()).isZero();
            assertThat(released.getExecutionToken()).isNotEqualTo(job.getExecutionToken());
            jdbc.update("UPDATE weekly_report_jobs SET next_retry_at=(CURRENT_TIMESTAMP AT TIME ZONE 'UTC')-INTERVAL '1 minute' WHERE id=?",job.getId());
        }
        assertThat(workerJobs.claimBatch(1).get(0).getAttempts()).isEqualTo(1);
    }

    @Test void storageTimestampsAndNativeClaimsAgreeAcrossJvmTimezones() {
        TimeZone original = TimeZone.getDefault();
        try {
            int week = 0;
            for (String zone : List.of("UTC", "Asia/Shanghai", "America/Los_Angeles")) {
                TimeZone.setDefault(TimeZone.getTimeZone(zone));
                LocalDate reportWeek = LocalDate.of(2020, 1, 6).plusWeeks(week++);
                workerJobs.enqueue(List.of(user.getId()), 7, reportWeek);
                var job = jobs.findByUserIdAndReportWeekAndDays(user.getId(), reportWeek, 7).orElseThrow();
                LocalDateTime stored = jdbc.queryForObject("SELECT next_retry_at FROM weekly_report_jobs WHERE id=?",
                        (rs, row) -> rs.getObject(1, LocalDateTime.class), job.getId());
                assertThat(Duration.between(AppTime.now(), stored).abs()).as(zone).isLessThan(Duration.ofSeconds(3));
                assertThat(Duration.between(stored, job.getNextRetryAt()).abs()).as(zone).isLessThan(Duration.ofMillis(1));

                LocalDateTime due = LocalDateTime.of(2020, 1, 1, 0, 0);
                job.setNextRetryAt(due);
                jobs.saveAndFlush(job);
                LocalDateTime saved = jdbc.queryForObject("SELECT next_retry_at FROM weekly_report_jobs WHERE id=?",
                        (rs, row) -> rs.getObject(1, LocalDateTime.class), job.getId());
                assertThat(saved).as(zone).isEqualTo(due);
                assertThat(workerJobs.claimBatch(1)).as(zone).extracting(WeeklyReportJob::getId).containsExactly(job.getId());
            }
        } finally {
            TimeZone.setDefault(original);
        }
    }

    @Test void expiredAttemptCannotPublishAndCurrentCompletionIsIdempotent() {
        var first = claim();
        jdbc.update("UPDATE weekly_report_jobs SET updated_at = CURRENT_TIMESTAMP - INTERVAL '2 days' WHERE id = ?", first.getId());
        var second = workerJobs.claimBatch().get(0);
        assertThat(second.getAttempts()).isEqualTo(2);
        assertThat(completion.complete(first.getId(), first.getAttempts(), first.getExecutionToken(), report())).isFalse();
        assertThat(snapshots.count()).isZero();
        assertThat(completion.complete(second.getId(), second.getAttempts(), second.getExecutionToken(), report())).isTrue();
        assertThat(completion.complete(second.getId(), second.getAttempts(), second.getExecutionToken(), report())).isFalse();
        assertThat(snapshots.count()).isEqualTo(1);
        assertThat(requests.status(new UserPrincipal(user), second.getId()).report()).isNotNull();
    }

    @Test void snapshotFailureRollsBackSucceededStatus() {
        var job = claim();
        // Force an actual database uniqueness failure after the status UPDATE.
        jdbc.update("INSERT INTO weekly_report_snapshots(user_id, days, job_id, report_json) VALUES (?, 7, ?, '{}')",
                user.getId(), job.getId());
        assertThatThrownBy(() -> completion.complete(job.getId(), job.getAttempts(), job.getExecutionToken(), report()))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThat(jobs.findById(job.getId()).orElseThrow().getStatus()).isEqualTo("RUNNING");
        assertThat(snapshots.count()).isEqualTo(1);
        snapshots.deleteById(snapshots.findByJobId(job.getId()).orElseThrow().getId());
        assertThat(completion.complete(job.getId(), job.getAttempts(), job.getExecutionToken(), report())).isTrue();
    }

    @Test void concurrentCompletionsPublishExactlyOneSnapshot() throws Exception {
        var job = claim();
        assertThat(concurrent(() -> completion.complete(job.getId(), job.getAttempts(), job.getExecutionToken(), report())))
                .containsExactlyInAnyOrder(true, false);
        assertThat(snapshots.count()).isEqualTo(1);
    }

    @Test void crashedWorkersEventuallyExhaustTheirRetryBudget() {
        var job = claim();
        jdbc.update("UPDATE weekly_report_jobs SET attempts = 3, updated_at = CURRENT_TIMESTAMP - INTERVAL '2 days' WHERE id = ?", job.getId());
        assertThat(workerJobs.claimBatch()).isEmpty();
        assertThat(jobs.findById(job.getId()).orElseThrow().getStatus()).isEqualTo("DEAD");
    }

    @Test void imageAndMealArePersistedTogetherAndRollbackCleansTheFile() throws Exception {
        var image = new ValidatedImage(new byte[]{1, 2, 3}, "image/jpeg", ".jpg", 1, 1, "meal.jpg");
        var record = meal();
        var saved = persistence.saveWithImage(user, record, image);
        assertThat(meals.findById(saved.getId())).isPresent();
        assertThat(images.resolve(saved.getStoredImagePath())).exists();
        assertThat(profiles.getOrRefresh(user).getTotalMeals()).isEqualTo(1);
        doThrow(new IllegalStateException("profile unavailable")).when(profiles).applyMeal(any(), any());
        var failed = meal();
        assertThatThrownBy(() -> persistence.saveWithImage(user, failed, image)).isInstanceOf(IllegalStateException.class);
        assertThat(meals.count()).isEqualTo(1);
        assertThat(images.resolve(failed.getStoredImagePath())).doesNotExist();
    }

    private MealRecord meal() {
        var meal = new MealRecord(); meal.setScore(90); meal.setGoal("balanced");
        meal.setDetectedFoodsJson("[]"); meal.setRiskTagsJson("[]"); meal.setCategoryCountsJson("{}");
        return meal;
    }
    private WeeklyReportJob claim() {
        workerJobs.enqueue(List.of(user.getId()), 7, LocalDate.now());
        return workerJobs.claimBatch().get(0);
    }
    private WeeklyReportResponse report() {
        var report = new WeeklyReportResponse(); report.setDays(7); report.setGeneratedAt(LocalDateTime.now());
        return report;
    }
    private <T> List<T> concurrent(Callable<T> task) throws Exception {
        var executor = Executors.newFixedThreadPool(2);
        var start = new CountDownLatch(1);
        try {
            Callable<T> gated = () -> { start.await(); return task.call(); };
            var first = executor.submit(gated); var second = executor.submit(gated); start.countDown();
            return List.of(first.get(20, TimeUnit.SECONDS), second.get(20, TimeUnit.SECONDS));
        } finally { executor.shutdownNow(); }
    }
}
