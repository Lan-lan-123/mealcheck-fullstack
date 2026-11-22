package com.example.mealcheck.service;

import com.example.mealcheck.config.AppProperties;
import com.example.mealcheck.config.MealAnalysisRabbitConfiguration;
import com.example.mealcheck.dto.*;
import com.example.mealcheck.entity.UserAccount;
import com.example.mealcheck.repository.*;
import com.example.mealcheck.security.UserPrincipal;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.amqp.autoconfigure.RabbitAutoConfiguration;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.*;
import org.springframework.transaction.annotation.*;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import static com.example.mealcheck.config.MealAnalysisRabbitConfiguration.*;
import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@DataJpaTest(properties = {"spring.jpa.hibernate.ddl-auto=validate", "spring.flyway.baseline-on-migrate=false",
        "spring.rabbitmq.listener.simple.auto-startup=false", "mealcheck.analysis.outbox-initial-delay-ms=3600000"})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ImportAutoConfiguration(RabbitAutoConfiguration.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import({TaskFailureRecorder.class, MealAnalysisLeaseService.class, MealAnalysisRabbitConfiguration.class, MealAnalysisRabbitConsumer.class, MealAnalysisOutboxStore.class,
        MealAnalysisOutboxPublisher.class, MealAnalysisService.class, BehaviorTrackingService.class, MealAnalysisRequestStore.class,
        MealAnalysisRequestService.class, MealScoringService.class, AdviceGenerationService.class,
        MealRecordPersistenceService.class, MealReportInvalidationService.class, UserDietProfileService.class,
        ImageStorageService.class, ImageUploadValidationService.class,
        WeeklyReportDatabaseIntegrationTest.Config.class, MealAnalysisRabbitDatabaseIntegrationTest.Config.class})
@Testcontainers
class MealAnalysisRabbitDatabaseIntegrationTest {
    @Container static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(org.testcontainers.utility.DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));
    @Container static final GenericContainer<?> RABBIT = new GenericContainer<>("rabbitmq:4.2-management-alpine")
            .withEnv("RABBITMQ_DEFAULT_USER", "test").withEnv("RABBITMQ_DEFAULT_PASS", "test-password")
            .withExposedPorts(5672).waitingFor(Wait.forLogMessage(".*Server startup complete.*\\n", 1));

    @DynamicPropertySource static void connections(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.rabbitmq.host", RABBIT::getHost);
        registry.add("spring.rabbitmq.port", () -> RABBIT.getMappedPort(5672));
        registry.add("spring.rabbitmq.username", () -> "test");
        registry.add("spring.rabbitmq.password", () -> "test-password");
    }
    @TestConfiguration static class Config {
        @Bean MeterRegistry meterRegistry() { return new SimpleMeterRegistry(); }
    }
    @Autowired MealAnalysisRequestService requests;
    @Autowired MealAnalysisRequestStore store;
    @Autowired MealAnalysisOutboxStore outbox;
    @Autowired MealAnalysisOutboxPublisher publisher;
    @Autowired MealAnalysisService analysis;
    @Autowired UserAccountRepository users;
    @Autowired MealRecordRepository meals;
    @Autowired AppProperties properties;
    @Autowired JdbcTemplate jdbc;
    @Autowired RabbitTemplate rabbit;
    @Autowired AmqpAdmin admin;
    @Autowired RabbitListenerEndpointRegistry listeners;
    @Autowired PlatformTransactionManager transactionManager;
    @MockitoBean RateLimitService limits;
    @MockitoBean UserGoalService goals;
    @MockitoBean NonFoodUploadGuardService guard;
    @MockitoBean KnowledgeIndexService knowledge;
    @MockitoBean VisionFoodRecognitionService vision;
    @MockitoBean AiChatClient ai;
    @MockitoBean BackgroundTaskDispatcher background;
    @TempDir Path uploadDir;
    UserPrincipal principal;
    MockMultipartFile image;
    static final String KEY = "rabbit-analysis-key-0001";

    @BeforeEach void setup() throws Exception {
        listeners.stop();
        // Opening a connection declares the durable topology before publishing.
        admin.getQueueProperties(QUEUE);
        admin.purgeQueue(QUEUE, false);
        admin.purgeQueue(DEAD_QUEUE, false);
        jdbc.execute("TRUNCATE users CASCADE");
        properties.setUploadDir(uploadDir.toString());
        var user = new UserAccount(); user.setUsername("rabbit-user"); user.setPasswordHash("hash");
        principal = new UserPrincipal(users.saveAndFlush(user));
        var bytes = new java.io.ByteArrayOutputStream();
        javax.imageio.ImageIO.write(new java.awt.image.BufferedImage(2,2,java.awt.image.BufferedImage.TYPE_INT_RGB), "jpg", bytes);
        image = new MockMultipartFile("image", "meal.jpg", "image/jpeg", bytes.toByteArray());
        when(limits.consumeMealAnalysis(anyString())).thenReturn(new RateLimitService.RateLimitDecision(
                RateLimitService.RateLimitStatus.ALLOWED, 3, 2, Duration.ZERO));
        when(limits.consumeUploadIngress(anyString())).thenReturn(new RateLimitService.RateLimitDecision(
                RateLimitService.RateLimitStatus.ALLOWED,20,19,Duration.ZERO));
        when(goals.effectiveGoal(any(), anyString())).thenReturn("balanced");
        when(knowledge.search(anyString(), anyInt())).thenReturn(List.of());
        when(vision.recognize(any(ValidatedImage.class))).thenAnswer(call -> recognition());
    }
    @AfterEach void stop() { listeners.stop(); }

    private void startListeners() {
        var containers = listeners.getListenerContainers();
        assertThat(containers).isNotEmpty();
        containers.forEach(MessageListenerContainer::start);
    }

    @Test void twoConsumersExecuteConcurrentlyAndDuplicateDeliveryPublishesOnlyOneMeal() throws Exception {
        var entered = new CountDownLatch(2);
        var release = new CountDownLatch(1);
        when(vision.recognize(any(ValidatedImage.class))).thenAnswer(call -> {
            entered.countDown();
            if (!release.await(15, TimeUnit.SECONDS)) throw new IllegalStateException("test timeout");
            return recognition();
        });
        requests.enqueue(principal, KEY, image, "current");
        UUID id = eventId();
        requests.enqueue(principal, KEY, image, "current");
        requests.enqueue(principal, KEY + "-2", image, "current");
        publisher.publish();
        rabbit.convertAndSend(EXCHANGE, ROUTING_KEY, id.toString());
        startListeners();
        try {
            assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM meal_analysis_requests WHERE status='PROCESSING'", Long.class)).isEqualTo(2);
        } finally { release.countDown(); }
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> assertThat(meals.count()).isEqualTo(2));
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(outboxCount()).isZero());
        listeners.stop(); startListeners();
        rabbit.convertAndSend(EXCHANGE, ROUTING_KEY, id.toString());
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertThat(admin.getQueueInfo(QUEUE).getMessageCount()).isZero());
        verify(vision, times(2)).recognize(any(ValidatedImage.class));
        verify(limits, times(2)).consumeMealAnalysis(anyString());
    }

    @Test void unroutablePublishIsNotConfirmedAndExplicitReplayRepairsEmptyBrokerRestore() {
        requests.enqueue(principal, KEY, image, "current");
        var binding = new Binding(QUEUE, Binding.DestinationType.QUEUE, EXCHANGE, ROUTING_KEY, Map.of());
        admin.removeBinding(binding);
        try {
            publisher.publish();
            assertThat(jdbc.queryForObject("SELECT published_at FROM meal_analysis_outbox", java.sql.Timestamp.class)).isNull();
        } finally { admin.declareBinding(binding); }
        due(); publisher.publish();
        assertThat(jdbc.queryForObject("SELECT published_at FROM meal_analysis_outbox", java.sql.Timestamp.class)).isNotNull();
        var lost = rabbit.receive(QUEUE, 5000);
        assertThat(lost).isNotNull();
        assertThat(new String(lost.getBody(), StandardCharsets.UTF_8)).isEqualTo(eventId().toString());
        assertThat(lost.getBody()).hasSize(36);
        // Normal backlog must not produce repeated copies after publisher confirmation.
        due();
        assertThat(outbox.claim()).isNull();
        // Explicit recovery after restoring the DB to an empty broker replays unfinished work.
        jdbc.update("UPDATE meal_analysis_outbox SET published_at=NULL");
        publisher.publish(); startListeners();
        awaitSuccess();
        verify(vision, times(1)).recognize(any(ValidatedImage.class));
    }

    @Test void expiredWorkerIsRedispatchedAndOldAttemptCannotCommit() {
        requests.enqueue(principal, KEY, image, "current");
        var old = store.takeMessage(eventId());
        assertThat(old).isNotNull();
        assertThat(store.takeMessage(eventId())).isNull();
        var oldPrepared = analysis.prepareValidated(old.user(), store.loadImage(old), old.goal());
        jdbc.update("UPDATE meal_analysis_requests SET lease_until=CURRENT_TIMESTAMP-INTERVAL '1 minute'");
        publisher.publish(); startListeners(); awaitSuccess();
        assertThatThrownBy(() -> store.loadImage(old)).isInstanceOf(MealAnalysisRequestStore.WorkNoLongerOwnedException.class);
        store.complete(old.user(), old.key(), old.token(), oldPrepared);
        assertThat(meals.count()).isEqualTo(1);
        assertThat(outboxCount()).isZero();
    }

    @Test void terminalModelFailureDeadLettersAndManualRetryRotatesDispatchIdentity() {
        when(vision.recognize(any(ValidatedImage.class))).thenThrow(new IllegalStateException("model unavailable"));
        requests.enqueue(principal, KEY, image, "current");
        UUID old = eventId();
        publisher.publish(); startListeners();
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> assertThat(requests.status(principal, KEY).status()).isEqualTo("FAILED"));
        var dead = rabbit.receive(DEAD_QUEUE, 10000);
        assertThat(dead).isNotNull();
        assertThat(new String(dead.getBody(), StandardCharsets.UTF_8)).isEqualTo(old.toString());
        assertThat(eventId()).isEqualTo(old); // DLQ still correlates to the failed request.
        due();
        assertThat(outbox.claim()).isNull(); // Terminal failure never automatically rebills the model.
        listeners.stop();
        when(vision.recognize(any(ValidatedImage.class))).thenAnswer(call -> recognition());
        requests.enqueue(principal, KEY, image, "current");
        assertThat(eventId()).isNotEqualTo(old);
        assertThat(store.takeMessage(old)).isNull();
        publisher.publish(); startListeners(); awaitSuccess();
        assertThat(meals.count()).isEqualTo(1);
        verify(vision, times(2)).recognize(any(ValidatedImage.class));
    }

    @Test void submissionAndOutboxRollbackTogetherAndDeletionRemovesDispatch() {
        new TransactionTemplate(transactionManager).executeWithoutResult(tx -> {
            requests.enqueue(principal, KEY, image, "current");
            assertThat(outboxCount()).isEqualTo(1);
            tx.setRollbackOnly();
        });
        assertThat(outboxCount()).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM meal_analysis_requests", Long.class)).isZero();
        requests.enqueue(principal, KEY, image, "current");
        UUID id = eventId();
        users.deleteById(principal.getId());
        assertThat(outboxCount()).isZero();
        assertThat(store.takeMessage(id)).isNull();
    }

    @Test void publisherLeasesAndAttemptTokensFenceStaleConfirmations() {
        requests.enqueue(principal, KEY, image, "current");
        var first = outbox.claim();
        assertThat(outbox.claim()).isNull();
        due();
        assertThat(outbox.confirmed(first)).isFalse(); // An expired claim cannot write, even before takeover.
        var recovered = outbox.claim();
        assertThat(recovered.eventId()).isEqualTo(first.eventId());
        assertThat(recovered.token()).isNotEqualTo(first.token());
        assertThat(outbox.confirmed(first)).isFalse();
        assertThat(jdbc.queryForObject("SELECT published_at FROM meal_analysis_outbox", java.sql.Timestamp.class)).isNull();
        assertThat(outbox.confirmed(recovered)).isTrue();
        assertThat(jdbc.queryForObject("SELECT published_at FROM meal_analysis_outbox", java.sql.Timestamp.class)).isNotNull();
    }

    @Test void multipleWindowsPublishDistinctPersistentMessagesAndDoNotReplayConfirmedBacklog() {
        enqueueMultiple(12);
        publisher.publish();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM meal_analysis_outbox WHERE published_at IS NOT NULL AND publish_attempts=1", Long.class)).isEqualTo(12);
        var received = new HashSet<UUID>();
        for (int i = 0; i < 12; i++) {
            var message = rabbit.receive(QUEUE, 5000);
            assertThat(message).isNotNull();
            UUID event = UUID.fromString(new String(message.getBody(), StandardCharsets.UTF_8));
            assertThat(received.add(event)).isTrue();
            assertThat(message.getMessageProperties().getMessageId()).isEqualTo(event.toString());
            assertThat(message.getMessageProperties().getReceivedDeliveryMode()).isEqualTo(MessageDeliveryMode.PERSISTENT);
        }
        due(); publisher.publish();
        assertThat(outbox.claimBatch(8)).isEmpty();
        assertThat(rabbit.receive(QUEUE)).isNull();
    }

    @Test void concurrentBatchClaimsSkipLockedRowsAndNeverShareDispatchOwnership() throws Exception {
        enqueueMultiple(6);
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        try {
            var first = executor.submit(() -> new TransactionTemplate(transactionManager).execute(tx -> {
                var batch = outbox.claimBatch(3);
                entered.countDown();
                try { if (!release.await(5, TimeUnit.SECONDS)) throw new AssertionError("timeout"); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
                return batch;
            }));
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            var second = executor.submit(() -> outbox.claimBatch(3)).get(3, TimeUnit.SECONDS);
            assertThat(second).hasSize(3);
            release.countDown();
            var original = first.get(3, TimeUnit.SECONDS);
            assertThat(original).hasSize(3);
            assertThat(original.stream().map(MealAnalysisOutboxStore.Dispatch::eventId).toList())
                    .doesNotContainAnyElementsOf(second.stream().map(MealAnalysisOutboxStore.Dispatch::eventId).toList());
            assertThat(outbox.claimBatch(3)).isEmpty();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM meal_analysis_outbox WHERE publish_attempts=1 AND publish_token IS NOT NULL", Long.class)).isEqualTo(6);
        } finally { release.countDown(); executor.shutdownNow(); }
    }

    @Test void batchClaimRollbackReleasesEveryRowAndRestoresAttemptCounters() {
        enqueueMultiple(4);
        new TransactionTemplate(transactionManager).executeWithoutResult(tx -> {
            assertThat(outbox.claimBatch(3)).hasSize(3);
            tx.setRollbackOnly();
        });
        assertThat(jdbc.queryForObject("SELECT count(*) FROM meal_analysis_outbox WHERE publish_attempts=0 AND publish_token IS NULL", Long.class)).isEqualTo(4);
        assertThat(outbox.claimBatch(3)).hasSize(3);
        assertThat(outbox.claimBatch(3)).hasSize(1);
        assertThat(outbox.claimBatch(3)).isEmpty();
    }

    private void enqueueMultiple(int count) {
        for (int i = 0; i < count; i++) {
            var user = new UserAccount(); user.setUsername("batch-user-" + i); user.setPasswordHash("hash");
            requests.enqueue(new UserPrincipal(users.saveAndFlush(user)), KEY + "-batch-" + i, image, "current");
        }
    }

    @Test void twoHundredBackloggedTasksSurviveWorkerPauseAndLeaseRecoveryWithoutDuplicateRecords() throws Exception {
        org.springframework.test.util.ReflectionTestUtils.setField(store,"maxOutstanding",200);
        requests.enqueue(principal,KEY,image,"current");
        requests.enqueue(principal,KEY+"-second",image,"current");
        assertThatThrownBy(() -> requests.enqueue(principal,KEY+"-third",image,"current"))
                .isInstanceOf(AnalysisAdmissionException.class)
                .extracting(error -> ((AnalysisAdmissionException)error).code()).isEqualTo("ANALYSIS_USER_BUSY");
        enqueueMultiple(198);
        var overflow = new UserAccount(); overflow.setUsername("queue-overflow"); overflow.setPasswordHash("hash");
        var overflowPrincipal = new UserPrincipal(users.saveAndFlush(overflow));
        assertThatThrownBy(() -> requests.enqueue(overflowPrincipal,KEY+"-overflow",image,"current"))
                .isInstanceOf(AnalysisAdmissionException.class)
                .extracting(error -> ((AnalysisAdmissionException)error).code()).isEqualTo("ANALYSIS_BUSY");
        assertThat(requests.enqueue(principal,KEY,image,"current").stage()).isEqualTo("QUEUED");
        assertThat(outboxCount()).isEqualTo(200);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM meal_analysis_requests WHERE input_image IS NOT NULL",Long.class)).isEqualTo(200);
        // Simulate minutes of backlog: the execution deadline must start on consumption.
        jdbc.update("UPDATE meal_analysis_outbox SET created_at=CURRENT_TIMESTAMP-INTERVAL '5 minutes'");
        var abandoned = store.takeMessage(eventId());
        jdbc.update("UPDATE meal_analysis_requests SET lease_until=CURRENT_TIMESTAMP-INTERVAL '1 second' WHERE request_key=?",KEY);
        assertThat(requests.status(principal,KEY).message()).contains("自动恢复");
        assertThatThrownBy(() -> store.loadImage(abandoned)).isInstanceOf(MealAnalysisRequestStore.WorkNoLongerOwnedException.class);
        for (int i=0;i<10;i++) publisher.publish();
        assertThat(admin.getQueueInfo(QUEUE).getMessageCount()).isEqualTo(200);
        due(); publisher.publish(); // Confirmed backlog must not be republished.
        startListeners();
        await().atMost(Duration.ofSeconds(120)).untilAsserted(() -> assertThat(meals.count()).isEqualTo(200));
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            assertThat(outboxCount()).isZero();
            assertThat(admin.getQueueInfo(QUEUE).getMessageCount()).isZero();
        });
        assertThat(jdbc.queryForObject("SELECT count(*) FROM meal_analysis_requests WHERE status='SUCCEEDED' AND input_image IS NULL",Long.class)).isEqualTo(200);
        assertThat(jdbc.queryForObject("SELECT count(DISTINCT record_id) FROM meal_analysis_requests",Long.class)).isEqualTo(200);
        verify(vision,times(200)).recognize(any(ValidatedImage.class));
        assertThat(requests.enqueue(overflowPrincipal,KEY+"-after-drain",image,"current").status()).isEqualTo("PENDING");
    }

    private UUID eventId() { return jdbc.queryForObject("SELECT event_id FROM meal_analysis_outbox WHERE request_key=?", UUID.class, KEY); }
    private long outboxCount() { return jdbc.queryForObject("SELECT count(*) FROM meal_analysis_outbox", Long.class); }
    private void due() { jdbc.update("UPDATE meal_analysis_outbox SET next_publish_at=CURRENT_TIMESTAMP-INTERVAL '1 second'"); }
    private void awaitSuccess() {
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> assertThat(requests.status(principal, KEY).status()).isEqualTo("SUCCEEDED"));
    }
    private RecognitionResult recognition() {
        var result = new RecognitionResult();
        result.setFoods(List.of(new FoodItem("米饭", "staple", .9, null)));
        return result;
    }
}
