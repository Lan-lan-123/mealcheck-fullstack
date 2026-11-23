package com.example.mealcheck.service;

import com.example.mealcheck.dto.*;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

class MealAdviceConsistencyTest {
    @Test void capturedUnknownAndConditionalAdviceIsNotAnAffirmedRisk() {
        String captured="由于番茄炒蛋和清炒青菜的高油风险属性目前未知，建议留意实际烹饪时的用油量，若感觉油腻可适当调整下一餐的口味选择。";
        assertThat(MealAdviceConsistency.consistent(captured,stirFry())).isTrue();
        assertThat(MealAdviceConsistency.consistent("番茄炒蛋高油属性未知，如果确认高油可在下次调整。",stirFry())).isTrue();
        for(String text:List.of("番茄炒蛋高油属性未知，但确实高油，若感觉油腻可调整。",
                "如果番茄炒蛋高油可调整，清炒青菜确实高油。","番茄炒蛋高油未知，但油炸已确认。"))
            assertThat(MealAdviceConsistency.consistent(text,stirFry())).as(text).isFalse();
    }
    private RecognitionResult stirFry() {
        var r=new RecognitionResult();r.setFoods(List.of(new FoodItem("番茄炒蛋","protein",.95,"炒制",new FoodAttributes(false,null)),
                new FoodItem("清炒青菜","vegetable",.95,"油亮",new FoodAttributes(false,null)),
                new FoodItem("白米饭","staple",.98,"蒸制",new FoodAttributes(false,false))));return r;
    }
    @Test void preservesEquivalentUncertaintyAndEligibleReviewExpressions() {
        for(String text:List.of("番茄炒蛋的高油风险属性目前未知。","番茄炒蛋的高油风险尚不清楚。",
                "本餐的高油风险属性目前未知。","建议核对番茄炒蛋的高油风险。","建议核对番茄炒蛋是否高油。",
                "番茄炒蛋是否高油目前无法确定。","尚未确认番茄炒蛋是否高油。"))
            assertThat(MealAdviceConsistency.consistent(text,stirFry())).as(text).isTrue();
    }
    @Test void unknownCannotBeRewrittenAsConfirmedAbsenceOrPresence() {
        for(String text:List.of("番茄炒蛋没有高油风险。","番茄炒蛋高油风险为否。","本餐没有高油风险。",
                "番茄炒蛋存在高油风险。","番茄炒蛋高油风险已确认。","番茄炒蛋不是高油食物。")) {
            assertThat(MealAdviceConsistency.consistent(text,stirFry())).as(text).isFalse();
            assertThat(MealAdviceConsistency.assess(text,stirFry()).findings()).as(text).isNotEmpty();
        }
        assertThat(MealAdviceConsistency.consistent("白米饭不是高油食物。",stirFry())).isTrue();
        assertThat(MealAdviceConsistency.consistent("红烧肉没有高油风险。",meal(false))).isFalse();
        assertThat(MealAdviceConsistency.consistent("红烧肉不高油。",meal(false))).isFalse();
        assertThat(MealAdviceConsistency.consistent("白米饭高油风险已确认没有。",stirFry())).isTrue();
        assertThat(MealAdviceConsistency.consistent("番茄炒蛋高油风险已确认没有。",stirFry())).isFalse();
    }
    @Test void mealAbsenceNeedsEveryRelevantAttributeFalseAndDiagnosticsKeepTheirScope() {
        var r=stirFry();r.getFoods().forEach(f->f.setAttributes(new FoodAttributes(false,false)));
        assertThat(MealAdviceConsistency.consistent("本餐没有高油风险。",r)).isTrue();
        var assessment=MealAdviceConsistency.assess("番茄炒蛋没有高油风险。",stirFry());
        assertThat(assessment.findings()).singleElement().satisfies(f->{
            assertThat(f.branch()).isEqualTo("UNKNOWN_AS_ABSENT");assertThat(f.food()).isEqualTo("番茄炒蛋");
            assertThat(f.attribute()).isEqualTo("HIGH_OIL");assertThat(f.knownValue()).isNull();
        });
        assertThat(assessment.toString()).doesNotContain("番茄炒蛋");
    }
    @Test void reviewsAndFutureClausesCannotHideCurrentFactsOrOtherFoods() {
        for(String text:List.of("建议核对已经高油的番茄炒蛋。","建议核对番茄炒蛋已有的高油风险。",
                "番茄炒蛋高油风险目前未知，但清炒青菜存在高油风险。",
                "番茄炒蛋高油风险未知且清炒青菜没有高油风险。",
                "番茄炒蛋存在高油风险，建议下一餐轮换食材。",
                "番茄炒蛋高油风险未知，但确实存在。",
                "番茄炒蛋高油风险未知而是明确存在。",
                "本餐高油风险未知，但确实存在。",
                "下一餐避免高油，但本餐番茄炒蛋存在高油风险。"))
            assertThat(MealAdviceConsistency.consistent(text,stirFry())).as(text).isFalse();
        assertThat(MealAdviceConsistency.removeUnnecessaryReviews("请核对白米饭是否高油。下一餐可轮换食材。",stirFry()))
                .isEqualTo("下一餐可轮换食材。");
        assertThat(MealAdviceConsistency.consistent("红烧肉高油风险未确认，但该风险确实不存在。",meal(false))).isFalse();
    }
    @Test void reportsFiniteFallbackReasonsAndKeepsUnknownSeparateFromFalse() {
        assertThat(MealAdviceConsistency.assess("面条经过油炸。",meal(false)).reasons()).containsExactly(MealAdviceConsistency.Reason.FACT_CONFLICT);
        assertThat(MealAdviceConsistency.assess("面条经过油炸。",meal(null)).reasons()).containsExactly(MealAdviceConsistency.Reason.UNKNOWN_ATTRIBUTE_ASSERTED);
        assertThat(MealAdviceConsistency.assess("",meal(null)).reasons()).containsExactly(MealAdviceConsistency.Reason.EMPTY_ADVICE);
        assertThat(MealAdviceConsistency.consistent("面条未识别为油炸食物。",meal(null))).isTrue();
        assertThat(MealAdviceConsistency.consistent("面条的高油风险尚不明确。",meal(null))).isTrue();
        assertThat(MealAdviceConsistency.consistent("面条的油炸属性不是未知。",meal(null))).isFalse();
        assertThat(MealAdviceConsistency.consistent("红烧肉高油，面条油炸属性尚不明确。",meal(false))).isTrue();
    }
    private RecognitionResult meal(Boolean fried) {
        var meal = new RecognitionResult();
        meal.setFoods(List.of(new FoodItem("面条","staple",.9,"",new FoodAttributes(fried,null)), new FoodItem("红烧肉","protein",.9,"",new FoodAttributes(false,true))));
        return meal;
    }
    @Test void rejectsFalseAndUnknownFryingAssertionsWithoutTransferringRiskBetweenFoods() {
        for (Boolean value : new Boolean[]{false,null}) {
            assertThat(MealAdviceConsistency.consistent("红烧肉的高油风险以及面条潜在的油炸属性提示需要注意油脂摄入。",meal(value))).isFalse();
            assertThat(MealAdviceConsistency.consistent("本餐面条经过油炸，下一餐可补充青菜。",meal(value))).isFalse();
            assertThat(MealAdviceConsistency.consistent("面条存在高油风险。",meal(value))).isFalse();
        }
        assertThat(MealAdviceConsistency.consistent("面条经过油炸，建议轮换做法。",meal(true))).isTrue();
    }
    @Test void allowsFutureSuggestionsUncertaintyAndNegation() {
        for (String text : List.of("下一餐避免油炸面条，选择水煮面条。", "面条不是油炸食物。", "面条是否油炸仍待核对。", "不能据此判断蔬菜摄入不足。", "鸡蛋不是肉类，豆腐属于豆制品。", "下一餐补充鸡蛋或瘦肉等蛋白质来源。")) {
            assertThat(MealAdviceConsistency.consistent(text,meal(false))).as(text).isTrue();
        }
    }
    @Test void rejectsEggsAsMeatEvenInFutureSuggestionsAndUnsupportedIntakeClaims() {
        for (String text : List.of("下一餐补充鸡蛋或瘦肉等明确肉类。", "鸡蛋属于肉类。", "豆腐是肉类。", "结构评分显示蔬菜摄入不足。", "本餐蔬菜明显不足。")) {
            assertThat(MealAdviceConsistency.consistent(text,meal(false))).as(text).isFalse();
        }
    }
    @Test void processedMeatFactsAndHabitsRequireInputEvidenceButFutureChoicesAreAllowed() {
        assertThat(MealAdviceConsistency.consistent("避免长期依赖加工肉类。",meal(false))).isFalse();
        assertThat(MealAdviceConsistency.consistent("本餐包含加工肉类。",meal(false))).isFalse();
        assertThat(MealAdviceConsistency.consistent("下一餐尽量避免加工肉类。",meal(false))).isTrue();
        var meal = meal(false); meal.getFoods().get(1).setName("香肠");
        assertThat(MealAdviceConsistency.consistent("本餐包含加工肉类。",meal)).isTrue();
    }
    @Test void combinedRiskLabelNeedsEitherAttributeNotBoth() {
        assertThat(MealAdviceConsistency.consistent("面条存在油炸或高油风险。",meal(true))).isTrue();
        assertThat(MealAdviceConsistency.consistent("红烧肉存在油炸或高油风险。",meal(false))).isTrue();
        assertThat(MealAdviceConsistency.consistent("本餐红烧肉存在油炸或高油风险。",meal(false))).isTrue();
        assertThat(MealAdviceConsistency.consistent("面条存在油炸或高油风险。",meal(false))).isFalse();
        assertThat(MealAdviceConsistency.consistent("红烧肉潜在的油炸属性带来风险。",meal(false))).isFalse();
        assertThat(MealAdviceConsistency.consistent("红烧肉有油炸或高油风险并经过油炸。",meal(false))).isFalse();
    }

    @Test void uncertaintyBelongsToOneAttributeAndDoesNotHideOtherAssertions() {
        for (String text : List.of("面条的高油风险状态为“未知”。", "面条的油炸属性尚未确认。", "面条高油风险为不确定。", "面条油炸状态为否。")) {
            assertThat(MealAdviceConsistency.consistent(text,meal(false))).as(text).isTrue();
        }
        for (String text : List.of("面条高油风险未知，但面条经过油炸。", "面条油炸未知且高油风险明确存在。",
                "面条的高油状态不是未知而是明确存在。", "红烧肉油炸未知，面条存在高油风险。",
                "面条油炸属性未知，鸡蛋属于肉类。")) {
            assertThat(MealAdviceConsistency.consistent(text,meal(false))).as(text).isFalse();
        }
        assertThat(MealAdviceConsistency.consistent("面条油炸属性未知，红烧肉存在高油风险。",meal(false))).isTrue();
    }

    @Test void reviewFilteringCannotConcealFactErrorsOrTurnEmptyAdviceIntoSuccess() {
        var recognition = new RecognitionResult();
        recognition.setFoods(List.of(new FoodItem("香蕉","fruit",.99,"新鲜切片，无高油或油炸迹象",new FoodAttributes(null,null))));
        String conflict = "香蕉经过油炸，请核对香蕉是否高油。";
        assertThat(MealAdviceConsistency.removeUnnecessaryReviews(conflict,recognition)).isEqualTo(conflict);
        assertThat(MealAdviceConsistency.consistent(conflict,recognition)).isFalse();
        String onlyRequest = "请核对香蕉是否高油。";
        assertThat(MealAdviceConsistency.removeUnnecessaryReviews(onlyRequest,recognition)).isEmpty();
        assertThat(MealAdviceConsistency.consistent("",recognition)).isFalse();
    }

    @Test void overallMealRiskIsNotAssignedToEveryFoodButNamedFactsStillNeedEvidence() {
        assertThat(MealAdviceConsistency.consistent("本餐包含面条和红烧肉，结构完整，但系统提示存在油炸或高油风险。",meal(false))).isTrue();
        assertThat(MealAdviceConsistency.consistent("识别结果显示面条有高油风险。",meal(false))).isFalse();
        assertThat(MealAdviceConsistency.consistent("面条高油未知，但本餐包含油炸食物。",meal(false))).isFalse();
        assertThat(MealAdviceConsistency.consistent("本餐包含面条和红烧肉，建议下一餐减少油炸，以免过于油腻。",meal(false))).isTrue();
    }
}
