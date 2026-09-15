package com.example.mealcheck.service;

import com.example.mealcheck.config.AppProperties;
import com.example.mealcheck.dto.WeeklyReportResponse;
import com.example.mealcheck.dto.assistant.AssistantProactiveAdviceResponse;
import com.example.mealcheck.entity.UserAccount;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AssistantToolOrchestratorTest {
    private final ExecutorService executor = Executors.newFixedThreadPool(5);
    private final ScheduledExecutorService timeoutScheduler = Executors.newSingleThreadScheduledExecutor();

    @AfterEach
    void shutdownExecutor() {
        executor.shutdownNow();
        timeoutScheduler.shutdownNow();
    }

    @Test
    void gathersIndependentSourcesInParallel() {
        MealAnalysisService mealService = mock(MealAnalysisService.class);
        UserGoalService goalService = mock(UserGoalService.class);
        WeeklyReportService reportService = mock(WeeklyReportService.class);
        KnowledgeIndexService knowledgeService = mock(KnowledgeIndexService.class);
        AssistantProactiveAdviceService adviceService = mock(AssistantProactiveAdviceService.class);
        CountDownLatch started = new CountDownLatch(4);
        CountDownLatch release = new CountDownLatch(1);
        UserAccount user = new UserAccount();

        when(mealService.listSince(user, 30)).thenAnswer(invocation -> awaitPeers(started, release, List.of()));
        when(goalService.effectiveGoal(user, "current"))
                .thenAnswer(invocation -> awaitPeers(started, release, "balanced"));
        when(reportService.latest(user))
                .thenAnswer(invocation -> awaitPeers(started, release, new WeeklyReportResponse()));
        when(knowledgeService.search(anyString(), anyInt()))
                .thenAnswer(invocation -> awaitPeers(started, release, List.of()));
        when(adviceService.build(any(), anyString(), any()))
                .thenReturn(new AssistantProactiveAdviceResponse(List.of("ok")));

        AppProperties properties = new AppProperties();
        properties.getAssistantTools().setTimeoutMillis(2000);
        AssistantToolOrchestrator orchestrator = new AssistantToolOrchestrator(
                mealService, goalService, reportService, knowledgeService, adviceService,
                executor, executor, executor, timeoutScheduler,
                properties, mock(ApplicationObservability.class),
                mock(AssistantLongTermMemoryService.class));

        AssistantToolOrchestrator.AssistantToolContext context = orchestrator.gather(user, null, "query");

        assertThat(context.currentGoal()).isEqualTo("balanced");
        assertThat(context.proactiveAdvice().getItems()).containsExactly("ok");
    }

    @Test
    void degradesOnlyTheFailedSource() {
        MealAnalysisService mealService = mock(MealAnalysisService.class);
        UserGoalService goalService = mock(UserGoalService.class);
        WeeklyReportService reportService = mock(WeeklyReportService.class);
        KnowledgeIndexService knowledgeService = mock(KnowledgeIndexService.class);
        AssistantProactiveAdviceService adviceService = mock(AssistantProactiveAdviceService.class);
        UserAccount user = new UserAccount();

        when(mealService.listSince(user, 30)).thenReturn(List.of());
        when(goalService.effectiveGoal(user, "current")).thenReturn("fat_loss");
        when(reportService.latest(user)).thenThrow(new IllegalStateException("database unavailable"));
        when(knowledgeService.search(anyString(), anyInt())).thenReturn(List.of());
        when(adviceService.build(any(), anyString(), any())).thenReturn(new AssistantProactiveAdviceResponse(List.of()));

        AssistantToolOrchestrator orchestrator = new AssistantToolOrchestrator(
                mealService, goalService, reportService, knowledgeService, adviceService,
                executor, executor, executor, timeoutScheduler,
                new AppProperties(), mock(ApplicationObservability.class),
                mock(AssistantLongTermMemoryService.class));

        AssistantToolOrchestrator.AssistantToolContext context = orchestrator.gather(user, null, "query");

        assertThat(context.currentGoal()).isEqualTo("fat_loss");
        assertThat(context.weeklyReport().getReportText()).isEqualTo("暂无可用周报。");
    }

    @Test
    void degradesWhenExecutorRejectsTasks() {
        MealAnalysisService mealService = mock(MealAnalysisService.class);
        UserGoalService goalService = mock(UserGoalService.class);
        WeeklyReportService reportService = mock(WeeklyReportService.class);
        KnowledgeIndexService knowledgeService = mock(KnowledgeIndexService.class);
        AssistantProactiveAdviceService adviceService = mock(AssistantProactiveAdviceService.class);
        ApplicationObservability observability = mock(ApplicationObservability.class);
        UserAccount user = new UserAccount();
        ExecutorService rejectingExecutor = mock(ExecutorService.class);
        when(rejectingExecutor.submit(any(Runnable.class)))
                .thenThrow(new RejectedExecutionException("saturated"));

        AssistantToolOrchestrator orchestrator = new AssistantToolOrchestrator(
                mealService, goalService, reportService, knowledgeService, adviceService,
                rejectingExecutor, rejectingExecutor, rejectingExecutor, timeoutScheduler,
                new AppProperties(), observability, mock(AssistantLongTermMemoryService.class));

        AssistantToolOrchestrator.AssistantToolContext context = orchestrator.gather(user, null, "query");

        assertThat(context.recentRecords()).isEmpty();
        assertThat(context.currentGoal()).isEqualTo("balanced");
        assertThat(context.weeklyReport().getReportText()).isEqualTo("暂无可用周报。");
        assertThat(context.snippets()).isEmpty();
    }

    @Test
    void timeoutCancelsTheUnderlyingTask() throws Exception {
        MealAnalysisService mealService = mock(MealAnalysisService.class);
        UserGoalService goalService = mock(UserGoalService.class);
        WeeklyReportService reportService = mock(WeeklyReportService.class);
        KnowledgeIndexService knowledgeService = mock(KnowledgeIndexService.class);
        AssistantProactiveAdviceService adviceService = mock(AssistantProactiveAdviceService.class);
        AssistantLongTermMemoryService longTermMemoryService = mock(AssistantLongTermMemoryService.class);
        CountDownLatch interrupted = new CountDownLatch(1);
        UserAccount user = new UserAccount();

        when(mealService.listSince(user, 30)).thenAnswer(invocation -> {
            try {
                Thread.sleep(5_000L);
                return List.of();
            } catch (InterruptedException error) {
                interrupted.countDown();
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted", error);
            }
        });
        when(goalService.effectiveGoal(user, "current")).thenReturn("balanced");
        when(reportService.latest(user)).thenReturn(new WeeklyReportResponse());
        when(knowledgeService.search(anyString(), anyInt())).thenReturn(List.of());
        when(longTermMemoryService.recall(any(), any(), anyString(), anyInt())).thenReturn(List.of());
        when(adviceService.build(any(), anyString(), any())).thenReturn(new AssistantProactiveAdviceResponse(List.of()));

        AppProperties properties = new AppProperties();
        properties.getAssistantTools().setTimeoutMillis(200);
        properties.getAssistantTools().setTotalTimeoutMillis(250);
        AssistantToolOrchestrator orchestrator = new AssistantToolOrchestrator(
                mealService, goalService, reportService, knowledgeService, adviceService,
                executor, executor, executor, timeoutScheduler,
                properties, mock(ApplicationObservability.class), longTermMemoryService);

        AssistantToolOrchestrator.AssistantToolContext context = orchestrator.gather(user, null, "query");

        assertThat(context.recentRecords()).isEmpty();
        assertThat(interrupted.await(1, TimeUnit.SECONDS)).isTrue();
    }

    private <T> T awaitPeers(CountDownLatch started, CountDownLatch release, T result) throws Exception {
        started.countDown();
        if (started.await(1, TimeUnit.SECONDS)) {
            release.countDown();
        }
        if (!release.await(1, TimeUnit.SECONDS)) {
            throw new IllegalStateException("sources were not executed concurrently");
        }
        return result;
    }
}
