package com.example.mealcheck.service;

import com.example.mealcheck.dto.WeeklyReportResponse;
import com.example.mealcheck.entity.MealRecord;
import com.example.mealcheck.entity.UserAccount;
import com.example.mealcheck.entity.WeeklyReportSnapshot;
import com.example.mealcheck.repository.UserAccountRepository;
import com.example.mealcheck.repository.WeeklyReportSnapshotRepository;
import com.example.mealcheck.security.UserPrincipal;
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
    private final UserAccountRepository userRepository;
    private final MealAnalysisService mealAnalysisService;
    private final ObjectMapper objectMapper;
    private final WeeklyReportSnapshotRepository snapshotRepository;
    private final UserGoalService userGoalService;

    public WeeklyReportService(UserAccountRepository userRepository,
                               MealAnalysisService mealAnalysisService,
                               ObjectMapper objectMapper,
                               WeeklyReportSnapshotRepository snapshotRepository,
                               UserGoalService userGoalService) {
        this.userRepository = userRepository;
        this.mealAnalysisService = mealAnalysisService;
        this.objectMapper = objectMapper;
        this.snapshotRepository = snapshotRepository;
        this.userGoalService = userGoalService;
    }

    @Transactional(readOnly = true)
    public WeeklyReportResponse generate(UserPrincipal principal, int days) {
        int safeDays = Math.max(1, Math.min(days, 30));
        UserAccount user = userRepository.findByUsername(principal.getUsername()).orElseThrow();
        return generateForUser(user, safeDays);
    }

    @Transactional(readOnly = true)
    public WeeklyReportResponse latest(UserPrincipal principal) {
        UserAccount user = userRepository.findByUsername(principal.getUsername()).orElseThrow();
        return latest(user);
    }

    @Transactional(readOnly = true)
    public WeeklyReportResponse latest(UserAccount user) {
        return snapshotRepository.findFirstByUserOrderByGeneratedAtDesc(user)
                .map(snapshot -> Jsons.fromJson(objectMapper, snapshot.getReportJson(), WeeklyReportResponse.class))
                .orElseGet(() -> generateForUser(user, 7));
    }

    @Transactional
    public WeeklyReportResponse generateAndStore(UserAccount user, int days) {
        WeeklyReportResponse response = generateForUser(user, days);
        WeeklyReportSnapshot snapshot = new WeeklyReportSnapshot();
        snapshot.setUser(user);
        snapshot.setDays(days);
        snapshot.setPeriodEnd(LocalDate.now());
        snapshot.setPeriodStart(LocalDate.now().minusDays(days - 1L));
        snapshot.setReportJson(Jsons.toJson(objectMapper, response));
        snapshotRepository.save(snapshot);
        return response;
    }

    private WeeklyReportResponse generateForUser(UserAccount user, int safeDays) {
        List<MealRecord> records = mealAnalysisService.listSince(user, safeDays);
        WeeklyReportResponse response = new WeeklyReportResponse();
        response.setDays(safeDays);
        response.setTotalMeals(records.size());

        Map<String, Integer> categoryTotals = new LinkedHashMap<>();
        Map<String, Integer> riskTotals = new LinkedHashMap<>();
        double scoreSum = 0.0;
        for (MealRecord record : records) {
            scoreSum += record.getScore();
            Jsons.readStringIntegerMap(objectMapper, record.getCategoryCountsJson())
                    .forEach((key, value) -> categoryTotals.put(key, categoryTotals.getOrDefault(key, 0) + value));
            List<?> risks = Jsons.readListOrEmpty(objectMapper, record.getRiskTagsJson());
            for (Object risk : risks) {
                String key = String.valueOf(risk);
                riskTotals.put(key, riskTotals.getOrDefault(key, 0) + 1);
            }
        }

        response.setCategoryTotals(categoryTotals);
        response.setRiskTotals(riskTotals);
        response.setAverageScore(records.isEmpty() ? 0.0 : Math.round(scoreSum / records.size() * 10.0) / 10.0);
        response.setHighlights(makeHighlights(records.size(), categoryTotals, riskTotals, response.getAverageScore()));
        response.setNextWeekSuggestions(makeSuggestions(categoryTotals, riskTotals, records.size()));
        response.setGoalType(userGoalService.effectiveGoal(user, "current"));
        response.setGeneratedAt(LocalDateTime.now());
        response.setReportText(makeReportText(response));
        return response;
    }

    private List<String> makeHighlights(int totalMeals,
                                        Map<String, Integer> categories,
                                        Map<String, Integer> risks,
                                        double averageScore) {
        List<String> highlights = new ArrayList<>();
        if (totalMeals == 0) {
            highlights.add("本周还没有饮食记录，建议先连续上传几餐，建立可分析的基线。");
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
            return "最近 " + response.getDays() + " 天还没有足够记录，先连续上传几餐，再查看趋势会更有参考价值。";
        }
        return "最近 " + response.getDays() + " 天共记录 " + response.getTotalMeals()
                + " 餐，平均评分为 " + response.getAverageScore()
                + "。当前目标为 " + response.getGoalType()
                + "。本周重点：" + String.join("", response.getHighlights());
    }
}
