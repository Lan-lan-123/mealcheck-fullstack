package com.example.mealcheck.service.weeklyagent;

import com.example.mealcheck.dto.WeeklyReportResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class WeeklySafetyReviewAgentTest {

    @Test
    void rejectsUnsafeMedicalClaimBeforeCallingLlm() {
        WeeklyReportAgentLlmClient llmClient = mock(WeeklyReportAgentLlmClient.class);
        WeeklySafetyReviewAgent agent = new WeeklySafetyReviewAgent(llmClient, new ObjectMapper());
        WeeklyReportDraft draft = new WeeklyReportDraft(
                "这个计划可以替代医生", List.of("重点"), List.of("建议停药"), true);
        WeeklyReportResponse report = new WeeklyReportResponse();
        report.setTotalMeals(5);

        WeeklyReportReview review = agent.review(
                report,
                "profile",
                new WeeklyTrendAnalysis("trend", List.of("finding"), List.of("keyword"), true),
                draft);

        assertThat(review.approved()).isFalse();
        assertThat(review.issues()).anyMatch(issue -> issue.contains("停药"));
        verifyNoInteractions(llmClient);
    }
}
