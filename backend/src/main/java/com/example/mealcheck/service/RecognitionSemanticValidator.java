package com.example.mealcheck.service;

import com.example.mealcheck.dto.FoodAttributes;
import com.example.mealcheck.dto.FoodItem;
import com.example.mealcheck.dto.FoodReviewIssue;
import java.util.ArrayList;
import java.util.Locale;
import java.util.regex.Pattern;

/** Checks model assertions, not user corrections. Conflicts become unknown, never inferred low oil. */
final class RecognitionSemanticValidator {
    private RecognitionSemanticValidator() { }
    private static final Pattern NON_DEEP = Pattern.compile("炒|煎|蒸|煮|炖|凉拌|stir[ -]fried|pan[ -]fried|steamed|boiled|braised");
    private static final Pattern NOT_DEEP = Pattern.compile("不是油炸|并非油炸|非油炸|未(?:经)?油炸|没有油炸|无油炸|未见(?:明显)?油炸|not (?:deep[ -])?fried|non[ -]fried");
    private static final Pattern DEEP = Pattern.compile("油炸|炸制|酥炸|炸鸡|炸鱼|炸豆腐|炸虾|薯条|油条|deep[ -]fried");
    private static final Pattern WHOLE_CORN = Pattern.compile("(?:水煮|煮|清蒸|蒸|烤|甜|糯)?玉米(?:棒|粒|段|棒子)?|(?:boiled |steamed |grilled )?corn(?: on the cob| kernels)?");

    static FoodItem review(FoodItem supplied) {
        var normalized = FoodClassification.normalized(supplied);
        var attributes = normalized.getAttributes();
        Boolean fried = attributes == null ? null : attributes.getFried();
        Boolean highOil = attributes == null ? null : attributes.getHighOil();
        String text = (supplied.getName() + " " + (supplied.getNote() == null ? "" : supplied.getNote())).toLowerCase(Locale.ROOT);
        boolean negated = NOT_DEEP.matcher(text).find() || text.matches("(?s).*(?:空气炸锅|空气炸|air[ -]fried).*" );
        String positive = NOT_DEEP.matcher(text).replaceAll("").replace("空气炸锅", "").replace("空气炸", "")
                .replace("air-fried", "").replace("air fried", "");
        boolean deep = DEEP.matcher(positive).find();
        var issues = new ArrayList<FoodReviewIssue>();
        if ((Boolean.TRUE.equals(fried) && (negated || (!deep && NON_DEEP.matcher(text).find())))
                || (Boolean.FALSE.equals(fried) && deep && !negated)) {
            issues.add(new FoodReviewIssue("FRYING_CONFLICT", "油炸属性（模型标记为" + (fried ? "是" : "否") + "）与名称或做法线索冲突，已保留未知；请核对是否经过油炸。"));
            fried = null;
        }
        if ((Boolean.TRUE.equals(highOil) && text.matches("(?s).*(?:未见明显油光|无明显油脂|无高油迹象).*"))
                || (Boolean.FALSE.equals(highOil) && text.matches("(?s).*(?:明显高油|大量油脂|厚重油层).*"))) {
            issues.add(new FoodReviewIssue("OIL_CONFLICT", "高油属性（模型标记为" + (highOil ? "是" : "否") + "）与描述冲突，已保留未知；不能据此认定实际用油量。"));
            highOil = null;
        }
        String category = normalized.getCategory();
        if (WHOLE_CORN.matcher(supplied.getName().trim().toLowerCase(Locale.ROOT)).matches() && !"staple".equals(category)) {
            issues.add(new FoodReviewIssue("CATEGORY_CONFLICT", "项目将整根或整粒玉米归为主食，模型分类为" + category + "，与此口径冲突；分类暂列其他，请核对。"));
            category = "other";
        }
        var result = new FoodItem(supplied.getName(), category, supplied.getConfidence(), supplied.getNote(),
                new FoodAttributes(fried, highOil));
        result.setReviewIssues(issues);
        result.setIdentityEvidence(supplied.getIdentityEvidence());
        return result;
    }
}
