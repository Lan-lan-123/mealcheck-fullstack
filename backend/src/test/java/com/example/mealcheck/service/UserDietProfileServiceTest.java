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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UserDietProfileServiceTest {

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
