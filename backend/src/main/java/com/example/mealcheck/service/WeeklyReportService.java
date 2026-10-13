package com.example.mealcheck.service;

import com.example.mealcheck.util.AppTime;

import com.example.mealcheck.dto.WeeklyReportResponse;
import com.example.mealcheck.entity.MealRecord;
import com.example.mealcheck.entity.UserAccount;
import com.example.mealcheck.entity.WeeklyReportSnapshot;
import com.example.mealcheck.repository.UserAccountRepository;
import com.example.mealcheck.repository.WeeklyReportSnapshotRepository;
import com.example.mealcheck.security.UserPrincipal;
import com.example.mealcheck.service.weeklyagent.WeeklyReportMultiAgentOrchestrator;
import com.example.mealcheck.util.Jsons;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class WeeklyReportService {
    private final BehaviorTrackingService tracking;
    private final UserAccountRepository userRepository;
    private final MealAnalysisService mealAnalysisService;
    private final ObjectMapper objectMapper;
    private final WeeklyReportSnapshotRepository snapshotRepository;
    private final UserGoalService userGoalService;
    private final WeeklyReportMultiAgentOrchestrator multiAgentOrchestrator;

    public WeeklyReportService(UserAccountRepository userRepository,
                               MealAnalysisService mealAnalysisService,
                               ObjectMapper objectMapper,
                               WeeklyReportSnapshotRepository snapshotRepository,
                               UserGoalService userGoalService,
                               WeeklyReportMultiAgentOrchestrator multiAgentOrchestrator, BehaviorTrackingService tracking) {
        this.tracking = tracking;
        this.userRepository = userRepository;
        this.mealAnalysisService = mealAnalysisService;
        this.objectMapper = objectMapper;
        this.snapshotRepository = snapshotRepository;
        this.userGoalService = userGoalService;
        this.multiAgentOrchestrator = multiAgentOrchestrator;
    }

    public WeeklyReportResponse generate(UserPrincipal principal, int days) {
        int safeDays = Math.max(1, Math.min(days, 30));
        UserAccount user = userRepository.findById(principal.getId()).orElseThrow();
        return snapshotRepository.findFirstByUserAndDaysOrderByGeneratedAtDesc(user, safeDays)
                .filter(snapshot -> AppTime.today().equals(snapshot.getPeriodEnd()))
                .map(snapshot -> withTracking(user,Jsons.fromJson(objectMapper, snapshot.getReportJson(), WeeklyReportResponse.class)))
                .orElseGet(() -> generateForUser(user, safeDays));
    }

    @Transactional(readOnly = true)
    public WeeklyReportResponse latest(UserPrincipal principal) {
        UserAccount user = userRepository.findById(principal.getId()).orElseThrow();
        return latest(user);
    }

    @Transactional(readOnly = true)
    public WeeklyReportResponse latest(UserAccount user) {
        return snapshotRepository.findFirstByUserAndDaysOrderByGeneratedAtDesc(user, 7)
                .filter(snapshot -> AppTime.today().equals(snapshot.getPeriodEnd()))
                .map(snapshot -> withTracking(user,Jsons.fromJson(objectMapper, snapshot.getReportJson(), WeeklyReportResponse.class)))
                .orElseGet(() -> generateForUser(user, 7));
    }

    public WeeklyReportResponse generateForJob(Long userId, int days, Long jobId, LocalDate periodEnd) {
        UserAccount user = userRepository.findById(userId).orElseThrow();
        int safeDays = Math.max(1, Math.min(days, 30));
        return multiAgentOrchestrator.generate(user, generateForUser(user, safeDays, periodEnd), jobId);
    }

    private WeeklyReportResponse generateForUser(UserAccount user, int safeDays) {
        return generateForUser(user, safeDays, AppTime.today());
    }

    private WeeklyReportResponse generateForUser(UserAccount user, int safeDays, LocalDate periodEnd) {
        LocalDate periodStart = periodEnd.minusDays(safeDays - 1L);
        List<MealRecord> records = mealAnalysisService.listPeriod(user, periodStart, periodEnd);
        WeeklyReportResponse response = new WeeklyReportResponse();
        response.setPeriodStart(periodStart);
        response.setPeriodEnd(periodEnd);
        response.setDays(safeDays);
        response.setTotalMeals(records.size());

        Map<String, Integer> categoryTotals = new LinkedHashMap<>();
        Map<String, Integer> categoryMealCounts = new LinkedHashMap<>();
        Map<String, Integer> riskTotals = new LinkedHashMap<>();
        double scoreSum = 0.0;
        for (MealRecord record : records) {
            scoreSum += record.getScore();
            Jsons.readStringIntegerMap(objectMapper, record.getCategoryCountsJson())
                    .forEach((key, value) -> {
                        if (value != null && value > 0) {
                            categoryTotals.merge(key, value, Integer::sum);
                            categoryMealCounts.merge(key, 1, Integer::sum);
                        }
                    });
            List<?> risks = Jsons.readListOrEmpty(objectMapper, record.getRiskTagsJson());
            for (Object risk : new java.util.LinkedHashSet<>(risks)) {
                String key = String.valueOf(risk);
                riskTotals.put(key, riskTotals.getOrDefault(key, 0) + 1);
            }
        }

        response.setCategoryTotals(categoryTotals);
        response.setCategoryMealCounts(categoryMealCounts);
        response.setRiskTotals(riskTotals);
        response.setAverageScore(records.isEmpty() ? 0.0 : Math.round(scoreSum / records.size() * 10.0) / 10.0);
        response.setHighlights(makeHighlights(records.size(), categoryMealCounts, riskTotals, response.getAverageScore()));
        response.setNextWeekSuggestions(makeSuggestions(categoryMealCounts, riskTotals, records.size()));
        response.setGoalType(userGoalService.effectiveGoal(user, "current"));
        response.setGeneratedAt(AppTime.now());
        response.setReportText(makeReportText(response));
        withTracking(user,response);
        return response;
    }

    private WeeklyReportResponse withTracking(UserAccount user,WeeklyReportResponse response) {
        if(tracking!=null && response.getPeriodStart()!=null && response.getPeriodEnd()!=null) response.setTracking(tracking.summary(user.getId(),response.getPeriodStart(),response.getPeriodEnd()));
        return response;
    }

    private List<String> makeHighlights(int totalMeals,
                                        Map<String, Integer> categories,
                                        Map<String, Integer> risks,
                                        double averageScore) {
        List<String> highlights = new ArrayList<>();
        if (totalMeals == 0) {
            highlights.add("本周还没有饮食记录，建议先记录几餐，建立可分析的基线。");
            return highlights;
        }
        highlights.add("本周共记录 " + totalMeals + " 餐，平均评分为 " + averageScore + "。");
        if (categories.getOrDefault("vegetable", 0) < totalMeals) {
            highlights.add("蔬菜覆盖率还可以继续提高。");
        }
        if (categories.getOrDefault("protein", 0) < totalMeals) {
            highlights.add("部分餐次缺少稳定蛋白来源。");
        }
        if (!risks.isEmpty()) {
            highlights.add("本周仍存在重复风险标签，需要继续压低频率。");
        }
        return highlights;
    }

    private List<String> makeSuggestions(Map<String, Integer> categories,
                                         Map<String, Integer> risks,
                                         int totalMeals) {
        List<String> suggestions = new ArrayList<>();
        if (categories.getOrDefault("vegetable", 0) < totalMeals) {
            suggestions.add("下周优先把蔬菜覆盖到更多餐次。");
        }
        if (categories.getOrDefault("protein", 0) < totalMeals) {
            suggestions.add("下周至少保证多数餐次有稳定蛋白来源。");
        }
        if (!risks.isEmpty()) {
            suggestions.add("下周先减少最常见风险标签对应的食物频率。");
        }
        if (suggestions.isEmpty()) {
            suggestions.add("继续保持当前结构，并观察评分是否稳定提升。");
        }
        return suggestions;
    }

    private String makeReportText(WeeklyReportResponse response) {
        if (response.getTotalMeals() == 0) {
            return "最近 " + response.getDays() + " 天还没有足够记录，先记录几餐，再查看趋势会更有参考价值。";
        }
        return "最近 " + response.getDays() + " 天共记录 " + response.getTotalMeals()
                + " 餐，平均评分为 " + response.getAverageScore()
                + "。当前目标为 " + response.getGoalType()
                + "。本周重点：" + String.join("", response.getHighlights());
    }
}
