package com.example.mealcheck.service;

import com.example.mealcheck.dto.MealSummaryPageResponse;
import com.example.mealcheck.dto.MealSummaryPageResponse.MealSummary;
import com.example.mealcheck.util.AppTime;
import com.example.mealcheck.util.Jsons;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

@Service
public class UserMealQueryService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public UserMealQueryService(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    @Transactional(readOnly = true)
    public MealSummaryPageResponse page(long userId, int page, int size, String goal,
                                        LocalDate from, LocalDate to, Integer minScore, Integer maxScore) {
        int safePage = Math.max(0, page), safeSize = Math.max(1, Math.min(50, size));
        List<String> conditions = new ArrayList<>(List.of("m.user_id=?"));
        List<Object> args = new ArrayList<>(List.of(userId));
        if (goal != null && !goal.isBlank() && !"all".equalsIgnoreCase(goal)) {
            conditions.add("m.goal=?"); args.add(goal.trim());
        }
        if (from != null) { conditions.add("coalesce(m.occurred_at,m.created_at)>=?"); args.add(AppTime.startOfDay(from)); }
        if (to != null) { conditions.add("coalesce(m.occurred_at,m.created_at)<?"); args.add(AppTime.startOfDay(to.plusDays(1))); }
        if (minScore != null) { conditions.add("m.score>=?"); args.add(minScore); }
        if (maxScore != null) { conditions.add("m.score<=?"); args.add(maxScore); }
        String where = " WHERE " + String.join(" AND ", conditions);
        long total = jdbc.queryForObject("SELECT count(*) FROM meal_records m" + where, Long.class, args.toArray());
        args.add((long) safePage * safeSize); args.add(safeSize);
        List<MealSummary> records = jdbc.query("""
                WITH selected AS (
                    SELECT m.id,m.created_at,coalesce(m.occurred_at,m.created_at) AS occurred_at,
                           m.meal_type,m.record_source,m.time_confirmed,m.goal,m.score,m.summary,m.detected_foods_json
                    FROM meal_records m
                """ + where + " ORDER BY coalesce(m.occurred_at,m.created_at) DESC,m.id DESC OFFSET ? LIMIT ?" + """
                )
                SELECT m.id,m.created_at,m.occurred_at,m.meal_type,m.record_source,m.time_confirmed,
                       m.goal,m.score,m.summary,
                       coalesce((SELECT jsonb_agg(f.item->>'name' ORDER BY f.position)
                         FROM jsonb_array_elements(coalesce(nullif(m.detected_foods_json,''),'[]')::jsonb)
                           WITH ORDINALITY AS f(item,position)
                         WHERE f.position<=6 AND nullif(f.item->>'name','') IS NOT NULL),'[]'::jsonb)::text AS food_names
                FROM selected m ORDER BY m.occurred_at DESC,m.id DESC
                """, (rs, row) -> new MealSummary(rs.getLong("id"), rs.getTimestamp("created_at").toLocalDateTime(),
                rs.getTimestamp("occurred_at").toLocalDateTime(), rs.getString("meal_type"), rs.getString("record_source"),
                rs.getBoolean("time_confirmed"), rs.getString("goal"), rs.getInt("score"), rs.getString("summary"),
                Jsons.fromJson(mapper, rs.getString("food_names"), new TypeReference<List<String>>() { })), args.toArray());
        return new MealSummaryPageResponse(records, total, (total + safeSize - 1) / safeSize, safePage, safeSize);
    }
}
