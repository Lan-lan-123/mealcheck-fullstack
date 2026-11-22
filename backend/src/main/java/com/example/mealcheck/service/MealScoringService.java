package com.example.mealcheck.service;

import com.example.mealcheck.dto.FoodItem;
import com.example.mealcheck.dto.RecognitionResult;
import com.example.mealcheck.dto.StructureEvaluation;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.stream.Collectors;

@Service
public class MealScoringService {
    public static final String VERSION = "structured-foods-v6";
    public static final double MIN_CONFIDENCE = 0.5;

    public StructureEvaluation evaluate(RecognitionResult recognition, String goal) {
        StructureEvaluation evaluation = new StructureEvaluation();

        List<FoodItem> foods = acceptedFoods(recognition);
        if (foods.isEmpty()) {
            throw new IllegalArgumentException("没有足够可信的食物识别结果，请重新拍摄清晰的餐食图片。");
        }

        Map<String, Integer> counts = new LinkedHashMap<>();
        for (FoodItem food : foods) {
            String category = normalize(food.getCategory());
            counts.put(category, counts.getOrDefault(category, 0) + 1);
        }
        evaluation.setCategoryCounts(counts);

        String foodText = buildFoodText(foods);
        String normalizedGoal = normalizeGoal(goal);

        boolean hasSoyMilk = foods.stream().filter(food -> !"dessert".equals(normalize(food.getCategory())))
                .anyMatch(food -> containsAny(food.getName().toLowerCase(Locale.ROOT), List.of(
                "豆浆", "热豆浆", "无糖豆浆", "原味豆浆", "soy milk", "soymilk"
        )));

        boolean hasMilk = foods.stream().anyMatch(food -> List.of(
                "牛奶", "纯牛奶", "鲜奶", "低脂牛奶", "无糖牛奶", "milk", "whole milk", "skim milk")
                .contains(food.getName().trim().toLowerCase(Locale.ROOT)));

        boolean hasPlainDrink = containsAny(foodText, List.of(
                "白水", "矿泉水", "纯净水", "无糖茶", "茶水", "黑咖啡", "无糖饮料", "无糖饮品"
        ));

        boolean hasSugaryDrink = foods.stream().anyMatch(food -> !isSugarFree(food.getName())
                && (containsAny(food.getName().toLowerCase(Locale.ROOT), List.of(
                "可乐", "cola", "汽水", "碳酸饮料", "含糖饮料", "含糖饮品", "甜饮",
                "奶茶", "果汁", "果味饮料", "乳酸菌饮料", "甜豆浆", "加糖豆浆", "糖水", "soda", "sweet drink"
        )) || ("drink".equals(normalize(food.getCategory())) && containsAny(food.getName().toLowerCase(Locale.ROOT), List.of(
                "橙汁", "苹果汁", "葡萄汁", "菠萝汁", "西瓜汁", "柠檬汁饮料",
                "orange juice", "apple juice", "grape juice", "pineapple juice"
        )))));

        boolean hasDessert = counts.getOrDefault("dessert", 0) > 0;

        boolean hasDrink = counts.getOrDefault("drink", 0) > 0
                || hasSoyMilk
                || hasMilk
                || hasPlainDrink
                || hasSugaryDrink
                || containsAny(foodText, List.of(
                "饮料", "饮品", "纸杯饮品", "杯装饮品", "杯装", "瓶装",
                "罐装", "易拉罐", "饮料罐", "黑色罐", "纸杯", "杯子"
        ));

        boolean hasUnknownDrink = hasDrink
                && !hasSoyMilk
                && !hasMilk
                && !hasPlainDrink
                && !hasSugaryDrink;

        boolean hasSugaryDrinkOrDessert = hasSugaryDrink || hasDessert;

        boolean hasStaple = counts.getOrDefault("staple", 0) > 0;
        boolean hasProtein = counts.getOrDefault("protein", 0) > 0
                || counts.getOrDefault("dairy", 0) > 0 || hasSoyMilk || hasMilk
                || foods.stream().anyMatch(food->FoodStructureRoles.has(food,"protein"));
        boolean hasVegetable = counts.getOrDefault("vegetable", 0) > 0;
        boolean hasFruit = counts.getOrDefault("fruit", 0) > 0;
        boolean hasSoup = counts.getOrDefault("soup", 0) > 0;

        boolean hasFriedOrHighOil = foods.stream().anyMatch(FoodClassification::hasMarkedRisk);
        boolean needsOilReview = foods.stream().anyMatch(FoodClassification::needsOilReview);
        boolean needsFryingReview = foods.stream().anyMatch(FoodClassification::needsFryingReview);

        var details = new java.util.ArrayList<com.example.mealcheck.dto.ScoreBreakdown.Line>();
        details.add(new com.example.mealcheck.dto.ScoreBreakdown.Line("base", "基础分", 50, List.of()));
        int score = 50;
        for (FoodItem food : foods) {
            for (var issue : food.getReviewIssues()) {
                addRisk(evaluation, issue.message());
                details.add(new com.example.mealcheck.dto.ScoreBreakdown.Line("semantic-review", issue.message(), 0, List.of(food.getName())));
            }
        }

        if (hasStaple) {
            score = trace(details, score, score + 8, "staple-0", "包含主食", foods, "staple");
            evaluation.getPositivePoints().add("包含主食，基础能量来源较明确");
        } else {
            score = trace(details, score, score - 8, "staple-1", "当前未识别到主食", foods, "staple");
            addRisk(evaluation, "当前未识别到主食");
        }

        if (hasProtein) {
            score = trace(details, score, score + 12, "protein-2", "包含蛋白质来源", foods, "protein");
            evaluation.getPositivePoints().add("包含蛋白质来源");
        } else {
            score = trace(details, score, score - 12, "protein-3", "当前未识别到蛋白质来源", foods, "protein");
            addRisk(evaluation, "当前未识别到蛋白质来源");
        }

        if (hasVegetable) {
            score = trace(details, score, score + 15, "vegetable-4", "包含蔬菜", foods, "vegetable");
            evaluation.getPositivePoints().add("包含蔬菜");
        } else {
            score = trace(details, score, score - 18, "vegetable-5", "当前未识别到蔬菜来源", foods, "vegetable");
            addRisk(evaluation, "当前未识别到蔬菜来源");
        }

        if (hasFruit) {
            score = trace(details, score, score + 3, "fruit-6", "包含水果", foods, "fruit");
            evaluation.getPositivePoints().add("包含水果或补充类食物");
        }

        if (hasSoup) {
            score = trace(details, score, score + 2, "soup-7", "包含汤类", foods, "soup");
            evaluation.getPositivePoints().add("包含汤粥类食物，餐食结构更丰富");
        }

        if (hasSoyMilk) {
            score = trace(details, score, score + 4, "drink-8", "豆浆加分", foods, "drink");
            evaluation.getPositivePoints().add("包含豆浆，可补充一定植物蛋白");
        } else if (hasMilk) {
            score = trace(details, score, score + 3, "drink-9", "牛奶加分", foods, "drink");
            evaluation.getPositivePoints().add("包含牛奶，饮品选择较合理");
        } else if (hasPlainDrink) {
            score = trace(details, score, score + 1, "drink-10", "清淡饮品加分", foods, "drink");
            evaluation.getPositivePoints().add("饮品选择较清淡");
        }

        if (foods.size() >= 4) {
            score = trace(details, score, score + 3, "variety-11", "食物达到四种", foods, "variety");
        }

        if (foods.size() <= 2) {
            score = trace(details, score, score - 7, "variety-12", "食物不超过两种", foods, "variety");
            addRisk(evaluation, "食物种类偏单一");
        }

        if (hasFriedOrHighOil) {
            score = trace(details, score, score - 18, "risk-13", "油炸或高油风险", foods, "risk");
            addRisk(evaluation, "油炸或高油风险");
        }
        if (needsOilReview) {
            addRisk(evaluation, "高油风险待核对，尚未确认实际用油量");
            details.add(new com.example.mealcheck.dto.ScoreBreakdown.Line("oil-review", "高油风险未知，暂不据此扣分", 0,
                    foods.stream().filter(FoodClassification::needsOilReview).map(FoodItem::getName).toList()));
        }
        if (needsFryingReview) {
            addRisk(evaluation, "油炸做法待核对，暂不据此扣分");
            details.add(new com.example.mealcheck.dto.ScoreBreakdown.Line("frying-review", "油炸做法未知，暂不据此扣分", 0,
                    foods.stream().filter(FoodClassification::needsFryingReview).map(FoodItem::getName).toList()));
        }

        if (hasSugaryDrinkOrDessert) {
            score = trace(details, score, score - 18, "sweet-14", "含糖饮料或甜品", foods, "sweet");
            addRisk(evaluation, "含糖饮料/甜品风险");
        }

        if (hasUnknownDrink) {
            score = trace(details, score, score - 3, "drink-15", "饮品类型未知", foods, "drink");
            addRisk(evaluation, "饮品类型不够明确，建议优先选择白水、豆浆、牛奶或无糖饮品");
        }

        switch (normalizedGoal) {
            case "fat_loss" -> {
                if (hasVegetable) {
                    score = trace(details, score, score + 2, "vegetable-16", "减脂目标：蔬菜", foods, "vegetable");
                }

                if (hasProtein) {
                    score = trace(details, score, score + 2, "protein-17", "减脂目标：蛋白质", foods, "protein");
                }

                if (hasSoyMilk || hasMilk || hasPlainDrink) {
                    score = trace(details, score, score + 1, "drink-18", "减脂目标：饮品", foods, "drink");
                }

                if (hasFriedOrHighOil) {
                    score = trace(details, score, score - 12, "risk-19", "减脂目标：油炸或高油", foods, "risk");
                    addRisk(evaluation, "减脂目标下需减少油炸食物");
                }

                if (hasSugaryDrinkOrDessert) {
                    score = trace(details, score, score - 12, "sweet-20", "减脂目标：含糖饮料或甜品", foods, "sweet");
                    addRisk(evaluation, "减脂目标下需避免含糖饮料或甜品");
                }

                if (hasStaple && hasFriedOrHighOil) {
                    score = trace(details, score, score - 4, "risk-21", "减脂目标：主食与高油组合", foods, "risk");
                    addRisk(evaluation, "减脂目标下需控制主食与高油食物组合");
                }
            }

            case "muscle_gain" -> {
                if (hasProtein) {
                    score = trace(details, score, score + 5, "protein-22", "增肌目标：蛋白质", foods, "protein");
                    evaluation.getPositivePoints().add("增肌目标下蛋白质来源较明确");
                } else {
                    score = trace(details, score, score - 18, "protein-23", "增肌目标：缺少蛋白质", foods, "protein");
                    addRisk(evaluation, "增肌目标下当前未识别到蛋白质来源");
                }

                if (hasStaple) {
                    score = trace(details, score, score + 2, "staple-24", "增肌目标：主食", foods, "staple");
                    evaluation.getPositivePoints().add("增肌目标下包含主食，有助于能量补充");
                }

                if (hasSoyMilk || hasMilk) {
                    score = trace(details, score, score + 2, "drink-25", "增肌目标：豆浆或牛奶", foods, "drink");
                    evaluation.getPositivePoints().add("增肌目标下饮品可提供一定蛋白补充");
                }

                if (hasFriedOrHighOil) {
                    score = trace(details, score, score - 8, "risk-26", "增肌目标：油炸或高油", foods, "risk");
                    addRisk(evaluation, "增肌目标下建议优先选择少油蛋白质");
                }

                if (hasSugaryDrinkOrDessert) {
                    score = trace(details, score, score - 8, "sweet-27", "增肌目标：含糖饮料或甜品", foods, "sweet");
                    addRisk(evaluation, "增肌目标下含糖饮料或甜品不利于饮食质量");
                }
            }

            case "light" -> {
                if (hasVegetable) {
                    score = trace(details, score, score + 2, "vegetable-28", "清淡目标：蔬菜", foods, "vegetable");
                }

                if (hasSoup) {
                    score = trace(details, score, score + 2, "soup-29", "清淡目标：汤类", foods, "soup");
                }

                if (hasSoyMilk || hasMilk || hasPlainDrink) {
                    score = trace(details, score, score + 2, "drink-30", "清淡目标：饮品", foods, "drink");
                }

                if (hasFriedOrHighOil) {
                    score = trace(details, score, score - 16, "risk-31", "清淡目标：油炸或高油", foods, "risk");
                    addRisk(evaluation, "清淡饮食目标下不建议选择油炸食物");
                }

                if (hasSugaryDrinkOrDessert) {
                    score = trace(details, score, score - 10, "sweet-32", "清淡目标：含糖饮料或甜品", foods, "sweet");
                    addRisk(evaluation, "清淡饮食目标下建议减少含糖饮料或甜品");
                }
            }

            case "balanced" -> {
                if (hasStaple && hasProtein && hasVegetable && !hasFriedOrHighOil && !hasSugaryDrinkOrDessert) {
                    score = trace(details, score, score + 5, "structure-33", "均衡目标：结构完整且无已标记风险", foods, "structure");
                    evaluation.getPositivePoints().add("主食、蛋白质和蔬菜搭配较完整");
                }

                if ((hasSoyMilk || hasMilk || hasPlainDrink) && !hasSugaryDrinkOrDessert) {
                    score = trace(details, score, score + 1, "drink-34", "均衡目标：清淡饮品", foods, "drink");
                    evaluation.getPositivePoints().add("饮品选择整体较合理");
                }

                if (hasFriedOrHighOil || hasSugaryDrinkOrDessert) {
                    addRisk(evaluation, hasStaple && hasProtein && hasVegetable
                            ? "整体结构虽完整，但饮食质量受油炸或含糖饮料影响"
                            : "饮食质量受油炸或含糖饮料影响，仍需完善餐食结构");
                }
            }

            default -> {
                if (hasStaple && hasProtein && hasVegetable) {
                    score = trace(details, score, score + 1, "structure-35", "其他目标：结构完整", foods, "structure");
                }
            }
        }

        // 风险封顶规则：防止“主食 + 蛋白质 + 蔬菜都有”但同时有炸鸡、可乐时仍然接近满分。
        if (hasFriedOrHighOil && hasSugaryDrinkOrDessert) {
            addRisk(evaluation, "油炸食物与含糖饮料组合风险");

            switch (normalizedGoal) {
                case "fat_loss" -> score = trace(details, score, Math.min(score, 58), "cap-36", "油炸与含糖风险组合封顶", foods, "cap");
                case "light" -> score = trace(details, score, Math.min(score, 58), "cap-37", "油炸与含糖风险组合封顶", foods, "cap");
                case "muscle_gain" -> score = trace(details, score, Math.min(score, 75), "cap-38", "油炸与含糖风险组合封顶", foods, "cap");
                default -> score = trace(details, score, Math.min(score, 68), "cap-39", "油炸与含糖风险组合封顶", foods, "cap");
            }
        } else if (hasFriedOrHighOil) {
            switch (normalizedGoal) {
                case "fat_loss" -> score = trace(details, score, Math.min(score, 68), "cap-40", "油炸或高油风险封顶", foods, "cap");
                case "light" -> score = trace(details, score, Math.min(score, 65), "cap-41", "油炸或高油风险封顶", foods, "cap");
                case "muscle_gain" -> score = trace(details, score, Math.min(score, 82), "cap-42", "油炸或高油风险封顶", foods, "cap");
                default -> score = trace(details, score, Math.min(score, 78), "cap-43", "油炸或高油风险封顶", foods, "cap");
            }
        } else if (hasSugaryDrinkOrDessert) {
            switch (normalizedGoal) {
                case "fat_loss" -> score = trace(details, score, Math.min(score, 70), "cap-44", "含糖饮料或甜品风险封顶", foods, "cap");
                case "light" -> score = trace(details, score, Math.min(score, 72), "cap-45", "含糖饮料或甜品风险封顶", foods, "cap");
                case "muscle_gain" -> score = trace(details, score, Math.min(score, 84), "cap-46", "含糖饮料或甜品风险封顶", foods, "cap");
                default -> score = trace(details, score, Math.min(score, 82), "cap-47", "含糖饮料或甜品风险封顶", foods, "cap");
            }
        }

        // 早餐友好修正：包子/粥/鸡蛋/蔬菜/豆浆这类组合不应被误判为高糖饮料风险。
        boolean looksLikeBalancedBreakfast = hasStaple
                && hasProtein
                && (hasVegetable || hasFruit)
                && (hasSoyMilk || hasMilk || hasPlainDrink || hasSoup)
                && !hasFriedOrHighOil
                && !hasSugaryDrinkOrDessert;

        if (looksLikeBalancedBreakfast) {
            switch (normalizedGoal) {
                case "fat_loss" -> score = trace(details, score, Math.max(score, 82), "floor-48", "清淡食物搭配保底修正", foods, "floor");
                case "muscle_gain" -> score = trace(details, score, Math.max(score, 84), "floor-49", "清淡食物搭配保底修正", foods, "floor");
                case "light" -> score = trace(details, score, Math.max(score, 84), "floor-50", "清淡食物搭配保底修正", foods, "floor");
                default -> score = trace(details, score, Math.max(score, 86), "floor-51", "清淡食物搭配保底修正", foods, "floor");
            }
        }

        score = trace(details, score, Math.max(0, Math.min(100, score)), "range-52", "最终分数限制在 0–100", foods, "range");
        evaluation.setScore(score);
        evaluation.setScoreBreakdown(new com.example.mealcheck.dto.ScoreBreakdown(VERSION, score, List.copyOf(details)));
        evaluation.setSummary(makeSummary(
                score,
                evaluation.getRiskTags(),
                evaluation.getPositivePoints(),
                normalizedGoal,
                hasSoyMilk,
                hasMilk,
                hasPlainDrink,
                hasUnknownDrink,
                hasSugaryDrinkOrDessert
        ));

        return evaluation;
    }

    private int trace(List<com.example.mealcheck.dto.ScoreBreakdown.Line> lines, int before, int after,
                      String rule, String label, List<FoodItem> foods, String source) {
        if (before != after) {
            var names = foods.stream().filter(food -> switch (source) {
                case "staple", "vegetable", "fruit", "soup" -> source.equals(food.getCategory());
                case "protein" -> FoodStructureRoles.has(food,"protein") || List.of("protein", "dairy").contains(food.getCategory()) || food.getName().contains("豆浆");
                case "risk" -> FoodClassification.hasMarkedRisk(food);
                case "drink" -> List.of("drink", "dairy").contains(food.getCategory());
                case "sweet" -> List.of("drink", "dessert").contains(food.getCategory());
                default -> true;
            }).map(FoodItem::getName).toList();
            lines.add(new com.example.mealcheck.dto.ScoreBreakdown.Line(rule, label, after - before, names));
        }
        return after;
    }

    private String buildFoodText(List<FoodItem> foods) {
        // Scene summaries and notes can contain negations or advice, not observed foods.
        return foods.stream().map(FoodItem::getName)
                .collect(Collectors.joining(" ")).toLowerCase(Locale.ROOT);
    }

    public List<FoodItem> acceptedFoods(RecognitionResult recognition) {
        Map<String, FoodItem> unique = new LinkedHashMap<>();
        if (recognition != null && recognition.isFoodImage() && recognition.getFoods() != null) {
            for (FoodItem food : recognition.getFoods()) {
                if (food == null || food.getName() == null || food.getName().isBlank()
                        || "UNKNOWN".equals(food.getIdentityEvidence())
                        || !Double.isFinite(food.getConfidence())
                        || food.getConfidence() < MIN_CONFIDENCE || food.getConfidence() > 1.0) continue;
                String key = food.getName().trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
                var normalized=FoodClassification.normalized(food);
                normalized.setStructureRoles(FoodStructureRoles.evidence(normalized));
                unique.merge(key, normalized, (a, b) -> a.getConfidence() >= b.getConfidence() ? a : b);
            }
        }
        return List.copyOf(unique.values());
    }

    public static List<FoodItem> uncertainFoods(List<FoodItem> foods) {
        if (foods == null) return List.of();
        return foods.stream().filter(food -> food != null && food.getName() != null && !food.getName().isBlank()
                && food.getCategory() != null && !food.getCategory().isBlank() && Double.isFinite(food.getConfidence())
                && food.getConfidence() >= 0 && food.getConfidence() <= 1
                && (food.getConfidence() < MIN_CONFIDENCE || "UNKNOWN".equals(food.getIdentityEvidence()))).toList();
    }

    private boolean isSugarFree(String name) {
        return containsAny(name.toLowerCase(Locale.ROOT), List.of(
                "无糖", "零糖", "0糖", "零度", "sugar-free", "sugar free", "zero sugar", "diet cola"));
    }

    private boolean containsAny(String text, List<String> keywords) {
        if (text == null || text.isBlank()) {
            return false;
        }

        return keywords.stream().anyMatch(text::contains);
    }

    private void addRisk(StructureEvaluation evaluation, String risk) {
        if (risk == null || risk.isBlank()) {
            return;
        }

        if (!evaluation.getRiskTags().contains(risk)) {
            evaluation.getRiskTags().add(risk);
        }
    }

    private String normalize(String category) {
        if (category == null || category.isBlank()) {
            return "other";
        }

        String c = category.trim().toLowerCase();
        List<String> allowed = List.of(
                "staple",
                "protein",
                "vegetable",
                "fruit",
                "dairy",
                "soup",
                "drink",
                "dessert",
                "fried",
                "oily",
                "other"
        );

        return allowed.contains(c) ? c : "other";
    }

    private String normalizeGoal(String goal) {
        if (goal == null || goal.isBlank()) {
            return "balanced";
        }

        return goal.trim().toLowerCase();
    }

    private String makeSummary(
            int score,
            List<String> risks,
            List<String> positivePoints,
            String goal,
            boolean hasSoyMilk,
            boolean hasMilk,
            boolean hasPlainDrink,
            boolean hasUnknownDrink,
            boolean hasSugaryDrinkOrDessert
    ) {
        String goalText = switch (goal) {
            case "fat_loss" -> "减脂目标";
            case "muscle_gain" -> "增肌目标";
            case "light" -> "清淡饮食目标";
            default -> "均衡饮食目标";
        };

        String drinkText = "";
        if (hasSoyMilk) {
            drinkText = "识别到豆浆，可补充一定植物蛋白。";
        } else if (hasMilk) {
            drinkText = "识别到牛奶，可补充一定蛋白质。";
        } else if (hasPlainDrink) {
            drinkText = "饮品选择较清淡。";
        } else if (hasUnknownDrink && !hasSugaryDrinkOrDessert) {
            drinkText = "杯装饮品类型无法完全确认，建议优先选择豆浆、牛奶、白水或无糖饮品。";
        }

        String riskText = formatRisks(risks);
        String positiveText = formatPositivePoints(positivePoints);

        if (score >= 90) {
            return "本餐在" + goalText + "下整体表现较好，主食、蛋白质和蔬菜搭配较完整。"
                    + (risks.isEmpty() ? "" : "仍需关注：" + riskText + "。") + drinkText;
        }

        if (score >= 80) {
            if (risks == null || risks.isEmpty()) {
                return "本餐在" + goalText + "下结构基本合理，" + positiveText + "。"
                        + drinkText;
            }

            return "本餐在" + goalText + "下结构基本合理，但仍有少量可优化项："
                    + riskText + "。" + drinkText;
        }

        if (score >= 65) {
            return "本餐在" + goalText + "下存在一定风险，建议关注："
                    + riskText + "。" + drinkText;
        }

        return "本餐在" + goalText + "下结构评分较低，建议关注：" + riskText + "。" + drinkText;
    }

    private String formatRisks(List<String> risks) {
        if (risks == null || risks.isEmpty()) {
            return "暂无明显风险";
        }

        return String.join("、", risks);
    }

    private String formatPositivePoints(List<String> positivePoints) {
        if (positivePoints == null || positivePoints.isEmpty()) {
            return "暂无明显结构优势";
        }

        return String.join("、", positivePoints);
    }
}
