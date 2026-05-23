package com.example.mealcheck.service;

import com.example.mealcheck.dto.assistant.AssistantProactiveAdviceResponse;
import com.example.mealcheck.entity.MealRecord;
import com.example.mealcheck.entity.UserAccount;
import com.example.mealcheck.util.Jsons;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

@Service
public class AssistantProactiveAdviceService {
    private final MealAnalysisService mealAnalysisService;
    private final ObjectMapper objectMapper;
    private final AtomicLong triggerCount = new AtomicLong();

    public AssistantProactiveAdviceService(MealAnalysisService mealAnalysisService, ObjectMapper objectMapper) {
        this.mealAnalysisService = mealAnalysisService;
        this.objectMapper = objectMapper;
    }

    public AssistantProactiveAdviceResponse build(UserAccount user, String goalType) {
        List<MealRecord> records = mealAnalysisService.listSince(user, 7);
        List<String> items = new ArrayList<>();
        addTodayBalanceAdvice(records, items);
        addVegetableStreakAdvice(records, items);
        addHighOilTrendAdvice(records, items);
        items.add(tomorrowAdvice(goalType, records));
        if (!items.isEmpty()) {
            triggerCount.incrementAndGet();
        }
        return new AssistantProactiveAdviceResponse(items);
    }

    public long triggerCount() {
        return triggerCount.get();
    }

    private void addTodayBalanceAdvice(List<MealRecord> records, List<String> items) {
        List<MealRecord> todayRecords = records.stream()
                .filter(record -> record.getCreatedAt() != null && record.getCreatedAt().toLocalDate().equals(LocalDate.now()))
                .toList();
        if (todayRecords.isEmpty()) {
            items.add("今天还没有饮食记录，先上传一餐，助手会更准确地判断今天是否失衡。");
            return;
        }
        boolean missingVegetable = todayRecords.stream().noneMatch(record -> category(record).getOrDefault("vegetable", 0) > 0);
        boolean missingProtein = todayRecords.stream().noneMatch(record -> category(record).getOrDefault("protein", 0) > 0);
        if (missingVegetable || missingProtein) {
            items.add("今天的记录里" + (missingVegetable ? "蔬菜" : "") + (missingVegetable && missingProtein ? "和" : "") + (missingProtein ? "优质蛋白" : "") + "偏少，下一餐可以优先补齐。");
        } else {
            items.add("今天已记录的餐次结构基本完整，继续注意高油高糖频率即可。");
        }
    }

    private void addVegetableStreakAdvice(List<MealRecord> records, List<String> items) {
        Map<LocalDate, List<MealRecord>> byDay = records.stream()
                .filter(record -> record.getCreatedAt() != null)
                .collect(Collectors.groupingBy(record -> record.getCreatedAt().toLocalDate()));
        int streak = 0;
        for (int offset = 0; offset < 7; offset++) {
            LocalDate day = LocalDate.now().minusDays(offset);
            List<MealRecord> dayRecords = byDay.getOrDefault(day, List.of());
            if (dayRecords.isEmpty() || dayRecords.stream().noneMatch(record -> category(record).getOrDefault("vegetable", 0) > 0)) {
                streak++;
            } else {
                break;
            }
        }
        if (streak >= 2) {
            items.add("已经连续 " + streak + " 天没有看到稳定蔬菜记录，建议明天先把一份绿叶菜放进固定搭配。");
        }
    }

    private void addHighOilTrendAdvice(List<MealRecord> records, List<String> items) {
        LocalDateTime now = LocalDateTime.now();
        long recent = records.stream()
                .filter(record -> record.getCreatedAt() != null && record.getCreatedAt().isAfter(now.minusDays(3)))
                .flatMap(record -> risks(record).stream())
                .filter(tag -> tag.contains("高油"))
                .count();
        long earlier = records.stream()
                .filter(record -> record.getCreatedAt() != null
                        && record.getCreatedAt().isAfter(now.minusDays(7))
                        && !record.getCreatedAt().isAfter(now.minusDays(3)))
                .flatMap(record -> risks(record).stream())
                .filter(tag -> tag.contains("高油"))
                .count();
        if (recent > earlier && recent > 0) {
            items.add("最近 3 天高油风险出现次数比前几天更多，明天可以优先选清蒸、炖煮或凉拌类菜品。");
        }
    }

    private String tomorrowAdvice(String goalType, List<MealRecord> records) {
        String goal = goalType == null ? "balanced" : goalType;
        return switch (goal) {
            case "fat_loss" -> "明天更适合安排“半份主食 + 一份蛋白 + 两份蔬菜”的减脂型餐盘。";
            case "muscle_gain" -> "明天更适合保证每餐都有蛋白质，并保留训练前后的主食补给。";
            case "light" -> "明天更适合选择清淡烹饪，减少红烧、油炸和重口酱汁。";
            default -> "明天更适合继续按“主食 + 蛋白 + 蔬菜”的均衡结构来选餐。";
        };
    }

    private Map<String, Integer> category(MealRecord record) {
        return Jsons.readStringIntegerMap(objectMapper, record.getCategoryCountsJson());
    }

    private List<String> risks(MealRecord record) {
        List<String> values = Jsons.fromJson(objectMapper, record.getRiskTagsJson(), new com.fasterxml.jackson.core.type.TypeReference<List<String>>() {});
        return values == null ? List.of() : values;
    }
}
