package com.example.mealcheck.service;

import com.example.mealcheck.dto.KnowledgeSnippet;
import com.example.mealcheck.dto.RagBenchmarkResponse;
import com.example.mealcheck.config.AppProperties;
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
        when(vectorService.search(anyString(), eq(20))).thenReturn(List.of(
                new KnowledgeSnippet(1L, "减脂建议", "content", "fat_loss", 0.9),
                new KnowledgeSnippet(2L, "高油风险", "content", "high_oil", 0.8),
                new KnowledgeSnippet(3L, "蔬菜建议", "content", "vegetable", 0.7)
        ));

        AppProperties properties = new AppProperties();
        RagBenchmarkService service = new RagBenchmarkService(
                vectorService, new ObjectMapper(), disabledReranker(properties),
                new KnowledgeResultPostProcessor(properties), properties);
        RagBenchmarkResponse response = service.evaluate();

        assertThat(response.getCaseCount()).isEqualTo(58);
        assertThat(response.getAnswerableCaseCount()).isEqualTo(48);
        assertThat(response.getNoAnswerCaseCount()).isEqualTo(10);
        assertThat(response.getCases()).hasSize(58);
        assertThat(response.getHitAt3()).isGreaterThan(0.0);
        assertThat(response.getBaselineHitAt3()).isEqualTo(response.getHitAt3());
        assertThat(response.getBaselineMrr()).isEqualTo(response.getMrr());
        assertThat(response.getBaselineHitAt5()).isEqualTo(response.getHitAt5());
        assertThat(response.getBaselineNdcgAt5()).isEqualTo(response.getNdcgAt5());
        assertThat(response.isRegressionPassed()).isTrue();
        assertThat(response.isRerankerEnabled()).isFalse();
        assertThat(response.getAverageCandidateCount()).isEqualTo(3.0);
        assertThat(response.getAverageResultCount()).isEqualTo(3.0);
        assertThat(response.getEmptyResultRate()).isZero();
        assertThat(response.getNoAnswerAccuracy()).isZero();
        assertThat(response.getFalsePositiveRate()).isEqualTo(1.0);
    }

    @Test
    void countsCategoryAccuracyWhenAnyTop3ItemMatchesExpectedCategory() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        Map<String, String> expectedCategories = expectedCategories(objectMapper);
        PgVectorKnowledgeService vectorService = mock(PgVectorKnowledgeService.class);
        when(vectorService.search(anyString(), eq(20))).thenAnswer(invocation -> {
            String expectedCategory = expectedCategories.get(invocation.getArgument(0, String.class));
            return List.of(
                    new KnowledgeSnippet(1L, "unrelated", "content", "other", 0.9),
                    new KnowledgeSnippet(2L, "unrelated again", "content", "other", 0.8),
                    new KnowledgeSnippet(3L, "expected third", "content", expectedCategory, 0.7)
            );
        });

        AppProperties properties = new AppProperties();
        RagBenchmarkService service = new RagBenchmarkService(
                vectorService, objectMapper, disabledReranker(properties),
                new KnowledgeResultPostProcessor(properties), properties);
        RagBenchmarkResponse response = service.evaluate();

        assertThat(response.getCategoryAccuracy()).isEqualTo(1.0);
        assertThat(response.getCases()).filteredOn(result -> !result.isExpectNoAnswer())
                .allMatch(RagBenchmarkResponse.CaseResult::isCategoryHitAt3);
    }

    @Test
    void countsCategoryAccuracyWhenTop3TitleImpliesExpectedCategory() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        Map<String, String> expectedCategories = expectedCategories(objectMapper);
        PgVectorKnowledgeService vectorService = mock(PgVectorKnowledgeService.class);
        when(vectorService.search(anyString(), eq(20))).thenAnswer(invocation -> {
            String expectedCategory = expectedCategories.get(invocation.getArgument(0, String.class));
            return List.of(
                    new KnowledgeSnippet(1L, "unrelated", "content", "other", 0.9),
                    new KnowledgeSnippet(2L, "unrelated again", "content", "other", 0.8),
                    new KnowledgeSnippet(3L, titleFor(expectedCategory), "content", "other", 0.7)
            );
        });

        AppProperties properties = new AppProperties();
        RagBenchmarkService service = new RagBenchmarkService(
                vectorService, objectMapper, disabledReranker(properties),
                new KnowledgeResultPostProcessor(properties), properties);
        RagBenchmarkResponse response = service.evaluate();

        assertThat(response.getCategoryAccuracy()).isEqualTo(1.0);
        assertThat(response.getCases()).filteredOn(result -> !result.isExpectNoAnswer())
                .allMatch(RagBenchmarkResponse.CaseResult::isCategoryHitAt3);
    }

    @Test
    void evaluatesNoAnswerCasesSeparatelyFromAnswerableRecall() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        Map<String, Boolean> noAnswerCases = expectedNoAnswerCases(objectMapper);
        PgVectorKnowledgeService vectorService = mock(PgVectorKnowledgeService.class);
        when(vectorService.search(anyString(), eq(20))).thenAnswer(invocation -> {
            String question = invocation.getArgument(0, String.class);
            if (Boolean.TRUE.equals(noAnswerCases.get(question))) {
                return List.of(new KnowledgeSnippet(99L, "无关知识", "无关内容", "general", 0.10));
            }
            return List.of(new KnowledgeSnippet(1L, "减脂建议", "content", "fat_loss", 0.90));
        });

        AppProperties properties = new AppProperties();
        RagBenchmarkService service = new RagBenchmarkService(
                vectorService, objectMapper, disabledReranker(properties),
                new KnowledgeResultPostProcessor(properties), properties);

        RagBenchmarkResponse response = service.evaluate();

        assertThat(response.getBaselineNoAnswerAccuracy()).isZero();
        assertThat(response.getNoAnswerAccuracy()).isEqualTo(1.0);
        assertThat(response.getFalsePositiveRate()).isZero();
        assertThat(response.getCases()).filteredOn(RagBenchmarkResponse.CaseResult::isExpectNoAnswer)
                .allMatch(RagBenchmarkResponse.CaseResult::isNoAnswerCorrect);
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

    private KnowledgeRerankerService disabledReranker(AppProperties properties) {
        return new KnowledgeRerankerService(
                properties,
                mock(java.net.http.HttpClient.class),
                new ObjectMapper(),
                mock(ApplicationObservability.class),
                mock(RemoteCallGuard.class)
        );
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

    private Map<String, Boolean> expectedNoAnswerCases(ObjectMapper objectMapper) throws Exception {
        List<Map<String, Object>> cases = objectMapper.readValue(
                new ClassPathResource("knowledge/rag_eval_cases.json").getInputStream(),
                new TypeReference<List<Map<String, Object>>>() {}
        );
        Map<String, Boolean> expected = new HashMap<>();
        for (Map<String, Object> evaluationCase : cases) {
            expected.put((String) evaluationCase.get("question"),
                    Boolean.TRUE.equals(evaluationCase.get("expectNoAnswer")));
        }
        return expected;
    }
}
