package com.example.mealcheck.service;

import com.example.mealcheck.config.AppProperties;
import com.example.mealcheck.dto.*;
import com.example.mealcheck.entity.*;
import com.example.mealcheck.repository.*;
import com.example.mealcheck.security.UserPrincipal;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.*;
import org.springframework.transaction.annotation.*;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@DataJpaTest(properties={"spring.jpa.hibernate.ddl-auto=validate","spring.flyway.baseline-on-migrate=false"})
@AutoConfigureTestDatabase(replace=AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation=Propagation.NOT_SUPPORTED)
@Import({TaskFailureRecorder.class, MealAnalysisLeaseService.class, MealAnalysisService.class, BehaviorTrackingService.class, MealAnalysisRequestStore.class, MealAnalysisRequestService.class,
        MealCorrectionService.class, MealCorrectionEvaluationService.class, MealScoringService.class,
        AdviceGenerationService.class, MealRecordPersistenceService.class, MealReportInvalidationService.class,
        UserDietProfileService.class, ImageStorageService.class, ImageUploadValidationService.class,
        WeeklyReportJobService.class, WeeklyReportJobCompletionService.class, WeeklyReportRequestService.class,
        WeeklyReportDatabaseIntegrationTest.Config.class, MealAnalysisDatabaseIntegrationTest.LeaseMetricsConfig.class})
@Testcontainers
class MealAnalysisDatabaseIntegrationTest {
    @org.springframework.boot.test.context.TestConfiguration
    static class LeaseMetricsConfig {
        @org.springframework.context.annotation.Bean
        io.micrometer.core.instrument.MeterRegistry leaseMetrics() {
            return new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
        }
    }
    @Container static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(org.testcontainers.utility.DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }
    @Autowired MealAnalysisRequestService requests;
    @Autowired MealAnalysisRequestStore store;
    @Autowired MealAnalysisService analysis;
    @Autowired MealCorrectionService corrections;
    @Autowired MealCorrectionEvaluationService evaluation;
    @Autowired MealRecordRepository meals;
    @Autowired UserAccountRepository users;
    @Autowired UserDietProfileService profiles;
    @Autowired WeeklyReportRequestService reports;
    @Autowired WeeklyReportJobService jobs;
    @Autowired WeeklyReportJobCompletionService completion;
    @Autowired AppProperties properties;
    @Autowired JdbcTemplate jdbc;
    @Autowired MealAnalysisLeaseService leases;
    @Autowired ObjectMapper mapper;
    @Autowired MealRecordPersistenceService persistence;
    @Autowired ImageStorageService storage;
    @Autowired org.springframework.transaction.PlatformTransactionManager transactions;
    @MockBean RateLimitService limits;
    @MockBean UserGoalService goals;
    @MockBean NonFoodUploadGuardService guard;
    @MockBean KnowledgeIndexService knowledge;
    @MockBean VisionFoodRecognitionService vision;
    @MockBean AiChatClient ai;
    @TempDir Path uploadDir;
    UserAccount user;
    UserPrincipal principal;
    MockMultipartFile image;
    static final String KEY="meal-request-key-0001";

    @Test void referencedPayloadLoadsOutsideClaimAndTransfersOwnershipWithoutAnotherUpload() throws Exception {
        var validated=new ValidatedImage(new byte[]{1,2,3},"image/jpeg",".jpg",1,1,"meal.jpg");
        var claim=store.claimUpload(principal,KEY,"hash","balanced");
        String reference=storage.store(user.getId(),validated);
        store.enqueueClaimedReference(principal,KEY,claim.token(),validated,reference);
        assertThat(jdbc.queryForObject("SELECT input_image IS NULL FROM meal_analysis_requests",Boolean.class)).isTrue();
        var work=store.next();assertThat(work.image().size()).isZero();
        assertThat(store.loadImage(work).bytes()).containsExactly(1,2,3);
        java.nio.file.Files.setLastModifiedTime(storage.resolve(reference),java.nio.file.attribute.FileTime.from(Instant.now().minusSeconds(172800)));
        String orphan=storage.store(user.getId(),validated);
        java.nio.file.Files.setLastModifiedTime(storage.resolve(orphan),java.nio.file.attribute.FileTime.from(Instant.now().minusSeconds(172800)));
        var cleanup=new ImageOrphanCleanupService(jdbc,storage,properties,mock(BackgroundTaskDispatcher.class));
        cleanup.cleanup();
        assertThat(storage.resolve(reference)).exists();assertThat(storage.resolve(orphan)).doesNotExist();
        var prepared=analysis.prepareValidated(work.user(),store.loadImage(work),work.goal());
        assertThat(store.complete(work.user(),work.key(),work.token(),prepared).status()).isEqualTo("SUCCEEDED");
        assertThat(meals.findAll().get(0).getStoredImagePath()).isEqualTo(reference);
        assertThat(jdbc.queryForObject("SELECT input_image_ref IS NULL FROM meal_analysis_requests",Boolean.class)).isTrue();
        assertThat(storage.resolve(reference)).exists();
    }
    @Test void referencedPayloadSurvivesRolledBackCompletionForRetry() {
        var validated=new ValidatedImage(new byte[]{1},"image/jpeg",".jpg",1,1,"meal.jpg");
        var claim=store.claimUpload(principal,KEY,"hash","balanced");
        String reference=storage.store(user.getId(),validated);
        store.enqueueClaimedReference(principal,KEY,claim.token(),validated,reference);
        var work=store.next();var prepared=analysis.prepareValidated(work.user(),store.loadImage(work),work.goal());
        var transaction=new org.springframework.transaction.support.TransactionTemplate(transactions);
        assertThatThrownBy(()->transaction.execute(status->{store.complete(work.user(),work.key(),work.token(),prepared);throw new IllegalStateException("rollback");}))
                .isInstanceOf(IllegalStateException.class);
        assertThat(meals.count()).isZero();assertThat(storage.resolve(reference)).exists();
        assertThat(jdbc.queryForObject("SELECT input_image_ref FROM meal_analysis_requests",String.class)).isEqualTo(reference);
    }

    @BeforeEach void setup() throws Exception {
        properties.getAiBudget().setOperationTimeoutSeconds(120);
        org.springframework.test.util.ReflectionTestUtils.setField(store,"maxOutstanding",32);
        org.springframework.test.util.ReflectionTestUtils.setField(store,"maxOutstandingPerUser",2);
        jdbc.execute("TRUNCATE meal_evaluation_runs,meal_evaluation_datasets CASCADE");
        jdbc.execute("TRUNCATE users CASCADE");
        properties.setUploadDir(uploadDir.toString());
        user = new UserAccount(); user.setUsername("alice"); user.setPasswordHash("hash"); user=users.saveAndFlush(user);
        principal=new UserPrincipal(user);
        var bytes=new java.io.ByteArrayOutputStream();
        javax.imageio.ImageIO.write(new java.awt.image.BufferedImage(2,2,java.awt.image.BufferedImage.TYPE_INT_RGB), "jpg", bytes);
        image=new MockMultipartFile("image","meal.jpg","image/jpeg",bytes.toByteArray());
        var allowed=new RateLimitService.RateLimitDecision(RateLimitService.RateLimitStatus.ALLOWED,3,2,Duration.ZERO);
        when(limits.consumeMealAnalysis(anyString())).thenReturn(allowed);
        when(limits.consumeUploadIngress(anyString())).thenReturn(allowed);
        when(limits.consumeWeeklyReport(anyString())).thenReturn(allowed);
        when(goals.effectiveGoal(any(),anyString())).thenAnswer(call -> "current".equals(call.getArgument(1)) ? "balanced" : call.getArgument(1));
        when(knowledge.search(anyString(),anyInt())).thenReturn(List.of());
        when(vision.recognize(any(ValidatedImage.class))).thenAnswer(call -> recognition());
    }
    private RecognitionResult recognition() {
        var result=new RecognitionResult(); result.setFoods(List.of(new FoodItem("米饭","staple",.9,null)));
        return result;
    }
    @Test void synchronousLateAdviceReturnsDeadlineErrorWithoutSavingMealOrImage() throws Exception {
        properties.getAiBudget().setOperationTimeoutSeconds(1);
        when(ai.isConfigured()).thenReturn(true);
        when(ai.complete(anyString(), anyList(), anyDouble())).thenAnswer(call -> {
            Thread.sleep(1100);
            return "late advice";
        });
        assertThatThrownBy(() -> analysis.analyze(principal, image, "balanced"))
                .isInstanceOf(RemoteCallGuard.RemoteCallDeadlineException.class);
        verify(ai, times(1)).complete(anyString(), anyList(), anyDouble());
        assertThat(meals.count()).isZero();
        try (var files = java.nio.file.Files.walk(uploadDir)) {
            assertThat(files.filter(java.nio.file.Files::isRegularFile).count()).isZero();
        }
        assertThat(AiCallContext.capture()).isNull();
    }

    @Test void deadlineExpiredBeforeCommitRollsBackMealAndDeletesStagedImage() {
        var prepared = analysis.prepare(principal, image, "balanced");
        try (var scope = AiCallContext.open(user.getId(), Duration.ofSeconds(1))) {
            assertThatThrownBy(() -> new org.springframework.transaction.support.TransactionTemplate(transactions)
                    .execute(ignored -> {
                        persistence.saveWithImage(user, prepared.record(), prepared.image());
                        try { Thread.sleep(1100); }
                        catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new IllegalStateException(error); }
                        return null;
                    })).isInstanceOf(RemoteCallGuard.RemoteCallDeadlineException.class);
        }
        assertThat(meals.count()).isZero();
        try (var files = java.nio.file.Files.walk(uploadDir)) {
            assertThat(files.filter(java.nio.file.Files::isRegularFile).count()).isZero();
        } catch (java.io.IOException error) { throw new java.io.UncheckedIOException(error); }
    }
    @Test void outstandingLimitIncludesUnpublishedAndPreparationButReplaysStillWork() {
        requests.enqueue(principal,KEY,image,"balanced");
        store.claim(principal,"admission-prepare-0001","hash","balanced");
        assertThatThrownBy(()->requests.enqueue(principal,"admission-reject-0001",image,"balanced"))
                .isInstanceOf(AnalysisAdmissionException.class)
                .extracting(e->((AnalysisAdmissionException)e).code()).isEqualTo("ANALYSIS_USER_BUSY");
        assertThat(requests.enqueue(principal,KEY,image,"balanced").status()).isEqualTo("PENDING");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM meal_analysis_requests",Long.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM meal_analysis_outbox",Long.class)).isEqualTo(1);
        verifyNoInteractions(vision);
        var work=store.next();store.fail(work.user().getId(),work.key(),work.token());
        assertThat(requests.enqueue(principal,"admission-reject-0001",image,"balanced").status()).isEqualTo("PENDING");
    }
    @Test void concurrentAccountsCannotOversubscribeGlobalOutstandingLimit() throws Exception {
        org.springframework.test.util.ReflectionTestUtils.setField(store,"maxOutstanding",4);
        var principals=new ArrayList<UserPrincipal>();
        for(int i=0;i<12;i++) {
            var account=new UserAccount();account.setUsername("admission-"+i);account.setPasswordHash("hash");
            principals.add(new UserPrincipal(users.saveAndFlush(account)));
        }
        var executor=Executors.newFixedThreadPool(12);var start=new CountDownLatch(1);
        try {
            var results=new ArrayList<Future<Boolean>>();
            for(var account:principals) results.add(executor.submit(()->{
                start.await();
                try { requests.enqueue(account,"concurrent-limit-0001",image,"balanced");return true; }
                catch(AnalysisAdmissionException busy) { assertThat(busy.code()).isEqualTo("ANALYSIS_BUSY");return false; }
            }));
            start.countDown();int accepted=0;
            for(var result:results) if(result.get(30,TimeUnit.SECONDS)) accepted++;
            assertThat(accepted).isEqualTo(4);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM meal_analysis_requests",Long.class)).isEqualTo(4);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM meal_analysis_outbox",Long.class)).isEqualTo(4);
        } finally {executor.shutdownNow();}
    }
    @Test void expiredPreparationFreesCapacityButCannotEnqueueLateAndExpiredWorkersRemainCounted() {
        org.springframework.test.util.ReflectionTestUtils.setField(store,"maxOutstanding",2);
        var prep=store.claimUpload(principal,"expired-preparation-01","hash","balanced");
        assertThat(jdbc.queryForObject("SELECT EXTRACT(EPOCH FROM lease_until-CURRENT_TIMESTAMP) FROM meal_analysis_requests",Double.class)).isBetween(50.0,61.0);
        jdbc.update("UPDATE meal_analysis_requests SET lease_until=CURRENT_TIMESTAMP-INTERVAL '1 second'");
        requests.enqueue(principal,KEY,image,"balanced");
        requests.enqueue(principal,"active-worker-key-02",image,"balanced");
        assertThat(store.enqueueClaimed(principal,"expired-preparation-01",prep.token(),new ValidatedImage(new byte[]{1},"image/jpeg","jpg",1,1,"image.jpg")).status()).isEqualTo("RETRYABLE");
        var work=store.next();
        jdbc.update("UPDATE meal_analysis_requests SET lease_until=CURRENT_TIMESTAMP-INTERVAL '1 second' WHERE request_key=?",work.key());
        assertThatThrownBy(()->requests.enqueue(principal,"capacity-new-key-0003",image,"balanced")).isInstanceOf(AnalysisAdmissionException.class);
        assertThat(store.complete(work.user(),work.key(),work.token(),analysis.prepareValidated(work.user(),work.image(),work.goal())).status()).isEqualTo("RETRYABLE");
        store.fail(work.user().getId(),work.key(),work.token());
        assertThat(jdbc.queryForObject("SELECT status FROM meal_analysis_requests WHERE request_key=?",String.class,work.key())).isEqualTo("PROCESSING");
        assertThat(meals.count()).isZero();
    }
    @Test void legacyAndAsyncRequestsShareSlotsAndLegacyReleaseDoesNotCreateOutbox() {
        var slot=store.reserveLegacy(principal);
        requests.enqueue(principal,KEY,image,"balanced");
        assertThatThrownBy(()->store.reserveLegacy(principal)).isInstanceOf(AnalysisAdmissionException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM meal_analysis_requests",Long.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM meal_analysis_outbox",Long.class)).isEqualTo(1);
        store.releaseLegacy(principal,slot);store.releaseLegacy(principal,slot);
        assertThat(store.reserveLegacy(principal)).isNotNull();
    }
    @Test void asyncSubmissionPersistsPayloadAndFreshWorkerPublishesOnce() {
        assertThat(requests.enqueue(principal,KEY,image,"current").status()).isEqualTo("PENDING");
        assertThat(requests.enqueue(principal,KEY,image,"current").status()).isEqualTo("PENDING");
        verifyNoInteractions(vision);
        verify(limits,times(1)).consumeMealAnalysis("alice");
        assertThat(jdbc.queryForObject("SELECT octet_length(input_image) FROM meal_analysis_requests",Integer.class)).isPositive();
        var worker=new MealAnalysisWorker(store,analysis,new io.micrometer.core.instrument.simple.SimpleMeterRegistry(),Runnable::run,leases);
        worker.processOne();worker.processOne();
        assertThat(requests.status(principal,KEY).status()).isEqualTo("SUCCEEDED");
        assertThat(meals.count()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM meal_analysis_requests WHERE input_image IS NOT NULL",Long.class)).isZero();
        verify(vision,times(1)).recognize(any(ValidatedImage.class));
    }
    @Test void expiredAsyncWorkerIsRecoveredAndOldAttemptCannotPublish() {
        requests.enqueue(principal,KEY,image,"balanced");
        var old=store.next();assertThat(store.next()).isNull();
        jdbc.update("UPDATE meal_analysis_requests SET lease_until=CURRENT_TIMESTAMP-INTERVAL '1 minute'");
        var recovered=store.next();assertThat(recovered.token()).isNotEqualTo(old.token());
        assertThat(store.complete(old.user(),old.key(),old.token(),analysis.prepareValidated(old.user(),old.image(),old.goal())).status()).isEqualTo("PROCESSING");
        assertThat(meals.count()).isZero();
        store.complete(recovered.user(),recovered.key(),recovered.token(),analysis.prepareValidated(recovered.user(),recovered.image(),recovered.goal()));
        assertThat(meals.count()).isEqualTo(1);
    }
    @Test void workerHeartbeatExtendsItsLeaseButNeverRevivesAnExpiredAttempt() {
        requests.enqueue(principal,KEY,image,"balanced");
        var work=store.next();
        double seconds=jdbc.queryForObject("SELECT EXTRACT(EPOCH FROM lease_until-CURRENT_TIMESTAMP) FROM meal_analysis_requests",Double.class);
        assertThat(seconds).isBetween(80.0,91.0);
        try (var lease=leases.track(work)) {
            jdbc.update("UPDATE meal_analysis_requests SET lease_until=CURRENT_TIMESTAMP+INTERVAL '5 seconds'");
            leases.renew();
            assertThat(jdbc.queryForObject("SELECT lease_until > CURRENT_TIMESTAMP+INTERVAL '80 seconds' FROM meal_analysis_requests",Boolean.class)).isTrue();
            jdbc.update("UPDATE meal_analysis_requests SET lease_until=CURRENT_TIMESTAMP-INTERVAL '1 second'");
            leases.renew();
            assertThat(jdbc.queryForObject("SELECT lease_until < CURRENT_TIMESTAMP FROM meal_analysis_requests",Boolean.class)).isTrue();
            assertThat(store.next().token()).isNotEqualTo(work.token());
        }
    }
    @Test void immutableEvaluationHistorySurvivesNewLabelsAndSourceDeletion() {
        var result=requests.submit(principal,KEY,image,"balanced");
        var foods=List.of(new MealCorrectionRequest.CorrectedFood("米饭","staple"));
        corrections.correct(principal,result.result().getRecordId(),new MealCorrectionRequest(0,foods,"确认分类"));
        var candidate=evaluation.candidates(0).get(0);
        evaluation.review(candidate.id(),new MealCorrectionEvaluationService.Review("APPROVED",true,foods,21),"admin");
        var first=evaluation.run();assertThat(first.passed()).isTrue();
        evaluation.review(candidate.id(),new MealCorrectionEvaluationService.Review("APPROVED",true,foods,90),"admin");
        var second=evaluation.run();assertThat(second.passed()).isFalse();
        assertThat(evaluation.compare(first.id(),second.id()).comparable()).isFalse();
        analysis.deleteMeal(principal,result.result().getRecordId());
        assertThat(evaluation.detail(first.id()).passed()).isTrue();
        assertThat(evaluation.detail(second.id()).cases().get(0).expectedScore()).isEqualTo(90);
    }
    @Test void retentionNeverDeletesActiveRequestsOrSuccessfulIdempotencyKeys() {
        requests.enqueue(principal,KEY,image,"balanced");
        var operations=new MealOperationsService(jdbc,new io.micrometer.core.instrument.simple.SimpleMeterRegistry(),new BackgroundTaskDispatcher(Runnable::run));
        org.springframework.test.util.ReflectionTestUtils.setField(operations,"retentionEnabled",true);
        org.springframework.test.util.ReflectionTestUtils.setField(operations,"imageDays",7);
        org.springframework.test.util.ReflectionTestUtils.setField(operations,"requestDays",90);
        jdbc.update("UPDATE meal_analysis_requests SET updated_at=CURRENT_TIMESTAMP-INTERVAL '200 days'");
        operations.retain();assertThat(requests.status(principal,KEY).status()).isEqualTo("PENDING");
        var worker=new MealAnalysisWorker(store,analysis,new io.micrometer.core.instrument.simple.SimpleMeterRegistry(),Runnable::run,leases);worker.processOne();
        jdbc.update("UPDATE meal_analysis_requests SET updated_at=CURRENT_TIMESTAMP-INTERVAL '200 days'");
        operations.retain();assertThat(requests.status(principal,KEY).status()).isEqualTo("SUCCEEDED");
        operations.sample();
        store.fail(principal.getId(),KEY,"not-current-token");
        assertThat(requests.status(principal,KEY).status()).isEqualTo("SUCCEEDED");
    }
    @Test void concurrentDuplicatesAndLostResponseReuseOneRecognitionAndRecord() throws Exception {
        var entered=new CountDownLatch(1); var release=new CountDownLatch(1);
        when(vision.recognize(any(ValidatedImage.class))).thenAnswer(call -> { entered.countDown(); if (!release.await(10,TimeUnit.SECONDS)) throw new IllegalStateException("timeout"); return recognition(); });
        var executor=Executors.newSingleThreadExecutor();
        try {
            var first=executor.submit(() -> requests.submit(principal,KEY,image,"current"));
            assertThat(entered.await(10,TimeUnit.SECONDS)).isTrue();
            assertThat(requests.submit(principal,KEY,image,"current").status()).isEqualTo("PROCESSING");
            release.countDown(); var result=first.get(10,TimeUnit.SECONDS);
            assertThat(requests.submit(principal,KEY,image,"current").result().getRecordId()).isEqualTo(result.result().getRecordId());
            assertThat(requests.status(principal,KEY).result().getRecordId()).isEqualTo(result.result().getRecordId());
            assertThat(meals.count()).isEqualTo(1); assertThat(profiles.getOrRefresh(user).getTotalMeals()).isEqualTo(1);
            verify(vision,times(1)).recognize(any(ValidatedImage.class)); verify(limits,times(1)).consumeMealAnalysis("alice");
        } finally { release.countDown(); executor.shutdownNow(); }
    }
    @Test void expiredAttemptCannotPersistButNewAttemptCanRecover() {
        var old=store.claim(principal,KEY,"same-hash","balanced");
        jdbc.update("UPDATE meal_analysis_requests SET lease_until=CURRENT_TIMESTAMP - INTERVAL '1 hour' WHERE user_id=?",user.getId());
        assertThat(requests.status(principal,KEY).status()).isEqualTo("RETRYABLE");
        var next=store.claim(principal,KEY,"same-hash","balanced");
        var prepared=analysis.prepare(principal,image,"balanced");
        assertThat(store.complete(principal,KEY,old.token(),prepared).status()).isEqualTo("PROCESSING");
        assertThat(meals.count()).isZero();
        assertThat(store.complete(principal,KEY,next.token(),prepared).status()).isEqualTo("SUCCEEDED");
        assertThat(meals.count()).isEqualTo(1);
    }
    @Test void changedPayloadOtherAccountAndDeletedResultCannotReplay() {
        var result=requests.submit(principal,KEY,image,"balanced");
        assertThatThrownBy(() -> requests.submit(principal,KEY,image,"light")).isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
                .hasMessageContaining("409");
        var other=new UserAccount(); other.setUsername("bob"); other.setPasswordHash("hash"); other=users.saveAndFlush(other);
        var otherPrincipal=new UserPrincipal(other);
        assertThatThrownBy(() -> requests.status(otherPrincipal,KEY)).hasMessageContaining("404");
        assertThat(requests.submit(otherPrincipal,KEY,image,"balanced").status()).isEqualTo("SUCCEEDED");
        analysis.deleteMeal(principal,result.result().getRecordId());
        assertThatThrownBy(() -> requests.status(principal,KEY)).hasMessageContaining("410");
    }
    @Test void correctionRebuildsProfileFencesReportsAndBecomesReviewedRegressionCase() {
        var result=requests.submit(principal,KEY,image,"balanced").result();
        var oldReport=reports.request(principal,7); var claimed=jobs.claimBatch().get(0);
        var request=new MealCorrectionRequest(0,List.of(new MealCorrectionRequest.CorrectedFood("米饭","staple"),
                new MealCorrectionRequest.CorrectedFood("清蒸鱼","protein"), new MealCorrectionRequest.CorrectedFood("青菜","vegetable")),"漏识别两项");
        var corrected=corrections.correct(principal,result.getRecordId(),request);
        assertThat(corrected.getEvaluation().getScore()).isEqualTo(90);
        assertThat(corrected.getRevision()).isEqualTo(1);
        assertThat(profiles.getOrRefresh(user).getTotalMeals()).isEqualTo(1);
        assertThat(profiles.getOrRefresh(user).getAverageScore()).isEqualTo(90);
        assertThat(meals.findById(result.getRecordId()).orElseThrow().getOriginalFoodsJson()).contains("米饭").doesNotContain("青菜");
        assertThat(requests.status(principal,KEY).result().getRevision()).isEqualTo(1);
        assertThatThrownBy(() -> corrections.correct(principal,result.getRecordId(),request)).hasMessageContaining("409");
        assertThat(reports.status(principal,oldReport.id()).status()).isEqualTo("STALE");
        assertThat(completion.complete(claimed.getId(), claimed.getAttempts(), claimed.getExecutionToken(),new WeeklyReportResponse())).isFalse();
        assertThat(reports.request(principal,7).id()).isNotEqualTo(oldReport.id());
        assertThat(evaluation.evaluate().caseCount()).isZero(); assertThat(evaluation.evaluate().passed()).isFalse();
        var candidate=evaluation.candidates(0).get(0);
        evaluation.review(candidate.id(),new MealCorrectionEvaluationService.Review("APPROVED",true,request.foods(),90),"admin");
        assertThat(evaluation.evaluate().passed()).isTrue();
        assertThat(evaluation.evaluate().caseCount()).isEqualTo(1);
        String fingerprint=evaluation.evaluate().datasetFingerprint();
        evaluation.review(candidate.id(),new MealCorrectionEvaluationService.Review("APPROVED",true,request.foods(),80),"admin");
        assertThat(evaluation.evaluate().passed()).isFalse();
        assertThat(evaluation.evaluate().datasetFingerprint()).isNotEqualTo(fingerprint);
    }
    @Test void failedRecognitionCanRetryWithoutPartialRecords() {
        when(vision.recognize(any(ValidatedImage.class))).thenThrow(new IllegalStateException("provider unavailable"));
        assertThatThrownBy(() -> requests.submit(principal,KEY,image,"balanced")).hasMessage("provider unavailable");
        assertThat(requests.status(principal,KEY).status()).isEqualTo("FAILED"); assertThat(meals.count()).isZero();
        when(vision.recognize(any(ValidatedImage.class))).thenReturn(recognition());
        assertThat(requests.submit(principal,KEY,image,"balanced").status()).isEqualTo("SUCCEEDED");
        assertThat(meals.count()).isEqualTo(1);
    }
    @Test void resultPublicationFailureRollsBackMealImageAndProfileTogether() throws Exception {
        jdbc.execute("ALTER TABLE meal_analysis_requests ADD CONSTRAINT test_no_success CHECK(status <> 'SUCCEEDED')");
        try {
            assertThatThrownBy(() -> requests.submit(principal,KEY,image,"balanced"))
                    .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
            assertThat(meals.count()).isZero();
            assertThat(requests.status(principal,KEY).status()).isEqualTo("FAILED");
            assertThat(jdbc.queryForObject("SELECT count(*) FROM user_diet_profiles",Long.class)).isZero();
            try (var files=java.nio.file.Files.walk(uploadDir)) {
                assertThat(files.filter(java.nio.file.Files::isRegularFile).count()).isZero();
            }
        } finally { jdbc.execute("ALTER TABLE meal_analysis_requests DROP CONSTRAINT test_no_success"); }
    }
}
