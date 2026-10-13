package com.example.mealcheck.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class AssistantEvaluationDatasetTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void evaluationDatasetHasValidMinimalCoverage() throws Exception {
        try (InputStream input = getClass().getResourceAsStream(
                "/evaluation/assistant_eval_cases.json")) {
            assertThat(input).as("assistant evaluation dataset").isNotNull();
            JsonNode root = objectMapper.readTree(input);

            assertThat(root.path("version").asText()).isEqualTo("1.0");
            assertRubric(root.path("rubric"));

            JsonNode cases = root.path("cases");
            JsonNode intentExpectations = root.path("intentExpectations");
            assertThat(cases.isArray()).isTrue();
            assertThat(cases.size()).isEqualTo(50);
            assertThat(intentExpectations.isObject()).isTrue();
            assertThat(intentExpectations.size()).isEqualTo(50);

            Set<String> ids = new HashSet<>();
            Set<String> categories = new HashSet<>();
            int highRiskCount = 0;
            Set<String> allowedTools = Set.of(
                    "search_diet_knowledge",
                    "get_my_recent_meals",
                    "get_my_weekly_report",
                    "get_my_current_goal");
            Set<String> allowedSkills = Set.of(
                    "weekly-report", "diet-trend", "fried-food-control", "barbecue-balance",
                    "vegetable-balance", "sweet-drink-control", "fat-loss", "muscle-gain",
                    "meal-analysis", "safety-boundary", "scope-boundary", "balanced-diet");

            for (JsonNode evaluationCase : cases) {
                String id = evaluationCase.path("id").asText();
                assertThat(id).isNotBlank();
                assertThat(ids.add(id)).as("duplicate case id: %s", id).isTrue();
                assertThat(evaluationCase.path("question").asText()).isNotBlank();
                assertThat(evaluationCase.path("context").isObject()).isTrue();

                String category = evaluationCase.path("category").asText();
                assertThat(category).isNotBlank();
                categories.add(category);

                String riskLevel = evaluationCase.path("riskLevel").asText();
                assertThat(riskLevel).isIn("LOW", "MEDIUM", "HIGH", "CRITICAL");
                if (riskLevel.equals("HIGH") || riskLevel.equals("CRITICAL")) {
                    highRiskCount++;
                }

                JsonNode expected = evaluationCase.path("expected");
                assertThat(expected.path("responseMode").asText())
                        .isIn("ANSWER", "LIMITED_ANSWER", "SAFE_REDIRECT");
                assertThat(expected.path("requiredBehaviors").isArray()).isTrue();
                assertThat(expected.path("requiredBehaviors").isEmpty()).isFalse();
                assertThat(expected.path("forbiddenBehaviors").isArray()).isTrue();
                assertThat(expected.path("forbiddenBehaviors").isEmpty()).isFalse();
                assertThat(expected.path("requiredEvidence").isArray()).isTrue();
                assertThat(expected.path("expectedTools").isArray()).isTrue();
                assertThat(expected.path("forbiddenTools").isArray()).isTrue();
                Set<String> expectedTools = new HashSet<>();
                expected.path("expectedTools").forEach(tool -> expectedTools.add(tool.asText()));
                Set<String> forbiddenTools = new HashSet<>();
                expected.path("forbiddenTools").forEach(tool -> forbiddenTools.add(tool.asText()));
                assertThat(expectedTools).isSubsetOf(allowedTools);
                assertThat(forbiddenTools).isSubsetOf(allowedTools);
                Set<String> overlap = new HashSet<>(expectedTools);
                overlap.retainAll(forbiddenTools);
                assertThat(overlap).as("tool overlap in case %s", id).isEmpty();

                JsonNode intentExpectation = intentExpectations.path(id);
                String expectedSkill = intentExpectation.path("expectedSkill").asText();
                assertThat(expectedSkill).as("expected skill for %s", id).isIn(allowedSkills);
                Set<String> acceptableSkills = new HashSet<>();
                intentExpectation.path("acceptableSkills")
                        .forEach(skill -> acceptableSkills.add(skill.asText()));
                assertThat(acceptableSkills).contains(expectedSkill).isSubsetOf(allowedSkills);
            }

            assertThat(categories).containsExactlyInAnyOrder(
                    "factual_grounding",
                    "personalization",
                    "multi_turn_memory",
                    "safety",
                    "uncertainty_boundary",
                    "tool_use_resilience",
                    "prompt_injection_privacy"
            );
            assertThat(highRiskCount).isGreaterThanOrEqualTo(6);

            Set<String> quickCaseIds = new HashSet<>();
            for (JsonNode quickCaseId : root.path("quickCaseIds")) {
                quickCaseIds.add(quickCaseId.asText());
            }
            assertThat(quickCaseIds).hasSize(10);
            assertThat(ids).containsAll(quickCaseIds);
            Set<String> quickCategories = new HashSet<>();
            for (JsonNode evaluationCase : cases) {
                if (quickCaseIds.contains(evaluationCase.path("id").asText())) {
                    quickCategories.add(evaluationCase.path("category").asText());
                }
            }
            assertThat(quickCategories).containsAll(categories);
        }
    }

    @Test
    void expansionDatasetAddsOneHundredFiftyValidCases() throws Exception {
        try (InputStream input = getClass().getResourceAsStream(
                "/evaluation/assistant_eval_expansion.json")) {
            assertThat(input).as("assistant evaluation expansion dataset").isNotNull();
            JsonNode root = objectMapper.readTree(input);
            assertThat(root.path("version").asText()).isEqualTo("2.0");
            assertThat(root.path("families").size()).isEqualTo(15);

            Set<String> ids = new HashSet<>();
            int caseCount = 0;
            for (JsonNode family : root.path("families")) {
                assertThat(family.path("idPrefix").asText()).isNotBlank();
                assertThat(family.path("variants").size()).isEqualTo(10);
                assertThat(family.path("expected").path("requiredBehaviors").isArray()).isTrue();
                assertThat(family.path("expected").path("forbiddenBehaviors").isArray()).isTrue();
                for (JsonNode variant : family.path("variants")) {
                    String id = family.path("idPrefix").asText() + "-" + variant.path("id").asText();
                    assertThat(ids.add(id)).as("duplicate expansion id: %s", id).isTrue();
                    assertThat(variant.path("question").asText()).isNotBlank();
                    caseCount++;
                }
            }
            assertThat(caseCount).isEqualTo(150);
        }
    }

    private void assertRubric(JsonNode rubric) {
        JsonNode dimensions = rubric.path("dimensions");
        assertThat(dimensions.isArray()).isTrue();
        assertThat(dimensions.size()).isEqualTo(5);

        double totalWeight = 0.0;
        Set<String> names = new HashSet<>();
        for (JsonNode dimension : dimensions) {
            names.add(dimension.path("name").asText());
            totalWeight += dimension.path("weight").asDouble();
        }
        assertThat(totalWeight).isCloseTo(1.0, org.assertj.core.data.Offset.offset(0.000001));
        assertThat(names).containsExactlyInAnyOrder(
                "correctness",
                "groundedness",
                "personalization",
                "actionability",
                "conversationConsistency"
        );

        assertThat(rubric.path("hardGates").isArray()).isTrue();
        assertThat(rubric.path("hardGates").size()).isGreaterThanOrEqualTo(5);
        assertThat(rubric.path("passPolicy").path("minimumWeightedScore").asDouble())
                .isEqualTo(3.2);
        assertThat(rubric.path("passPolicy").path("minimumApplicableDimensionScore").asInt())
                .isEqualTo(2);
        assertThat(rubric.path("passPolicy").path("allHardGatesMustPass").asBoolean())
                .isTrue();

        JsonNode categoryDimensions = rubric.path("categoryApplicableDimensions");
        assertThat(categoryDimensions.isObject()).isTrue();
        Set<String> expectedCategories = Set.of(
                "factual_grounding", "personalization", "multi_turn_memory", "safety",
                "uncertainty_boundary", "tool_use_resilience", "prompt_injection_privacy");
        for (String category : expectedCategories) {
            JsonNode applicable = categoryDimensions.path(category);
            assertThat(applicable.isArray()).as(category).isTrue();
            assertThat(applicable.isEmpty()).as(category).isFalse();
            for (JsonNode dimension : applicable) {
                assertThat(names).contains(dimension.asText());
            }
        }

        JsonNode suitePolicy = rubric.path("suitePolicy");
        assertThat(suitePolicy.path("minimumQualityPassRate").asDouble()).isBetween(0.0, 1.0);
        assertThat(suitePolicy.path("minimumMacroCategoryPassRate").asDouble()).isBetween(0.0, 1.0);
        assertThat(suitePolicy.path("minimumIntentAccuracy").asDouble()).isBetween(0.0, 1.0);
        assertThat(suitePolicy.path("blockOnAnyHardGate").asBoolean()).isTrue();
        assertThat(suitePolicy.path("requiredPerfectCategories").isArray()).isTrue();
    }
}
