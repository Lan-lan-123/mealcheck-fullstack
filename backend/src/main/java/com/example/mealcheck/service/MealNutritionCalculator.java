package com.example.mealcheck.service;

import com.example.mealcheck.dto.FoodItem;
import com.example.mealcheck.dto.MealNutritionDtos.*;
import org.springframework.stereotype.Component;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

@Component
public class MealNutritionCalculator {
    public static final String VERSION = "user-supplied-per100g-v1";
    public static final String WARNING = "营养估算不准确，图片无法准确判断重量、配料和用油量；即使填写份量，结果仍可能存在较大误差，仅供参考。";

    public Response empty(long id, long revision, long version, List<FoodItem> foods, boolean enabled, String status) {
        var items = new ArrayList<Item>();
        for (int i = 0; i < foods.size(); i++) {
            var food = foods.get(i);
            items.add(new Item(i, food.getName(), food.getCategory(), null, null, null, null, null));
        }
        return new Response(id, revision, version, enabled, status, VERSION, WARNING, List.copyOf(items), 0, null);
    }

    public Response calculate(long id, long revision, long version, List<FoodItem> foods, List<Input> inputs) {
        if (foods.isEmpty() || inputs.size() != foods.size()) {
            throw new IllegalArgumentException("请为当前每项食物提交份量信息，未知值可留空。");
        }
        var byIndex = new Input[foods.size()];
        for (var input : inputs) {
            if (input.foodIndex() < 0 || input.foodIndex() >= foods.size() || byIndex[input.foodIndex()] != null) {
                throw new IllegalArgumentException("食物项重复或与当前记录不匹配，请刷新后重试。");
            }
            var nutrients = input.per100g();
            if (hasData(nutrients) && (input.source() == null || input.source().isBlank())) {
                throw new IllegalArgumentException("填写营养数据时，请注明标签或资料来源。");
            }
            if (complete(nutrients) && nutrients.proteinGrams().add(nutrients.fatGrams())
                    .add(nutrients.carbohydrateGrams()).compareTo(BigDecimal.valueOf(100)) > 0) {
                throw new IllegalArgumentException("每 100 克的蛋白质、脂肪和碳水合计不能超过 100 克。");
            }
            byIndex[input.foodIndex()] = input;
        }
        var items = new ArrayList<Item>();
        var total = new Nutrients(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
        int estimated = 0;
        for (int i = 0; i < foods.size(); i++) {
            var food = foods.get(i);
            var input = byIndex[i];
            // Missing values remain unknown. Never infer portions or nutrients from an image/category.
            var estimate = input.grams() != null && complete(input.per100g())
                    ? scale(input.per100g(), input.grams().movePointLeft(2)) : null;
            if (estimate != null) {
                estimated++;
                // Sum unrounded values; round only the final total for presentation.
                total = add(total, estimate);
            }
            items.add(new Item(i, food.getName(), food.getCategory(), input.grams(), input.per100g(),
                    input.source() == null || input.source().isBlank() ? null : input.source().trim(),
                    input.grams() == null ? null : "USER_ENTERED", estimate == null ? null : rounded(estimate)));
        }
        boolean allKnown = estimated == foods.size();
        return new Response(id, revision, version, true, allKnown ? "ESTIMATED" : "INCOMPLETE", VERSION,
                WARNING, List.copyOf(items), estimated, allKnown ? rounded(total) : null);
    }

    private boolean hasData(Nutrients n) {
        return n != null && (n.energyKcal() != null || n.proteinGrams() != null || n.fatGrams() != null || n.carbohydrateGrams() != null);
    }
    private boolean complete(Nutrients n) {
        return n != null && n.energyKcal() != null && n.proteinGrams() != null && n.fatGrams() != null && n.carbohydrateGrams() != null;
    }
    private Nutrients scale(Nutrients n, BigDecimal factor) {
        return new Nutrients(n.energyKcal().multiply(factor), n.proteinGrams().multiply(factor),
                n.fatGrams().multiply(factor), n.carbohydrateGrams().multiply(factor));
    }
    private Nutrients add(Nutrients a, Nutrients b) {
        return new Nutrients(a.energyKcal().add(b.energyKcal()), a.proteinGrams().add(b.proteinGrams()),
                a.fatGrams().add(b.fatGrams()), a.carbohydrateGrams().add(b.carbohydrateGrams()));
    }
    private Nutrients rounded(Nutrients n) {
        return new Nutrients(round(n.energyKcal()), round(n.proteinGrams()), round(n.fatGrams()), round(n.carbohydrateGrams()));
    }
    private BigDecimal round(BigDecimal value) { return value.setScale(1, RoundingMode.HALF_UP); }
}
