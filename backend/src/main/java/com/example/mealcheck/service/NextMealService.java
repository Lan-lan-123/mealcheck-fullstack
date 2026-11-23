package com.example.mealcheck.service;

import com.example.mealcheck.dto.DietPreferences;
import com.example.mealcheck.dto.MealPlanningDtos.*;
import com.example.mealcheck.repository.MealPlanningRepository;
import com.example.mealcheck.security.UserPrincipal;
import com.example.mealcheck.util.AppTime;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.io.IOException;
import java.util.*;

/** Deterministic planning: explicit reference costs and ingredients, no additional AI calls. */
@Service
public class NextMealService {
    public record Ingredient(String id, String name, String group, List<String> tags, String animal, List<String> mealTypes,
                             int homeMinutes, Map<String, List<Integer>> costs) { }
    public record Catalog(String version, String costBasis, String timeBasis, List<Ingredient> foods) { }
    private record Candidate(List<Ingredient> foods, int min, int max, int minutes, int repeats, int goalPenalty) { }
    private final DietPreferenceService preferences;
    private final UserGoalService goals;
    private final MealPlanningRepository meals;
    private final Catalog catalog;
    public NextMealService(DietPreferenceService preferences, UserGoalService goals, MealPlanningRepository meals, ObjectMapper mapper) {
        this.preferences = preferences; this.goals = goals; this.meals = meals;
        try (var input = new ClassPathResource("knowledge/meal_planning_catalog.json").getInputStream()) {
            catalog = mapper.readValue(input, Catalog.class);
        } catch (IOException error) { throw new IllegalStateException("无法加载餐食搭配库", error); }
    }
    @Transactional(readOnly = true, isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public Recommendations recommend(UserPrincipal user, NextMealRequest request) {
        var settings = preferences.get(user.getId());
        var profile = goals.current(user);
        var goal = profile == null || profile.getGoalType() == null ? "balanced" : profile.getGoalType();
        var day = AppTime.today();
        var recent = meals.recent(user.getId(), day);
        var names = recent.stream().flatMap(meal -> meal.foods().stream()).map(food -> Objects.toString(food.getName(), "")).toList();
        boolean vegetablesSeen = recent.stream().flatMap(meal -> meal.foods().stream()).anyMatch(food -> "vegetable".equals(FoodClassification.baseCategory(food)));
        String context = recent.isEmpty() ? "近 7 天暂无餐食记录，先按你的设置提供搭配参考。"
                : "参考近 7 天最近 " + recent.size() + " 条已记录餐食，优先提供不同食物的搭配。"
                  + (vegetablesSeen ? "" : "这些记录中未识别到蔬菜来源，可在下一餐留意搭配；不代表实际没有摄入。");
        var allowed = catalog.foods().stream().filter(food -> food.costs().containsKey(request.scene()))
                .filter(food -> food.mealTypes().contains(request.mealType()))
                .filter(food -> allowed(food, settings)).toList();
        var staples = allowed.stream().filter(food -> food.group().equals("staple")).toList();
        var proteins = allowed.stream().filter(food -> food.group().equals("protein"))
                .filter(food -> request.mealType().equals("BREAKFAST") || !food.id().equals("soymilk")).toList();
        var sides = allowed.stream().filter(food -> food.group().equals("vegetable")
                || request.mealType().equals("BREAKFAST") && food.group().equals("fruit")).toList();
        List<Candidate> candidates = new ArrayList<>();
        for (var staple : staples) for (var protein : proteins) for (var side : sides) {
            var foods = List.of(staple, protein, side);
            int min = foods.stream().mapToInt(food -> food.costs().get(request.scene()).get(0)).sum();
            int max = foods.stream().mapToInt(food -> food.costs().get(request.scene()).get(1)).sum();
            int minutes = request.scene().equals("HOME") ? foods.stream().mapToInt(Ingredient::homeMinutes).max().orElse(0) + 5 : 5;
            int repeats = foods.stream().mapToInt(food -> (int) names.stream().filter(name -> name.contains(food.name())
                    || food.tags().stream().anyMatch(name::contains)).count()).sum();
            int goalPenalty = goal.equals("muscle_gain") && protein.id().equals("egg") ? 1
                    : goal.equals("light") && side.id().equals("greens") ? 1 : 0;
            if (max <= request.budget() && minutes <= request.availableMinutes()) candidates.add(new Candidate(foods, min, max, minutes, repeats, goalPenalty));
        }
        candidates.sort(Comparator.comparingInt(Candidate::goalPenalty).thenComparingInt(Candidate::repeats).thenComparingInt(Candidate::max)
                .thenComparingInt(Candidate::minutes).thenComparing(candidate -> key(candidate.foods())));
        List<Candidate> selected = new ArrayList<>();
        Set<String> proteinIds = new HashSet<>();
        for (var candidate : candidates) {
            if (proteinIds.add(candidate.foods().get(1).id())) selected.add(candidate);
            if (selected.size() == 3) break;
        }
        for (var candidate : candidates) {
            if (selected.size() == 3) break;
            if (!selected.contains(candidate)) selected.add(candidate);
        }
        String goalAdvice = switch (goal) {
            case "fat_loss" -> "按减脂方向选择搭配，份量仍需结合实际需要调整，不按照片推算热量。";
            case "muscle_gain" -> "按增肌方向保留主食与蛋白质食物，份量需结合实际活动和需要调整。";
            case "light" -> "按清淡方向优先蒸煮和原味搭配，点餐时可要求酱汁另放。";
            default -> "按均衡方向组合主食、蛋白质食物与蔬果，份量按实际需要调整。";
        };
        var options = selected.stream().map(candidate -> {
            var foods = candidate.foods().stream().map(Ingredient::name).toList();
            String title = String.join(" + ", foods);
            var reasons = List.of(goalAdvice, "已按保存的饮食类型、忌口和不喜欢的食材筛选具名食材；包装配料和门店做法仍需核对。");
            String action = mealLabel(request.mealType()) + "搭配：" + String.join("、", foods) + "。场景：" + sceneLabel(request.scene())
                    + "；参考费用 " + candidate.min() + "–" + candidate.max() + " 元（非报价，不含配送等费用）"
                    + "；选餐/准备参考 " + candidate.minutes() + " 分钟（不含排队与配送）。" + goalAdvice + "配料不明时先核对。";
            return new MealOption(catalog.version() + ":" + key(candidate.foods()), title, foods, candidate.min(), candidate.max(), candidate.minutes(), reasons, action);
        }).toList();
        String summary = switch (settings.style()) { case VEGAN -> "纯素"; case VEGETARIAN -> "蛋奶素"; default -> "饮食类型不限"; };
        summary += "；已应用 " + settings.avoidFoods().size() + " 项忌口、" + settings.dislikedFoods().size() + " 项不喜欢的食材";
        return new Recommendations(day, request, goal, summary, recent.size(), context, catalog.costBasis(), catalog.timeBasis(), options,
                options.isEmpty() ? "当前搭配库没有同时满足这些条件的方案。可增加预算或准备时间、切换场景；保留忌口设置。" : "选择一套搭配，可编辑后加入行动计划。");
    }
    static boolean allowed(Ingredient food, DietPreferences settings) {
        if (settings.style() == DietPreferences.Style.VEGAN && !food.animal().equals("NONE")) return false;
        if (settings.style() == DietPreferences.Style.VEGETARIAN && food.animal().equals("MEAT")) return false;
        String text = food.name() + " " + String.join(" ", food.tags());
        return text.equals(DietPreferenceService.guard(text, settings));
    }
    private static String key(List<Ingredient> foods) { return String.join("-", foods.stream().map(Ingredient::id).toList()); }
    private static String mealLabel(String meal) { return switch (meal) { case "BREAKFAST" -> "早餐"; case "LUNCH" -> "午餐"; default -> "晚餐"; }; }
    private static String sceneLabel(String scene) { return switch (scene) { case "HOME" -> "自己准备"; case "CANTEEN" -> "食堂"; default -> "外卖"; }; }
}
