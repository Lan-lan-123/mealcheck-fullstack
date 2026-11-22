package com.example.mealcheck.service.weeklyagent;

import com.example.mealcheck.dto.WeeklyReportResponse;
import com.example.mealcheck.dto.KnowledgeSnippet;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.mockito.ArgumentMatchers.*;

class WeeklySafetyReviewAgentTest {

    @Test void reviewerSeesTheSameBoundedEvidenceAsPlannerEvenWithAnEmptyTrace() {
        var llm = mock(WeeklyReportAgentLlmClient.class);
        var mapper = new ObjectMapper();
        var evidence = List.of(new KnowledgeSnippet(7L,"蔬菜搭配","优先增加一份蔬菜。"+"甲".repeat(700),.9));
        var report = new WeeklyReportResponse(); report.setTotalMeals(7);
        var trend = new WeeklyTrendAnalysis("已记录7餐",List.of("蔬菜覆盖4餐"),List.of("蔬菜"),true);
        var draft = new WeeklyReportDraft("本周7条记录",List.of("蔬菜覆盖4餐"),List.of("下次增加一份青菜"),true);
        when(llm.complete(eq("weekly-report-plan-agent"),anyString(),anyDouble())).thenReturn(Optional.empty());
        when(llm.complete(eq("weekly-report-review-agent"),anyString(),anyDouble())).thenReturn(Optional.of("{\"approved\":true,\"feedback\":\"证据一致\",\"issues\":[]}"));
        new WeeklyPlanAgent(llm,mapper).createPlan(report,"profile",trend,evidence,"");
        var reviewed = new WeeklySafetyReviewAgent(llm,mapper).review(report,"profile",trend,evidence,draft);
        String supplied = WeeklyEvidenceContext.format(mapper,evidence);
        assertThat(supplied).contains("\"id\":7","蔬菜搭配").doesNotContain("甲".repeat(501));
        verify(llm).complete(eq("weekly-report-plan-agent"),contains(supplied),anyDouble());
        verify(llm).complete(eq("weekly-report-review-agent"),argThat(prompt -> prompt.contains(supplied) && prompt.contains("不以尚未写回的 agentTrace")
                && prompt.contains("不与审查者身份冲突") && prompt.contains("不因这类条件句单独退回") && prompt.contains("不要因偏好的语气")),anyDouble());
        assertThat(report.getAgentTrace()).isEmpty();assertThat(reviewed.approved()).isTrue();
    }

    @Test void missingEvidenceIsExplicitRatherThanInvented() {
        var llm = mock(WeeklyReportAgentLlmClient.class);
        when(llm.complete(anyString(),anyString(),anyDouble())).thenReturn(Optional.empty());
        var report=new WeeklyReportResponse();report.setTotalMeals(2);
        new WeeklySafetyReviewAgent(llm,new ObjectMapper()).review(report,"",new WeeklyTrendAnalysis("",List.of(),List.of(),false),List.of(),new WeeklyReportDraft("两条记录",List.of("重点"),List.of("继续记录"),false));
        verify(llm).complete(eq("weekly-report-review-agent"),contains("本次 RAG 证据（与生成阶段一致）：[]"),anyDouble());
    }

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
                List.of(), draft);

        assertThat(review.approved()).isFalse();
        assertThat(review.issues()).anyMatch(issue -> issue.contains("停药"));
        verifyNoInteractions(llmClient);
    }
}
