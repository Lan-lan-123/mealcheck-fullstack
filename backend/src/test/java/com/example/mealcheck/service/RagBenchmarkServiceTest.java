package com.example.mealcheck.service;

import com.example.mealcheck.dto.KnowledgeSnippet;
import com.example.mealcheck.dto.RagBenchmarkResponse;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RagBenchmarkServiceTest {

    @Test
    void aggregatesBenchmarkMetricsFromConfiguredCases() {
        PgVectorKnowledgeService vectorService = mock(PgVectorKnowledgeService.class);
        when(vectorService.search(anyString(), eq(3))).thenReturn(List.of(
                new KnowledgeSnippet(1L, "减脂建议", "content", "fat_loss", 0.9),
                new KnowledgeSnippet(2L, "高油风险", "content", "high_oil", 0.8),
                new KnowledgeSnippet(3L, "蔬菜建议", "content", "vegetable", 0.7)
        ));

        RagBenchmarkService service = new RagBenchmarkService(vectorService, new ObjectMapper());
        RagBenchmarkResponse response = service.evaluate();

        assertThat(response.getCaseCount()).isEqualTo(10);
        assertThat(response.getCases()).hasSize(10);
        assertThat(response.getHitAt3()).isGreaterThan(0.0);
    }

    @Test
    void countsCategoryAccuracyWhenAnyTop3ItemMatchesExpectedCategory() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        Map<String, String> expectedCategories = expectedCategories(objectMapper);
        PgVectorKnowledgeService vectorService = mock(PgVectorKnowledgeService.class);
        when(vectorService.search(anyString(), eq(3))).thenAnswer(invocation -> {
            String expectedCategory = expectedCategories.get(invocation.getArgument(0, String.class));
            return List.of(
                    new KnowledgeSnippet(1L, "unrelated", "content", "other", 0.9),
                    new KnowledgeSnippet(2L, "unrelated again", "content", "other", 0.8),
                    new KnowledgeSnippet(3L, "expected third", "content", expectedCategory, 0.7)
            );
        });

        RagBenchmarkService service = new RagBenchmarkService(vectorService, objectMapper);
        RagBenchmarkResponse response = service.evaluate();

        assertThat(response.getCategoryAccuracy()).isEqualTo(1.0);
        assertThat(response.getCases()).allMatch(RagBenchmarkResponse.CaseResult::isCategoryHitAt3);
    }

    @Test
    void countsCategoryAccuracyWhenTop3TitleImpliesExpectedCategory() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        Map<String, String> expectedCategories = expectedCategories(objectMapper);
        PgVectorKnowledgeService vectorService = mock(PgVectorKnowledgeService.class);
        when(vectorService.search(anyString(), eq(3))).thenAnswer(invocation -> {
            String expectedCategory = expectedCategories.get(invocation.getArgument(0, String.class));
            return List.of(
                    new KnowledgeSnippet(1L, "unrelated", "content", "other", 0.9),
                    new KnowledgeSnippet(2L, "unrelated again", "content", "other", 0.8),
                    new KnowledgeSnippet(3L, titleFor(expectedCategory), "content", "other", 0.7)
            );
        });

        RagBenchmarkService service = new RagBenchmarkService(vectorService, objectMapper);
        RagBenchmarkResponse response = service.evaluate();

        assertThat(response.getCategoryAccuracy()).isEqualTo(1.0);
        assertThat(response.getCases()).allMatch(RagBenchmarkResponse.CaseResult::isCategoryHitAt3);
    }

    private String titleFor(String category) {
        return switch (category) {
            case "fat_loss" -> "减脂建议";
            case "muscle_gain" -> "增肌蛋白建议";
            case "high_oil" -> "高油烹饪风险";
            case "sugar" -> "含糖饮料建议";
            case "vegetable" -> "蔬菜结构建议";
            case "staple" -> "主食结构建议";
            default -> "清淡饮食建议";
        };
    }

    private Map<String, String> expectedCategories(ObjectMapper objectMapper) throws Exception {
        List<Map<String, Object>> cases = objectMapper.readValue(
                new ClassPathResource("knowledge/rag_eval_cases.json").getInputStream(),
                new TypeReference<List<Map<String, Object>>>() {}
        );
        Map<String, String> expectedCategories = new HashMap<>();
        for (Map<String, Object> evaluationCase : cases) {
            expectedCategories.put(
                    (String) evaluationCase.get("question"),
                    (String) evaluationCase.get("expectedCategory")
            );
        }
        return expectedCategories;
    }
}
