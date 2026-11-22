package com.example.mealcheck.service;

import com.example.mealcheck.config.AppProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class RagToolEvaluationRulesTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void allTwoHundredQuestionsHaveValidRulesAndTopicLabels() throws Exception {
        JsonNode source;
        try (var input = new ClassPathResource("knowledge/rag_eval_cases.json").getInputStream()) {
            source = mapper.readTree(input);
        }
        var definition = RagAgentEvaluationDataset.load(mapper, new AppProperties());
        assertThat(definition.cases()).hasSize(200);
        assertThat(definition.version()).isEqualTo("rag-200-v7+rag-tools-v2");
        assertThat(definition.fingerprint()).matches("[0-9a-f]{64}");
        assertThat(definition.fingerprint()).isEqualTo(RagAgentEvaluationDataset.load(mapper, new AppProperties()).fingerprint());
        int noAnswers = 0;
        for (int index = 0; index < source.size(); index++) {
            var original = source.get(index);
            var adapted = definition.cases().get(index);
            assertThat(adapted.path("question")).isEqualTo(original.path("question"));
            assertThat(adapted.path("id").asText()).isEqualTo("rag-%03d".formatted(index + 1));
            boolean noAnswer = original.path("expectNoAnswer").asBoolean();
            var expected = adapted.path("expected");
            RagToolEvaluationRules.validate(expected, noAnswer);
            if (noAnswer) {
                noAnswers++;
                assertThat(expected.path("forbiddenTools").size()).isEqualTo(4);
                assertThat(RagToolEvaluationRules.assess(expected, List.of(), List.of(), mapper)).isEmpty();
            } else {
                assertThat(adapted.path("context").path("ragKnowledgeFixtures").size())
                        .as(original.path("id").asText() + " has a controlled source").isGreaterThan(0);
                String args = mapper.createObjectNode().put("query", original.path("question").asText()).put("limit", 3).toString();
                assertThat(RagToolEvaluationRules.assess(expected, List.of(RagToolEvaluationRules.SEARCH),
                        List.of(trace(args, "SUCCESS")), mapper)).as(original.path("id").asText()).isEmpty();
            }
        }
        assertThat(noAnswers).isEqualTo(20);
    }

    @Test
    void rejectsOffTopicQueriesInvalidArgumentsAndFailedCalls() throws Exception {
        JsonNode rules = RagAgentEvaluationDataset.load(mapper, new AppProperties()).cases().get(0).path("expected");
        for (String args : List.of("{}", "[]", "invalid", "{\"query\":\"早餐\",\"limit\":6}",
                "{\"query\":\"早餐\",\"limit\":1.5}", "{\"query\":\"早餐\",\"limit\":\"3\"}",
                "{\"query\":\"早餐\",\"userId\":1}")) {
            assertThat(RagToolEvaluationRules.assess(rules, List.of(RagToolEvaluationRules.SEARCH),
                    List.of(trace(args, "SUCCESS")), mapper)).as(args).isNotEmpty();
        }
        assertThat(RagToolEvaluationRules.assess(rules, List.of(RagToolEvaluationRules.SEARCH),
                List.of(trace("{\"query\":\"天气\"}", "SUCCESS")), mapper)).anyMatch(text -> text.contains("主题组"));
        assertThat(RagToolEvaluationRules.assess(rules, List.of(RagToolEvaluationRules.SEARCH),
                List.of(trace("{\"query\":\"早餐\"}", "TIMEOUT")), mapper)).anyMatch(text -> text.contains("未成功"));
        assertThat(RagToolEvaluationRules.assess(rules, List.of(RagToolEvaluationRules.SEARCH),
                List.of(), mapper)).anyMatch(text -> text.contains("轨迹不一致"));
        var unknown = new AssistantFunctionCallingService.ToolCallTrace("unexpected_tool", "{}", "SUCCESS", 1, 1L);
        assertThat(RagToolEvaluationRules.assess(rules, List.of("unexpected_tool"), List.of(unknown), mapper))
                .anyMatch(text -> text.contains("未允许"));
    }

    @Test
    void rejectsDuplicateQueriesAndExcessCalls() throws Exception {
        var row = RagAgentEvaluationDataset.load(mapper, new AppProperties()).cases().get(0);
        String args = mapper.createObjectNode().put("query", row.path("question").asText()).toString();
        var call = trace(args, "SUCCESS");
        assertThat(RagToolEvaluationRules.assess(row.path("expected"),
                List.of(call.name(), call.name(), call.name()), List.of(call, call, call), mapper))
                .anyMatch(text -> text.contains("上限")).anyMatch(text -> text.contains("重复"));
    }

    private AssistantFunctionCallingService.ToolCallTrace trace(String args, String status) {
        return new AssistantFunctionCallingService.ToolCallTrace(RagToolEvaluationRules.SEARCH, args, status, 1, 1L);
    }
}
