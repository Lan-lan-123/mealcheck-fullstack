package com.example.mealcheck.service;

import com.example.mealcheck.dto.FoodItem;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/** An unknown attribute is not itself a reason to ask the user to verify cooking risks. */
final class MealAdviceRiskPolicy {
    private MealAdviceRiskPolicy() { }
    enum Risk { FRIED, HIGH_OIL, EITHER }
    private static final Pattern FRYING_CUE = Pattern.compile("炸|薯条|油条|过油|面衣|裹粉|酥脆|虎皮|干煸|烧茄子|fried|battered");
    private static final Pattern OIL_CUE = Pattern.compile("红烧肉|五花肉|烧茄子|焖|干煸|煎|炒|炸|油光|油亮|油润|油腻|高油|肥瘦|用油|裹油|greasy|oily|fried");

    static Boolean value(FoodItem food, Risk risk) {
        var attributes = food.getAttributes();
        Boolean fried = attributes == null ? null : attributes.getFried();
        Boolean oil = attributes == null ? null : attributes.getHighOil();
        if (risk == Risk.FRIED) return fried;
        if (risk == Risk.HIGH_OIL) return oil;
        if (Boolean.TRUE.equals(fried) || Boolean.TRUE.equals(oil)) return true;
        if (Boolean.FALSE.equals(fried) && Boolean.FALSE.equals(oil)) return false;
        return null;
    }

    static boolean shouldReview(FoodItem food, Risk risk) {
        if (risk == Risk.EITHER) return shouldReview(food, Risk.FRIED) || shouldReview(food, Risk.HIGH_OIL);
        if (value(food, risk) != null) return false;
        if (FoodClassification.reviewIssue(food, risk == Risk.FRIED ? "FRYING_CONFLICT" : "OIL_CONFLICT")) return true;
        String cues = (food.getName() + " " + (food.getNote() == null ? "" : food.getNote())).toLowerCase(Locale.ROOT)
                .replaceAll("(?:无|未见|没有|不含)(?:明显)?(?:油炸|高油|油光|油脂)(?:(?:或|和|及)(?:油炸|高油|油光|油脂))?(?:风险|迹象|痕迹|特征)?|非油炸|非高油|不油腻|not fried|no oil", "");
        return (risk == Risk.FRIED ? FRYING_CUE : OIL_CUE).matcher(cues).find();
    }

    static String reviewSummary(List<FoodItem> foods) {
        String result = foods.stream().map(food -> {
            var risks = new java.util.ArrayList<String>();
            if (shouldReview(food, Risk.FRIED)) risks.add("油炸做法");
            if (shouldReview(food, Risk.HIGH_OIL)) risks.add("高油风险");
            return risks.isEmpty() ? "" : food.getName() + "：" + String.join("、", risks);
        }).filter(text -> !text.isBlank()).collect(Collectors.joining("；"));
        return result.isBlank() ? "无（不能仅因属性未知就要求核对）" : result;
    }
}
