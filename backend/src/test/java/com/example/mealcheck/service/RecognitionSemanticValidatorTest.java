package com.example.mealcheck.service;

import com.example.mealcheck.dto.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

class RecognitionSemanticValidatorTest {
    private FoodItem food(String name, String note, Boolean fried, Boolean oil) {
        return new FoodItem(name, "protein", .98, note, new FoodAttributes(fried, oil));
    }
    @Test void stirAndPanFryingConflictsBecomeUnknownWithoutErasingIndependentOilRisk() {
        for (String name : List.of("番茄炒蛋", "清炒青菜", "煎蘑菇", "stir-fried eggs", "pan-fried tofu")) {
            var original = food(name, "普通炒制或煎制", true, true);
            var reviewed = RecognitionSemanticValidator.review(original);
            assertThat(reviewed.getAttributes().getFried()).isNull();
            assertThat(reviewed.getAttributes().getHighOil()).isTrue();
            assertThat(reviewed.getReviewIssues()).extracting(FoodReviewIssue::code).containsExactly("FRYING_CONFLICT");
            assertThat(original.getAttributes().getFried()).isTrue();
        }
    }
    @Test void explicitDeepFryingSurvivesMixedDishesAndUnknownIsNeverConvertedIntoFalse() {
        var fried = RecognitionSemanticValidator.review(food("青椒炒炸鸡", "油炸鸡块与青椒炒制", true, null));
        assertThat(fried.getAttributes().getFried()).isTrue();
        assertThat(fried.getAttributes().getHighOil()).isNull();
        assertThat(fried.getReviewIssues()).isEmpty();
        var legacy = RecognitionSemanticValidator.review(new FoodItem("炸鸡","fried",.95,""));
        assertThat(legacy.getCategory()).isEqualTo("protein");
        assertThat(legacy.getAttributes().getFried()).isTrue();
        var uncertain = RecognitionSemanticValidator.review(food("清炒青菜", "少量油光，无法确定用油量", false, null));
        assertThat(uncertain.getAttributes().getFried()).isFalse();
        assertThat(uncertain.getAttributes().getHighOil()).isNull();
    }
    @Test void negationAndContradictoryFalseAreHandledInBothDirections() {
        for (String note : List.of("不是油炸，无油炸痕迹", "未见明显油炸痕迹", "空气炸锅制作")) {
            assertThat(RecognitionSemanticValidator.review(food("薯条", note, true, false)).getAttributes().getFried()).isNull();
            assertThat(RecognitionSemanticValidator.review(food("薯条", note, false, false)).getAttributes().getFried()).isFalse();
        }
        assertThat(RecognitionSemanticValidator.review(food("炸鸡", "油炸面衣", false, false)).getAttributes().getFried()).isNull();
        var oil = RecognitionSemanticValidator.review(food("豆腐", "厚重油层", false, false));
        assertThat(oil.getAttributes().getHighOil()).isNull();
        assertThat(oil.getAttributes().getFried()).isFalse();
    }
    @Test void cornPolicyDoesNotRewriteMixedDishesAndConflictsAreReviewable() {
        var corn = new FoodItem("煮玉米", "vegetable", .99, "整根", new FoodAttributes(false, null));
        var reviewed = RecognitionSemanticValidator.review(corn);
        assertThat(reviewed.getCategory()).isEqualTo("other");
        assertThat(reviewed.getReviewIssues()).extracting(FoodReviewIssue::code).containsExactly("CATEGORY_CONFLICT");
        for (String name : List.of("玉米沙拉", "玉米汁", "玉米炒鸡丁")) {
            assertThat(RecognitionSemanticValidator.review(new FoodItem(name,"vegetable",.9,"",new FoodAttributes(null,null))).getReviewIssues()).isEmpty();
        }
        corn.setCategory("staple");
        assertThat(RecognitionSemanticValidator.review(corn).getCategory()).isEqualTo("staple");
    }
    @Test void conflictsSurviveNormalizationAndJsonWhileScoringRulesStayDeterministic() throws Exception {
        var raw = food("番茄炒蛋", "炒制", true, null);
        var reviewed = RecognitionSemanticValidator.review(raw);
        var mapper = new ObjectMapper();
        var restored = mapper.readValue(mapper.writeValueAsString(reviewed), FoodItem.class);
        var meal = new RecognitionResult();
        meal.setFoods(List.of(new FoodItem("米饭","staple",.9,""), restored, new FoodItem("青菜","vegetable",.9,"")));
        var scoring = new MealScoringService();
        var result = scoring.evaluate(meal,"balanced");
        assertThat(scoring.acceptedFoods(meal).get(1).getReviewIssues()).hasSize(1);
        assertThat(result.getScore()).isEqualTo(90);
        assertThat(result.getRiskTags()).anyMatch(tag -> tag.contains("油炸做法待核对"));
        assertThat(result.getRiskTags()).doesNotContain("油炸或高油风险");
        assertThat(result.getScoreBreakdown().items().stream().mapToInt(ScoreBreakdown.Line::points).sum()).isEqualTo(90);
        assertThat(result.getScoreBreakdown().items()).filteredOn(line -> line.rule().equals("semantic-review")).allSatisfy(line -> assertThat(line.points()).isZero());
    }
}
