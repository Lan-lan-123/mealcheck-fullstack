package com.example.mealcheck.service;

import com.example.mealcheck.config.AppProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.utility.DockerImageName;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@DataJpaTest(properties={"spring.jpa.hibernate.ddl-auto=validate", "spring.flyway.baseline-on-migrate=false",
        "mealcheck.knowledge.auto-index=false", "mealcheck.ai.api-key=environment-key",
        "mealcheck.ai.base-url=https://provider.test/v1", "mealcheck.ai.model=environment-model",
        "mealcheck.ai-settings.encryption-key=stable-test-encryption-secret"})
@AutoConfigureTestDatabase(replace=AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation=Propagation.NOT_SUPPORTED)
@EnableConfigurationProperties(AppProperties.class)
@Import(AiSettingsService.class)
@Testcontainers
class AiSettingsDatabaseIntegrationTest {
    @Container static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));
    @DynamicPropertySource static void database(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        r.add("spring.datasource.username", POSTGRES::getUsername);
        r.add("spring.datasource.password", POSTGRES::getPassword);
    }
    @Autowired AiSettingsService settings;
    @Autowired AppProperties properties;
    @Autowired JdbcTemplate jdbc;
    @MockBean AdminAuditService audit;

    @Test void encryptedSettingsSurviveReloadRejectConflictsAndDoNotPublishRolledBackWrites() throws Exception {
        var initial=settings.view();
        assertThat(initial.source()).isEqualTo("ENVIRONMENT");
        var saved=settings.save(new AiSettingsService.Update("qwen3.7-flash", "https://provider.test/v1/chat/completions/",
                "new-private-key",initial.version()),null);
        assertThat(saved.baseUrl()).isEqualTo("https://provider.test/v1");
        assertThat(saved.source()).isEqualTo("ADMIN");
        assertThat(properties.getAi().getModel()).isEqualTo("qwen3.7-flash");
        assertThat(properties.getAi().getApiKey()).isEqualTo("new-private-key");
        String encrypted=jdbc.queryForObject("SELECT api_key_ciphertext FROM ai_connection_settings WHERE id=1",String.class);
        assertThat(encrypted).doesNotContain("new-private-key");
        assertThat(new ObjectMapper().findAndRegisterModules().writeValueAsString(saved)).doesNotContain("new-private-key", "apiKey\"");

        var otherProperties=new AppProperties();
        var replica=new AiSettingsService(jdbc,otherProperties,audit,"stable-test-encryption-secret");
        replica.initialize();
        assertThat(otherProperties.getAi().getApiKey()).isEqualTo("new-private-key");
        var changed=settings.save(new AiSettingsService.Update("second-model",saved.baseUrl(),"",saved.version()),null);
        assertThat(properties.getAi().getApiKey()).isEqualTo("new-private-key");
        replica.synchronize();
        assertThat(otherProperties.getAi().getModel()).isEqualTo("second-model");
        assertThatThrownBy(()->settings.save(new AiSettingsService.Update("stale-model",saved.baseUrl(),"",saved.version()),null))
                .isInstanceOfSatisfying(ResponseStatusException.class,e->assertThat(e.getStatusCode().value()).isEqualTo(409));

        doThrow(new IllegalStateException("audit unavailable")).when(audit).log(any(),eq("UPDATE_AI_SETTINGS"),anyString(),anyLong(),anyString());
        assertThatThrownBy(()->settings.save(new AiSettingsService.Update("rolled-back",saved.baseUrl(),"",changed.version()),null))
                .isInstanceOf(IllegalStateException.class);
        assertThat(properties.getAi().getModel()).isEqualTo("second-model");
        assertThat(settings.view().version()).isEqualTo(changed.version());
        reset(audit);

        for(String url:java.util.List.of("https://different.test/v1", "https://key@provider.test/v1", "file:///tmp/api", "https://provider.test/v1?key=private")) {
            assertThatThrownBy(()->settings.save(new AiSettingsService.Update("test",url,"",changed.version()),null))
                    .isInstanceOfSatisfying(ResponseStatusException.class,e->assertThat(e.getStatusCode().value()).isEqualTo(400));
        }
        var restored=settings.reset(new AiSettingsService.Version(changed.version()),null);
        assertThat(restored.source()).isEqualTo("ENVIRONMENT");
        assertThat(properties.getAi().getModel()).isEqualTo("environment-model");
        assertThat(properties.getAi().getApiKey()).isEqualTo("environment-key");
        assertThat(jdbc.queryForObject("SELECT api_key_ciphertext FROM ai_connection_settings WHERE id=1",String.class)).isNull();
    }
}
