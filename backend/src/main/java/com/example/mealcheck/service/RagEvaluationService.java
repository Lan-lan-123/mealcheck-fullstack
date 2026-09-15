package com.example.mealcheck.service;

import com.example.mealcheck.config.AppProperties;
import com.example.mealcheck.dto.AdminMealAnalyticsResponse;
import com.example.mealcheck.dto.KnowledgeSnippet;
import com.example.mealcheck.dto.RagEvaluationSummaryResponse;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class RagEvaluationService {
    private final JdbcTemplate jdbcTemplate;
    private final AppProperties properties;

    public RagEvaluationService(JdbcTemplate jdbcTemplate, AppProperties properties) {
        this.jdbcTemplate = jdbcTemplate;
        this.properties = properties;
    }

    public void recordSearch(String query, List<KnowledgeSnippet> snippets) {
        int count = snippets == null ? 0 : snippets.size();
        double topScore = count == 0 ? 0.0 : snippets.get(0).getScore();
        double averageScore = count == 0
                ? 0.0
                : snippets.stream().mapToDouble(KnowledgeSnippet::getScore).average().orElse(0.0);
        String category = count == 0 ? "none" : snippets.get(0).getCategory();
        double roughTopScore = count == 0 ? 0.0 : snippets.stream()
                .mapToDouble(this::roughScore).max().orElse(0.0);
        Double rerankerTopScore = count == 0 ? null : snippets.stream()
                .map(KnowledgeSnippet::getRerankerRawScore)
                .filter(java.util.Objects::nonNull)
                .max(Double::compareTo).orElse(null);
        boolean rerankerApplied = count > 0 && snippets.stream().anyMatch(KnowledgeSnippet::isReranked);

        insertSearch(query, count, topScore, averageScore, category, roughTopScore,
                rerankerTopScore, topScore, rerankerApplied, count == 0, 0, 0);
    }

    public void recordSearch(String query,
                             List<KnowledgeSnippet> snippets,
                             KnowledgeResultPostProcessor.Result processingResult) {
        int count = snippets == null ? 0 : snippets.size();
        double topScore = count == 0 ? 0.0 : snippets.get(0).getScore();
        double averageScore = count == 0
                ? 0.0
                : snippets.stream().mapToDouble(KnowledgeSnippet::getScore).average().orElse(0.0);
        String category = count == 0 ? "none" : snippets.get(0).getCategory();
        insertSearch(query, count, topScore, averageScore, category,
                processingResult.topRoughScore(), processingResult.topRerankerRawScore(),
                processingResult.topFinalScore(), processingResult.rerankerApplied(), count == 0,
                processingResult.thresholdFilteredCount(), processingResult.duplicateFilteredCount());
    }

    private void insertSearch(String query,
                              int count,
                              double topScore,
                              double averageScore,
                              String category,
                              double roughTopScore,
                              Double rerankerTopScore,
                              double finalTopScore,
                              boolean rerankerApplied,
                              boolean noAnswer,
                              int thresholdFilteredCount,
                              int duplicateFilteredCount) {
        jdbcTemplate.update("""
                INSERT INTO rag_search_events(
                    query_text, result_count, top_score, average_score, matched_category,
                    rough_top_score, reranker_top_score, final_top_score, reranker_applied,
                    no_answer, threshold_filtered_count, duplicate_filtered_count)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, query == null ? "" : query, count, topScore, averageScore, category,
                roughTopScore, rerankerTopScore, finalTopScore, rerankerApplied, noAnswer,
                Math.max(0, thresholdFilteredCount), Math.max(0, duplicateFilteredCount));
    }

    public RagEvaluationSummaryResponse summary24h() {
        LocalDateTime since = LocalDateTime.now().minusHours(24);
        SummaryRow summary = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) AS searches,
                       COALESCE(AVG(top_score), 0) AS avg_top_score,
                       COALESCE(AVG(average_score), 0) AS avg_score,
                       COALESCE(AVG(result_count), 0) AS avg_result_count,
                       COALESCE(SUM(CASE WHEN top_score < ? THEN 1 ELSE 0 END), 0) AS low_confidence,
                       COALESCE(AVG(rough_top_score), 0) AS avg_rough_top_score,
                       COALESCE(AVG(reranker_top_score), 0) AS avg_reranker_top_score,
                       COALESCE(SUM(CASE WHEN reranker_applied THEN 1 ELSE 0 END), 0) AS reranker_applied,
                       COALESCE(SUM(CASE WHEN no_answer THEN 1 ELSE 0 END), 0) AS no_answer
                FROM rag_search_events
                WHERE created_at >= ?
                """, (rs, rowNum) -> new SummaryRow(
                rs.getLong("searches"),
                rs.getDouble("avg_top_score"),
                rs.getDouble("avg_score"),
                rs.getDouble("avg_result_count"),
                rs.getLong("low_confidence"),
                rs.getDouble("avg_rough_top_score"),
                rs.getDouble("avg_reranker_top_score"),
                rs.getLong("reranker_applied"),
                rs.getLong("no_answer")
        ), lowConfidenceThreshold(), since);

        List<AdminMealAnalyticsResponse.MetricItem> categories = jdbcTemplate.query("""
                SELECT COALESCE(matched_category, 'none') AS category, COUNT(*) AS total
                FROM rag_search_events
                WHERE created_at >= ?
                GROUP BY COALESCE(matched_category, 'none')
                ORDER BY total DESC, category ASC
                LIMIT 6
                """, (rs, rowNum) -> new AdminMealAnalyticsResponse.MetricItem(
                rs.getString("category"),
                rs.getInt("total")
        ), since);

        SummaryRow safe = summary == null ? new SummaryRow(0, 0, 0, 0, 0, 0, 0, 0, 0) : summary;
        return new RagEvaluationSummaryResponse(
                safe.searches(),
                round(safe.averageTopScore()),
                round(safe.averageScore()),
                round(safe.averageResultCount()),
                safe.lowConfidenceSearches(),
                round(safe.averageRoughTopScore()),
                round(safe.averageRerankerTopScore()),
                safe.rerankerAppliedSearches(),
                safe.noAnswerSearches(),
                categories
        );
    }

    private double round(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    private double lowConfidenceThreshold() {
        double configured = properties.getKnowledge().getResultMinScore();
        return Double.isFinite(configured) ? Math.max(0.0, Math.min(1.0, configured)) : 0.35;
    }

    private double roughScore(KnowledgeSnippet snippet) {
        return snippet.getRoughRank() > 0 || snippet.getRoughScore() != 0.0
                ? snippet.getRoughScore()
                : snippet.getScore();
    }

    private record SummaryRow(long searches,
                              double averageTopScore,
                              double averageScore,
                              double averageResultCount,
                              long lowConfidenceSearches,
                              double averageRoughTopScore,
                              double averageRerankerTopScore,
                              long rerankerAppliedSearches,
                              long noAnswerSearches) {
    }
}
