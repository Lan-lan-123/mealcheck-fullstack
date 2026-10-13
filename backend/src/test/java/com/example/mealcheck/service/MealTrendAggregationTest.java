package com.example.mealcheck.service;

import com.example.mealcheck.config.AppProperties;
import com.example.mealcheck.dto.UserMealTrendResponse.MetricItem;
import com.example.mealcheck.entity.UserAccount;
import com.example.mealcheck.repository.MealRecordRepository;
import com.example.mealcheck.repository.UserAccountRepository;
import com.example.mealcheck.security.UserPrincipal;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class MealTrendAggregationTest {
    private final MealRecordRepository meals = mock(MealRecordRepository.class);
    private final ObjectMapper mapper = spy(new ObjectMapper());
    private final MealAnalysisService service = new MealAnalysisService(
            mock(UserAccountRepository.class), meals, mock(VisionFoodRecognitionService.class),
            mock(MealScoringService.class), mock(KnowledgeIndexService.class), mock(AdviceGenerationService.class),
            mock(ImageStorageService.class), mock(ImageUploadValidationService.class),
            mock(NonFoodUploadGuardService.class), mock(UserGoalService.class),
            mock(MealRecordPersistenceService.class), mapper, new AppProperties(), null);

    private UserPrincipal principal(long total) {
        var summary = mock(MealRecordRepository.TrendSummary.class);
        when(summary.getTotal()).thenReturn(total);
        when(summary.getScored()).thenReturn(0L);
        when(summary.getLow()).thenReturn(0L);
        when(summary.getHigh()).thenReturn(0L);
        when(meals.trendSummary(eq(1L), any())).thenReturn(summary);
        when(meals.trendDailyScores(eq(1L), any(), anyString())).thenReturn(List.of());
        var user = new UserAccount(); user.setId(1L); user.setUsername("trend-user");
        return new UserPrincipal(user);
    }

    private MealRecordRepository.TrendFacts facts(String foods, String risks, long occurrences) {
        var fact = mock(MealRecordRepository.TrendFacts.class);
        when(fact.getFoods()).thenReturn(foods); when(fact.getRisks()).thenReturn(risks);
        when(fact.getOccurrences()).thenReturn(occurrences);
        return fact;
    }

    @Test void repeatedMealsCountEveryOccurrenceWithoutRepeatedJsonParsing() throws Exception {
        var owner = principal(10002);
        var groups = List.of(
                facts("[{\"name\":\" rice \"},{\"name\":\"rice\"},null,{\"name\":\" \"},{}]",
                        "[\" risk \",\"risk\",null,\" \"]", 10000),
                facts("[{\"name\":\"egg\"},{\"name\":\"rice\"}]", "[\"risk\"]", 2));
        when(meals.trendFacts(eq(1L), any())).thenReturn(groups);
        var response = service.trend(owner, 90);
        assertThat(response.getTotal()).isEqualTo(10002);
        assertThat(response.getFoodCounts()).extracting(MetricItem::getLabel).containsExactly("rice", "egg");
        assertThat(response.getFoodCounts()).extracting(MetricItem::getValue).containsExactly(20002, 2);
        assertThat(response.getRiskTagCounts()).singleElement().satisfies(metric -> {
            assertThat(metric.getLabel()).isEqualTo("risk"); assertThat(metric.getValue()).isEqualTo(20002);
        });
        verify(mapper, times(4)).readValue(anyString(), any(TypeReference.class));
    }

    @Test void tiedMetricsKeepFirstOccurrenceOrderAndResultsStayBounded() {
        var owner = principal(10);
        var groups = java.util.stream.IntStream.range(0, 10)
                .mapToObj(i -> facts("[{\"name\":\"food-" + (9 - i) + "\"}]", "null", 1)).toList();
        when(meals.trendFacts(eq(1L), any())).thenReturn(groups);
        var response = service.trend(owner, 90);
        assertThat(response.getFoodCounts()).extracting(MetricItem::getLabel)
                .containsExactly("food-9", "food-8", "food-7", "food-6", "food-5", "food-4", "food-3", "food-2");
        assertThat(response.getRiskTagCounts()).isEmpty();
    }
}
