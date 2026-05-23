package com.example.mealcheck.service;

import com.example.mealcheck.dto.AdminMealAnalyticsResponse;
import com.example.mealcheck.dto.KnowledgeSnippet;
import com.example.mealcheck.dto.RagEvaluationSummaryResponse;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class RagEvaluationService implements InitializingBean {
    private static final double LOW_CONFIDENCE_THRESHOLD = 0.35;

    private final JdbcTemplate jdbcTemplate;

    public RagEvaluationService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void afterPropertiesSet() {
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS rag_search_events (
                    id BIGSERIAL PRIMARY KEY,
                    query_text TEXT NOT NULL,
                    result_count INTEGER NOT NULL,
                    top_score DOUBLE PRECISION NOT NULL,
                    average_score DOUBLE PRECISION NOT NULL,
                    matched_category VARCHAR(64),
                    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                )
                """);
        jdbcTemplate.execute("CREATE INDEX IF NOT EXISTS idx_rag_search_events_created_at ON rag_search_events(created_at)");
    }

    public void recordSearch(String query, List<KnowledgeSnippet> snippets) {
        int count = snippets == null ? 0 : snippets.size();
        double topScore = count == 0 ? 0.0 : snippets.get(0).getScore();
        double averageScore = count == 0
                ? 0.0
                : snippets.stream().mapToDouble(KnowledgeSnippet::getScore).average().orElse(0.0);
        String category = count == 0 ? "none" : snippets.get(0).getCategory();

        jdbcTemplate.update("""
                INSERT INTO rag_search_events(query_text, result_count, top_score, average_score, matched_category)
                VALUES (?, ?, ?, ?, ?)
                """, query == null ? "" : query, count, topScore, averageScore, category);
    }

    public RagEvaluationSummaryResponse summary24h() {
        LocalDateTime since = LocalDateTime.now().minusHours(24);
        SummaryRow summary = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) AS searches,
                       COALESCE(AVG(top_score), 0) AS avg_top_score,
                       COALESCE(AVG(average_score), 0) AS avg_score,
                       COALESCE(AVG(result_count), 0) AS avg_result_count,
                       COALESCE(SUM(CASE WHEN top_score < ? THEN 1 ELSE 0 END), 0) AS low_confidence
                FROM rag_search_events
                WHERE created_at >= ?
                """, (rs, rowNum) -> new SummaryRow(
                rs.getLong("searches"),
                rs.getDouble("avg_top_score"),
                rs.getDouble("avg_score"),
                rs.getDouble("avg_result_count"),
                rs.getLong("low_confidence")
        ), LOW_CONFIDENCE_THRESHOLD, since);

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

        SummaryRow safe = summary == null ? new SummaryRow(0, 0, 0, 0, 0) : summary;
        return new RagEvaluationSummaryResponse(
                safe.searches(),
                round(safe.averageTopScore()),
                round(safe.averageScore()),
                round(safe.averageResultCount()),
                safe.lowConfidenceSearches(),
                categories
        );
    }

    private double round(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    private record SummaryRow(long searches,
                              double averageTopScore,
                              double averageScore,
                              double averageResultCount,
                              long lowConfidenceSearches) {
    }
}
