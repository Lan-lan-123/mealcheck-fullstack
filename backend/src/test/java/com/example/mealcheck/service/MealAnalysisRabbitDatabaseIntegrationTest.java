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
import org.springframework.boot.autoconfigure.amqp.RabbitAutoConfiguration;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
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
    @MockBean RateLimitService limits;
    @MockBean UserGoalService goals;
    @MockBean NonFoodUploadGuardService guard;
    @MockBean KnowledgeIndexService knowledge;
    @MockBean VisionFoodRecognitionService vision;
    @MockBean AiChatClient ai;
    @MockBean BackgroundTaskDispatcher background;
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
        listeners.start();
        try {
            assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM meal_analysis_requests WHERE status='PROCESSING'", Long.class)).isEqualTo(2);
        } finally { release.countDown(); }
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> assertThat(meals.count()).isEqualTo(2));
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(outboxCount()).isZero());
        listeners.stop(); listeners.start();
        rabbit.convertAndSend(EXCHANGE, ROUTING_KEY, id.toString());
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertThat(admin.getQueueProperties(QUEUE).get("QUEUE_MESSAGE_COUNT")).isEqualTo(0));
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
        publisher.publish(); listeners.start();
        awaitSuccess();
        verify(vision, times(1)).recognize(any(ValidatedImage.class));
    }

    @Test void expiredWorkerIsRedispatchedAndOldAttemptCannotCommit() {
        requests.enqueue(principal, KEY, image, "current");
        var old = store.takeMessage(eventId());
        assertThat(old).isNotNull();
        assertThat(store.takeMessage(eventId())).isNull();
        jdbc.update("UPDATE meal_analysis_requests SET lease_until=CURRENT_TIMESTAMP-INTERVAL '1 minute'");
        publisher.publish(); listeners.start(); awaitSuccess();
        store.complete(old.user(), old.key(), old.token(), analysis.prepareValidated(old.user(), old.image(), old.goal()));
        assertThat(meals.count()).isEqualTo(1);
        assertThat(outboxCount()).isZero();
    }

    @Test void terminalModelFailureDeadLettersAndManualRetryRotatesDispatchIdentity() {
        when(vision.recognize(any(ValidatedImage.class))).thenThrow(new IllegalStateException("model unavailable"));
        requests.enqueue(principal, KEY, image, "current");
        UUID old = eventId();
        publisher.publish(); listeners.start();
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
        publisher.publish(); listeners.start(); awaitSuccess();
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
        var recovered = outbox.claim();
        assertThat(recovered.eventId()).isEqualTo(first.eventId());
        assertThat(recovered.token()).isNotEqualTo(first.token());
        outbox.confirmed(first);
        assertThat(jdbc.queryForObject("SELECT published_at FROM meal_analysis_outbox", java.sql.Timestamp.class)).isNull();
        outbox.confirmed(recovered);
        assertThat(jdbc.queryForObject("SELECT published_at FROM meal_analysis_outbox", java.sql.Timestamp.class)).isNotNull();
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
