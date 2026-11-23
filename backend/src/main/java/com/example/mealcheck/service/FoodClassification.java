package com.example.mealcheck.service;

import com.example.mealcheck.dto.FoodAttributes;
import com.example.mealcheck.dto.FoodItem;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/** New writes use a base food group plus independent risk attributes. Old JSON remains readable. */
public final class FoodClassification {
    private FoodClassification() { }
    public static final List<String> BASE_CATEGORIES = List.of(
            "staple", "protein", "vegetable", "fruit", "dairy", "soup", "drink", "dessert", "other");
    private static final Pattern STIR_FRIED = Pattern.compile("\\bstir[ -]+fried\\b");
    private static final Pattern NEGATED_FRIED = Pattern.compile("\\b(?:not|non)[ -]+fried\\b");
    private static final Pattern FRIED_NAME = Pattern.compile("\\bfried\\b");
    // Deliberately narrow: a legacy generic 'fried food' cannot establish its ingredients.
    private static final Pattern LEGACY_PROTEIN = Pattern.compile(
            "(?:(?:油炸|香酥|酥炸|脆皮|煎炸|炸)?(?:鸡|鸡腿|鸡翅|鸡块|鸡排|鸡胸肉|鸡肉|鱼|鱼排|鱼块|虾|虾仁|豆腐)(?:块)?"
                    + "|红烧肉|红烧五花肉|五花肉|braised pork belly|pork belly"
                    + "|(?:(?:deep|pan)[ -])?fried (?:chicken|fish|shrimp|tofu)(?: wings| legs| pieces)?)");
    private static final Pattern LEGACY_STAPLE = Pattern.compile(
            "(?:(?:油炸|香脆|酥脆|炸)?(?:薯条|土豆条|油条|馒头)|(?:french |fried )?fries)");

    public static boolean isSupportedCategory(String category) {
        String value = categoryCode(category);
        return BASE_CATEGORIES.contains(value) || value.equals("fried") || value.equals("oily");
    }

    public static String baseCategory(FoodItem food) {
        String category = categoryCode(food.getCategory());
        if (BASE_CATEGORIES.contains(category)) return category;
        if (!category.equals("fried") && !category.equals("oily")) return "other";
        String name = normalizedName(food);
        if (LEGACY_PROTEIN.matcher(name).matches()) return "protein";
        if (LEGACY_STAPLE.matcher(name).matches()) return "staple";
        return "other";
    }

    public static FoodItem normalized(FoodItem food) {
        FoodAttributes supplied = food.getAttributes();
        Boolean fried = supplied == null ? null : supplied.getFried();
        Boolean highOil = supplied == null ? null : supplied.getHighOil();
        String legacy = categoryCode(food.getCategory());
        // An explicitly supplied null stays unknown. Only missing legacy attributes use name/category recovery.
        if (supplied == null) {
            if (legacy.equals("fried") || namedFried(food)) fried = true;
            if (legacy.equals("oily") || namedHighOil(food)) highOil = true;
        }
        var result = new FoodItem(food.getName().trim(), baseCategory(food), food.getConfidence(), food.getNote(),
                new FoodAttributes(fried, highOil));
        result.setReviewIssues(food.getReviewIssues());
        result.setIdentityEvidence(food.getIdentityEvidence());
        return result;
    }

    public static boolean hasMarkedRisk(FoodItem food) {
        FoodAttributes attributes = food.getAttributes();
        return attributes != null && (Boolean.TRUE.equals(attributes.getFried()) || Boolean.TRUE.equals(attributes.getHighOil()));
    }

    public static boolean needsOilReview(FoodItem food) {
        FoodAttributes attributes = food.getAttributes();
        if (hasMarkedRisk(food) || (attributes != null && attributes.getHighOil() != null)) return false;
        String name = normalizedName(food);
        return reviewIssue(food, "OIL_CONFLICT") || namedHighOil(food) || List.of("红烧肉", "五花肉", "pork belly", "红烧茄子", "油焖茄子").stream().anyMatch(name::contains);
    }

    public static boolean needsFryingReview(FoodItem food) {
        var attributes = food.getAttributes();
        return (attributes == null || attributes.getFried() == null) && (reviewIssue(food, "FRYING_CONFLICT") || namedFried(food));
    }

    public static boolean reviewIssue(FoodItem food, String code) {
        return food.getReviewIssues() != null && food.getReviewIssues().stream().anyMatch(issue -> code.equals(issue.code()));
    }

    private static boolean namedFried(FoodItem food) {
        String name = normalizedName(food);
        if (name.matches("(?:空气炸锅|空气炸)(?:薯条|鸡腿|鸡翅|鸡块|鸡排|鸡胸肉|鱼排)")
                || name.matches("air[ -]fried (?:fries|chicken|chicken legs|chicken wings|fish)")) return false;
        name = STIR_FRIED.matcher(name).replaceAll("");
        name = NEGATED_FRIED.matcher(name).replaceAll("")
                .replace("非油炸", "").replace("非油煎", "")
                .replace("空气炸锅", "").replace("空气炸", "").replace("air-fried", "").replace("air fried", "");
        return FRIED_NAME.matcher(name).find() || name.contains("炸") || name.contains("薯条");
    }

    private static boolean namedHighOil(FoodItem food) {
        String name = normalizedName(food).replace("非高油", "").replace("不油腻", "").replace("不重油", "");
        return List.of("高油", "重油", "油脂多", "油腻").stream().anyMatch(name::contains);
    }

    private static String normalizedName(FoodItem food) {
        return food.getName() == null ? "" : food.getName().trim().toLowerCase(Locale.ROOT)
                .replaceAll("[\\u2010-\\u2015\\u2212]", "-").replaceAll("\\s+", " ");
    }
    private static String categoryCode(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }
}
