package com.example.mealcheck.service;

import com.example.mealcheck.dto.AdminMealAnalyticsResponse;
import com.example.mealcheck.dto.AdminUploadTrendResponse;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Date;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class AdminAnalyticsQueryService {
    private final JdbcTemplate jdbcTemplate;

    public AdminAnalyticsQueryService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Transactional(readOnly = true)
    public AdminMealAnalyticsResponse mealAnalytics(String username,
                                                    LocalDate from,
                                                    LocalDate to,
                                                    Integer minScore,
                                                    Integer maxScore) {
        SqlFilter filter = mealFilter(username, from, to, minScore, maxScore);
        Summary summary = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) AS total,
                       COALESCE(ROUND(AVG(m.score)), 0) AS average_score,
                       COUNT(*) FILTER (WHERE m.score >= 80) AS high_scores,
                       COUNT(*) FILTER (WHERE m.score >= 60 AND m.score < 80) AS medium_scores,
                       COUNT(*) FILTER (WHERE m.score < 60) AS low_scores
                FROM meal_records m
                JOIN users u ON u.id = m.user_id
                """ + filter.whereClause(), (rs, rowNum) -> new Summary(
                rs.getLong("total"),
                rs.getInt("average_score"),
                rs.getInt("high_scores"),
                rs.getInt("medium_scores"),
                rs.getInt("low_scores")
        ), filter.args().toArray());
        Summary safe = summary == null ? new Summary(0, 0, 0, 0, 0) : summary;

        return new AdminMealAnalyticsResponse(
                safe.total(),
                safe.averageScore(),
                List.of(
                        new AdminMealAnalyticsResponse.MetricItem("80 分及以上", safe.highScores()),
                        new AdminMealAnalyticsResponse.MetricItem("60-79 分", safe.mediumScores()),
                        new AdminMealAnalyticsResponse.MetricItem("60 分以下", safe.lowScores())
                ),
                groupedMetric("u.username", "u.username", filter, 8),
                jsonMetric("jsonb_array_elements(COALESCE(NULLIF(m.detected_foods_json, ''), '[]')::jsonb)",
                        "item ->> 'name'", filter, 10),
                jsonMetric("jsonb_array_elements_text(COALESCE(NULLIF(m.risk_tags_json, ''), '[]')::jsonb)",
                        "item", filter, 10),
                dailyAverageScores(filter)
        );
    }

    @Transactional(readOnly = true)
    public AdminUploadTrendResponse uploadTrends(LocalDate start, LocalDate end) {
        List<UploadTrendRow> rows = jdbcTemplate.query("""
                WITH days AS (
                    SELECT generate_series(CAST(? AS date), CAST(? AS date), INTERVAL '1 day')::date AS day
                )
                SELECT d.day,
                       (SELECT COUNT(*) FROM meal_records m
                        WHERE m.created_at >= d.day AND m.created_at < d.day + INTERVAL '1 day') AS normal_count,
                       (SELECT COUNT(*) FROM non_food_upload_events e
                        WHERE e.created_at >= d.day AND e.created_at < d.day + INTERVAL '1 day') AS abnormal_count,
                       (SELECT COUNT(*) FROM non_food_upload_events e
                        WHERE e.threshold_reached = true
                          AND e.created_at >= d.day AND e.created_at < d.day + INTERVAL '1 day') AS alert_count,
                       (SELECT COUNT(DISTINCT e.username) FROM non_food_upload_events e
                        WHERE e.threshold_reached = true
                          AND e.created_at >= d.day AND e.created_at < d.day + INTERVAL '1 day') AS blocked_users
                FROM days d
                ORDER BY d.day
                """, (rs, rowNum) -> new UploadTrendRow(
                rs.getDate("day").toLocalDate(),
                rs.getInt("normal_count"),
                rs.getInt("abnormal_count"),
                rs.getInt("alert_count"),
                rs.getInt("blocked_users")
        ), Date.valueOf(start), Date.valueOf(end));

        return new AdminUploadTrendResponse(
                rows.stream().map(row -> metric(row.day(), row.normalCount())).toList(),
                rows.stream().map(row -> metric(row.day(), row.abnormalCount())).toList(),
                rows.stream().map(row -> metric(row.day(), row.alertCount())).toList(),
                rows.stream().map(row -> metric(row.day(), row.blockedUsers())).toList()
        );
    }

    @Transactional(readOnly = true)
    public Map<LocalDate, Integer> assistantQuestionTrend(LocalDate start, LocalDate end) {
        Map<LocalDate, Integer> values = new LinkedHashMap<>();
        jdbcTemplate.query("""
                SELECT created_at::date AS day, COUNT(*) AS total
                FROM assistant_conversation_messages
                WHERE role = 'user' AND created_at >= ? AND created_at < ?
                GROUP BY created_at::date
                ORDER BY day
                """, (org.springframework.jdbc.core.RowCallbackHandler) rs ->
                        values.put(rs.getDate("day").toLocalDate(), rs.getInt("total")),
                start.atStartOfDay(), end.plusDays(1).atStartOfDay());
        return values;
    }

    private List<AdminMealAnalyticsResponse.MetricItem> groupedMetric(String selectExpression,
                                                                      String groupExpression,
                                                                      SqlFilter filter,
                                                                      int limit) {
        return jdbcTemplate.query("SELECT " + selectExpression + " AS label, COUNT(*) AS total "
                        + "FROM meal_records m JOIN users u ON u.id = m.user_id "
                        + filter.whereClause()
                        + " GROUP BY " + groupExpression + " ORDER BY total DESC, label ASC LIMIT ?",
                (rs, rowNum) -> new AdminMealAnalyticsResponse.MetricItem(rs.getString("label"), rs.getInt("total")),
                append(filter.args(), limit));
    }

    private List<AdminMealAnalyticsResponse.MetricItem> jsonMetric(String expansion,
                                                                   String labelExpression,
                                                                   SqlFilter filter,
                                                                   int limit) {
        String where = filter.whereClause() + (filter.whereClause().isBlank() ? " WHERE " : " AND ")
                + "COALESCE(" + labelExpression + ", '') <> ''";
        return jdbcTemplate.query("SELECT " + labelExpression + " AS label, COUNT(*) AS total "
                        + "FROM meal_records m JOIN users u ON u.id = m.user_id "
                        + "CROSS JOIN LATERAL " + expansion + " AS expanded(item) "
                        + where + " GROUP BY " + labelExpression + " ORDER BY total DESC, label ASC LIMIT ?",
                (rs, rowNum) -> new AdminMealAnalyticsResponse.MetricItem(rs.getString("label"), rs.getInt("total")),
                append(filter.args(), limit));
    }

    private List<AdminMealAnalyticsResponse.MetricItem> dailyAverageScores(SqlFilter filter) {
        String sql = """
                SELECT day, average_score FROM (
                    SELECT m.created_at::date AS day, ROUND(AVG(m.score))::integer AS average_score
                    FROM meal_records m JOIN users u ON u.id = m.user_id
                """ + filter.whereClause() + """
                    GROUP BY m.created_at::date
                    ORDER BY day DESC
                    LIMIT 14
                ) recent_days ORDER BY day
                """;
        return jdbcTemplate.query(sql,
                (rs, rowNum) -> new AdminMealAnalyticsResponse.MetricItem(
                        rs.getDate("day").toLocalDate().toString(), rs.getInt("average_score")),
                filter.args().toArray());
    }

    private SqlFilter mealFilter(String username,
                                 LocalDate from,
                                 LocalDate to,
                                 Integer minScore,
                                 Integer maxScore) {
        List<String> predicates = new ArrayList<>();
        List<Object> args = new ArrayList<>();
        if (username != null && !username.isBlank()) {
            predicates.add("LOWER(u.username) LIKE ?");
            args.add("%" + username.trim().toLowerCase() + "%");
        }
        if (from != null) {
            predicates.add("m.created_at >= ?");
            args.add(from.atStartOfDay());
        }
        if (to != null) {
            predicates.add("m.created_at < ?");
            args.add(to.plusDays(1).atStartOfDay());
        }
        if (minScore != null) {
            predicates.add("m.score >= ?");
            args.add(minScore);
        }
        if (maxScore != null) {
            predicates.add("m.score <= ?");
            args.add(maxScore);
        }
        return new SqlFilter(predicates.isEmpty() ? "" : " WHERE " + String.join(" AND ", predicates), args);
    }

    private Object[] append(List<Object> args, Object value) {
        List<Object> copy = new ArrayList<>(args);
        copy.add(value);
        return copy.toArray();
    }

    private AdminUploadTrendResponse.MetricItem metric(LocalDate day, int value) {
        return new AdminUploadTrendResponse.MetricItem(day.toString(), value);
    }

    private record SqlFilter(String whereClause, List<Object> args) {}
    private record Summary(long total, int averageScore, int highScores, int mediumScores, int lowScores) {}
    private record UploadTrendRow(LocalDate day, int normalCount, int abnormalCount, int alertCount, int blockedUsers) {}
}
