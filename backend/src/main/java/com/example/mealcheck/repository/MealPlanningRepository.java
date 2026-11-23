package com.example.mealcheck.repository;

import com.example.mealcheck.dto.FoodItem;
import com.example.mealcheck.util.AppTime;
import com.example.mealcheck.util.Jsons;
import com.example.mealcheck.service.MealScoringService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import java.time.LocalDate;
import java.util.List;

/** Small projections only: no image payloads, advice or per-record follow-up queries. */
@Repository
public class MealPlanningRepository {
    public record Meal(long id, String mealType, List<FoodItem> foods, List<FoodItem> uncertainFoods) { }
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    public MealPlanningRepository(JdbcTemplate jdbc, ObjectMapper mapper) { this.jdbc = jdbc; this.mapper = mapper; }
    private List<FoodItem> foods(String json) {
        return json == null || json.isBlank() ? List.of() : Jsons.fromJson(mapper, json, new TypeReference<List<FoodItem>>() { });
    }
    private RowMapper<Meal> rowMapper() {
        return (rs, row) -> new Meal(rs.getLong("id"), rs.getString("meal_type"),
                foods(rs.getString("detected_foods_json")), rs.getLong("revision") == 0
                    ? MealScoringService.uncertainFoods(foods(rs.getString("original_foods_json"))) : List.of());
    }
    public List<Meal> today(long userId, LocalDate date) {
        return jdbc.query("""
                SELECT id,meal_type,detected_foods_json,original_foods_json,revision FROM meal_records
                WHERE user_id=? AND coalesce(occurred_at,created_at)>=? AND coalesce(occurred_at,created_at)<?
                ORDER BY coalesce(occurred_at,created_at) DESC,id DESC
                """, rowMapper(), userId, AppTime.startOfDay(date), AppTime.startOfDay(date.plusDays(1)));
    }
    public List<Meal> recent(long userId, LocalDate date) {
        return jdbc.query("""
                SELECT id,meal_type,detected_foods_json,original_foods_json,revision FROM meal_records
                WHERE user_id=? AND coalesce(occurred_at,created_at)>=? AND coalesce(occurred_at,created_at)<?
                ORDER BY coalesce(occurred_at,created_at) DESC,id DESC LIMIT 10
                """, rowMapper(), userId, AppTime.startOfDay(date.minusDays(6)), AppTime.startOfDay(date.plusDays(1)));
    }
}
