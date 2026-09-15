package com.example.mealcheck.service;

import com.example.mealcheck.config.AppProperties;
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
    private final KnowledgeRerankerService rerankerService;
    private final KnowledgeResultPostProcessor resultPostProcessor;
    private final AppProperties properties;

    public RagBenchmarkService(PgVectorKnowledgeService vectorKnowledgeService,
                               ObjectMapper objectMapper,
                               KnowledgeRerankerService rerankerService,
                               KnowledgeResultPostProcessor resultPostProcessor,
                               AppProperties properties) {
        this.vectorKnowledgeService = vectorKnowledgeService;
        this.objectMapper = objectMapper;
        this.rerankerService = rerankerService;
        this.resultPostProcessor = resultPostProcessor;
        this.properties = properties;
    }

    public RagBenchmarkResponse evaluate() {
        long startedAt = System.nanoTime();
        List<EvaluationCase> cases = loadCases();
        List<RagBenchmarkResponse.CaseResult> results = cases.stream()
                .map(this::evaluateCase)
                .toList();
        List<RagBenchmarkResponse.CaseResult> answerableResults = results.stream()
                .filter(result -> !result.isExpectNoAnswer()).toList();
        List<RagBenchmarkResponse.CaseResult> noAnswerResults = results.stream()
                .filter(RagBenchmarkResponse.CaseResult::isExpectNoAnswer).toList();

        double baselineHitAt3 = ratio(answerableResults.stream()
                .filter(RagBenchmarkResponse.CaseResult::isBaselineHitAt3).count(), answerableResults.size());
        double hitAt3 = ratio(answerableResults.stream()
                .filter(RagBenchmarkResponse.CaseResult::isHitAt3).count(), answerableResults.size());
        double baselineHitAt5 = ratio(answerableResults.stream()
                .filter(RagBenchmarkResponse.CaseResult::isBaselineHitAt5).count(), answerableResults.size());
        double hitAt5 = ratio(answerableResults.stream()
                .filter(RagBenchmarkResponse.CaseResult::isHitAt5).count(), answerableResults.size());
        double baselineMrr = reciprocalRankAverage(answerableResults, true);
        double baselineNdcgAt5 = answerableResults.stream()
                .mapToDouble(RagBenchmarkResponse.CaseResult::getBaselineNdcgAt5)
                .average().orElse(0.0);
        double ndcgAt5 = answerableResults.stream()
                .mapToDouble(RagBenchmarkResponse.CaseResult::getNdcgAt5)
                .average().orElse(0.0);
        double mrr = reciprocalRankAverage(answerableResults, false);
        double baselineCategoryAccuracy = ratio(answerableResults.stream()
                .filter(RagBenchmarkResponse.CaseResult::isBaselineCategoryHitAt3)
                .count(), answerableResults.size());
        double categoryAccuracy = ratio(answerableResults.stream()
                .filter(RagBenchmarkResponse.CaseResult::isCategoryHitAt3)
                .count(), answerableResults.size());
        double averageRetrievalDurationMs = results.stream()
                .mapToLong(RagBenchmarkResponse.CaseResult::getRetrievalDurationMs)
                .average().orElse(0.0);
        double averageRerankDurationMs = results.stream()
                .mapToLong(RagBenchmarkResponse.CaseResult::getRerankDurationMs)
                .average().orElse(0.0);
        double averageCandidateCount = results.stream()
                .mapToInt(RagBenchmarkResponse.CaseResult::getCandidateCount)
                .average().orElse(0.0);
        double averageResultCount = results.stream()
                .mapToInt(RagBenchmarkResponse.CaseResult::getFinalResultCount)
                .average().orElse(0.0);
        double emptyResultRate = ratio(results.stream()
                .filter(result -> result.getFinalResultCount() == 0).count(), results.size());
        double baselineNoAnswerAccuracy = ratio(noAnswerResults.stream()
                .filter(RagBenchmarkResponse.CaseResult::isBaselineNoAnswerCorrect).count(), noAnswerResults.size());
        double noAnswerAccuracy = ratio(noAnswerResults.stream()
                .filter(RagBenchmarkResponse.CaseResult::isNoAnswerCorrect).count(), noAnswerResults.size());
        double falsePositiveRate = noAnswerResults.isEmpty() ? 0.0 : 1.0 - noAnswerAccuracy;
        double tolerance = Math.max(0.0, Math.min(0.25,
                properties.getKnowledge().getBenchmarkRegressionTolerance()));
        boolean regressionPassed = hitAt5 + tolerance >= baselineHitAt5
                && mrr + tolerance >= baselineMrr
                && ndcgAt5 + tolerance >= baselineNdcgAt5
                && noAnswerAccuracy + tolerance >= baselineNoAnswerAccuracy;

        return new RagBenchmarkResponse(
                results.size(),
                answerableResults.size(),
                noAnswerResults.size(),
                round(baselineHitAt3),
                round(hitAt3),
                round(baselineHitAt5),
                round(hitAt5),
                round(baselineMrr),
                round(mrr),
                round(baselineNdcgAt5),
                round(ndcgAt5),
                round(baselineCategoryAccuracy),
                round(categoryAccuracy),
                rerankerService.isConfigured(),
                rerankerService.providerName(),
                java.time.Duration.ofNanos(System.nanoTime() - startedAt).toMillis(),
                round(averageRetrievalDurationMs),
                round(averageRerankDurationMs),
                round(averageCandidateCount),
                round(averageResultCount),
                round(emptyResultRate),
                round(baselineNoAnswerAccuracy),
                round(noAnswerAccuracy),
                round(falsePositiveRate),
                regressionPassed,
                results
        );
    }

    private RagBenchmarkResponse.CaseResult evaluateCase(EvaluationCase evaluationCase) {
        int rerankMultiplier = rerankerService.isConfigured()
                ? Math.max(2, properties.getKnowledge().getRerankCandidateMultiplier())
                : 1;
        int candidateMultiplier = Math.max(resultPostProcessor.candidateMultiplier(), rerankMultiplier);
        int candidateLimit = Math.min(20, 5 * candidateMultiplier);
        long retrievalStartedAt = System.nanoTime();
        List<KnowledgeSnippet> candidates = vectorKnowledgeService.search(evaluationCase.question(), candidateLimit);
        long retrievalDurationMs = java.time.Duration.ofNanos(
                System.nanoTime() - retrievalStartedAt).toMillis();
        List<KnowledgeSnippet> baseline = candidates.stream().limit(5).toList();
        long rerankStartedAt = System.nanoTime();
        List<KnowledgeSnippet> ranked = rerankerService.rerank(
                evaluationCase.question(), candidates, candidateLimit);
        long rerankDurationMs = java.time.Duration.ofNanos(System.nanoTime() - rerankStartedAt).toMillis();
        KnowledgeResultPostProcessor.Result processed = resultPostProcessor.process(ranked, 5);
        List<KnowledgeSnippet> snippets = processed.snippets();
        List<String> expectedTitleKeywords = evaluationCase.expectedTitleKeywords() == null
                ? List.of() : evaluationCase.expectedTitleKeywords();
        int baselineFirstRelevantRank = firstRelevantRank(baseline, expectedTitleKeywords);
        int firstRelevantRank = firstRelevantRank(snippets, expectedTitleKeywords);
        boolean baselineNoAnswerCorrect = evaluationCase.expectNoAnswer() && baseline.isEmpty();
        boolean noAnswerCorrect = evaluationCase.expectNoAnswer() && snippets.isEmpty();
        return new RagBenchmarkResponse.CaseResult(
                evaluationCase.question(),
                baselineFirstRelevantRank > 0 && baselineFirstRelevantRank <= 3,
                baselineFirstRelevantRank > 0 && baselineFirstRelevantRank <= 5,
                baselineFirstRelevantRank,
                baseline.stream().limit(5).map(KnowledgeSnippet::getTitle).toList(),
                categoryHitAt3(baseline, evaluationCase.expectedCategory()),
                firstRelevantRank > 0 && firstRelevantRank <= 3,
                firstRelevantRank > 0 && firstRelevantRank <= 5,
                firstRelevantRank,
                ndcgAt5(baselineFirstRelevantRank),
                ndcgAt5(firstRelevantRank),
                evaluationCase.expectedCategory(),
                categoryHitAt3(snippets, evaluationCase.expectedCategory()),
                snippets.isEmpty() ? "" : snippets.get(0).getCategory(),
                snippets.stream().limit(5).map(KnowledgeSnippet::getTitle).toList(),
                retrievalDurationMs,
                rerankDurationMs,
                candidates.size(),
                ranked.size(),
                snippets.size(),
                processed.thresholdFilteredCount(),
                processed.duplicateFilteredCount(),
                processed.effectiveThreshold(),
                evaluationCase.expectNoAnswer(),
                baselineNoAnswerCorrect,
                noAnswerCorrect,
                processed.topRoughScore(),
                processed.topRerankerRawScore(),
                processed.topFinalScore()
        );
    }

    private double ndcgAt5(int rank) {
        return rank <= 0 || rank > 5 ? 0.0 : 1.0 / (Math.log(rank + 1.0) / Math.log(2.0));
    }

    private int firstRelevantRank(List<KnowledgeSnippet> snippets, List<String> expectedTitleKeywords) {
        for (int index = 0; index < snippets.size(); index++) {
            if (matches(snippets.get(index), expectedTitleKeywords)) {
                return index + 1;
            }
        }
        return 0;
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

    private double reciprocalRankAverage(List<RagBenchmarkResponse.CaseResult> results, boolean baseline) {
        if (results.isEmpty()) {
            return 0.0;
        }
        double total = results.stream().mapToDouble(result -> {
            int rank = baseline ? result.getBaselineFirstRelevantRank() : result.getFirstRelevantRank();
            return rank > 0 ? 1.0 / rank : 0.0;
        }).sum();
        return total / results.size();
    }

    private double round(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    private record EvaluationCase(String question,
                                  List<String> expectedTitleKeywords,
                                  String expectedCategory,
                                  boolean expectNoAnswer) {
    }
}
