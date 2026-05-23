package com.example.mealcheck.service;

import com.example.mealcheck.dto.KnowledgeSnippet;
import com.example.mealcheck.dto.WeeklyReportResponse;
import com.example.mealcheck.dto.assistant.AssistantProactiveAdviceResponse;
import com.example.mealcheck.entity.MealRecord;
import com.example.mealcheck.entity.UserAccount;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class AssistantToolOrchestrator {
    private final MealAnalysisService mealAnalysisService;
    private final UserGoalService userGoalService;
    private final WeeklyReportService weeklyReportService;
    private final KnowledgeIndexService knowledgeIndexService;
    private final AssistantProactiveAdviceService proactiveAdviceService;

    public AssistantToolOrchestrator(MealAnalysisService mealAnalysisService,
                                     UserGoalService userGoalService,
                                     WeeklyReportService weeklyReportService,
                                     KnowledgeIndexService knowledgeIndexService,
                                     AssistantProactiveAdviceService proactiveAdviceService) {
        this.mealAnalysisService = mealAnalysisService;
        this.userGoalService = userGoalService;
        this.weeklyReportService = weeklyReportService;
        this.knowledgeIndexService = knowledgeIndexService;
        this.proactiveAdviceService = proactiveAdviceService;
    }

    public AssistantToolContext gather(UserAccount user, String ragQuery) {
        List<MealRecord> recentRecords = mealAnalysisService.listSince(user, 30);
        String currentGoal = userGoalService.effectiveGoal(user, "current");
        WeeklyReportResponse weeklyReport = weeklyReportService.latest(user);
        List<KnowledgeSnippet> snippets = knowledgeIndexService.search(ragQuery, 5);
        AssistantProactiveAdviceResponse proactiveAdvice = proactiveAdviceService.build(user, currentGoal);
        return new AssistantToolContext(recentRecords, currentGoal, weeklyReport, snippets, proactiveAdvice);
    }

    public record AssistantToolContext(List<MealRecord> recentRecords,
                                       String currentGoal,
                                       WeeklyReportResponse weeklyReport,
                                       List<KnowledgeSnippet> snippets,
                                       AssistantProactiveAdviceResponse proactiveAdvice) {
    }
}
