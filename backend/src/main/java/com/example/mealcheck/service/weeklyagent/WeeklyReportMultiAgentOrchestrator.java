package com.example.mealcheck.service.weeklyagent;

import com.example.mealcheck.config.AppProperties;
import com.example.mealcheck.dto.KnowledgeSnippet;
import com.example.mealcheck.dto.WeeklyReportResponse;
import com.example.mealcheck.entity.UserAccount;
import com.example.mealcheck.service.KnowledgeIndexService;
import com.example.mealcheck.service.UserDietProfileService;
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
    private final CompiledGraph<WeeklyReportAgentState> graph;

    public WeeklyReportMultiAgentOrchestrator(KnowledgeIndexService knowledgeIndexService,
                                              UserDietProfileService profileService,
                                              WeeklyTrendAgent trendAgent,
                                              WeeklyPlanAgent planAgent,
                                              WeeklySafetyReviewAgent reviewAgent,
                                              AppProperties properties,
                                              @Qualifier("weeklyReportAgentExecutor") Executor graphExecutor) {
        this.knowledgeIndexService = knowledgeIndexService;
        this.profileService = profileService;
        this.trendAgent = trendAgent;
        this.planAgent = planAgent;
        this.reviewAgent = reviewAgent;
        this.properties = properties;
        this.graphExecutor = graphExecutor;
        this.graph = buildGraph();
        this.graph.setMaxIterations(12);
    }

    public WeeklyReportResponse generate(UserAccount user, WeeklyReportResponse baseReport) {
        if (!properties.getWeeklyReport().isMultiAgentEnabled()) {
            baseReport.setGenerationMode("RULE_BASED");
            baseReport.setReviewStatus("DISABLED");
            baseReport.setAgentTrace(List.of("multi-agent:disabled"));
            return baseReport;
        }

        Map<String, Object> inputs = new LinkedHashMap<>();
        inputs.put(WeeklyReportAgentState.BASE_REPORT, baseReport);
        inputs.put(WeeklyReportAgentState.PROFILE_CONTEXT, profileContext(user));
        inputs.put(WeeklyReportAgentState.REVISION_COUNT, 0);

        RunnableConfig config = RunnableConfig.builder()
                .threadId("weekly-report-user-" + safeUserId(user))
                .addParallelNodeExecutor(PREPARE, graphExecutor)
                .build();
        try {
            WeeklyReportAgentState state = graph.invoke(inputs, config)
                    .orElseThrow(() -> new IllegalStateException("Weekly report graph returned no final state"));
            return finalizeReport(baseReport, state);
        } catch (Exception error) {
            log.warn("Weekly report graph degraded to rule-based report. userId={}, reason={}",
                    safeUserId(user), rootMessage(error), error);
            baseReport.setGenerationMode("RULE_BASED_GRAPH_FALLBACK");
            baseReport.setReviewStatus("GRAPH_FAILED");
            baseReport.setAgentTrace(List.of("graph:fallback"));
            return baseReport;
        }
    }

    private CompiledGraph<WeeklyReportAgentState> buildGraph() {
        try {
            StateGraph<WeeklyReportAgentState> workflow = new StateGraph<>(
                    WeeklyReportAgentState.SCHEMA, WeeklyReportAgentState::new);
            workflow.addNode(PREPARE, node_async(state -> Map.of(
                            WeeklyReportAgentState.TRACE, List.of("prepare:completed"))))
                    .addNode(TREND_AGENT, node_async(this::analyzeTrend))
                    .addNode(EVIDENCE_TOOL, node_async(this::retrieveEvidence))
                    .addNode(PLAN_AGENT, node_async(this::createPlan))
                    .addNode(REVIEW_AGENT, node_async(this::reviewPlan))
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

    private Map<String, Object> analyzeTrend(WeeklyReportAgentState state) {
        WeeklyTrendAnalysis trend = trendAgent.analyze(state.baseReport(), state.profileContext());
        return Map.of(
                WeeklyReportAgentState.TREND, trend,
                WeeklyReportAgentState.TRACE,
                List.of("trend-agent:" + (trend.aiGenerated() ? "ai" : "fallback")));
    }

    private Map<String, Object> retrieveEvidence(WeeklyReportAgentState state) {
        try {
            List<KnowledgeSnippet> evidence = knowledgeIndexService.search(
                    evidenceQuery(state.baseReport()), ragLimit());
            knowledgeIndexService.recordHits(evidence.stream().map(KnowledgeSnippet::getId).toList());
            return Map.of(
                    WeeklyReportAgentState.EVIDENCE, evidence,
                    WeeklyReportAgentState.TRACE, List.of("evidence-tool:" + evidence.size()));
        } catch (Exception error) {
            log.warn("Weekly report RAG evidence degraded. reason={}", rootMessage(error));
            return Map.of(
                    WeeklyReportAgentState.EVIDENCE, List.of(),
                    WeeklyReportAgentState.TRACE, List.of("evidence-tool:fallback"));
        }
    }

    private Map<String, Object> createPlan(WeeklyReportAgentState state) {
        WeeklyReportReview previousReview = state.review();
        WeeklyReportDraft draft = planAgent.createPlan(
                state.baseReport(),
                state.profileContext(),
                state.trend(),
                state.evidence(),
                previousReview.approved() ? "" : previousReview.feedback());
        int attempt = state.revisionCount() + 1;
        return Map.of(
                WeeklyReportAgentState.DRAFT, draft,
                WeeklyReportAgentState.REVISION_COUNT, attempt,
                WeeklyReportAgentState.TRACE,
                List.of("plan-agent:" + (draft.aiGenerated() ? "ai" : "fallback") + ":attempt-" + attempt));
    }

    private Map<String, Object> reviewPlan(WeeklyReportAgentState state) {
        WeeklyReportReview review = reviewAgent.review(
                state.baseReport(), state.profileContext(), state.trend(), state.draft());
        String outcome = review.approved() ? "approved" : "rejected";
        return Map.of(
                WeeklyReportAgentState.REVIEW, review,
                WeeklyReportAgentState.TRACE,
                List.of("review-agent:" + outcome + (review.aiGenerated() ? ":ai" : ":rules")));
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
        if (report.getCategoryTotals().getOrDefault("vegetable", 0) < report.getTotalMeals()) {
            terms.add("蔬菜 膳食纤维");
        }
        if (report.getCategoryTotals().getOrDefault("protein", 0) < report.getTotalMeals()) {
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
