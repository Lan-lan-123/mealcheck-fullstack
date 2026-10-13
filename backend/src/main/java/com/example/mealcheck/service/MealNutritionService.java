package com.example.mealcheck.service;

import com.example.mealcheck.dto.FoodItem;
import com.example.mealcheck.dto.MealNutritionDtos.*;
import com.example.mealcheck.entity.MealRecord;
import com.example.mealcheck.repository.MealRecordRepository;
import com.example.mealcheck.repository.UserAccountRepository;
import com.example.mealcheck.security.UserPrincipal;
import com.example.mealcheck.util.Jsons;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import java.util.List;

@Service
public class MealNutritionService {
    private final MealRecordRepository meals;
    private final UserAccountRepository users;
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final MealNutritionCalculator calculator;

    public MealNutritionService(MealRecordRepository meals, UserAccountRepository users, JdbcTemplate jdbc,
                                ObjectMapper mapper, MealNutritionCalculator calculator) {
        this.meals = meals; this.users = users; this.jdbc = jdbc; this.mapper = mapper; this.calculator = calculator;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Response get(UserPrincipal principal, long id) {
        var meal = owned(principal, id);
        var saved = saved(id);
        if (saved == null) return calculator.empty(id, meal.getRevision(), 0, foods(meal), false, "DISABLED");
        if (!saved.enabled()) return calculator.empty(id, meal.getRevision(), saved.version(), foods(meal), false, "DISABLED");
        if (saved.recordRevision() != meal.getRevision()) {
            // Food corrections make every previous portion/match untrusted; return no old values.
            return calculator.empty(id, meal.getRevision(), saved.version(), foods(meal), true, "STALE");
        }
        return saved;
    }

    @Transactional
    public Response update(UserPrincipal principal, long id, Update request) {
        // Share the account lock with correction, deletion and upload transactions.
        users.findByIdForUpdate(principal.getId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "用户不存在"));
        var meal = owned(principal, id);
        var saved = saved(id);
        long version = saved == null ? 0 : saved.version();
        if (request.expectedRevision() != meal.getRevision() || request.expectedVersion() != version) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "餐食或份量已被修改，请刷新后重新填写。");
        }
        var response = request.enabled()
                ? calculator.calculate(id, meal.getRevision(), version + 1, foods(meal), request.items())
                : calculator.empty(id, meal.getRevision(), version + 1, List.of(), false, "DISABLED");
        // Disabling clears inputs and estimates but retains a version to reject late writes.
        jdbc.update("""
                INSERT INTO meal_nutrition_snapshots(record_id,snapshot_json,updated_at)
                VALUES (?,?,CURRENT_TIMESTAMP)
                ON CONFLICT(record_id) DO UPDATE SET snapshot_json=EXCLUDED.snapshot_json,updated_at=CURRENT_TIMESTAMP
                """, id, Jsons.toJson(mapper, response));
        return request.enabled() ? response : calculator.empty(id, meal.getRevision(), response.version(), foods(meal), false, "DISABLED");
    }

    private MealRecord owned(UserPrincipal principal, long id) {
        return meals.findById(id).filter(meal -> meal.getUser().getId().equals(principal.getId()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "饮食记录不存在"));
    }
    private Response saved(long id) {
        var rows = jdbc.query("SELECT snapshot_json FROM meal_nutrition_snapshots WHERE record_id=?",
                (rs, row) -> Jsons.fromJson(mapper, rs.getString(1), Response.class), id);
        return rows.isEmpty() ? null : rows.get(0);
    }
    private List<FoodItem> foods(MealRecord meal) {
        return meal.getDetectedFoodsJson() == null ? List.of()
                : Jsons.fromJson(mapper, meal.getDetectedFoodsJson(), new TypeReference<List<FoodItem>>() { });
    }
}
