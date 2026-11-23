package com.example.mealcheck.service;

import com.example.mealcheck.repository.AdminAuditLogRepository;
import com.example.mealcheck.repository.UserAccountRepository;
import com.example.mealcheck.security.UserPrincipal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@DataJpaTest(properties = {"spring.jpa.hibernate.ddl-auto=validate", "spring.flyway.baseline-on-migrate=false"})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Testcontainers
class AdminAccountStatusDatabaseIntegrationTest {
    @Container static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }
    @Autowired UserAccountRepository users;
    @Autowired AdminAuditLogRepository audits;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactions;
    long targetId;
    UserPrincipal admin;
    AdminService service;

    @BeforeEach void setup() {
        jdbc.execute("TRUNCATE users CASCADE");
        long adminId = jdbc.queryForObject("INSERT INTO users(username,password_hash,role) VALUES ('admin','hash','ADMIN') RETURNING id", Long.class);
        targetId = jdbc.queryForObject("INSERT INTO users(username,password_hash) VALUES ('alice','hash') RETURNING id", Long.class);
        admin = new UserPrincipal(users.findById(adminId).orElseThrow());
        service = AdminAccountStatusTest.service(users, new AdminAuditService(audits, users));
    }

    @Test void migrationDefaultsAndStatusAndAuditSurviveTransactionBoundaries() {
        assertThat(users.findById(targetId).orElseThrow().isEnabled()).isTrue();
        assertThat(users.findById(targetId).orElseThrow().getTokenVersion()).isZero();
        change(false);
        var banned = users.findById(targetId).orElseThrow();
        assertThat(banned.isEnabled()).isFalse();
        assertThat(banned.getTokenVersion()).isEqualTo(1);
        change(false);
        change(true);
        var active = users.findById(targetId).orElseThrow();
        assertThat(active.isEnabled()).isTrue();
        assertThat(active.getTokenVersion()).isEqualTo(2);
        assertThat(jdbc.queryForList("SELECT action FROM admin_audit_logs ORDER BY id", String.class))
                .containsExactly("BAN_USER", "UNBAN_USER");
    }

    @Test void repeatedConcurrentBansAreSerializedAndInvalidateOnlyOnce() throws Exception {
        var executor = Executors.newFixedThreadPool(2);
        var start = new CountDownLatch(1);
        Callable<Void> ban = () -> { start.await(); change(false); return null; };
        try {
            var first = executor.submit(ban);
            var second = executor.submit(ban);
            start.countDown();
            first.get(20, TimeUnit.SECONDS); second.get(20, TimeUnit.SECONDS);
        } finally { executor.shutdownNow(); }
        var banned = users.findById(targetId).orElseThrow();
        assertThat(banned.isEnabled()).isFalse();
        assertThat(banned.getTokenVersion()).isEqualTo(1);
        assertThat(audits.count()).isEqualTo(1);
    }

    @Test void auditFailureRollsBackBothStatusAndTokenVersion() {
        var failingAudit = mock(AdminAuditService.class);
        doThrow(new IllegalStateException("audit unavailable")).when(failingAudit).log(any(), anyString(), anyString(), anyLong(), anyString());
        var failingService = AdminAccountStatusTest.service(users, failingAudit);
        assertThatThrownBy(() -> new TransactionTemplate(transactions)
                .executeWithoutResult(ignored -> failingService.setUserEnabled(targetId, false, admin)))
                .isInstanceOf(IllegalStateException.class);
        var unchanged = users.findById(targetId).orElseThrow();
        assertThat(unchanged.isEnabled()).isTrue();
        assertThat(unchanged.getTokenVersion()).isZero();
        assertThat(audits.count()).isZero();
    }

    private void change(boolean enabled) {
        new TransactionTemplate(transactions).executeWithoutResult(ignored -> service.setUserEnabled(targetId, enabled, admin));
    }
}
