package com.example.mealcheck.service;

import com.example.mealcheck.config.AssistantSkillConfiguration;
import com.example.mealcheck.service.skill.AssistantSkill;
import com.example.mealcheck.service.skill.AssistantSkillPlan;
import com.example.mealcheck.service.skill.AssistantSkillRegistry;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AssistantIntentRoutingDatasetTest {

    @Test
    void productionRouterMatchesAllTwoHundredLabeledEvaluationCases() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        try (AnnotationConfigApplicationContext context =
                     new AnnotationConfigApplicationContext(AssistantSkillConfiguration.class);
             InputStream input = getClass().getResourceAsStream(
                     "/evaluation/assistant_eval_cases.json")) {
            AssistantSkillRegistry registry = new AssistantSkillRegistry(
                    new ArrayList<>(context.getBeansOfType(AssistantSkill.class).values()));
            JsonNode root = mapper.readTree(input);
            List<String> mismatches = new ArrayList<>();

            for (JsonNode item : root.path("cases")) {
                List<String> history = new ArrayList<>();
                for (JsonNode message : item.path("context").path("history")) {
                    if ("USER".equalsIgnoreCase(message.path("role").asText())) {
                        history.add(message.path("text").asText());
                    }
                }
                AssistantSkillPlan selected = registry.select(
                        item.path("question").asText(), history);
                JsonNode expectation = root.path("intentExpectations")
                        .path(item.path("id").asText());
                List<String> acceptable = new ArrayList<>();
                expectation.path("acceptableSkills")
                        .forEach(skill -> acceptable.add(skill.asText()));
                if (!acceptable.contains(selected.skillName())) {
                    mismatches.add(item.path("id").asText() + ": expected="
                            + acceptable + ", actual=" + selected.skillName());
                }
            }

            try (InputStream expansionInput = getClass().getResourceAsStream(
                    "/evaluation/assistant_eval_expansion.json")) {
                JsonNode expansion = mapper.readTree(expansionInput);
                for (JsonNode family : expansion.path("families")) {
                    for (JsonNode variant : family.path("variants")) {
                        JsonNode contextNode = variant.path("context").has("history")
                                ? variant.path("context") : family.path("context");
                        List<String> history = new ArrayList<>();
                        for (JsonNode message : contextNode.path("history")) {
                            if ("USER".equalsIgnoreCase(message.path("role").asText())) {
                                history.add(message.path("text").asText());
                            }
                        }
                        AssistantSkillPlan selected = registry.select(
                                variant.path("question").asText(), history);
                        JsonNode acceptableNode = variant.has("acceptableSkills")
                                ? variant.path("acceptableSkills") : family.path("acceptableSkills");
                        List<String> acceptable = new ArrayList<>();
                        acceptableNode.forEach(skill -> acceptable.add(skill.asText()));
                        if (!acceptable.contains(selected.skillName())) {
                            mismatches.add(family.path("idPrefix").asText() + "-"
                                    + variant.path("id").asText() + ": expected="
                                    + acceptable + ", actual=" + selected.skillName());
                        }
                    }
                }
            }

            assertThat(mismatches).isEmpty();
        }
    }
}
