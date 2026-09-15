package com.example.mealcheck.service;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

@Service
public class ApplicationObservability {
    private final MeterRegistry registry;

    public ApplicationObservability(MeterRegistry registry) {
        this.registry = registry;
    }

    public long start() {
        return System.nanoTime();
    }

    public void recordEmbedding(long startedAt, String provider, String outcome) {
        timer("mealcheck.knowledge.embedding.duration", "provider", provider, "outcome", outcome)
                .record(elapsed(startedAt), TimeUnit.NANOSECONDS);
        counter("mealcheck.knowledge.embedding.requests", "provider", provider, "outcome", outcome).increment();
    }

    public void recordRagSearch(long startedAt, String cache, String outcome, int resultCount, double topScore) {
        timer("mealcheck.rag.search.duration", "cache", cache, "outcome", outcome)
                .record(elapsed(startedAt), TimeUnit.NANOSECONDS);
        counter("mealcheck.rag.search.requests", "cache", cache, "outcome", outcome).increment();
        DistributionSummary.builder("mealcheck.rag.search.result.count")
                .baseUnit("results").register(registry).record(resultCount);
        DistributionSummary.builder("mealcheck.rag.search.top.score")
                .publishPercentileHistogram().register(registry).record(Math.max(0.0, topScore));
    }

    public void recordRerank(long startedAt, String provider, String outcome, int candidateCount) {
        timer("mealcheck.rag.rerank.duration", "provider", provider, "outcome", outcome)
                .record(elapsed(startedAt), TimeUnit.NANOSECONDS);
        counter("mealcheck.rag.rerank.requests", "provider", provider, "outcome", outcome).increment();
        DistributionSummary.builder("mealcheck.rag.rerank.candidate.count")
                .baseUnit("candidates").register(registry).record(Math.max(0, candidateCount));
    }

    public void recordRagFiltering(int inputCount,
                                   int outputCount,
                                   int thresholdFilteredCount,
                                   int duplicateFilteredCount,
                                   double effectiveThreshold) {
        String outcome = outputCount == 0 ? "empty" : "retained";
        counter("mealcheck.rag.filter.requests", "outcome", outcome).increment();
        DistributionSummary.builder("mealcheck.rag.filter.input.count")
                .baseUnit("results").register(registry).record(Math.max(0, inputCount));
        DistributionSummary.builder("mealcheck.rag.filter.output.count")
                .baseUnit("results").register(registry).record(Math.max(0, outputCount));
        DistributionSummary.builder("mealcheck.rag.filter.threshold.removed.count")
                .baseUnit("results").register(registry).record(Math.max(0, thresholdFilteredCount));
        DistributionSummary.builder("mealcheck.rag.filter.duplicate.removed.count")
                .baseUnit("results").register(registry).record(Math.max(0, duplicateFilteredCount));
        DistributionSummary.builder("mealcheck.rag.filter.effective.threshold")
                .register(registry).record(Math.max(0.0, effectiveThreshold));
    }

    public void recordRagScoreLayers(double roughTopScore,
                                     Double rerankerTopScore,
                                     double finalTopScore,
                                     boolean rerankerApplied) {
        DistributionSummary.builder("mealcheck.rag.score.rough.top")
                .publishPercentileHistogram().register(registry).record(Math.max(0.0, roughTopScore));
        if (rerankerTopScore != null && Double.isFinite(rerankerTopScore)) {
            DistributionSummary.builder("mealcheck.rag.score.reranker.top")
                    .publishPercentileHistogram().register(registry)
                    .record(Math.max(0.0, rerankerTopScore));
        }
        DistributionSummary.builder("mealcheck.rag.score.final.top")
                .publishPercentileHistogram().register(registry).record(Math.max(0.0, finalTopScore));
        counter("mealcheck.rag.score.layers", "rerankerApplied", Boolean.toString(rerankerApplied)).increment();
    }

    public void recordIndex(long startedAt, String outcome, PgVectorKnowledgeService.SyncResult result) {
        timer("mealcheck.knowledge.index.duration", "outcome", outcome)
                .record(elapsed(startedAt), TimeUnit.NANOSECONDS);
        counter("mealcheck.knowledge.index.runs", "outcome", outcome).increment();
        if (result != null) {
            incrementIndexChunks("inserted", result.inserted());
            incrementIndexChunks("updated", result.updated());
            incrementIndexChunks("unchanged", result.unchanged());
            incrementIndexChunks("deleted", result.deleted());
        }
    }

    public void recordAiCall(long startedAt, String operation, String outcome) {
        timer("mealcheck.ai.call.duration", "operation", safeOperation(operation), "outcome", outcome)
                .record(elapsed(startedAt), TimeUnit.NANOSECONDS);
        counter("mealcheck.ai.call.requests", "operation", safeOperation(operation), "outcome", outcome).increment();
    }

    public void recordWeeklyReport(String outcome, int count) {
        counter("mealcheck.weekly.report.generated", "outcome", outcome).increment(Math.max(0, count));
    }

    public void recordWeeklyReportJob(String outcome) {
        counter("mealcheck.weekly.report.jobs", "outcome", outcome).increment();
    }

    public void recordMcpTool(long startedAt, String tool, String outcome) {
        timer("mealcheck.mcp.tool.duration", "tool", tool, "outcome", outcome)
                .record(elapsed(startedAt), TimeUnit.NANOSECONDS);
        counter("mealcheck.mcp.tool.requests", "tool", tool, "outcome", outcome).increment();
    }

    public void recordRateLimit(String scope, String outcome) {
        counter("mealcheck.rate.limit.requests", "scope", safeRateLimitScope(scope), "outcome", outcome).increment();
    }

    public void recordAssistantMemory(int messageCount,
                                      int estimatedTokens,
                                      boolean summaryUpdated,
                                      boolean followUpRewritten) {
        counter("mealcheck.assistant.memory.contexts",
                "summaryUpdated", Boolean.toString(summaryUpdated),
                "followUpRewritten", Boolean.toString(followUpRewritten)).increment();
        DistributionSummary.builder("mealcheck.assistant.memory.message.count")
                .baseUnit("messages").register(registry).record(Math.max(0, messageCount));
        DistributionSummary.builder("mealcheck.assistant.memory.token.count")
                .baseUnit("tokens").publishPercentileHistogram().register(registry)
                .record(Math.max(0, estimatedTokens));
    }

    public void recordAssistantTool(long startedAt, String tool, String outcome) {
        timer("mealcheck.assistant.tool.duration", "tool", safeTool(tool), "outcome", outcome)
                .record(elapsed(startedAt), TimeUnit.NANOSECONDS);
        counter("mealcheck.assistant.tool.requests", "tool", safeTool(tool), "outcome", outcome).increment();
    }

    private void incrementIndexChunks(String action, int count) {
        counter("mealcheck.knowledge.index.chunks", "action", action).increment(Math.max(0, count));
    }

    private Timer timer(String name, String... tags) {
        return Timer.builder(name).publishPercentileHistogram().tags(tags).register(registry);
    }

    private Counter counter(String name, String... tags) {
        return Counter.builder(name).tags(tags).register(registry);
    }

    private long elapsed(long startedAt) {
        return Math.max(0L, System.nanoTime() - startedAt);
    }

    private String safeOperation(String operation) {
        if (operation == null || operation.isBlank()) {
            return "unknown";
        }
        return switch (operation) {
            case "vision-recognition", "meal-advice", "diet-assistant",
                 "diet-assistant-function-calling" -> operation;
            default -> "other";
        };
    }

    private String safeTool(String tool) {
        if (tool == null || tool.isBlank()) {
            return "unknown";
        }
        return switch (tool) {
            case "recent-meals", "current-goal", "weekly-report", "rag", "long-term-memory",
                 "long-term-memory-write", "proactive-advice", "search_diet_knowledge",
                 "get_my_recent_meals", "get_my_weekly_report", "get_my_current_goal" -> tool;
            default -> "other";
        };
    }

    private String safeRateLimitScope(String scope) {
        if (scope == null || scope.isBlank()) {
            return "unknown";
        }
        return switch (scope) {
            case "assistant", "meal-analysis" -> scope;
            default -> "other";
        };
    }
}
