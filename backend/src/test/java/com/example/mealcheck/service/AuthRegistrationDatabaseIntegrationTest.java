package com.example.mealcheck.service;

import com.example.mealcheck.controller.GlobalExceptionHandler;
import com.example.mealcheck.dto.AuthDtos;
import com.example.mealcheck.repository.UserAccountRepository;
import com.example.mealcheck.security.JwtService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.ArgumentMatchers.*;

@DataJpaTest(properties = {"spring.jpa.hibernate.ddl-auto=validate", "spring.flyway.baseline-on-migrate=false"})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Testcontainers
class AuthRegistrationDatabaseIntegrationTest {
    @Container static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            org.testcontainers.utility.DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }
    @Autowired UserAccountRepository users;
    @Autowired PlatformTransactionManager transactions;

    @Test void simultaneousRegistrationsCommitOneAccountAndReportOneConflict() throws Exception {
        var barrier = new CyclicBarrier(2);
        var repository = mock(UserAccountRepository.class, delegatesTo(users));
        doAnswer(call -> {
            boolean existing = users.existsByUsername("same-user");
            assertThat(existing).isFalse();
            barrier.await(10, TimeUnit.SECONDS);
            return existing;
        }).when(repository).existsByUsername("same-user");
        var captcha = mock(CaptchaService.class); when(captcha.verify(anyString(), anyString())).thenReturn(true);
        var passwords = mock(PasswordEncoder.class); when(passwords.encode(anyString())).thenReturn("encoded-password");
        var jwt = mock(JwtService.class); when(jwt.generateToken(any())).thenReturn("test-token");
        var service = new AuthService(repository, passwords, mock(AuthenticationManager.class), jwt, captcha);
        var executor = Executors.newFixedThreadPool(2);
        try {
            Callable<Integer> register = () -> {
                var request = new AuthDtos.RegisterRequest(); request.setUsername("same-user");
                request.setPassword("long-password"); request.setCaptchaId("id"); request.setCaptchaAnswer("4");
                try {
                    new TransactionTemplate(transactions).execute(ignored -> service.register(request));
                    return 200;
                } catch (UsernameConflictException conflict) {
                    return new GlobalExceptionHandler().usernameConflict(conflict).getStatusCode().value();
                }
            };
            var first = executor.submit(register); var second = executor.submit(register);
            assertThat(java.util.List.of(first.get(20, TimeUnit.SECONDS), second.get(20, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(200, 409);
            assertThat(users.findByUsername("same-user")).isPresent();
            assertThat(users.count()).isEqualTo(1);
            verify(jwt, times(1)).generateToken(any());
        } finally { executor.shutdownNow(); }
    }
}
