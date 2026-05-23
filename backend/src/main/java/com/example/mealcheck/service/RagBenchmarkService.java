package com.example.mealcheck.service;

import com.example.mealcheck.dto.KnowledgeSnippet;
import com.example.mealcheck.dto.RagBenchmarkResponse;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;

@Service
public class RagBenchmarkService {
    private final PgVectorKnowledgeService vectorKnowledgeService;
    private final ObjectMapper objectMapper;

    public RagBenchmarkService(PgVectorKnowledgeService vectorKnowledgeService, ObjectMapper objectMapper) {
        this.vectorKnowledgeService = vectorKnowledgeService;
        this.objectMapper = objectMapper;
    }

    public RagBenchmarkResponse evaluate() {
        List<EvaluationCase> cases = loadCases();
        List<RagBenchmarkResponse.CaseResult> results = cases.stream()
                .map(this::evaluateCase)
                .toList();

        double hitAt3 = ratio(results.stream().filter(RagBenchmarkResponse.CaseResult::isHitAt3).count(), results.size());
        double mrr = results.stream()
                .filter(result -> result.getFirstRelevantRank() > 0)
                .mapToDouble(result -> 1.0 / result.getFirstRelevantRank())
                .average()
                .orElse(0.0);
        double categoryAccuracy = ratio(results.stream()
                .filter(RagBenchmarkResponse.CaseResult::isCategoryHitAt3)
                .count(), results.size());

        return new RagBenchmarkResponse(
                results.size(),
                round(hitAt3),
                round(mrr),
                round(categoryAccuracy),
                results
        );
    }

    private RagBenchmarkResponse.CaseResult evaluateCase(EvaluationCase evaluationCase) {
        List<KnowledgeSnippet> snippets = vectorKnowledgeService.search(evaluationCase.question(), 3);
        int firstRelevantRank = 0;
        for (int index = 0; index < snippets.size(); index++) {
            if (matches(snippets.get(index), evaluationCase.expectedTitleKeywords())) {
                firstRelevantRank = index + 1;
                break;
            }
        }
        return new RagBenchmarkResponse.CaseResult(
                evaluationCase.question(),
                firstRelevantRank > 0,
                firstRelevantRank,
                evaluationCase.expectedCategory(),
                categoryHitAt3(snippets, evaluationCase.expectedCategory()),
                snippets.isEmpty() ? "" : snippets.get(0).getCategory(),
                snippets.stream().map(KnowledgeSnippet::getTitle).toList()
        );
    }

    private boolean categoryHitAt3(List<KnowledgeSnippet> snippets, String expectedCategory) {
        String expected = normalizeCategory(expectedCategory);
        if (expected.isBlank()) {
            return false;
        }
        long matchedCount = snippets.stream()
                .limit(3)
                .filter(snippet -> expected.equals(normalizeCategory(snippet.getCategory()))
                        || expected.equals(inferCategory(snippet.getTitle(), snippet.getContent())))
                .count();
        return matchedCount >= 1;
    }

    private String inferCategory(String title, String content) {
        String text = ((title == null ? "" : title) + " " + (content == null ? "" : content)).toLowerCase(Locale.ROOT);
        if (containsAny(text, "减脂", "减肥", "控卡", "热量", "fat")) return "fat_loss";
        if (containsAny(text, "增肌", "蛋白", "肌肉", "训练", "muscle")) return "muscle_gain";
        if (containsAny(text, "油炸", "高油", "炸鸡", "烧烤", "烤肉", "红烧")) return "high_oil";
        if (containsAny(text, "甜", "含糖", "奶茶", "饮料", "糖")) return "sugar";
        if (containsAny(text, "蔬菜", "纤维", "绿叶菜", "维生素")) return "vegetable";
        if (containsAny(text, "主食", "米饭", "面", "碳水", "红薯")) return "staple";
        return "general";
    }

    private boolean containsAny(String text, String... keywords) {
        for (String keyword : keywords) {
            if (text.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    private String normalizeCategory(String category) {
        return category == null ? "" : category.trim().toLowerCase(Locale.ROOT);
    }

    private boolean matches(KnowledgeSnippet snippet, List<String> expectedTitleKeywords) {
        String title = snippet.getTitle() == null ? "" : snippet.getTitle();
        return expectedTitleKeywords.stream().anyMatch(title::contains);
    }

    private List<EvaluationCase> loadCases() {
        try {
            return objectMapper.readValue(
                    new ClassPathResource("knowledge/rag_eval_cases.json").getInputStream(),
                    new TypeReference<List<EvaluationCase>>() {}
            );
        } catch (Exception e) {
            throw new IllegalStateException("Failed to load RAG evaluation cases.", e);
        }
    }

    private double ratio(long numerator, int denominator) {
        return denominator == 0 ? 0.0 : (double) numerator / denominator;
    }

    private double round(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    private record EvaluationCase(String question, List<String> expectedTitleKeywords, String expectedCategory) {
    }
}
