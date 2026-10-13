package com.example.mealcheck.service.weeklyagent;

import com.example.mealcheck.config.AppProperties;
import com.example.mealcheck.dto.KnowledgeSnippet;
import com.example.mealcheck.dto.WeeklyReportResponse;
import com.example.mealcheck.entity.UserAccount;
import com.example.mealcheck.service.KnowledgeIndexService;
import com.example.mealcheck.service.UserDietProfileService;
import com.example.mealcheck.service.WeeklyReportCoordinationRecorder;
import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.GraphStateException;
import org.bsc.langgraph4j.RunnableConfig;
import org.bsc.langgraph4j.StateGraph;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.stream.Collectors;

import static org.bsc.langgraph4j.StateGraph.END;
import static org.bsc.langgraph4j.StateGraph.START;
import static org.bsc.langgraph4j.action.AsyncEdgeAction.edge_async;
import static org.bsc.langgraph4j.action.AsyncNodeAction.node_async;

@Service
public class WeeklyReportMultiAgentOrchestrator {
    private static final Logger log = LoggerFactory.getLogger(WeeklyReportMultiAgentOrchestrator.class);
    private static final String PREPARE = "prepare";
    private static final String TREND_AGENT = "trend_agent";
    private static final String EVIDENCE_TOOL = "evidence_tool";
    private static final String PLAN_AGENT = "plan_agent";
    private static final String REVIEW_AGENT = "review_agent";

    private final KnowledgeIndexService knowledgeIndexService;
    private final UserDietProfileService profileService;
    private final WeeklyTrendAgent trendAgent;
    private final WeeklyPlanAgent planAgent;
    private final WeeklySafetyReviewAgent reviewAgent;
    private final AppProperties properties;
    private final Executor graphExecutor;
    private final WeeklyReportCoordinationRecorder coordinationRecorder;
    private final CompiledGraph<WeeklyReportAgentState> graph;

    public WeeklyReportMultiAgentOrchestrator(KnowledgeIndexService knowledgeIndexService,
                                              UserDietProfileService profileService,
                                              WeeklyTrendAgent trendAgent,
                                              WeeklyPlanAgent planAgent,
                                              WeeklySafetyReviewAgent reviewAgent,
                                              AppProperties properties,
                                              WeeklyReportCoordinationRecorder coordinationRecorder,
                                              @Qualifier("weeklyReportAgentExecutor") Executor graphExecutor) {
        this.knowledgeIndexService = knowledgeIndexService;
        this.profileService = profileService;
        this.trendAgent = trendAgent;
        this.planAgent = planAgent;
        this.reviewAgent = reviewAgent;
        this.properties = properties;
        this.coordinationRecorder = coordinationRecorder;
        this.graphExecutor = graphExecutor;
        this.graph = buildGraph();
        this.graph.setMaxIterations(12);
    }

    public WeeklyReportResponse generate(UserAccount user, WeeklyReportResponse baseReport) {
        return generate(user, baseReport, null);
    }

    public WeeklyReportResponse generate(UserAccount user,
                                         WeeklyReportResponse baseReport,
                                         Long jobId) {
        try (var scope=com.example.mealcheck.service.AiCallContext.open(user.getId(),java.time.Duration.ofSeconds(Math.max(1,properties.getAiBudget().getOperationTimeoutSeconds())))) {
            return generateWithinDeadline(user,baseReport,jobId);
        }
    }
    private WeeklyReportResponse generateWithinDeadline(UserAccount user,WeeklyReportResponse baseReport,Long jobId) {
        if (!properties.getWeeklyReport().isMultiAgentEnabled()) {
            baseReport.setGenerationMode("RULE_BASED");
            baseReport.setReviewStatus("DISABLED");
            baseReport.setAgentTrace(List.of("multi-agent:disabled"));
            return baseReport;
        }

        String runId = coordinationRecorder.startRun(safeUserId(user), jobId);
        baseReport.setCoordinationRunId(runId);
        Map<String, Object> inputs = new LinkedHashMap<>();
        inputs.put(WeeklyReportAgentState.AI_CONTEXT,com.example.mealcheck.service.AiCallContext.capture());
        inputs.put(WeeklyReportAgentState.BASE_REPORT, baseReport);
        if (runId != null && !runId.isBlank()) {
            inputs.put(WeeklyReportAgentState.RUN_ID, runId);
        }
        inputs.put(WeeklyReportAgentState.PROFILE_CONTEXT, profileContext(user));
        inputs.put(WeeklyReportAgentState.REVISION_COUNT, 0);

        RunnableConfig config = RunnableConfig.builder()
                .threadId(runId == null ? "weekly-report-user-" + safeUserId(user) : runId)
                .addParallelNodeExecutor(PREPARE, graphExecutor)
                .build();
        try {
            WeeklyReportAgentState state = graph.invoke(inputs, config)
                    .orElseThrow(() -> new IllegalStateException("Weekly report graph returned no final state"));
            WeeklyReportResponse response = finalizeReport(baseReport, state);
            coordinationRecorder.completeRun(runId, response.getGenerationMode(),
                    response.getReviewStatus(), state.revisionCount());
            return response;
        } catch (Exception error) {
            log.warn("Weekly report graph degraded to rule-based report. userId={}, reason={}",
                    safeUserId(user), rootMessage(error), error);
            baseReport.setGenerationMode("RULE_BASED_GRAPH_FALLBACK");
            baseReport.setReviewStatus("GRAPH_FAILED");
            baseReport.setAgentTrace(List.of("graph:fallback"));
            coordinationRecorder.failRun(runId, baseReport.getGenerationMode(),
                    baseReport.getReviewStatus(), 0, error);
            return baseReport;
        }
    }

    private CompiledGraph<WeeklyReportAgentState> buildGraph() {
        try {
            StateGraph<WeeklyReportAgentState> workflow = new StateGraph<>(
                    WeeklyReportAgentState.SCHEMA, WeeklyReportAgentState::new);
            workflow.addNode(PREPARE, node_async(state->withAiContext(state,this::prepare)))
                    .addNode(TREND_AGENT, node_async(state->withAiContext(state,this::analyzeTrend)))
                    .addNode(EVIDENCE_TOOL, node_async(state->withAiContext(state,this::retrieveEvidence)))
                    .addNode(PLAN_AGENT, node_async(state->withAiContext(state,this::createPlan)))
                    .addNode(REVIEW_AGENT, node_async(state->withAiContext(state,this::reviewPlan)))
                    .addEdge(START, PREPARE)
                    .addEdge(PREPARE, TREND_AGENT)
                    .addEdge(PREPARE, EVIDENCE_TOOL)
                    .addEdge(TREND_AGENT, PLAN_AGENT)
                    .addEdge(EVIDENCE_TOOL, PLAN_AGENT)
                    .addEdge(PLAN_AGENT, REVIEW_AGENT)
                    .addConditionalEdges(REVIEW_AGENT,
                            edge_async(this::reviewRoute),
                            Map.of("revise", PLAN_AGENT, "finish", END));
            return workflow.compile();
        } catch (GraphStateException error) {
            throw new IllegalStateException("Invalid weekly report LangGraph4j workflow", error);
        }
    }

    private Map<String, Object> prepare(WeeklyReportAgentState state) {
        WeeklyReportCoordinationRecorder.StepHandle step = coordinationRecorder.startStep(
                state.runId(), PREPARE, "ORCHESTRATION", 1);
        coordinationRecorder.completeStep(step, "COMPLETED", null, false);
        return Map.of(WeeklyReportAgentState.TRACE, List.of("prepare:completed"));
    }

    private Map<String,Object> withAiContext(WeeklyReportAgentState state,
            org.bsc.langgraph4j.action.NodeAction<WeeklyReportAgentState> action) throws Exception {
        try (var scope=com.example.mealcheck.service.AiCallContext.restore(state.aiContext())) { return action.apply(state); }
    }

    private Map<String, Object> analyzeTrend(WeeklyReportAgentState state) {
        WeeklyReportCoordinationRecorder.StepHandle step = coordinationRecorder.startStep(
                state.runId(), TREND_AGENT, "AGENT", 1);
        try {
            WeeklyTrendAnalysis trend = trendAgent.analyze(state.baseReport(), state.profileContext());
            coordinationRecorder.completeStep(step, trend.aiGenerated() ? "AI" : "FALLBACK",
                    trend.aiGenerated(), !trend.aiGenerated());
            return Map.of(
                    WeeklyReportAgentState.TREND, trend,
                    WeeklyReportAgentState.TRACE,
                    List.of("trend-agent:" + (trend.aiGenerated() ? "ai" : "fallback")));
        } catch (RuntimeException error) {
            coordinationRecorder.failStep(step, error);
            throw error;
        }
    }

    private Map<String, Object> retrieveEvidence(WeeklyReportAgentState state) {
        WeeklyReportCoordinationRecorder.StepHandle step = coordinationRecorder.startStep(
                state.runId(), EVIDENCE_TOOL, "TOOL", 1);
        try {
            List<KnowledgeSnippet> evidence = knowledgeIndexService.search(
                    evidenceQuery(state.baseReport()), ragLimit());
            knowledgeIndexService.recordHits(evidence.stream().map(KnowledgeSnippet::getId).toList());
            coordinationRecorder.completeStep(step, evidence.isEmpty() ? "EMPTY" : "FOUND",
                    null, false);
            return Map.of(
                    WeeklyReportAgentState.EVIDENCE, evidence,
                    WeeklyReportAgentState.TRACE, List.of("evidence-tool:" + evidence.size()));
        } catch (Exception error) {
            log.warn("Weekly report RAG evidence degraded. reason={}", rootMessage(error));
            coordinationRecorder.degradeStep(step, "FALLBACK", null, error);
            return Map.of(
                    WeeklyReportAgentState.EVIDENCE, List.of(),
                    WeeklyReportAgentState.TRACE, List.of("evidence-tool:fallback"));
        }
    }

    private Map<String, Object> createPlan(WeeklyReportAgentState state) {
        int attempt = state.revisionCount() + 1;
        WeeklyReportCoordinationRecorder.StepHandle step = coordinationRecorder.startStep(
                state.runId(), PLAN_AGENT, "AGENT", attempt);
        WeeklyReportReview previousReview = state.review();
        try {
            WeeklyReportDraft draft = planAgent.createPlan(
                    state.baseReport(),
                    state.profileContext(),
                    state.trend(),
                    state.evidence(),
                    previousReview.approved() ? "" : previousReview.feedback());
            coordinationRecorder.completeStep(step, draft.aiGenerated() ? "AI" : "FALLBACK",
                    draft.aiGenerated(), !draft.aiGenerated());
            return Map.of(
                    WeeklyReportAgentState.DRAFT, draft,
                    WeeklyReportAgentState.REVISION_COUNT, attempt,
                    WeeklyReportAgentState.TRACE,
                    List.of("plan-agent:" + (draft.aiGenerated() ? "ai" : "fallback") + ":attempt-" + attempt));
        } catch (RuntimeException error) {
            coordinationRecorder.failStep(step, error);
            throw error;
        }
    }

    private Map<String, Object> reviewPlan(WeeklyReportAgentState state) {
        WeeklyReportCoordinationRecorder.StepHandle step = coordinationRecorder.startStep(
                state.runId(), REVIEW_AGENT, "AGENT", Math.max(1, state.revisionCount()));
        try {
            WeeklyReportReview review = reviewAgent.review(
                    state.baseReport(), state.profileContext(), state.trend(), state.draft());
            String outcome = review.approved() ? "APPROVED" : "REJECTED";
            coordinationRecorder.completeStep(step, outcome, review.aiGenerated(), !review.aiGenerated());
            return Map.of(
                    WeeklyReportAgentState.REVIEW, review,
                    WeeklyReportAgentState.TRACE,
                    List.of("review-agent:" + outcome.toLowerCase()
                            + (review.aiGenerated() ? ":ai" : ":rules")));
        } catch (RuntimeException error) {
            coordinationRecorder.failStep(step, error);
            throw error;
        }
    }

    private String reviewRoute(WeeklyReportAgentState state) {
        if (!state.review().approved() && state.revisionCount() <= maxReviewRevisions()) {
            return "revise";
        }
        return "finish";
    }

    private WeeklyReportResponse finalizeReport(WeeklyReportResponse baseReport,
                                                WeeklyReportAgentState state) {
        WeeklyReportReview review = state.review();
        baseReport.setAgentTrace(List.copyOf(state.trace()));
        if (!review.approved()) {
            baseReport.setGenerationMode("RULE_BASED_REVIEW_FALLBACK");
            baseReport.setReviewStatus("REJECTED_FALLBACK");
            return baseReport;
        }

        WeeklyReportDraft draft = state.draft();
        baseReport.setReportText(draft.reportText());
        baseReport.setHighlights(draft.highlights());
        baseReport.setNextWeekSuggestions(draft.nextWeekSuggestions());
        boolean fullyAiGenerated = state.trend().aiGenerated()
                && draft.aiGenerated()
                && review.aiGenerated();
        baseReport.setGenerationMode(fullyAiGenerated ? "MULTI_AGENT" : "MULTI_AGENT_PARTIAL_FALLBACK");
        baseReport.setReviewStatus(review.aiGenerated() ? "APPROVED_AI" : "APPROVED_RULES");
        return baseReport;
    }

    private String profileContext(UserAccount user) {
        try {
            return profileService.promptText(profileService.getOrRefresh(user));
        } catch (Exception error) {
            log.warn("Weekly report profile degraded. userId={}, reason={}", safeUserId(user), rootMessage(error));
            return "暂无长期饮食画像。";
        }
    }

    private String evidenceQuery(WeeklyReportResponse report) {
        List<String> terms = new ArrayList<>();
        terms.add("饮食周报");
        terms.add(report.getGoalType() == null ? "均衡饮食" : report.getGoalType());
        report.getRiskTotals().entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue(Comparator.reverseOrder()))
                .limit(3)
                .map(Map.Entry::getKey)
                .forEach(terms::add);
        if (report.getCategoryMealCounts().getOrDefault("vegetable", 0) < report.getTotalMeals()) {
            terms.add("蔬菜 膳食纤维");
        }
        if (report.getCategoryMealCounts().getOrDefault("protein", 0) < report.getTotalMeals()) {
            terms.add("蛋白质");
        }
        return terms.stream().filter(value -> value != null && !value.isBlank())
                .distinct().collect(Collectors.joining(" "));
    }

    private int ragLimit() {
        return Math.max(1, Math.min(10, properties.getWeeklyReport().getRagLimit()));
    }

    private int maxReviewRevisions() {
        return Math.max(0, Math.min(3, properties.getWeeklyReport().getMaxReviewRevisions()));
    }

    private Long safeUserId(UserAccount user) {
        return user == null ? null : user.getId();
    }

    private String rootMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }
}
