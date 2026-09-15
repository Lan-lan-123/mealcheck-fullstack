package com.example.mealcheck.service;

import com.example.mealcheck.config.AppProperties;
import com.example.mealcheck.dto.KnowledgeSnippet;
import com.example.mealcheck.dto.WeeklyReportResponse;
import com.example.mealcheck.dto.assistant.AssistantProactiveAdviceResponse;
import com.example.mealcheck.entity.MealRecord;
import com.example.mealcheck.entity.AssistantConversation;
import com.example.mealcheck.entity.UserAccount;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

@Service
public class AssistantToolOrchestrator {
    private static final Logger log = LoggerFactory.getLogger(AssistantToolOrchestrator.class);

    private final MealAnalysisService mealAnalysisService;
    private final UserGoalService userGoalService;
    private final WeeklyReportService weeklyReportService;
    private final KnowledgeIndexService knowledgeIndexService;
    private final AssistantProactiveAdviceService proactiveAdviceService;
    private final ExecutorService dbExecutor;
    private final ExecutorService ragExecutor;
    private final ExecutorService backgroundExecutor;
    private final ScheduledExecutorService timeoutScheduler;
    private final AppProperties properties;
    private final ApplicationObservability observability;
    private final AssistantLongTermMemoryService longTermMemoryService;

    public AssistantToolOrchestrator(MealAnalysisService mealAnalysisService,
                                     UserGoalService userGoalService,
                                     WeeklyReportService weeklyReportService,
                                     KnowledgeIndexService knowledgeIndexService,
                                     AssistantProactiveAdviceService proactiveAdviceService,
                                     @Qualifier("assistantDbExecutor") ExecutorService dbExecutor,
                                     @Qualifier("assistantRagExecutor") ExecutorService ragExecutor,
                                     @Qualifier("assistantBackgroundExecutor") ExecutorService backgroundExecutor,
                                     @Qualifier("assistantTimeoutScheduler") ScheduledExecutorService timeoutScheduler,
                                     AppProperties properties,
                                     ApplicationObservability observability,
                                     AssistantLongTermMemoryService longTermMemoryService) {
        this.mealAnalysisService = mealAnalysisService;
        this.userGoalService = userGoalService;
        this.weeklyReportService = weeklyReportService;
        this.knowledgeIndexService = knowledgeIndexService;
        this.proactiveAdviceService = proactiveAdviceService;
        this.dbExecutor = dbExecutor;
        this.ragExecutor = ragExecutor;
        this.backgroundExecutor = backgroundExecutor;
        this.timeoutScheduler = timeoutScheduler;
        this.properties = properties;
        this.observability = observability;
        this.longTermMemoryService = longTermMemoryService;
    }

    public AssistantToolContext gather(UserAccount user,
                                       AssistantConversation conversation,
                                       String ragQuery) {
        long deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(totalTimeoutMillis());
        CompletableFuture<List<MealRecord>> recentRecords = async(
                "recent-meals", () -> mealAnalysisService.listSince(user, 30), List::of,
                dbExecutor, deadlineNanos);
        CompletableFuture<String> currentGoal = async(
                "current-goal", () -> userGoalService.effectiveGoal(user, "current"), () -> "balanced",
                dbExecutor, deadlineNanos);
        CompletableFuture<WeeklyReportResponse> weeklyReport = async(
                "weekly-report", () -> weeklyReportService.latest(user), AssistantToolOrchestrator::emptyWeeklyReport,
                dbExecutor, deadlineNanos);
        CompletableFuture<List<KnowledgeSnippet>> snippets = async(
                "rag", () -> knowledgeIndexService.search(ragQuery, 5), List::of,
                ragExecutor, deadlineNanos);
        CompletableFuture<List<AssistantLongTermMemoryService.MemoryFact>> longTermMemories = async(
                "long-term-memory",
                () -> longTermMemoryService.recall(user, conversation, ragQuery, 5), List::of,
                ragExecutor, deadlineNanos);
        CompletableFuture<AssistantProactiveAdviceResponse> proactiveAdvice = recentRecords
                .thenCombine(currentGoal, AdviceInput::new)
                .thenCompose(input -> async(
                        "proactive-advice",
                        () -> proactiveAdviceService.build(user, input.goal(), input.records()),
                        AssistantToolOrchestrator::emptyAdvice,
                        backgroundExecutor,
                        deadlineNanos));

        CompletableFuture.allOf(recentRecords, currentGoal, weeklyReport, snippets,
                longTermMemories, proactiveAdvice).join();
        return new AssistantToolContext(
                recentRecords.join(), currentGoal.join(), weeklyReport.join(), snippets.join(),
                longTermMemories.join(), proactiveAdvice.join());
    }

    public void remember(UserAccount user, AssistantConversation conversation, String userText) {
        long deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis());
        async("long-term-memory-write",
                () -> longTermMemoryService.remember(user, conversation, userText),
                () -> 0,
                backgroundExecutor,
                deadlineNanos);
    }

    private <T> CompletableFuture<T> async(String tool,
                                           Supplier<T> action,
                                           Supplier<T> fallbackValue,
                                           ExecutorService executor,
                                           long deadlineNanos) {
        long startedAt = observability.start();
        long timeoutMillis = taskTimeoutMillis(deadlineNanos);
        if (timeoutMillis <= 0L) {
            observability.recordAssistantTool(startedAt, tool, "deadline");
            return CompletableFuture.completedFuture(fallback(
                    tool, new TimeoutException("Assistant request deadline exceeded"), fallbackValue.get()));
        }

        CompletableFuture<T> result = new CompletableFuture<>();
        AtomicBoolean terminal = new AtomicBoolean();
        AtomicReference<Future<?>> submittedTask = new AtomicReference<>();
        try {
            Future<?> task = executor.submit(() -> {
                try {
                    T value = action.get();
                    if (terminal.compareAndSet(false, true)) {
                        result.complete(value);
                        observability.recordAssistantTool(startedAt, tool, "success");
                    }
                } catch (Exception error) {
                    if (terminal.compareAndSet(false, true)) {
                        result.complete(fallback(tool, error, fallbackValue.get()));
                        observability.recordAssistantTool(startedAt, tool, "fallback");
                    }
                }
            });
            submittedTask.set(task);
        } catch (RejectedExecutionException error) {
            observability.recordAssistantTool(startedAt, tool, "rejected");
            return CompletableFuture.completedFuture(fallback(tool, error, fallbackValue.get()));
        }
        try {
            ScheduledFuture<?> timeout = timeoutScheduler.schedule(() -> {
                TimeoutException error = new TimeoutException(
                        "Assistant tool timed out after " + timeoutMillis + " ms");
                if (terminal.compareAndSet(false, true)) {
                    result.complete(fallback(tool, error, fallbackValue.get()));
                    observability.recordAssistantTool(startedAt, tool, "timeout");
                    cancel(submittedTask.get(), executor);
                }
            }, timeoutMillis, TimeUnit.MILLISECONDS);
            result.whenComplete((value, error) -> timeout.cancel(false));
            return result;
        } catch (RejectedExecutionException error) {
            cancel(submittedTask.get(), executor);
            observability.recordAssistantTool(startedAt, tool, "rejected");
            return CompletableFuture.completedFuture(fallback(tool, error, fallbackValue.get()));
        }
    }

    private void cancel(Future<?> task, ExecutorService executor) {
        if (task != null) {
            task.cancel(true);
        }
        if (executor instanceof java.util.concurrent.ThreadPoolExecutor threadPool) {
            threadPool.purge();
        }
    }

    private <T> T fallback(String tool, Throwable error, T fallback) {
        Throwable cause = error == null ? null : (error.getCause() == null ? error : error.getCause());
        log.warn("Assistant tool degraded. tool={}, reason={}", tool,
                cause == null ? "unknown" : cause.getMessage());
        return fallback;
    }

    private long timeoutMillis() {
        return Math.max(200L, Math.min(30_000L, properties.getAssistantTools().getTimeoutMillis()));
    }

    private long totalTimeoutMillis() {
        return Math.max(200L, Math.min(60_000L,
                properties.getAssistantTools().getTotalTimeoutMillis()));
    }

    private long taskTimeoutMillis(long deadlineNanos) {
        long remainingNanos = deadlineNanos - System.nanoTime();
        if (remainingNanos <= 0L) {
            return 0L;
        }
        long remainingMillis = Math.max(1L, TimeUnit.NANOSECONDS.toMillis(remainingNanos));
        return Math.min(timeoutMillis(), remainingMillis);
    }

    private static WeeklyReportResponse emptyWeeklyReport() {
        WeeklyReportResponse response = new WeeklyReportResponse();
        response.setDays(7);
        response.setTotalMeals(0);
        response.setAverageScore(0.0);
        response.setHighlights(List.of());
        response.setNextWeekSuggestions(List.of());
        response.setGoalType("balanced");
        response.setReportText("暂无可用周报。");
        response.setGeneratedAt(LocalDateTime.now());
        return response;
    }

    private static AssistantProactiveAdviceResponse emptyAdvice() {
        return new AssistantProactiveAdviceResponse(List.of());
    }

    private record AdviceInput(List<MealRecord> records, String goal) {
    }

    public record AssistantToolContext(List<MealRecord> recentRecords,
                                       String currentGoal,
                                       WeeklyReportResponse weeklyReport,
                                       List<KnowledgeSnippet> snippets,
                                       List<AssistantLongTermMemoryService.MemoryFact> longTermMemories,
                                       AssistantProactiveAdviceResponse proactiveAdvice) {
    }
}
