package com.example.mealcheck.service;

import com.example.mealcheck.dto.*;
import org.junit.jupiter.api.Test;
import java.util.List;
import static com.example.mealcheck.service.MealAdviceRiskPolicy.Risk;
import static org.assertj.core.api.Assertions.*;

class MealAdviceRiskPolicyTest {
    @Test void unknownFreshFoodsAreNotAutomaticReviewTasks() {
        var banana = new FoodItem("香蕉","fruit",.99,"新鲜切片，未见高油或油炸迹象",new FoodAttributes(null,null));
        var drink = new FoodItem("白水","drink",.99,"透明饮用水",new FoodAttributes(null,null));
        for (var food : List.of(banana,drink)) {
            assertThat(MealAdviceRiskPolicy.shouldReview(food,Risk.EITHER)).isFalse();
            assertThat(MealAdviceRiskPolicy.value(food,Risk.EITHER)).isNull();
        }
        assertThat(MealAdviceRiskPolicy.reviewSummary(List.of(banana,drink))).startsWith("无（");
    }
    @Test void concreteCookingCuesAndLocalConflictsKeepReviewAvailable() {
        var cooked = new FoodItem("煎蘑菇","vegetable",.95,"油亮反光",new FoodAttributes(false,null));
        assertThat(MealAdviceRiskPolicy.shouldReview(cooked,Risk.HIGH_OIL)).isTrue();
        assertThat(MealAdviceRiskPolicy.shouldReview(cooked,Risk.FRIED)).isFalse();
        var disputed = new FoodItem("豆腐","protein",.99,"做法不清",new FoodAttributes(null,null));
        disputed.setReviewIssues(List.of(new FoodReviewIssue("FRYING_CONFLICT","油炸属性冲突")));
        assertThat(MealAdviceRiskPolicy.shouldReview(disputed,Risk.FRIED)).isTrue();
        assertThat(MealAdviceRiskPolicy.reviewSummary(List.of(cooked,disputed))).contains("煎蘑菇：高油风险", "豆腐：油炸做法");
        assertThat(MealAdviceConsistency.consistent("请核对煎蘑菇是否有高油风险。", recognition(cooked))).isTrue();
        assertThat(MealAdviceConsistency.removeUnnecessaryReviews("请核对煎蘑菇是否有高油风险。",recognition(cooked))).contains("核对");
        assertThat(MealAdviceConsistency.consistent("建议核对煎蘑菇的油脂情况，确保整体摄入不过于油腻。",recognition(cooked))).isTrue();
    }
    private RecognitionResult recognition(FoodItem food) {
        var result = new RecognitionResult(); result.setFoods(List.of(food)); return result;
    }
}
