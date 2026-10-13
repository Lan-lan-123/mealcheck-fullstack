package com.example.mealcheck.service;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import java.net.URI;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;

/** Full bean wiring matters here: slice tests do not validate optional storage/pricing injection. */
@SpringBootTest(properties={
        "mealcheck.analysis.dispatch=database", "mealcheck.analysis.worker-enabled=false",
        "mealcheck.mcp.enabled=false", "mealcheck.knowledge.auto-index=false",
        "mealcheck.upload-dir=target/runtime-test-uploads",
        "mealcheck.ai.api-key=", "mealcheck.ai-budget.global-daily-calls=100000",
        "mealcheck.ai-pricing.catalog-json=[{\"endpoint\":\"https://models.invalid/v1/embeddings\",\"model\":\"embedding-fixture\",\"inputCostPerMillion\":2,\"outputCostPerMillion\":0,\"currency\":\"CNY\"}]"
})
@Testcontainers
class BackendRuntimeDatabaseIntegrationTest {
    @Container static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }
    @MockBean BackgroundTaskDispatcher background;
    @Autowired ApplicationContext context;
    @Autowired AiBudgetService budgets;
    @Autowired ImageStorageService storage;
    @Autowired ModelPriceCatalog prices;

    @Test void applicationBootsWithLocalStorageAndExposesConfiguredGlobalAccounting() {
        assertThat(storage.usesObjectStorage()).isFalse();
        assertThat(context.getBeansOfType(OssImageBlobStore.class)).isEmpty();
        assertThat(prices.lookup(URI.create("https://models.invalid/v1/embeddings"), "embedding-fixture")).isPresent();
        var monitoring = budgets.monitoring();
        assertThat(((Map<?,?>) monitoring.get("pricing")).containsValue(1)).isTrue();
        var usage = (Map<?,?>) monitoring.get("globalUsage");
        assertThat(((Number) usage.get("callLimit")).intValue()).isEqualTo(100000);
        assertThat(((Number) usage.get("remainingCalls")).intValue()).isEqualTo(100000);
        assertThat(usage.get("resetAt")).isNotNull();
    }
}
