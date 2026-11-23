package com.example.mealcheck.service;

import com.example.mealcheck.dto.*;
import com.example.mealcheck.dto.MealPlanningDtos.*;
import com.example.mealcheck.dto.TrackingDtos.*;
import com.example.mealcheck.entity.UserAccount;
import com.example.mealcheck.repository.*;
import com.example.mealcheck.security.UserPrincipal;
import com.example.mealcheck.util.AppTime;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class MealPlanningServiceTest {
    final MealPlanningRepository meals = mock(MealPlanningRepository.class);
    final DietPreferenceService preferences = mock(DietPreferenceService.class);
    final UserGoalService goals = mock(UserGoalService.class);
    final UserPrincipal user;
    MealPlanningServiceTest() {
        var account = new UserAccount(); account.setId(7L); account.setUsername("planner"); user = new UserPrincipal(account);
        when(preferences.get(7)).thenReturn(DietPreferences.defaults());
        when(meals.recent(eq(7L), any())).thenReturn(List.of());
    }
    NextMealService next() { return new NextMealService(preferences, goals, meals, new ObjectMapper()); }
    FoodItem food(String name, String category, double confidence) { return new FoodItem(name, category, confidence, null); }
    @Test void budgetTimeAndSceneAreHardConstraintsAndNoDataIsNotInventedHistory() {
        var response = next().recommend(user, new NextMealRequest("LUNCH", "CANTEEN", 20, 5));
        assertThat(response.options()).hasSize(3);
        assertThat(response.context()).contains("暂无餐食记录");
        assertThat(response.recentMealCount()).isZero();
        assertThat(response.options()).allSatisfy(option -> {
            assertThat(option.costMax()).isLessThanOrEqualTo(20);
            assertThat(option.readyMinutes()).isLessThanOrEqualTo(5);
            assertThat(option.foods()).hasSize(3);
            assertThat(option.actionText()).contains("非报价", "不含排队与配送");
        });
        assertThat(response.costBasis()).contains("不是门店报价", "配送费");
        assertThat(response.timeBasis()).contains("不含排队和配送");
        assertThat(next().recommend(user, new NextMealRequest("LUNCH", "HOME", 20, 5)).options()).isEmpty();
        assertThat(next().recommend(user, new NextMealRequest("LUNCH", "CANTEEN", 1, 30)).message()).contains("没有同时满足", "保留忌口");
        verify(preferences, atLeastOnce()).get(7L);
        verify(meals, atLeastOnce()).recent(eq(7L), any());
    }
    @Test void restrictionsDislikesAndIngredientAliasesAreAppliedBeforeSelection() {
        when(preferences.get(7)).thenReturn(new DietPreferences(DietPreferences.Style.VEGAN, List.of("大豆"), List.of("番茄"), 1));
        var response = next().recommend(user, new NextMealRequest("LUNCH", "HOME", 30, 40));
        assertThat(response.options()).isNotEmpty();
        assertThat(response.options()).allSatisfy(option -> assertThat(String.join(" ", option.foods()))
                .contains("鹰嘴豆").doesNotContain("豆腐", "鸡蛋", "鸡肉", "鱼", "番茄"));
        when(preferences.get(7)).thenReturn(new DietPreferences(DietPreferences.Style.VEGAN, List.of("豆"), List.of(), 2));
        assertThat(next().recommend(user, new NextMealRequest("LUNCH", "CANTEEN", 100, 60)).options()).isEmpty();
    }
    @Test void recentRecordsAffectVarietyAndCurrentGoalIsRetained() {
        var profile = new UserGoalResponse(null,"muscle_gain",null,null,null,true,null);
        when(goals.current(user)).thenReturn(profile);
        when(meals.recent(eq(7L), any())).thenReturn(List.of(new MealPlanningRepository.Meal(1,"LUNCH",
                List.of(food("米饭","staple",1),food("清蒸豆腐","protein",1),food("原味黄瓜","vegetable",1)),List.of())));
        var response = next().recommend(user, new NextMealRequest("LUNCH", "CANTEEN", 30, 30));
        assertThat(response.goal()).isEqualTo("muscle_gain");
        assertThat(response.recentMealCount()).isEqualTo(1);
        assertThat(response.options().get(0).foods()).doesNotContain("米饭","清蒸豆腐","原味黄瓜");
        assertThat(response.options().get(0).reasons().get(0)).contains("增肌方向");
        assertThat(response.context()).doesNotContain("未识别到蔬菜");
    }
    @Test void todayCountsAllReviewItemsButOnlyPreviewsFiveMealsAndUsesExactPendingCount() {
        var tracking = mock(TrackingStatisticsService.class);
        var plans = mock(ActionPlanRepository.class);
        var day = AppTime.today();
        var slots = Map.of("BREAKFAST","RECORDED","LUNCH","SKIPPED","DINNER","UNKNOWN","SNACK","UNKNOWN");
        when(tracking.summary(7,day,day)).thenReturn(new Summary(day,day,7,1,1,100,1,1,
                List.of(new Day(day,7,slots)),List.of(),Map.of(),""));
        var selected = new ArrayList<MealPlanningRepository.Meal>();
        for (int i=0;i<7;i++) selected.add(new MealPlanningRepository.Meal(i+1,"BREAKFAST",List.of(food("牛奶","dairy",.7)),List.of(food("候选食物","other",.3))));
        when(meals.today(7,day)).thenReturn(selected);
        when(plans.pendingToday(7,day)).thenReturn(new Page<>(List.of(),12,3,0,5));
        var response = new TodayMealService(meals,tracking,plans).get(7);
        assertThat(response.totalMeals()).isEqualTo(7);
        assertThat(response.slots()).containsEntry("LUNCH","SKIPPED").containsEntry("DINNER","UNKNOWN");
        assertThat(response.reviewFoodCount()).isEqualTo(14);
        assertThat(response.reviewMealCount()).isEqualTo(7);
        assertThat(response.reviews()).hasSize(5);
        assertThat(response.pendingPlanCount()).isEqualTo(12);
    }
    @Test void reviewPolicyIncludesHighConfidenceAmbiguityConflictsAndUnknownOilButNotAllUnknowns() {
        assertThat(TodayMealService.needsReview(food("米饭","staple",1))).isFalse();
        assertThat(TodayMealService.needsReview(new FoodItem("牛奶","dairy",.99,"可能是牛奶或豆浆"))).isTrue();
        assertThat(TodayMealService.needsReview(new FoodItem("红烧肉","protein",.99,null,new FoodAttributes(false,null)))).isTrue();
        assertThat(TodayMealService.needsReview(new FoodItem("红烧肉","protein",1,null,new FoodAttributes(false,false)))).isFalse();
        var corn = food("玉米","other",.99); corn.setReviewIssues(List.of(new FoodReviewIssue("CATEGORY_CONFLICT","分类待核对")));
        assertThat(TodayMealService.needsReview(corn)).isTrue();
    }
    @Test void requestRejectsMissingUnknownAndOutOfRangeConstraints() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var validator = factory.getValidator();
            assertThat(validator.validate(new NextMealRequest(null,"UNKNOWN",0,121))).hasSize(4);
            assertThat(validator.validate(new NextMealRequest("LUNCH","HOME",20,30))).isEmpty();
        }
    }
}
