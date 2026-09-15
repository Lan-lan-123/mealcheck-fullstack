package com.example.mealcheck.service.weeklyagent;

import com.example.mealcheck.config.AppProperties;
import com.example.mealcheck.dto.KnowledgeSnippet;
import com.example.mealcheck.dto.WeeklyReportResponse;
import com.example.mealcheck.entity.UserAccount;
import com.example.mealcheck.entity.UserDietProfile;
import com.example.mealcheck.service.KnowledgeIndexService;
import com.example.mealcheck.service.UserDietProfileService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WeeklyReportMultiAgentOrchestratorTest {
    private final ExecutorService graphExecutor = Executors.newFixedThreadPool(2);

    @AfterEach
    void shutdown() {
        graphExecutor.shutdownNow();
    }

    @Test
    void revisesRejectedDraftAndReturnsApprovedMultiAgentReport() {
        Fixture fixture = fixture();
        WeeklyReportDraft firstDraft = new WeeklyReportDraft(
                "初稿", List.of("重点一"), List.of("建议一"), true);
        WeeklyReportDraft revisedDraft = new WeeklyReportDraft(
                "审查通过后的周报", List.of("蔬菜覆盖不足"), List.of("下周每餐增加一份蔬菜"), true);
        when(fixture.planAgent.createPlan(any(), anyString(), any(), any(), anyString()))
                .thenReturn(firstDraft, revisedDraft);
        when(fixture.reviewAgent.review(any(), anyString(), any(), any()))
                .thenReturn(
                        new WeeklyReportReview(false, "建议需要更具体", List.of("不够具体"), true),
                        new WeeklyReportReview(true, "通过", List.of(), true));

        WeeklyReportResponse response = fixture.orchestrator.generate(fixture.user, baseReport());

        assertThat(response.getGenerationMode()).isEqualTo("MULTI_AGENT");
        assertThat(response.getReviewStatus()).isEqualTo("APPROVED_AI");
        assertThat(response.getReportText()).isEqualTo("审查通过后的周报");
        assertThat(response.getAgentTrace()).contains(
                "trend-agent:ai",
                "evidence-tool:1",
                "plan-agent:ai:attempt-1",
                "review-agent:rejected:ai",
                "plan-agent:ai:attempt-2",
                "review-agent:approved:ai");
        verify(fixture.planAgent, times(2)).createPlan(any(), anyString(), any(), any(), anyString());
        verify(fixture.reviewAgent, times(2)).review(any(), anyString(), any(), any());
        verify(fixture.knowledgeIndexService).recordHits(List.of(7L));
    }

    @Test
    void keepsRuleBasedReportWhenReviewerRejectsFinalDraft() {
        Fixture fixture = fixture();
        fixture.properties.getWeeklyReport().setMaxReviewRevisions(0);
        when(fixture.planAgent.createPlan(any(), anyString(), any(), any(), anyString()))
                .thenReturn(new WeeklyReportDraft("有风险的草稿", List.of("重点"), List.of("建议"), true));
        when(fixture.reviewAgent.review(any(), anyString(), any(), any()))
                .thenReturn(new WeeklyReportReview(false, "不通过", List.of("风险"), true));
        WeeklyReportResponse base = baseReport();

        WeeklyReportResponse response = fixture.orchestrator.generate(fixture.user, base);

        assertThat(response.getGenerationMode()).isEqualTo("RULE_BASED_REVIEW_FALLBACK");
        assertThat(response.getReviewStatus()).isEqualTo("REJECTED_FALLBACK");
        assertThat(response.getReportText()).isEqualTo("规则周报");
        verify(fixture.planAgent, times(1)).createPlan(any(), anyString(), any(), any(), anyString());
    }

    @Test
    void bypassesGraphWhenFeatureIsDisabled() {
        Fixture fixture = fixture();
        fixture.properties.getWeeklyReport().setMultiAgentEnabled(false);

        WeeklyReportResponse response = fixture.orchestrator.generate(fixture.user, baseReport());

        assertThat(response.getGenerationMode()).isEqualTo("RULE_BASED");
        assertThat(response.getReviewStatus()).isEqualTo("DISABLED");
        verify(fixture.trendAgent, never()).analyze(any(), anyString());
        verify(fixture.knowledgeIndexService, never()).search(anyString(), anyInt());
    }

    private Fixture fixture() {
        KnowledgeIndexService knowledgeIndexService = mock(KnowledgeIndexService.class);
        UserDietProfileService profileService = mock(UserDietProfileService.class);
        WeeklyTrendAgent trendAgent = mock(WeeklyTrendAgent.class);
        WeeklyPlanAgent planAgent = mock(WeeklyPlanAgent.class);
        WeeklySafetyReviewAgent reviewAgent = mock(WeeklySafetyReviewAgent.class);
        AppProperties properties = new AppProperties();
        properties.getWeeklyReport().setMultiAgentEnabled(true);
        properties.getWeeklyReport().setMaxReviewRevisions(1);
        properties.getWeeklyReport().setRagLimit(5);

        UserAccount user = new UserAccount();
        user.setId(42L);
        user.setUsername("demo");
        user.setPasswordHash("hash");
        KnowledgeSnippet snippet = new KnowledgeSnippet(7L, "蔬菜", "增加蔬菜摄入", 0.9);
        UserDietProfile profile = mock(UserDietProfile.class);
        when(profileService.getOrRefresh(user)).thenReturn(profile);
        when(profileService.promptText(profile)).thenReturn("用户长期目标为均衡饮食");
        when(knowledgeIndexService.search(anyString(), anyInt())).thenReturn(List.of(snippet));
        when(trendAgent.analyze(any(), anyString())).thenReturn(
                new WeeklyTrendAnalysis("蔬菜覆盖不足", List.of("蔬菜不足"), List.of("蔬菜"), true));

        WeeklyReportMultiAgentOrchestrator orchestrator = new WeeklyReportMultiAgentOrchestrator(
                knowledgeIndexService,
                profileService,
                trendAgent,
                planAgent,
                reviewAgent,
                properties,
                graphExecutor);
        return new Fixture(orchestrator, knowledgeIndexService, trendAgent, planAgent, reviewAgent, properties, user);
    }

    private WeeklyReportResponse baseReport() {
        WeeklyReportResponse response = new WeeklyReportResponse();
        response.setDays(7);
        response.setTotalMeals(5);
        response.setAverageScore(72.0);
        response.setCategoryTotals(new LinkedHashMap<>(java.util.Map.of("vegetable", 2, "protein", 4)));
        response.setRiskTotals(new LinkedHashMap<>(java.util.Map.of("high_oil", 2)));
        response.setHighlights(List.of("规则重点"));
        response.setNextWeekSuggestions(List.of("规则建议"));
        response.setGoalType("balanced");
        response.setReportText("规则周报");
        return response;
    }

    private record Fixture(WeeklyReportMultiAgentOrchestrator orchestrator,
                           KnowledgeIndexService knowledgeIndexService,
                           WeeklyTrendAgent trendAgent,
                           WeeklyPlanAgent planAgent,
                           WeeklySafetyReviewAgent reviewAgent,
                           AppProperties properties,
                           UserAccount user) {
    }
}
