package com.example.mealcheck.service.weeklyagent;

import com.example.mealcheck.dto.KnowledgeSnippet;
import com.example.mealcheck.dto.WeeklyReportResponse;
import org.bsc.langgraph4j.state.AgentState;
import org.bsc.langgraph4j.state.Channel;
import org.bsc.langgraph4j.state.Channels;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class WeeklyReportAgentState extends AgentState {
    public static final String BASE_REPORT = "baseReport";
    public static final String PROFILE_CONTEXT = "profileContext";
    public static final String EVIDENCE = "evidence";
    public static final String TREND = "trend";
    public static final String DRAFT = "draft";
    public static final String REVIEW = "review";
    public static final String REVISION_COUNT = "revisionCount";
    public static final String TRACE = "trace";

    public static final Map<String, Channel<?>> SCHEMA = schema();

    public WeeklyReportAgentState(Map<String, Object> initData) {
        super(initData);
    }

    public WeeklyReportResponse baseReport() {
        return this.<WeeklyReportResponse>value(BASE_REPORT)
                .orElseThrow(() -> new IllegalStateException("Weekly report graph is missing baseReport"));
    }

    public String profileContext() {
        return value(PROFILE_CONTEXT, "");
    }

    public List<KnowledgeSnippet> evidence() {
        return this.<List<KnowledgeSnippet>>value(EVIDENCE).orElse(List.of());
    }

    public WeeklyTrendAnalysis trend() {
        return this.<WeeklyTrendAnalysis>value(TREND)
                .orElseThrow(() -> new IllegalStateException("Weekly report graph is missing trend"));
    }

    public WeeklyReportDraft draft() {
        return this.<WeeklyReportDraft>value(DRAFT)
                .orElseThrow(() -> new IllegalStateException("Weekly report graph is missing draft"));
    }

    public WeeklyReportReview review() {
        return this.<WeeklyReportReview>value(REVIEW)
                .orElse(new WeeklyReportReview(false, "尚未审查", List.of(), false));
    }

    public int revisionCount() {
        return value(REVISION_COUNT, 0);
    }

    public List<String> trace() {
        return this.<List<String>>value(TRACE).orElse(List.of());
    }

    private static Map<String, Channel<?>> schema() {
        Map<String, Channel<?>> schema = new LinkedHashMap<>();
        schema.put(BASE_REPORT, Channels.base(WeeklyReportResponse::new));
        schema.put(PROFILE_CONTEXT, Channels.base(() -> ""));
        schema.put(EVIDENCE, Channels.base(() -> List.<KnowledgeSnippet>of()));
        schema.put(TREND, Channels.base(() -> new WeeklyTrendAnalysis("", List.of(), List.of(), false)));
        schema.put(DRAFT, Channels.base(() -> new WeeklyReportDraft("", List.of(), List.of(), false)));
        schema.put(REVIEW, Channels.base(() -> new WeeklyReportReview(false, "尚未审查", List.of(), false)));
        schema.put(REVISION_COUNT, Channels.base(() -> 0));
        schema.put(TRACE, Channels.appenderWithDuplicate(ArrayList::new));
        return Map.copyOf(schema);
    }
}
