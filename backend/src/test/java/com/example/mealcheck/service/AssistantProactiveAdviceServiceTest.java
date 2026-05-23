package com.example.mealcheck.service;

import com.example.mealcheck.entity.MealRecord;
import com.example.mealcheck.entity.UserAccount;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AssistantProactiveAdviceServiceTest {

    @Test
    void buildsVegetableAndHighOilWarningsFromRecentRecords() {
        MealAnalysisService mealAnalysisService = mock(MealAnalysisService.class);
        AssistantProactiveAdviceService service = new AssistantProactiveAdviceService(mealAnalysisService, new ObjectMapper());
        UserAccount user = new UserAccount();

        MealRecord today = record(LocalDateTime.now(), "{\"protein\":1}", "[\"高油风险\"]");
        MealRecord yesterday = record(LocalDateTime.now().minusDays(1), "{\"protein\":1}", "[]");
        MealRecord twoDaysAgo = record(LocalDateTime.now().minusDays(2), "{\"protein\":1}", "[]");
        when(mealAnalysisService.listSince(user, 7)).thenReturn(List.of(twoDaysAgo, yesterday, today));

        var response = service.build(user, "balanced");

        assertThat(response.getItems()).anyMatch(item -> item.contains("蔬菜"));
        assertThat(response.getItems()).anyMatch(item -> item.contains("高油"));
        assertThat(response.getItems()).anyMatch(item -> item.contains("明天"));
    }

    private MealRecord record(LocalDateTime createdAt, String categories, String risks) {
        MealRecord record = new MealRecord();
        record.setCreatedAt(createdAt);
        record.setCategoryCountsJson(categories);
        record.setRiskTagsJson(risks);
        return record;
    }
}
