package com.example.mealcheck.service;

import com.example.mealcheck.dto.*;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

class MealScoringServiceTest {
    @Test
    void breakdownReconcilesEveryGoalIncludingRiskCapsAndZeroClamp() {
        for (String goal : List.of("balanced", "fat_loss", "muscle_gain", "light", "other")) {
            var complete = meal();
            for (int stage = 0; stage < 3; stage++) {
                if (stage == 1) complete.getFoods().add(new FoodItem("炸鸡", "fried", .95, ""));
                if (stage == 2) complete.getFoods().add(new FoodItem("可乐", "drink", .95, ""));
                var result = service.evaluate(complete, goal);
                assertThat(result.getScoreBreakdown().items().stream().mapToInt(line -> line.points()).sum()).isEqualTo(result.getScore());
                assertThat(result.getScoreBreakdown().total()).isEqualTo(result.getScore());
                assertThat(result.getScoreBreakdown().scoringVersion()).isEqualTo(MealScoringService.VERSION);
                if (stage > 0) assertThat(result.getScoreBreakdown().items()).anySatisfy(line -> {
                    assertThat(line.label()).contains("油炸"); assertThat(line.foods()).contains("炸鸡");
                });
            }
        }
        var sparse = new RecognitionResult();
        sparse.setFoods(List.of(new FoodItem("巧克力蛋糕", "dessert", 1, "")));
        var zero = service.evaluate(sparse, "fat_loss");
        assertThat(zero.getScore()).isZero();
        assertThat(zero.getScoreBreakdown().items()).anyMatch(line -> line.rule().startsWith("range") && line.points() > 0);
        assertThat(zero.getScoreBreakdown().items().stream().mapToInt(line -> line.points()).sum()).isZero();
    }
    private final MealScoringService service = new MealScoringService();

    @Test void stirFriedNamesHaveTheSameScoreAsChineseStirFriedVegetables() {
        int expected = service.evaluate(meal(), "balanced").getScore();
        for (String name : List.of("清炒小白菜", "stir-fried bok choy", "Stir Fried Bok Choy", "stir–fried bok choy")) {
            var input = meal();
            input.getFoods().get(2).setName(name);
            var actual = service.evaluate(input, "balanced");
            assertThat(actual.getScore()).as(name).isEqualTo(expected).isEqualTo(90);
            assertThat(actual.getRiskTags()).as(name).doesNotContain("油炸或高油风险");
        }
    }

    @Test void removingStirFriedOrNegatedPhrasesCannotHideAnotherFriedFood() {
        for (String name : List.of("炸鸡", "deep-fried chicken", "fried chicken", "pan-fried chicken",
                "stir-fried rice with fried chicken", "non-fried rice with deep-fried chicken", "非油炸米饭配炸鸡")) {
            var input = meal();
            input.getFoods().get(1).setName(name);
            assertThat(service.evaluate(input, "balanced").getRiskTags()).as(name).contains("油炸或高油风险");
        }
        var input = meal();
        input.getFoods().get(1).setName("not fried chicken");
        assertThat(service.evaluate(input, "balanced").getRiskTags()).doesNotContain("油炸或高油风险");
        input.getFoods().get(1).setCategory("fried");
        assertThat(service.evaluate(input, "balanced").getRiskTags()).contains("油炸或高油风险");
    }

    @Test
    void negatedDescriptionsAndNotesCannotChangeTheScore() {
        var meal = meal();
        var baseline = service.evaluate(meal, "balanced");
        meal.setSceneSummary("没有油炸食物，也没有含糖饮料; no fried food and no sweet drink");
        meal.getFoods().get(0).setNote("建议避免油炸食物与含糖饮料");
        var actual = service.evaluate(meal, "balanced");
        assertThat(actual.getScore()).isEqualTo(90).isEqualTo(baseline.getScore());
        assertThat(actual.getRiskTags()).isEqualTo(baseline.getRiskTags());
    }

    @Test
    void sugarFreeDrinkIsNotTreatedAsSugarButDoesNotHideAnotherSugaryDrink() {
        var meal = meal();
        meal.getFoods().add(new FoodItem("无糖可乐", "drink", .9, ""));
        assertThat(service.evaluate(meal, "balanced").getRiskTags()).doesNotContain("含糖饮料/甜品风险");
        meal.getFoods().add(new FoodItem("奶茶", "drink", .9, ""));
        assertThat(service.evaluate(meal, "balanced").getRiskTags()).contains("含糖饮料/甜品风险");
    }

    @Test
    void duplicatesAndLowConfidenceGuessesDoNotChangeScoreOrCategoryCounts() {
        var meal = meal();
        var expected = service.evaluate(meal, "balanced");
        meal.getFoods().add(new FoodItem("米饭", "staple", .8, ""));
        meal.getFoods().add(new FoodItem("炸鸡", "fried", .2, ""));
        meal.getFoods().add(new FoodItem("奶茶", "drink", Double.NaN, ""));
        meal.getFoods().add(null);
        var actual = service.evaluate(meal, "balanced");
        assertThat(actual.getScore()).isEqualTo(expected.getScore());
        assertThat(actual.getCategoryCounts()).isEqualTo(expected.getCategoryCounts());
    }

    @Test
    void realFriedAndSugaryFoodsStillReduceScore() {
        var meal = meal();
        meal.getFoods().add(new FoodItem("炸鸡", "fried", .9, ""));
        meal.getFoods().add(new FoodItem("可乐", "drink", .9, ""));
        var actual = service.evaluate(meal, "fat_loss");
        assertThat(actual.getRiskTags()).contains("油炸或高油风险", "含糖饮料/甜品风险");
        assertThat(actual.getScore()).isLessThanOrEqualTo(58);
    }

    @Test
    void dessertNamesDoNotCountAsStructuredProteinSources() {
        var meal = new RecognitionResult();
        meal.setFoods(List.of(new FoodItem("鸡蛋蛋糕", "dessert", .9, "")));
        assertThat(service.evaluate(meal, "balanced").getRiskTags()).contains("当前未识别到蛋白质来源");
    }

    @Test
    void uncertainOrNonFoodImagesDoNotReceiveMisleadingScores() {
        var meal = new RecognitionResult();
        meal.setFoods(List.of(new FoodItem("米饭", "staple", .1, "")));
        assertThatThrownBy(() -> service.evaluate(meal, "balanced")).isInstanceOf(IllegalArgumentException.class);
        var nonFood = meal();
        nonFood.setFoodImage(false);
        assertThatThrownBy(() -> service.evaluate(nonFood, "balanced")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void incompleteRiskyMealsNeverClaimTheirStructureIsComplete() {
        for (var foods : List.of(
                List.of(new FoodItem("炸鸡", "fried", .95, ""), new FoodItem("薯条", "fried", .95, ""),
                        new FoodItem("可乐", "drink", .95, "")),
                List.of(new FoodItem("巧克力蛋糕", "dessert", .95, ""), new FoodItem("橙汁", "drink", .95, ""),
                        new FoodItem("草莓", "fruit", .95, "")))) {
            var input = new RecognitionResult();
            input.setFoods(foods);
            var evaluation = service.evaluate(input, "balanced");
            assertThat(evaluation.getRiskTags())
                    .contains("当前未识别到蔬菜来源", "饮食质量受油炸或含糖饮料影响，仍需完善餐食结构")
                    .doesNotContain("整体结构虽完整，但饮食质量受油炸或含糖饮料影响");
            assertThat(evaluation.getScore()).isBetween(0, 68);
        }
    }

    @Test
    void legacyFriedFoodsRetainTheirProteinAndStapleSourcesWithoutMutatingOriginalRecognition() {
        var input = new RecognitionResult();
        input.setFoods(List.of(new FoodItem("炸鸡", "fried", .95, ""), new FoodItem("薯条", "fried", .95, ""),
                new FoodItem("可乐", "drink", .95, "")));
        var result = service.evaluate(input, "balanced");
        assertThat(result.getCategoryCounts()).containsEntry("protein", 1).containsEntry("staple", 1).doesNotContainKey("fried");
        assertThat(result.getRiskTags()).contains("油炸或高油风险", "当前未识别到蔬菜来源").doesNotContain("当前未识别到主食", "当前未识别到蛋白质来源");
        assertThat(input.getFoods().get(0).getCategory()).isEqualTo("fried");
    }

    @Test
    void oilAndFryingAttributesAreIndependentAndUnknownIsNotAnAssertion() {
        var input = meal();
        input.getFoods().set(1, new FoodItem("红烧肉", "protein", .95, "", new FoodAttributes(false, true)));
        var marked = service.evaluate(input, "balanced");
        assertThat(marked.getCategoryCounts()).containsEntry("protein", 1);
        assertThat(marked.getRiskTags()).contains("油炸或高油风险").doesNotContain("当前未识别到蛋白质来源");
        input.getFoods().get(1).setAttributes(new FoodAttributes(null, null));
        var unknown = service.evaluate(input, "balanced");
        assertThat(unknown.getRiskTags()).contains("高油风险待核对，尚未确认实际用油量").doesNotContain("油炸或高油风险");
        assertThat(unknown.getSummary()).contains("待核对");
        assertThat(unknown.getScoreBreakdown().items()).anySatisfy(line -> {
            assertThat(line.rule()).isEqualTo("oil-review");
            assertThat(line.points()).isZero();
            assertThat(line.foods()).contains("红烧肉");
        });
        input.getFoods().get(1).setAttributes(new FoodAttributes(false, false));
        assertThat(service.evaluate(input, "balanced").getRiskTags()).doesNotContain("高油风险待核对，尚未确认实际用油量", "油炸或高油风险");
    }

    @Test
    void unknownLegacyFriedIngredientsAreNotInventedAndExplicitUnknownFryingIsPreserved() {
        var input = new RecognitionResult();
        input.setFoods(List.of(new FoodItem("油炸食品", "fried", .95, "")));
        assertThat(service.acceptedFoods(input).get(0).getCategory()).isEqualTo("other");
        input.setFoods(List.of(new FoodItem("炸鸡", "protein", .95, "", new FoodAttributes(null, null))));
        var accepted = service.acceptedFoods(input);
        assertThat(accepted.get(0).getAttributes().getFried()).isNull();
        assertThat(service.evaluate(input, "balanced").getRiskTags()).contains("油炸做法待核对，暂不据此扣分");
    }

    @Test
    void completeMealWithSugaryDrinkKeepsItsStructureButRetainsRisk() {
        var input = meal();
        input.getFoods().add(new FoodItem("可乐", "drink", .95, ""));
        var evaluation = service.evaluate(input, "balanced");
        assertThat(evaluation.getRiskTags()).contains("含糖饮料/甜品风险", "整体结构虽完整，但饮食质量受油炸或含糖饮料影响")
                .doesNotContain("饮食质量受油炸或含糖饮料影响，仍需完善餐食结构", "当前未识别到主食", "当前未识别到蛋白质来源", "当前未识别到蔬菜来源");
        assertThat(evaluation.getScore()).isLessThan(90);
    }

    @Test
    void namedFruitJuicesAreKnownSugaryDrinksWhileFruitAndSaucesAreNot() {
        for (String juice : List.of("橙汁", "鲜榨橙汁", "无添加糖橙汁", "苹果汁", "葡萄汁", "菠萝汁", "西瓜汁", "Orange Juice")) {
            var input = meal();
            input.getFoods().add(new FoodItem(juice, "drink", .95, ""));
            var evaluation = service.evaluate(input, "balanced");
            assertThat(evaluation.getRiskTags()).as(juice).contains("含糖饮料/甜品风险")
                    .doesNotContain("饮品类型不够明确，建议优先选择白水、豆浆、牛奶或无糖饮品");
        }
        var input = meal();
        input.getFoods().add(new FoodItem("橙子", "fruit", .95, ""));
        input.getFoods().get(1).setName("橙汁鸡胸肉");
        assertThat(service.evaluate(input, "balanced").getRiskTags()).doesNotContain("含糖饮料/甜品风险");
        input.getFoods().add(new FoodItem("杯装饮品", "drink", .95, ""));
        assertThat(service.evaluate(input, "balanced").getRiskTags())
                .contains("饮品类型不够明确，建议优先选择白水、豆浆、牛奶或无糖饮品");
    }

    @Test
    void drinkBenefitsDoNotInventAMealType() {
        for (String drink : List.of("牛奶", "豆浆")) {
            var input = meal();
            input.getFoods().add(new FoodItem(drink, "drink", .95, ""));
            var evaluation = service.evaluate(input, "balanced");
            assertThat(evaluation.getSummary()).doesNotContain("早餐", "午餐", "晚餐");
            assertThat(String.join("、", evaluation.getPositivePoints())).doesNotContain("早餐", "午餐", "晚餐");
        }
    }

    private RecognitionResult meal() {
        var result = new RecognitionResult();
        result.setFoods(new ArrayList<>(List.of(new FoodItem("米饭", "staple", .95, ""),
                new FoodItem("鸡蛋", "protein", .95, ""), new FoodItem("西兰花", "vegetable", .95, ""))));
        return result;
    }
}
