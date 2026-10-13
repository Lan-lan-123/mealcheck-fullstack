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
    public static final String VERSION = "structured-foods-v2";

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
                && containsAny(food.getName().toLowerCase(Locale.ROOT), List.of(
                "可乐", "cola", "汽水", "碳酸饮料", "含糖饮料", "含糖饮品", "甜饮",
                "奶茶", "果汁", "果味饮料", "乳酸菌饮料", "甜豆浆", "加糖豆浆",
                "糖水", "soda", "sweet drink"
        )));

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
                || counts.getOrDefault("dairy", 0) > 0 || hasSoyMilk || hasMilk;
        boolean hasVegetable = counts.getOrDefault("vegetable", 0) > 0;
        boolean hasFruit = counts.getOrDefault("fruit", 0) > 0;
        boolean hasSoup = counts.getOrDefault("soup", 0) > 0;

        boolean hasFriedOrHighOil = foods.stream().anyMatch(food -> containsAny(food.getName().toLowerCase(Locale.ROOT),
                List.of("非油炸", "非油煎", "not fried", "non-fried", "non fried"))
                ? false : (List.of("fried", "oily").contains(normalize(food.getCategory()))
                || containsAny(food.getName().toLowerCase(Locale.ROOT), List.of(
                "炸", "油炸", "炸鸡", "炸鸡排", "炸猪排", "炸排", "酥脆", "脆皮", "煎炸", "裹粉",
                "面包糠", "薯条", "高油", "重油", "油脂", "油腻",
                "fried", "crispy"
        ))));

        int score = 50;

        if (hasStaple) {
            score += 8;
            evaluation.getPositivePoints().add("包含主食，基础能量来源较明确");
        } else {
            score -= 8;
            addRisk(evaluation, "缺少主食");
        }

        if (hasProtein) {
            score += 12;
            evaluation.getPositivePoints().add("包含蛋白质来源");
        } else {
            score -= 12;
            addRisk(evaluation, "蛋白质不足");
        }

        if (hasVegetable) {
            score += 15;
            evaluation.getPositivePoints().add("包含蔬菜");
        } else {
            score -= 18;
            addRisk(evaluation, "蔬菜不足");
        }

        if (hasFruit) {
            score += 3;
            evaluation.getPositivePoints().add("包含水果或补充类食物");
        }

        if (hasSoup) {
            score += 2;
            evaluation.getPositivePoints().add("包含汤粥类食物，餐食结构更丰富");
        }

        if (hasSoyMilk) {
            score += 4;
            evaluation.getPositivePoints().add("包含豆浆，早餐饮品选择较合理，可补充一定植物蛋白");
        } else if (hasMilk) {
            score += 3;
            evaluation.getPositivePoints().add("包含牛奶，饮品选择较合理");
        } else if (hasPlainDrink) {
            score += 1;
            evaluation.getPositivePoints().add("饮品选择较清淡");
        }

        if (foods.size() >= 4) {
            score += 3;
        }

        if (foods.size() <= 2) {
            score -= 7;
            addRisk(evaluation, "食物种类偏单一");
        }

        if (hasFriedOrHighOil) {
            score -= 18;
            addRisk(evaluation, "油炸或高油风险");
        }

        if (hasSugaryDrinkOrDessert) {
            score -= 18;
            addRisk(evaluation, "含糖饮料/甜品风险");
        }

        if (hasUnknownDrink) {
            score -= 3;
            addRisk(evaluation, "饮品类型不够明确，建议优先选择白水、豆浆、牛奶或无糖饮品");
        }

        switch (normalizedGoal) {
            case "fat_loss" -> {
                if (hasVegetable) {
                    score += 2;
                }

                if (hasProtein) {
                    score += 2;
                }

                if (hasSoyMilk || hasMilk || hasPlainDrink) {
                    score += 1;
                }

                if (hasFriedOrHighOil) {
                    score -= 12;
                    addRisk(evaluation, "减脂目标下需减少油炸食物");
                }

                if (hasSugaryDrinkOrDessert) {
                    score -= 12;
                    addRisk(evaluation, "减脂目标下需避免含糖饮料或甜品");
                }

                if (hasStaple && hasFriedOrHighOil) {
                    score -= 4;
                    addRisk(evaluation, "减脂目标下需控制主食与高油食物组合");
                }
            }

            case "muscle_gain" -> {
                if (hasProtein) {
                    score += 5;
                    evaluation.getPositivePoints().add("增肌目标下蛋白质来源较明确");
                } else {
                    score -= 18;
                    addRisk(evaluation, "增肌目标下蛋白质不足");
                }

                if (hasStaple) {
                    score += 2;
                    evaluation.getPositivePoints().add("增肌目标下包含主食，有助于能量补充");
                }

                if (hasSoyMilk || hasMilk) {
                    score += 2;
                    evaluation.getPositivePoints().add("增肌目标下饮品可提供一定蛋白补充");
                }

                if (hasFriedOrHighOil) {
                    score -= 8;
                    addRisk(evaluation, "增肌目标下建议优先选择少油蛋白质");
                }

                if (hasSugaryDrinkOrDessert) {
                    score -= 8;
                    addRisk(evaluation, "增肌目标下含糖饮料或甜品不利于饮食质量");
                }
            }

            case "light" -> {
                if (hasVegetable) {
                    score += 2;
                }

                if (hasSoup) {
                    score += 2;
                }

                if (hasSoyMilk || hasMilk || hasPlainDrink) {
                    score += 2;
                }

                if (hasFriedOrHighOil) {
                    score -= 16;
                    addRisk(evaluation, "清淡饮食目标下不建议选择油炸食物");
                }

                if (hasSugaryDrinkOrDessert) {
                    score -= 10;
                    addRisk(evaluation, "清淡饮食目标下建议减少含糖饮料或甜品");
                }
            }

            case "balanced" -> {
                if (hasStaple && hasProtein && hasVegetable && !hasFriedOrHighOil && !hasSugaryDrinkOrDessert) {
                    score += 5;
                    evaluation.getPositivePoints().add("主食、蛋白质和蔬菜搭配较完整");
                }

                if ((hasSoyMilk || hasMilk || hasPlainDrink) && !hasSugaryDrinkOrDessert) {
                    score += 1;
                    evaluation.getPositivePoints().add("饮品选择整体较合理");
                }

                if (hasFriedOrHighOil || hasSugaryDrinkOrDessert) {
                    addRisk(evaluation, "整体结构虽完整，但饮食质量受油炸或含糖饮料影响");
                }
            }

            default -> {
                if (hasStaple && hasProtein && hasVegetable) {
                    score += 1;
                }
            }
        }

        // 风险封顶规则：防止“主食 + 蛋白质 + 蔬菜都有”但同时有炸鸡、可乐时仍然接近满分。
        if (hasFriedOrHighOil && hasSugaryDrinkOrDessert) {
            addRisk(evaluation, "油炸食物与含糖饮料组合风险");

            switch (normalizedGoal) {
                case "fat_loss" -> score = Math.min(score, 58);
                case "light" -> score = Math.min(score, 58);
                case "muscle_gain" -> score = Math.min(score, 75);
                default -> score = Math.min(score, 68);
            }
        } else if (hasFriedOrHighOil) {
            switch (normalizedGoal) {
                case "fat_loss" -> score = Math.min(score, 68);
                case "light" -> score = Math.min(score, 65);
                case "muscle_gain" -> score = Math.min(score, 82);
                default -> score = Math.min(score, 78);
            }
        } else if (hasSugaryDrinkOrDessert) {
            switch (normalizedGoal) {
                case "fat_loss" -> score = Math.min(score, 70);
                case "light" -> score = Math.min(score, 72);
                case "muscle_gain" -> score = Math.min(score, 84);
                default -> score = Math.min(score, 82);
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
                case "fat_loss" -> score = Math.max(score, 82);
                case "muscle_gain" -> score = Math.max(score, 84);
                case "light" -> score = Math.max(score, 84);
                default -> score = Math.max(score, 86);
            }
        }

        score = Math.max(0, Math.min(100, score));
        evaluation.setScore(score);
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
                        || !Double.isFinite(food.getConfidence())
                        || food.getConfidence() < 0.5 || food.getConfidence() > 1.0) continue;
                String key = food.getName().trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
                unique.merge(key, food, (a, b) -> a.getConfidence() >= b.getConfidence() ? a : b);
            }
        }
        return List.copyOf(unique.values());
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
            drinkText = "饮品更接近豆浆，属于较常见的早餐搭配，可补充一定植物蛋白。";
        } else if (hasMilk) {
            drinkText = "饮品更接近牛奶，属于较合理的早餐饮品选择。";
        } else if (hasPlainDrink) {
            drinkText = "饮品选择较清淡。";
        } else if (hasUnknownDrink && !hasSugaryDrinkOrDessert) {
            drinkText = "杯装饮品类型无法完全确认，建议优先选择豆浆、牛奶、白水或无糖饮品。";
        }

        String riskText = formatRisks(risks);
        String positiveText = formatPositivePoints(positivePoints);

        if (score >= 90) {
            return "本餐在" + goalText + "下整体表现较好，主食、蛋白质和蔬菜搭配较完整。"
                    + drinkText;
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

        return "本餐在" + goalText + "下饮食质量一般，建议优先减少油炸、高油或含糖饮料，并补充优质蛋白和蔬菜。"
                + drinkText;
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
