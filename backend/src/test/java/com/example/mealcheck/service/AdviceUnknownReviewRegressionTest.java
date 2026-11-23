package com.example.mealcheck.service;

import com.example.mealcheck.dto.RecognitionResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static com.example.mealcheck.service.MealAdviceRiskPolicy.Risk;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Fixed inputs and explicitly authored rule expectations; never calls a provider. */
class AdviceUnknownReviewRegressionTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    private static JsonNode dataset() throws Exception {
        try (var input = AdviceUnknownReviewRegressionTest.class.getResourceAsStream(
                "/evaluation/advice_unknown_review_regression.json")) {
            assertThat(input).as("Regression dataset must be packaged with tests").isNotNull();
            return JSON.readTree(input);
        }
    }

    static Stream<Arguments> fixtures() throws Exception {
        var arguments = new ArrayList<Arguments>();
        for (var fixture : dataset().path("cases")) {
            arguments.add(Arguments.of(fixture.path("id").asText(), fixture.path("scenario").asText(), fixture));
        }
        return arguments.stream();
    }

    private static String rawAdvice(JsonNode fixture) {
        return fixture.path("rawAdvice").isNull() ? null : fixture.path("rawAdvice").asText();
    }

    private static RecognitionResult recognition(JsonNode fixture) throws Exception {
        return JSON.treeToValue(fixture.path("recognition"), RecognitionResult.class);
    }

    @Test void datasetHasFiftyUniqueExplicitCasesAndAllTriStateCombinations() throws Exception {
        var root = dataset();
        assertThat(root.path("schemaVersion").asInt()).isEqualTo(2);
        assertThat(root.path("datasetVersion").asText()).isEqualTo("advice-unknown-review-v2-50");
        assertThat(root.path("independentlyAnnotated").isBoolean()).isTrue();
        assertThat(root.path("independentlyAnnotated").asBoolean()).isFalse();
        assertThat(root.path("sourceSha256").asText()).matches("[a-f0-9]{64}");
        assertThat(root.path("expectedCaseCount").asInt()).isEqualTo(50);
        assertThat(root.path("cases").size()).isEqualTo(50);
        var ids = new HashSet<String>();
        var inputs = new HashSet<String>();
        var combinations = new HashSet<String>();
        var groups = new java.util.HashMap<String, Integer>();
        var outcomes = new java.util.HashMap<String, Integer>();
        var sources = new java.util.HashMap<String, Integer>();
        for (var fixture : root.path("cases")) {
            String id = fixture.path("id").asText();
            assertThat(id).isNotBlank();
            assertThat(ids.add(id)).as("Unique ID: %s", id).isTrue();
            assertThat(inputs.add(fixture.path("recognition") + "|" + fixture.path("rawAdvice")))
                    .as("Distinct input: %s", id).isTrue();
            assertThat(fixture.path("scenario").asText()).as(id).isNotBlank();
            assertThat(fixture.has("rawAdvice")).as(id).isTrue();
            assertThat(fixture.path("rawAdvice").isTextual() || fixture.path("rawAdvice").isNull()).as(id).isTrue();
            var expected = fixture.path("expected");
            for (String field : List.of("rawConsistent", "filteredConsistent")) {
                assertThat(expected.path(field).isBoolean()).as("%s: %s", id, field).isTrue();
            }
            assertThat(expected.path("filteredAdvice").isTextual()).as(id).isTrue();
            String outcome = expected.path("outcome").asText();
            assertThat(outcome).as(id).isIn("KEEP", "FILTER", "FALLBACK");
            assertThat(expected.path("rawConsistent").asBoolean()).as(id).isEqualTo(outcome.equals("KEEP"));
            assertThat(expected.path("filteredConsistent").asBoolean()).as(id).isEqualTo(!outcome.equals("FALLBACK"));
            if (outcome.equals("KEEP")) {
                assertThat(expected.path("filteredAdvice").asText()).as(id).isEqualTo(rawAdvice(fixture));
            } else if (outcome.equals("FILTER")) {
                assertThat(expected.path("filteredAdvice").asText()).as(id).isNotBlank().isNotEqualTo(rawAdvice(fixture));
            }
            var foods = fixture.path("recognition").path("foods");
            var reviews = expected.path("reviewEligibility");
            assertThat(foods.size()).as(id).isGreaterThan(0);
            assertThat(reviews.size()).as(id).isEqualTo(foods.size());
            for (int index = 0; index < foods.size(); index++) {
                var food = foods.get(index);
                var review = reviews.get(index);
                assertThat(review.path("food").asText()).as(id).isEqualTo(food.path("name").asText());
                for (String risk : List.of("fried", "highOil")) {
                    assertThat(review.path(risk).isBoolean()).as("%s: %s eligibility", id, risk).isTrue();
                    var attribute = food.path("attributes").path(risk);
                    assertThat(attribute.isBoolean() || attribute.isNull()).as("%s: %s tri-state", id, risk).isTrue();
                }
                combinations.add(food.path("attributes").path("fried") + "/" + food.path("attributes").path("highOil"));
            }
            groups.merge(fixture.path("group").asText(), 1, Integer::sum);
            outcomes.merge(outcome, 1, Integer::sum);
            sources.merge(fixture.path("provenance").asText(), 1, Integer::sum);
        }
        assertThat(ids).contains("synth-044", "synth-074");
        assertThat(groups).containsExactlyInAnyOrderEntriesOf(Map.of(
                "recorded_regression", 2, "unknown_negation_future", 8,
                "confirmed_and_conflicting_risks", 8, "justified_review", 8,
                "unnecessary_review", 8, "food_attribute_meal_isolation", 8,
                "filter_and_fallback_boundaries", 8));
        assertThat(outcomes).containsExactlyInAnyOrderEntriesOf(Map.of("KEEP", 24, "FILTER", 11, "FALLBACK", 15));
        assertThat(sources).containsExactlyInAnyOrderEntriesOf(Map.of(
                "recorded-provider-response", 2, "authored-synthetic-rule-case", 48));
        assertThat(combinations).containsExactlyInAnyOrder("true/true", "true/false", "true/null",
                "false/true", "false/false", "false/null", "null/true", "null/false", "null/null");
    }

    @ParameterizedTest(name = "{0}: {1}")
    @MethodSource("fixtures")
    void filteringAndFactChecksMatchExplicitExpectations(String id, String scenario, JsonNode fixture) throws Exception {
        var recognition = recognition(fixture);
        var expected = fixture.path("expected");
        String raw = rawAdvice(fixture);
        assertThat(MealAdviceConsistency.consistent(raw, recognition)).as(id + " raw consistency")
                .isEqualTo(expected.path("rawConsistent").asBoolean());
        String filtered = MealAdviceConsistency.removeUnnecessaryReviews(raw, recognition);
        assertThat(filtered).as(id + " exact filtered content").isEqualTo(expected.path("filteredAdvice").asText());
        assertThat(MealAdviceConsistency.consistent(filtered, recognition)).as(id + " filtered consistency")
                .isEqualTo(expected.path("filteredConsistent").asBoolean());
        assertThat(MealAdviceConsistency.removeUnnecessaryReviews(filtered, recognition))
                .as(id + " filtering is stable on replay").isEqualTo(filtered);
    }

    @ParameterizedTest(name = "{0}: {1}")
    @MethodSource("fixtures")
    void reviewEligibilityStaysWithItsFoodAndAttribute(String id, String scenario, JsonNode fixture) throws Exception {
        var foods = recognition(fixture).getFoods();
        var reviews = fixture.path("expected").path("reviewEligibility");
        for (int index = 0; index < foods.size(); index++) {
            var food = foods.get(index);
            var expected = reviews.get(index);
            boolean fried = expected.path("fried").asBoolean();
            boolean oil = expected.path("highOil").asBoolean();
            assertThat(MealAdviceRiskPolicy.shouldReview(food, Risk.FRIED)).as(id + " " + food.getName() + " fried").isEqualTo(fried);
            assertThat(MealAdviceRiskPolicy.shouldReview(food, Risk.HIGH_OIL)).as(id + " " + food.getName() + " oil").isEqualTo(oil);
            assertThat(MealAdviceRiskPolicy.shouldReview(food, Risk.EITHER)).as(id + " " + food.getName() + " either").isEqualTo(fried || oil);
        }
    }

    @ParameterizedTest(name = "{0}: {1}")
    @MethodSource("fixtures")
    void displayedAdviceKeepsFiltersOrFallsBackWithoutAnotherModelCall(String id, String scenario, JsonNode fixture) throws Exception {
        var client = mock(AiChatClient.class);
        when(client.isConfigured()).thenReturn(true);
        when(client.complete(anyString(), anyList(), anyDouble())).thenReturn(rawAdvice(fixture));
        var recognition = recognition(fixture);
        String goal = fixture.path("goal").asText("balanced");
        var evaluation = new MealScoringService().evaluate(recognition, goal);
        String answer = new AdviceGenerationService(client).generate(recognition, evaluation, List.of(), goal);
        var expected = fixture.path("expected");
        if (expected.path("outcome").asText().equals("FALLBACK")) {
            assertThat(answer).as(id).startsWith("本餐识别到：")
                    .contains("AI 建议与已知信息不一致，当前展示的是本地规则建议。");
        } else {
            assertThat(answer).as(id).isEqualTo(expected.path("filteredAdvice").asText())
                    .doesNotContain("当前展示的是本地规则建议");
        }
        verify(client, times(1)).complete(eq("meal-advice"), anyList(), eq(0.2));
    }
}
