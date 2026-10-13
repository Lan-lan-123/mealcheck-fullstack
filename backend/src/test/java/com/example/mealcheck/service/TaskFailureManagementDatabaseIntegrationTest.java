package com.example.mealcheck.service;

import com.example.mealcheck.dto.TaskFailureDtos.*;
import com.example.mealcheck.dto.WeeklyReportResponse;
import com.example.mealcheck.entity.UserAccount;
import com.example.mealcheck.repository.*;
import com.example.mealcheck.security.UserPrincipal;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.transaction.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@DataJpaTest(properties={"spring.jpa.hibernate.ddl-auto=validate","spring.flyway.baseline-on-migrate=false"})
@AutoConfigureTestDatabase(replace=AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation=Propagation.NOT_SUPPORTED)
@Import({TaskFailureRecorder.class,TaskFailureManagementService.class,AdminAuditService.class,MealAnalysisDeadLetterArchive.class,
        MealAnalysisRequestStore.class,WeeklyReportJobService.class,WeeklyReportJobCompletionService.class,WeeklyReportDatabaseIntegrationTest.Config.class})
@Testcontainers
class TaskFailureManagementDatabaseIntegrationTest {
    @Container static final PostgreSQLContainer<?> POSTGRES=new PostgreSQLContainer<>(org.testcontainers.utility.DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",POSTGRES::getJdbcUrl);registry.add("spring.datasource.username",POSTGRES::getUsername);
        registry.add("spring.datasource.password",POSTGRES::getPassword);
    }
    @Autowired TaskFailureManagementService failures;
    @Autowired MealAnalysisRequestStore store;
    @Autowired MealAnalysisDeadLetterArchive archive;
    @Autowired WeeklyReportJobService weekly;
    @Autowired WeeklyReportJobCompletionService completion;
    @Autowired WeeklyReportJobRepository jobs;
    @Autowired WeeklyReportSnapshotRepository snapshots;
    @Autowired UserAccountRepository users;
    @Autowired AdminAuditLogRepository audits;
    @Autowired JdbcTemplate jdbc;
    @MockBean UserGoalService goals;
    @MockBean RateLimitService limits;
    @MockBean MealRecordPersistenceService persistence;
    UserPrincipal user,admin;
    final String key="failure-management-key-0001";
    @BeforeEach void setup() {
        jdbc.execute("TRUNCATE task_failure_events,users CASCADE");
        var account=new UserAccount();account.setUsername("task-user");account.setPasswordHash("hash");user=new UserPrincipal(users.saveAndFlush(account));
        var manager=new UserAccount();manager.setUsername("task-admin");manager.setPasswordHash("hash");manager.setRole("ADMIN");admin=new UserPrincipal(users.saveAndFlush(manager));
        when(goals.effectiveGoal(any(),any())).thenReturn("balanced");
        when(limits.consumeMealAnalysis(anyString())).thenReturn(new RateLimitService.RateLimitDecision(RateLimitService.RateLimitStatus.ALLOWED,3,2,Duration.ZERO));
    }
    @Test void concurrentIdenticalRetryIsIdempotentAndOldMessagesCannotClaimNewWork() throws Exception {
        var old=failedMeal();var event=currentEvent();var failure=onlyOpen();var action=action(failure);
        var results=concurrent(() -> failures.retry(failure.id(),action,admin));
        assertThat(results).hasSize(2);assertThat(currentEvent()).isNotEqualTo(event);
        assertThat(store.takeMessage(event)).isNull();assertThat(jdbc.queryForObject("SELECT manual_retry_count FROM meal_analysis_requests",Integer.class)).isEqualTo(1);
        assertThat(audits.count()).isEqualTo(1);
        var current=store.takeMessage(currentEvent());assertThat(current.token()).isNotEqualTo(old.token());
        store.fail(user.getId(),key,old.token(),"SAVE",new IllegalStateException("late failure"));
        assertThat(store.status(user,key).status()).isEqualTo("PROCESSING");
        store.fail(user.getId(),key,current.token(),"ANALYZE",new IllegalStateException("again"));
        assertThat(failures.detail(failure.id()).failure().status()).isEqualTo("RETRY_FAILED");
        assertThat(onlyOpen().id()).isNotEqualTo(failure.id());
        assertThat(failures.retry(failure.id(),action,admin).failure().status()).isEqualTo("RETRY_FAILED");
        assertThat(jdbc.queryForObject("SELECT manual_retry_count FROM meal_analysis_requests",Integer.class)).isEqualTo(1);
    }
    @Test void competingOperationsAndUserRetryCannotEnqueueTwice() throws Exception {
        failedMeal();var failure=onlyOpen();
        var results=concurrent(() -> {
            try { return failures.retry(failure.id(),action(failure),admin).failure().status(); }
            catch(ResponseStatusException error) { return "CONFLICT"; }
        });
        assertThat(results).containsExactlyInAnyOrder("RETRYING","CONFLICT");
        assertThat(jdbc.queryForObject("SELECT manual_retry_count FROM meal_analysis_requests",Integer.class)).isEqualTo(1);
        enqueue(); // Same user request sees PENDING instead of rotating the dispatch a second time.
        assertThat(jdbc.queryForObject("SELECT count(*) FROM meal_analysis_outbox",Integer.class)).isEqualTo(1);
    }
    @Test void pendingDeadLetterIsRecoverableAndOldDeadLettersDoNotReplaceNewState() {
        enqueue();var event=currentEvent();archive.archive(event,event.toString());var failure=onlyOpen();
        assertThat(failure.taskStatus()).isEqualTo("PENDING");assertThat(failure.retryable()).isTrue();
        failures.retry(failure.id(),action(failure),admin);var current=currentEvent();
        archive.archive(event,event.toString());
        assertThat(currentEvent()).isEqualTo(current);assertThat(failures.detail(failure.id()).failure().status()).isEqualTo("RETRYING");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM task_failure_events",Integer.class)).isEqualTo(1);
    }
    @Test void missingImageInvalidInputDeletedAccountAndRetryLimitAreBlocked() {
        failedMeal();var failure=onlyOpen();jdbc.update("UPDATE meal_analysis_requests SET input_image=NULL");
        assertThat(failures.detail(failure.id()).failure().retryBlockedReason()).contains("原图");
        assertThatThrownBy(() -> failures.retry(failure.id(),action(failure),admin)).isInstanceOf(ResponseStatusException.class);
        jdbc.update("UPDATE meal_analysis_requests SET input_image=?,manual_retry_count=3",new byte[]{1});
        assertThat(failures.detail(failure.id()).failure().retryBlockedReason()).contains("上限");
        jdbc.update("UPDATE meal_analysis_requests SET manual_retry_count=0");
        jdbc.update("UPDATE task_failure_events SET recoverable=false");
        assertThat(failures.detail(failure.id()).failure().retryable()).isFalse();
        users.deleteById(user.getId());
        assertThatThrownBy(() -> failures.retry(failure.id(),action(failure),admin)).isInstanceOf(ResponseStatusException.class);
    }
    @Test void manualWeeklyRetryPreservesPeriodAndFencesOldAttemptEvenWhenCountIsReset() {
        weekly.enqueue(List.of(user.getId()),7,LocalDate.now());var first=weekly.claimBatch().get(0);
        jdbc.update("UPDATE weekly_report_jobs SET attempts=3 WHERE id=?",first.getId());
        assertThat(weekly.markFailed(first.getId(),3,first.getExecutionToken(),new IllegalStateException("secret"))).isTrue();
        var failure=onlyOpen();failures.retry(failure.id(),action(failure),admin);
        var claimed=weekly.claimBatch();
        assertThat(claimed).withFailMessage("Retry should be immediately claimable: %s; javaNow=%s",jdbc.queryForList("SELECT status,next_retry_at,CURRENT_TIMESTAMP FROM weekly_report_jobs"),LocalDateTime.now()).hasSize(1);
        var second=claimed.get(0);
        assertThat(second.getAttempts()).isEqualTo(first.getAttempts());
        assertThat(second.getExecutionToken()).isNotEqualTo(first.getExecutionToken());
        assertThat(completion.complete(first.getId(),1,first.getExecutionToken(),new WeeklyReportResponse())).isFalse();
        assertThat(weekly.markFailed(first.getId(),1,first.getExecutionToken(),new IllegalStateException("late"))).isFalse();
        assertThat(completion.complete(second.getId(),1,second.getExecutionToken(),new WeeklyReportResponse())).isTrue();
        assertThat(snapshots.findByJobId(second.getId()).orElseThrow().getPeriodEnd()).isEqualTo(first.getCreatedAt().toLocalDate());
        assertThat(failures.detail(failure.id()).failure().status()).isEqualTo("RECOVERED");
    }
    @Test void closingIsAuditedIdempotentAndStaleVersionCannotRetry() {
        failedMeal();var failure=onlyOpen();var action=action(failure);
        assertThat(failures.close(failure.id(),action,admin).failure().status()).isEqualTo("CLOSED");
        failures.close(failure.id(),action,admin);assertThat(audits.count()).isEqualTo(1);
        assertThatThrownBy(() -> failures.retry(failure.id(),action(failure),admin)).isInstanceOf(ResponseStatusException.class);
        assertThat(store.status(user,key).status()).isEqualTo("FAILED");
    }
    @Test void malformedDeadLettersAreDeduplicatedAndNeverAuthorizeExecution() {
        archive.archive(null,"digest");archive.archive(null,"digest");var failure=onlyOpen();
        assertThat(failure.taskType()).isEqualTo("BROKER");assertThat(failure.retryable()).isFalse();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM task_failure_events",Integer.class)).isEqualTo(1);
        assertThatThrownBy(() -> failures.retry(failure.id(),action(failure),admin)).isInstanceOf(ResponseStatusException.class);
    }
    @Test void interruptedLeaseExhaustionRecordsFailureAndSanitizedErrorHistory() {
        enqueue();var work=store.takeMessage(currentEvent());
        jdbc.update("UPDATE meal_analysis_requests SET attempts=3,lease_until=CURRENT_TIMESTAMP-INTERVAL '1 minute'");
        assertThat(store.takeMessage(currentEvent())).isNull();assertThat(onlyOpen().errorCode()).isEqualTo("WORKER_INTERRUPTED");
        failures.retry(onlyOpen().id(),action(onlyOpen()),admin);work=store.takeMessage(currentEvent());
        store.fail(user.getId(),key,work.token(),"SAVE",new org.springframework.dao.DataIntegrityViolationException("secret-password-model-body"));
        assertThat(onlyOpen().errorSummary()).doesNotContain("secret-password-model-body");
        assertThat(onlyOpen().errorCode()).isEqualTo("DATABASE_UNAVAILABLE");
    }
    @Test void failurePagesKeepOrderingAndEnrichmentWithMalformedWeeklyKeys() {
        failedMeal();
        long mealFailure = onlyOpen().id();
        jdbc.update("INSERT INTO task_failure_events(dedupe_key,task_type,user_id,task_key,phase,error_code,error_summary,recoverable,failed_at) VALUES ('malformed-weekly','WEEKLY_REPORT',?,'9223372036854775808','WORKER','TEST','test',true,CURRENT_TIMESTAMP+INTERVAL '1 second')", user.getId());
        var first = failures.list("ALL", "OPEN", null, null, null, null, 0, 1);
        assertThat(first.totalElements()).isEqualTo(2);
        assertThat(first.content()).hasSize(1);
        assertThat(first.content().get(0).taskType()).isEqualTo("WEEKLY_REPORT");
        assertThat(first.content().get(0).retryable()).isFalse();
        assertThat(first.content().get(0).retryBlockedReason()).contains("任务已清理");
        var second = failures.list("ALL", "OPEN", null, null, null, null, 1, 1);
        assertThat(second.content()).hasSize(1);
        assertThat(second.content().get(0).id()).isEqualTo(mealFailure);
        assertThat(second.content().get(0).taskStatus()).isEqualTo("FAILED");
        assertThat(second.content().get(0).retryable()).isTrue();
    }

    private void enqueue() { store.enqueue(user,key,"hash","balanced",new ValidatedImage(new byte[]{1},"image/jpeg",".jpg",1,1,"meal.jpg")); }
    private MealAnalysisRequestStore.Work failedMeal() {
        enqueue();var work=store.takeMessage(currentEvent());store.fail(user.getId(),key,work.token(),"ANALYZE",new IllegalStateException("secret"));return work;
    }
    private UUID currentEvent() { return jdbc.queryForObject("SELECT event_id FROM meal_analysis_outbox",UUID.class); }
    private Item onlyOpen() { return failures.list("ALL","OPEN",null,null,null,null,0,20).content().get(0); }
    private Action action(Item item) { return new Action(UUID.randomUUID(),item.version(),"依赖已恢复，重新执行"); }
    private <T> List<T> concurrent(Callable<T> action) throws Exception {
        var executor=Executors.newFixedThreadPool(2);var gate=new CountDownLatch(1);
        try {
            Callable<T> waiting=() -> { gate.await();return action.call(); };
            var a=executor.submit(waiting);var b=executor.submit(waiting);gate.countDown();
            return List.of(a.get(20,TimeUnit.SECONDS),b.get(20,TimeUnit.SECONDS));
        } finally { executor.shutdownNow(); }
    }
}
