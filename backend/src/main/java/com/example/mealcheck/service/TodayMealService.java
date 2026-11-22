package com.example.mealcheck.service;

import com.example.mealcheck.dto.FoodItem;
import com.example.mealcheck.dto.MealPlanningDtos.*;
import com.example.mealcheck.repository.ActionPlanRepository;
import com.example.mealcheck.repository.MealPlanningRepository;
import com.example.mealcheck.util.AppTime;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import java.util.*;
import java.util.regex.Pattern;

@Service
public class TodayMealService {
    private static final Pattern AMBIGUOUS = Pattern.compile("可能是|可能为|疑似|或豆浆|或牛奶|无法辨认|名称不确定|未确认");
    private static final Pattern FRYING = Pattern.compile("炸鸡|薯条|炸鱼|炸豆腐|油炸|炸制|煎炸");
    private static final Pattern OIL = Pattern.compile("红烧肉|五花肉|红烧茄子|用油|油光|油润|油腻|高油|肥瘦");
    private final MealPlanningRepository meals;
    private final TrackingStatisticsService tracking;
    private final ActionPlanRepository plans;
    public TodayMealService(MealPlanningRepository meals, TrackingStatisticsService tracking, ActionPlanRepository plans) {
        this.meals = meals; this.tracking = tracking; this.plans = plans;
    }
    /** Same review triggers as recognitionExplanation.js; unknown alone is not a confirmed risk. */
    public static boolean needsReview(FoodItem food) {
        String text = Objects.toString(food.getName(), "") + Objects.toString(food.getNote(), "");
        var attributes = food.getAttributes();
        return (food.getReviewIssues() != null && !food.getReviewIssues().isEmpty()) || food.getConfidence() < .8 || AMBIGUOUS.matcher(text).find()
                || ((attributes == null || attributes.getFried() == null) && FRYING.matcher(text).find())
                || ((attributes == null || attributes.getHighOil() == null)
                    && (attributes == null || !Boolean.TRUE.equals(attributes.getFried())) && OIL.matcher(text).find());
    }
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Today get(long userId) {
        var day = AppTime.today();
        var summary = tracking.summary(userId, day, day);
        List<ReviewMeal> reviews = new ArrayList<>();
        int reviewFoods = 0;
        for (var meal : meals.today(userId, day)) {
            List<String> names = new ArrayList<>();
            meal.foods().stream().filter(TodayMealService::needsReview).map(FoodItem::getName).forEach(names::add);
            meal.uncertainFoods().stream().map(FoodItem::getName).forEach(names::add);
            if (!names.isEmpty()) { reviewFoods += names.size(); reviews.add(new ReviewMeal(meal.id(), meal.mealType(), names, names.size())); }
        }
        var pending = plans.pendingToday(userId, day);
        return new Today(day, AppTime.businessZone().getId(), summary.totalMeals(), summary.daily().get(0).slots(),
                reviewFoods, reviews.size(), reviews.stream().limit(5).toList(), pending.totalElements(), pending.content());
    }
}
