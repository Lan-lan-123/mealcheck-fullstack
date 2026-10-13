package com.example.mealcheck.repository;

import com.example.mealcheck.util.AppTime;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.time.LocalDate;
import java.util.*;

@Repository
public class TrackingQueryRepository {
    public record MealCount(LocalDate day, String type, int count) { }
    public record Slot(LocalDate day, String type) { }
    private final JdbcTemplate jdbc;
    public TrackingQueryRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    public List<MealCount> meals(long userId, LocalDate from, LocalDate to) {
        return jdbc.query("""
            SELECT CAST(coalesce(occurred_at,created_at) AT TIME ZONE ? AT TIME ZONE ? AS date) AS day,
                   meal_type, count(*) AS total
            FROM meal_records WHERE user_id=? AND coalesce(occurred_at,created_at)>=? AND coalesce(occurred_at,created_at)<?
            GROUP BY 1,meal_type ORDER BY 1,meal_type
            """, (rs, n) -> new MealCount(rs.getDate("day").toLocalDate(), rs.getString("meal_type"), rs.getInt("total")),
            AppTime.storageZone().getId(), AppTime.businessZone().getId(), userId, AppTime.startOfDay(from), AppTime.startOfDay(to.plusDays(1)));
    }
    public Set<Slot> skipped(long userId, LocalDate from, LocalDate to) {
        return new HashSet<>(jdbc.query("SELECT business_day,meal_type FROM meal_slot_declarations WHERE user_id=? AND business_day BETWEEN ? AND ?",
            (rs, n) -> new Slot(rs.getDate(1).toLocalDate(), rs.getString(2)), userId, from, to));
    }
    public Map<String, Integer> plans(long userId, LocalDate from, LocalDate to) {
        Map<String, Integer> result = new LinkedHashMap<>(); for (String state : List.of("OPEN", "DONE", "SKIPPED")) result.put(state, 0);
        jdbc.query("SELECT status,count(*) FROM action_plans WHERE user_id=? AND planned_date BETWEEN ? AND ? GROUP BY status",
            (org.springframework.jdbc.core.RowCallbackHandler) rs -> result.put(rs.getString(1), rs.getInt(2)), userId, from, to);
        return result;
    }
}
