package com.example.mealcheck.service;

import com.example.mealcheck.dto.*;
import com.example.mealcheck.entity.*;
import com.example.mealcheck.repository.*;
import com.example.mealcheck.security.UserPrincipal;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.List;
import java.util.Optional;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class MealCorrectionSemanticReviewTest {
    @Test void unknownAndOmittedAnswersKeepStoredReviewWhileAnExplicitAnswerResolvesItWithoutAi() throws Exception {
        var mapper = new ObjectMapper().registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());
        var users = mock(UserAccountRepository.class); var meals = mock(MealRecordRepository.class);
        var profiles = mock(UserDietProfileService.class); var client = mock(AiChatClient.class);
        var service = new MealCorrectionService(users,meals,profiles,new MealScoringService(),new AdviceGenerationService(client),
                mock(MealReportInvalidationService.class),mock(JdbcTemplate.class),mapper);
        var user = new UserAccount(); user.setId(7L); user.setUsername("tester"); user.setPasswordHash("test");
        var record = new MealRecord(); record.setId(9L); record.setUser(user); record.setGoal("balanced"); record.setScore(50);
        var reviewed = RecognitionSemanticValidator.review(new FoodItem("番茄炒蛋","protein",.99,"炒制",new FoodAttributes(true,true)));
        record.setDetectedFoodsJson(mapper.writeValueAsString(List.of(reviewed)));
        when(users.findByIdForUpdate(7L)).thenReturn(Optional.of(user));
        when(meals.findById(9L)).thenReturn(Optional.of(record));
        var principal = new UserPrincipal(user);
        service.correct(principal,9L,new MealCorrectionRequest(0,List.of(
                new MealCorrectionRequest.CorrectedFood("番茄炒蛋","protein",new FoodAttributes(null,true))),"仍不能确认油炸"));
        var stored = mapper.readValue(record.getDetectedFoodsJson(),FoodItem[].class)[0];
        assertThat(stored.getReviewIssues()).extracting(FoodReviewIssue::code).containsExactly("FRYING_CONFLICT");
        assertThat(stored.getAttributes().getFried()).isNull();
        service.correct(principal,9L,new MealCorrectionRequest(1,List.of(
                new MealCorrectionRequest.CorrectedFood("番茄炒蛋","protein")),"仅确认分类，未填写风险"));
        stored = mapper.readValue(record.getDetectedFoodsJson(),FoodItem[].class)[0];
        assertThat(stored.getReviewIssues()).hasSize(1);
        assertThat(stored.getAttributes().getHighOil()).isTrue();
        var result = service.correct(principal,9L,new MealCorrectionRequest(2,List.of(
                new MealCorrectionRequest.CorrectedFood("番茄炒蛋","protein",new FoodAttributes(false,true))),"确认非油炸，保留高油"));
        stored = mapper.readValue(record.getDetectedFoodsJson(),FoodItem[].class)[0];
        assertThat(stored.getReviewIssues()).isEmpty();
        assertThat(stored.getAttributes().getFried()).isFalse();
        assertThat(stored.getAttributes().getHighOil()).isTrue();
        assertThat(result.getRecognition().getFoods().get(0).getReviewIssues()).isEmpty();
        assertThat(record.getOriginalFoodsJson()).contains("FRYING_CONFLICT");
        verify(meals,times(3)).saveAndFlush(record);
        verifyNoInteractions(client);
    }
}
