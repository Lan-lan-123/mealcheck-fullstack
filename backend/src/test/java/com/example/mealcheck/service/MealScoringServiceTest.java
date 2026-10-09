package com.example.mealcheck.service;

import com.example.mealcheck.dto.*;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

class MealScoringServiceTest {
    private final MealScoringService service = new MealScoringService();

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
        assertThat(service.evaluate(meal, "balanced").getRiskTags()).contains("蛋白质不足");
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

    private RecognitionResult meal() {
        var result = new RecognitionResult();
        result.setFoods(new ArrayList<>(List.of(new FoodItem("米饭", "staple", .95, ""),
                new FoodItem("鸡蛋", "protein", .95, ""), new FoodItem("西兰花", "vegetable", .95, ""))));
        return result;
    }
}
