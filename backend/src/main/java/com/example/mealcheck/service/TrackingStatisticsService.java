package com.example.mealcheck.service;

import com.example.mealcheck.dto.TrackingDtos.*;
import com.example.mealcheck.repository.*;
import com.example.mealcheck.repository.TrackingQueryRepository.Slot;
import com.example.mealcheck.util.AppTime;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.*;
import java.time.temporal.*;
import java.util.*;

@Service
public class TrackingStatisticsService {
    private static final List<String> TYPES = List.of("BREAKFAST", "LUNCH", "DINNER", "SNACK");
    private final TrackingQueryRepository queries;
    private final BehaviorGoalRepository goals;
    public TrackingStatisticsService(TrackingQueryRepository queries, BehaviorGoalRepository goals) { this.queries = queries; this.goals = goals; }
    @Transactional(readOnly = true, isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public Summary summary(long userId, LocalDate from, LocalDate to) {
        JournalValidation.range(from, to);
        var selectedGoals = goals.overlapping(userId, from, to);
        LocalDate aggregateFrom = selectedGoals.stream().map(Goal::startDate).min(LocalDate::compareTo).filter(d -> d.isBefore(from)).orElse(from);
        Map<LocalDate, Map<String, Integer>> meals = new HashMap<>();
        for (var row : queries.meals(userId, aggregateFrom, to)) meals.computeIfAbsent(row.day(), ignored -> new HashMap<>()).put(row.type(), row.count());
        var skipped = queries.skipped(userId, from, to);
        List<Day> daily = new ArrayList<>(); int total = 0, recorded = 0, unknown = 0, confirmed = 0;
        for (LocalDate day = from; !day.isAfter(to); day = day.plusDays(1)) {
            var types = meals.getOrDefault(day, Map.of()); int count = types.values().stream().mapToInt(Integer::intValue).sum();
            Map<String, String> slots = new LinkedHashMap<>();
            for (String type : TYPES) {
                String state = types.containsKey(type) ? "RECORDED" : skipped.contains(new Slot(day, type)) ? "SKIPPED" : "UNKNOWN";
                slots.put(type, state);
                if (!type.equals("SNACK")) { if (state.equals("UNKNOWN")) unknown++; if (state.equals("SKIPPED")) confirmed++; }
            }
            if (count > 0) recorded++; total += count; daily.add(new Day(day, count, slots));
        }
        // Prefix counts make each goal calculation independent of its history length.
        var recordedPrefix = new TreeMap<LocalDate, Integer>();
        List<NavigableMap<LocalDate, Integer>> achievedPrefix = new ArrayList<>();
        for (int i = 0; i < 4; i++) achievedPrefix.add(new TreeMap<>());
        int recordedSum = 0; int[] achieved = new int[4];
        for (LocalDate day = aggregateFrom; !day.isAfter(to); day = day.plusDays(1)) {
            var types = meals.getOrDefault(day, Map.of()); if (!types.isEmpty()) recordedSum++;
            int slots = (int) TYPES.stream().filter(types::containsKey).count(); recordedPrefix.put(day, recordedSum);
            for (int i = 0; i < 4; i++) { if (slots >= i + 1) achieved[i]++; achievedPrefix.get(i).put(day, achieved[i]); }
        }
        var progress = selectedGoals.stream().map(g -> progress(g, to, meals, recordedPrefix, achievedPrefix)).toList();
        int days = daily.size();
        String explanation = "按实际用餐时间（" + AppTime.businessZone().getId() + "）统计；尚未确认时间的图片记录暂用创建时间。记录覆盖率=有记录的天数/统计天数。未记录表示未知，只有主动标记才表示未吃。餐次覆盖以三餐为基准，加餐单列；计划按计划日期归入周期。营养估算不参与目标判断。";
        if (recorded < Math.min(3, days)) explanation += " 当前记录较少，评分和食物分布仅代表已记录餐食，不能代表全部饮食。";
        return new Summary(from, to, total, recorded, days, Math.round(recorded * 1000.0 / days) / 10.0, confirmed, unknown, daily, progress, queries.plans(userId, from, to), explanation);
    }
    private int sum(NavigableMap<LocalDate, Integer> prefix, LocalDate from, LocalDate to) {
        var end = prefix.floorEntry(to); var before = prefix.lowerEntry(from);
        return (end == null ? 0 : end.getValue()) - (before == null ? 0 : before.getValue());
    }
    private Progress progress(Goal goal, LocalDate reference, Map<LocalDate, Map<String, Integer>> meals,
                              NavigableMap<LocalDate, Integer> recorded, List<NavigableMap<LocalDate, Integer>> achieved) {
        LocalDate day = reference.isAfter(AppTime.today()) ? AppTime.today() : reference;
        if (day.isAfter(goal.endDate())) day = goal.endDate();
        if (day.isBefore(goal.startDate())) return new Progress(goal, goal.startDate(), goal.startDate(), 0, goal.target(), false, 0, 0);
        boolean daily = goal.kind().equals("MEALS_DAILY");
        LocalDate from = daily ? day : day.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        LocalDate to = daily ? day : from.plusDays(6);
        if (from.isBefore(goal.startDate())) from = goal.startDate(); if (to.isAfter(goal.endDate())) to = goal.endDate();
        var types = meals.getOrDefault(day, Map.of());
        int completed = daily ? (int) TYPES.stream().filter(types::containsKey).count() : sum(recorded, from, day);
        int eligible = daily ? (int) ChronoUnit.DAYS.between(goal.startDate(), day) + 1 : 0;
        int met = daily ? sum(achieved.get(goal.target() - 1), goal.startDate(), day) : 0;
        return new Progress(goal, from, to, completed, Math.max(0, goal.target() - completed), completed >= goal.target(), eligible, met);
    }
}
