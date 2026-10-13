package com.example.mealcheck.service;

import com.example.mealcheck.dto.FoodItem;
import com.example.mealcheck.dto.MealNutritionDtos.*;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

class MealNutritionCalculatorTest {
    private final MealNutritionCalculator calculator = new MealNutritionCalculator();
    private final List<FoodItem> foods = List.of(new FoodItem("示例食物", "other", 1, null));
    private BigDecimal number(String n) { return new BigDecimal(n); }
    private Nutrients nutrients(String energy, String protein, String fat, String carbs) {
        return new Nutrients(number(energy), number(protein), number(fat), number(carbs));
    }

    @Test void convertsUserSuppliedDataByWeightWithoutChangingScore() {
        var result = calculator.calculate(1, 4, 2, foods,
                List.of(new Input(0, number("150"), nutrients("200", "10", "5", "20"), "包装标签")));
        assertThat(result.status()).isEqualTo("ESTIMATED");
        assertThat(result.totals()).isEqualTo(nutrients("300.0", "15.0", "7.5", "30.0"));
        assertThat(result.recordRevision()).isEqualTo(4);
        assertThat(result.items().get(0).portionSource()).isEqualTo("USER_ENTERED");
        assertThat(result.warning()).contains("不准确", "仅供参考");
    }

    @Test void missingFoodDataSuppressesWholeMealTotalInsteadOfTreatingItAsZero() {
        var twoFoods = List.of(foods.get(0), new FoodItem("未填写食物", "other", 1, null));
        var result = calculator.calculate(1, 0, 1, twoFoods, List.of(
                new Input(0, number("100"), nutrients("200", "10", "5", "20"), "标签"),
                new Input(1, null, null, null)));
        assertThat(result.status()).isEqualTo("INCOMPLETE");
        assertThat(result.totals()).isNull();
        assertThat(result.estimatedFoods()).isEqualTo(1);
        assertThat(result.items().get(1).grams()).isNull();
        assertThat(result.items().get(1).estimate()).isNull();
    }

    @Test void portionOnlyAndPartialNutrientsRemainUnknownButExplicitZeroIsKnown() {
        var portionOnly = calculator.calculate(1, 0, 1, foods, List.of(new Input(0, number("100"), null, null)));
        assertThat(portionOnly.totals()).isNull();
        var partial = calculator.calculate(1, 0, 1, foods, List.of(new Input(0, number("100"),
                new Nutrients(number("0"), null, number("0"), number("0")), "标签")));
        assertThat(partial.status()).isEqualTo("INCOMPLETE");
        var zero = calculator.calculate(1, 0, 1, foods, List.of(new Input(0, number("100"), nutrients("0", "0", "0", "0"), "标签")));
        assertThat(zero.status()).isEqualTo("ESTIMATED");
        assertThat(zero.totals().energyKcal()).isZero();
    }

    @Test void totalRoundsAfterSummingRatherThanAccumulatingRoundingErrors() {
        var twoFoods = List.of(foods.get(0), foods.get(0));
        var result = calculator.calculate(1, 0, 1, twoFoods, List.of(
                new Input(0, number("1"), nutrients("4", "0", "0", "0"), "标签"),
                new Input(1, number("1"), nutrients("4", "0", "0", "0"), "标签")));
        assertThat(result.totals().energyKcal()).isEqualByComparingTo("0.1");
    }

    @Test void rejectsInventedFoodIndexesMissingSourcesAndImpossibleMacroTotals() {
        assertThatThrownBy(() -> calculator.calculate(1, 0, 1, foods, List.of(new Input(2, null, null, null))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> calculator.calculate(1, 0, 1, List.of(foods.get(0), foods.get(0)),
                List.of(new Input(0, null, null, null), new Input(0, null, null, null))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> calculator.calculate(1, 0, 1, foods,
                List.of(new Input(0, number("100"), nutrients("200", "10", "5", "20"), " "))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("来源");
        assertThatThrownBy(() -> calculator.calculate(1, 0, 1, foods,
                List.of(new Input(0, number("100"), nutrients("200", "60", "30", "20"), "标签"))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("100 克");
    }
}
