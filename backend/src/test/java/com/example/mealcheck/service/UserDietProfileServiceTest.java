package com.example.mealcheck.service;

import com.example.mealcheck.dto.FoodItem;
import com.example.mealcheck.entity.MealRecord;
import com.example.mealcheck.entity.UserAccount;
import com.example.mealcheck.entity.UserDietProfile;
import com.example.mealcheck.repository.MealRecordRepository;
import com.example.mealcheck.repository.UserDietProfileRepository;
import com.example.mealcheck.util.Jsons;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UserDietProfileServiceTest {

    @Test void replacesOnlyTheEditedMealsContributionWithoutScanningHistory() {
        var profileRepository = mock(UserDietProfileRepository.class);
        var mealRepository = mock(MealRecordRepository.class);
        var mapper = new ObjectMapper();
        var service = new UserDietProfileService(profileRepository, mealRepository, mapper);
        var user = new UserAccount();
        var profile = new UserDietProfile(); profile.setUser(user); profile.setAggregateInitialized(true);
        profile.setTotalMeals(1000); profile.setTotalScore(70000);
        profile.setFoodCountsJson("{\"米饭\":1000,\"炸鸡\":1}");
        profile.setRiskCountsJson("{\"油炸\":1,\"蔬菜不足\":500}");
        profile.setGoalCountsJson("{\"balanced\":1000}");
        when(profileRepository.findByUser(user)).thenReturn(Optional.of(profile));
        when(profileRepository.save(any())).thenAnswer(call -> call.getArgument(0));
        var edited = record(mapper, user, 50, "balanced", "炸鸡", "油炸");
        var before = service.contribution(edited);
        // Mutate the same managed object, not a second meal, to catch aliased snapshots.
        edited.setDetectedFoodsJson(Jsons.toJson(mapper, List.of(new FoodItem("青菜", "vegetable", 1, "用户确认"))));
        edited.setRiskTagsJson("[]"); edited.setScore(90);
        service.replaceMeal(user, before, edited);
        assertThat(profile.getTotalMeals()).isEqualTo(1000);
        assertThat(profile.getTotalScore()).isEqualTo(70040);
        assertThat(profile.getAverageScore()).isEqualTo(70);
        assertThat(Jsons.fromJson(mapper, profile.getFoodCountsJson(), Map.class)).containsEntry("米饭", 1000).containsEntry("青菜", 1).doesNotContainKey("炸鸡");
        assertThat(Jsons.fromJson(mapper, profile.getRiskCountsJson(), Map.class)).containsEntry("蔬菜不足", 500).doesNotContainKey("油炸");
        assertThat(profile.getGoalCountsJson()).isEqualTo("{\"balanced\":1000}");
        verify(mealRepository, never()).findByUser(user);
    }

    @Test void repeatedReplacementPreservesMultiplicityZerosAndRankingForUnchangedValues() {
        var profileRepository = mock(UserDietProfileRepository.class); var mealRepository = mock(MealRecordRepository.class);
        var mapper = new ObjectMapper(); var service = new UserDietProfileService(profileRepository, mealRepository, mapper);
        var user = new UserAccount(); var meal = record(mapper, user, 0, "balanced", " 米饭 ", " ");
        meal.setDetectedFoodsJson("[{\"name\":\" 米饭 \"},{\"name\":\"米饭\"}]");
        when(profileRepository.save(any())).thenAnswer(call -> call.getArgument(0));
        when(profileRepository.findByUser(user)).thenReturn(Optional.empty());
        var profile = service.applyMeal(user, meal);
        when(profileRepository.findByUser(user)).thenReturn(Optional.of(profile));
        var before = service.contribution(meal);
        meal.setDetectedFoodsJson("[{\"name\":\"米饭\"}]");
        service.replaceMeal(user, before, meal);
        assertThat(profile.getFoodCountsJson()).isEqualTo("{\"米饭\":1}");
        var identical = service.contribution(meal);
        service.replaceMeal(user, identical, meal);
        assertThat(profile.getTotalMeals()).isEqualTo(1); assertThat(profile.getTotalScore()).isZero();
        assertThat(profile.getAverageScore()).isZero(); assertThat(profile.getRiskCountsJson()).isEqualTo("{}");
        assertThat(profile.getFoodCountsJson()).isEqualTo("{\"米饭\":1}");
        verify(mealRepository, never()).findByUser(user);
    }

    @Test void missingLegacyOrInconsistentAggregatesRebuildFromTheAlreadyCorrectedRecords() {
        for (String state : List.of("missing", "legacy", "broken-json", "missing-count", "invalid-total")) {
            var profileRepository = mock(UserDietProfileRepository.class); var mealRepository = mock(MealRecordRepository.class);
            var mapper = new ObjectMapper(); var service = new UserDietProfileService(profileRepository, mealRepository, mapper);
            var user = new UserAccount(); var profile = new UserDietProfile(); profile.setUser(user);
            profile.setAggregateInitialized(!state.equals("legacy")); profile.setTotalMeals(1); profile.setTotalScore(state.equals("invalid-total") ? 0 : 40);
            profile.setFoodCountsJson(state.equals("broken-json") ? "broken" : "{}"); profile.setRiskCountsJson("{}"); profile.setGoalCountsJson("{}");
            when(profileRepository.findByUser(user)).thenReturn(state.equals("missing") ? Optional.empty() : Optional.of(profile));
            when(profileRepository.save(any())).thenAnswer(call -> call.getArgument(0));
            var edited = record(mapper, user, 40, "balanced", "炸鸡", "油炸"); var before = service.contribution(edited);
            edited.setDetectedFoodsJson("[{\"name\":\"青菜\"}]"); edited.setRiskTagsJson("[]"); edited.setScore(90);
            when(mealRepository.findByUser(user)).thenReturn(List.of(edited));
            var rebuilt = service.replaceMeal(user, before, edited);
            assertThat(rebuilt.getTotalMeals()).isEqualTo(1); assertThat(rebuilt.getTotalScore()).isEqualTo(90);
            assertThat(service.commonFoods(rebuilt)).containsExactly("青菜"); assertThat(service.commonRisks(rebuilt)).isEmpty();
            verify(mealRepository).findByUser(user);
        }
    }

    @Test
    void appliesNewMealWithoutScanningHistory() {
        UserDietProfileRepository profileRepository = mock(UserDietProfileRepository.class);
        MealRecordRepository mealRepository = mock(MealRecordRepository.class);
        ObjectMapper objectMapper = new ObjectMapper();
        UserDietProfileService service = new UserDietProfileService(profileRepository, mealRepository, objectMapper);
        UserAccount user = new UserAccount();
        when(profileRepository.findByUser(user)).thenReturn(Optional.empty());
        when(profileRepository.save(any(UserDietProfile.class))).thenAnswer(invocation -> invocation.getArgument(0));

        MealRecord record = record(objectMapper, user, 82, "fat_loss", "鸡胸肉", "high_sodium");
        UserDietProfile profile = service.applyMeal(user, record);

        assertThat(profile.getTotalMeals()).isEqualTo(1L);
        assertThat(profile.getTotalScore()).isEqualTo(82L);
        assertThat(profile.getAverageScore()).isEqualTo(82);
        assertThat(service.commonFoods(profile)).containsExactly("鸡胸肉");
        assertThat(service.commonRisks(profile)).containsExactly("high_sodium");
        assertThat(profile.getPreferredGoal()).isEqualTo("fat_loss");
        verify(mealRepository, never()).findByUser(user);
    }

    @Test
    void removesMealFromInitializedAggregate() {
        UserDietProfileRepository profileRepository = mock(UserDietProfileRepository.class);
        MealRecordRepository mealRepository = mock(MealRecordRepository.class);
        ObjectMapper objectMapper = new ObjectMapper();
        UserDietProfileService service = new UserDietProfileService(profileRepository, mealRepository, objectMapper);
        UserAccount user = new UserAccount();
        UserDietProfile profile = new UserDietProfile();
        profile.setUser(user);
        profile.setAggregateInitialized(true);
        profile.setTotalMeals(2L);
        profile.setTotalScore(150L);
        profile.setFoodCountsJson("{\"鸡胸肉\":2}");
        profile.setRiskCountsJson("{\"high_sodium\":1}");
        profile.setGoalCountsJson("{\"fat_loss\":2}");
        when(profileRepository.findByUser(user)).thenReturn(Optional.of(profile));
        when(profileRepository.save(any(UserDietProfile.class))).thenAnswer(invocation -> invocation.getArgument(0));

        service.removeMeal(user, record(objectMapper, user, 70, "fat_loss", "鸡胸肉", "high_sodium"));

        assertThat(profile.getTotalMeals()).isEqualTo(1L);
        assertThat(profile.getTotalScore()).isEqualTo(80L);
        assertThat(profile.getAverageScore()).isEqualTo(80);
        assertThat(service.commonRisks(profile)).isEmpty();
    }

    private MealRecord record(ObjectMapper objectMapper,
                              UserAccount user,
                              int score,
                              String goal,
                              String food,
                              String risk) {
        FoodItem foodItem = new FoodItem();
        foodItem.setName(food);
        MealRecord record = new MealRecord();
        record.setUser(user);
        record.setScore(score);
        record.setGoal(goal);
        record.setDetectedFoodsJson(Jsons.toJson(objectMapper, List.of(foodItem)));
        record.setRiskTagsJson(Jsons.toJson(objectMapper, List.of(risk)));
        return record;
    }
}
