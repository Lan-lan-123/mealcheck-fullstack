package com.example.mealcheck.service;

import com.example.mealcheck.dto.AdminMealPageResponse;
import com.example.mealcheck.dto.AdminMealRecordResponse;
import com.example.mealcheck.dto.FoodItem;
import com.example.mealcheck.util.AppTime;
import com.example.mealcheck.util.Jsons;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** List queries never materialize MealRecord or its long analysis/advice fields. */
@Service
public class AdminMealQueryService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public AdminMealQueryService(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    public record CursorPage(List<AdminMealRecordResponse> content, boolean hasMore,
                             LocalDateTime nextBeforeTime, Long nextBeforeId, int size) { }

    public static LocalDateTime parseCursorTime(String value) {
        if (value == null) return null;
        try {
            return OffsetDateTime.parse(value).atZoneSameInstant(AppTime.storageZone()).toLocalDateTime();
        } catch (DateTimeParseException noOffset) {
            try { return LocalDateTime.parse(value); }
            catch (DateTimeParseException invalid) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "游标时间格式不正确");
            }
        }
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public AdminMealPageResponse page(int page, int size, String username, LocalDate from,
                                     LocalDate to, Integer minScore, Integer maxScore) {
        int safePage = Math.max(0, page);
        int safeSize = safeSize(size);
        Filter filter = filter(username, from, to, minScore, maxScore);
        long total = jdbc.queryForObject("SELECT count(*) FROM meal_records m" + filter.where(),
                Long.class, filter.args().toArray());
        List<Object> args = new ArrayList<>(filter.args());
        args.add(safeSize);
        args.add((long) safePage * safeSize);
        List<AdminMealRecordResponse> content = query(filter.where(), "LIMIT ? OFFSET ?", args);
        int totalPages = (int) Math.min(Integer.MAX_VALUE, total / safeSize + (total % safeSize == 0 ? 0 : 1));
        return new AdminMealPageResponse(content, total, totalPages, safePage, safeSize);
    }

    @Transactional(readOnly = true)
    public CursorPage cursor(int size, String username, LocalDate from, LocalDate to,
                             Integer minScore, Integer maxScore, LocalDateTime beforeTime, Long beforeId) {
        if ((beforeTime == null) != (beforeId == null) || (beforeId != null && beforeId <= 0)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "游标必须同时提供有效的时间和记录 ID");
        }
        int safeSize = safeSize(size);
        Filter filter = filter(username, from, to, minScore, maxScore);
        List<Object> args = new ArrayList<>(filter.args());
        String where = filter.where();
        if (beforeTime != null) {
            where += (where.isEmpty() ? " WHERE " : " AND ") + "(m.created_at,m.id) < (?,?)";
            args.add(beforeTime);
            args.add(beforeId);
        }
        args.add(safeSize + 1);
        List<AdminMealRecordResponse> rows = query(where, "LIMIT ?", args);
        boolean hasMore = rows.size() > safeSize;
        List<AdminMealRecordResponse> content = List.copyOf(rows.subList(0, Math.min(rows.size(), safeSize)));
        AdminMealRecordResponse last = hasMore ? content.get(content.size() - 1) : null;
        return new CursorPage(content, hasMore, last == null ? null : last.getCreatedAt(),
                last == null ? null : last.getId(), safeSize);
    }

    private List<AdminMealRecordResponse> query(String where, String pagination, List<Object> args) {
        // Bound the meal side before joining users, including for the legacy numbered-page API.
        String sql = """
                WITH meal_page AS MATERIALIZED (
                    SELECT m.id,m.user_id,m.score,m.summary,m.detected_foods_json,m.created_at
                    FROM meal_records m
                """ + where + " ORDER BY m.created_at DESC,m.id DESC " + pagination + """
                )
                SELECT p.id,COALESCE(u.username,'未知用户') AS username,p.score,p.summary,
                       p.detected_foods_json,p.created_at
                FROM meal_page p LEFT JOIN users u ON u.id=p.user_id
                ORDER BY p.created_at DESC,p.id DESC
                """;
        return jdbc.query(sql, (rs, n) -> new AdminMealRecordResponse(rs.getLong("id"),
                rs.getString("username"), rs.getObject("score", Integer.class), rs.getString("summary"),
                foodNames(rs.getString("detected_foods_json")), rs.getTimestamp("created_at").toLocalDateTime()),
                args.toArray());
    }

    private List<String> foodNames(String json) {
        if (json == null || json.isBlank()) return List.of();
        List<FoodItem> foods = Jsons.fromJson(mapper, json, new TypeReference<List<FoodItem>>() { });
        return foods == null ? List.of() : foods.stream().map(FoodItem::getName)
                .filter(name -> name != null && !name.isBlank()).toList();
    }

    private Filter filter(String username, LocalDate from, LocalDate to, Integer minScore, Integer maxScore) {
        List<String> predicates = new ArrayList<>();
        List<Object> args = new ArrayList<>();
        if (username != null && !username.isBlank()) {
            predicates.add("EXISTS (SELECT 1 FROM users u WHERE u.id=m.user_id AND lower(u.username) LIKE ?)");
            args.add("%" + username.trim().toLowerCase(Locale.ROOT) + "%");
        }
        if (from != null) { predicates.add("m.created_at>=?"); args.add(AppTime.startOfDay(from)); }
        if (to != null) { predicates.add("m.created_at<?"); args.add(AppTime.startOfDay(to.plusDays(1))); }
        if (minScore != null) { predicates.add("m.score>=?"); args.add(minScore); }
        if (maxScore != null) { predicates.add("m.score<=?"); args.add(maxScore); }
        return new Filter(predicates.isEmpty() ? "" : " WHERE " + String.join(" AND ", predicates), args);
    }

    private int safeSize(int size) { return Math.max(1, Math.min(size, 100)); }
    private record Filter(String where, List<Object> args) { }
}
