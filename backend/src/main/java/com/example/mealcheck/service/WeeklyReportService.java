package com.example.mealcheck.service;

import com.example.mealcheck.dto.WeeklyReportResponse;
import com.example.mealcheck.entity.MealRecord;
import com.example.mealcheck.entity.UserAccount;
import com.example.mealcheck.repository.UserAccountRepository;
import com.example.mealcheck.security.UserPrincipal;
import com.example.mealcheck.util.Jsons;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class WeeklyReportService {
    private final UserAccountRepository userRepository;
    private final MealAnalysisService mealAnalysisService;
    private final ObjectMapper objectMapper;

    public WeeklyReportService(UserAccountRepository userRepository, MealAnalysisService mealAnalysisService, ObjectMapper objectMapper) {
        this.userRepository = userRepository;
        this.mealAnalysisService = mealAnalysisService;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public WeeklyReportResponse generate(UserPrincipal principal, int days) {
        int safeDays = Math.max(1, Math.min(days, 30));
        UserAccount user = userRepository.findByUsername(principal.getUsername()).orElseThrow();
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
                    .forEach((k, v) -> categoryTotals.put(k, categoryTotals.getOrDefault(k, 0) + v));
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
        response.setReportText(makeReportText(response));
        return response;
    }

    private List<String> makeHighlights(int totalMeals, Map<String, Integer> categories, Map<String, Integer> risks, double avgScore) {
        List<String> highlights = new ArrayList<>();
        if (totalMeals == 0) {
            highlights.add("本周期还没有饮食记录，请先上传几张食堂餐盘照片。 ");
            return highlights;
        }
        highlights.add("本周期共记录 " + totalMeals + " 餐，平均饮食结构评分为 " + avgScore + " 分。 ");
        if (categories.getOrDefault("vegetable", 0) < totalMeals) {
            highlights.add("蔬菜出现频率偏低，建议每餐至少搭配一份绿叶菜、菌菇或瓜茄类蔬菜。 ");
        } else {
            highlights.add("蔬菜覆盖情况较好，继续保持。 ");
        }
        if (categories.getOrDefault("protein", 0) < totalMeals) {
            highlights.add("部分餐次蛋白质不足，可增加鸡蛋、豆腐、鸡肉、鱼肉或牛奶。 ");
        }
        if (risks.getOrDefault("高油风险", 0) > 0) {
            highlights.add("出现高油风险餐次，建议减少油炸、干锅、红烧类菜品频率。 ");
        }
        if (risks.getOrDefault("含糖饮品/甜品风险", 0) > 0) {
            highlights.add("出现含糖饮品或甜品，建议优先选择白水、无糖茶或牛奶。 ");
        }
        return highlights;
    }

    private String makeReportText(WeeklyReportResponse response) {
        if (response.getTotalMeals() == 0) {
            return "最近 " + response.getDays() + " 天暂无记录。上传饭菜照片后，系统会自动生成饮食结构趋势报告。";
        }
        return "最近 " + response.getDays() + " 天共记录 " + response.getTotalMeals() + " 餐，平均评分为 "
                + response.getAverageScore() + " 分。"
                + String.join("", response.getHighlights())
                + "下周建议继续关注蔬菜、优质蛋白和高油高糖风险，逐步改善食堂就餐结构。";
    }
}
