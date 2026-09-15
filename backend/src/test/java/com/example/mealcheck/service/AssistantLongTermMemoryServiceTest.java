package com.example.mealcheck.service;

import com.example.mealcheck.config.AppProperties;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class AssistantLongTermMemoryServiceTest {
    @Test
    void extractsOnlyStableUserFactsAndClassifiesThem() {
        AppProperties properties = new AppProperties();
        AssistantLongTermMemoryService service = new AssistantLongTermMemoryService(
                mock(JdbcTemplate.class), mock(KnowledgeEmbeddingService.class),
                new VectorCompressionService(properties), properties);

        var facts = service.extractFacts("我对花生过敏。我不喜欢香菜；今天午饭吃什么？我的目标是减脂！");

        assertThat(facts).extracting(AssistantLongTermMemoryService.ExtractedFact::type)
                .containsExactly("restriction", "preference", "goal");
        assertThat(facts).extracting(AssistantLongTermMemoryService.ExtractedFact::text)
                .containsExactly("我对花生过敏", "我不喜欢香菜", "我的目标是减脂");
    }
}
