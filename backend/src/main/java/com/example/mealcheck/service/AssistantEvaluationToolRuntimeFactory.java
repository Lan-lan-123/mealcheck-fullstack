package com.example.mealcheck.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import org.springframework.stereotype.Component;

import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
public class AssistantEvaluationToolRuntimeFactory {
    private static final Set<String> TOOL_NAMES = Set.of(
            "search_diet_knowledge",
            "get_my_recent_meals",
            "get_my_weekly_report",
            "get_my_current_goal"
    );

    private final AssistantFunctionToolRegistry productionRegistry;
    private final ObjectMapper objectMapper;

    public AssistantEvaluationToolRuntimeFactory(AssistantFunctionToolRegistry productionRegistry,
                                                 ObjectMapper objectMapper) {
        this.productionRegistry = productionRegistry;
        this.objectMapper = objectMapper;
    }

    public AssistantFunctionToolRuntime create(JsonNode context, String knowledgeContext) {
        JsonNode safeContext = context == null ? objectMapper.createObjectNode() : context.deepCopy();
        String knowledge = knowledgeContext == null ? "" : knowledgeContext;
        return new SyntheticRuntime(safeContext, knowledge);
    }

    private final class SyntheticRuntime implements AssistantFunctionToolRuntime {
        private final JsonNode context;
        private final String knowledge;

        private SyntheticRuntime(JsonNode context, String knowledge) {
            this.context = context;
            this.knowledge = knowledge;
        }

        @Override
        public List<ToolSpecification> specifications() {
            return productionRegistry.specifications();
        }

        @Override
        public ToolResult execute(com.example.mealcheck.entity.UserAccount user,
                                  ToolExecutionRequest request,
                                  long deadlineNanos) {
            if (System.nanoTime() >= deadlineNanos) {
                throw new IllegalStateException("Assistant Function Calling deadline exceeded");
            }
            if (request == null || request.name() == null || !TOOL_NAMES.contains(request.name())) {
                throw new IllegalArgumentException("Tool is not allowed: "
                        + (request == null ? "null" : request.name()));
            }
            JsonNode arguments = parseArguments(request.arguments());
            return switch (request.name()) {
                case "search_diet_knowledge" -> searchKnowledge(arguments);
                case "get_my_recent_meals" -> recentMeals(arguments);
                case "get_my_weekly_report" -> weeklyReport(arguments);
                case "get_my_current_goal" -> currentGoal(arguments);
                default -> throw new IllegalArgumentException("Tool is not allowed: " + request.name());
            };
        }

        private ToolResult searchKnowledge(JsonNode arguments) {
            rejectUnknownArguments(arguments, Set.of("query", "limit"));
            JsonNode query = arguments.get("query");
            if (query == null || !query.isTextual() || query.asText().isBlank()) {
                throw new IllegalArgumentException("Missing required string argument: query");
            }
            JsonNode limit = arguments.get("limit");
            if (limit != null && !limit.isNull() && !limit.canConvertToInt()) {
                throw new IllegalArgumentException("limit must be an integer");
            }
            if ("DIET_KNOWLEDGE_UNAVAILABLE".equals(toolState())) {
                return unavailable("diet knowledge is unavailable");
            }
            return jsonResult(Map.of(
                    "count", knowledge.isBlank() ? 0 : 1,
                    "results", knowledge.isBlank()
                            ? List.of()
                            : List.of(Map.of(
                            "title", "Evaluation dietary knowledge",
                            "content", knowledge))
            ));
        }

        private ToolResult recentMeals(JsonNode arguments) {
            rejectUnknownArguments(arguments, Set.of());
            JsonNode meals = context.path("recentMeals");
            return jsonResult(Map.of(
                    "count", meals.isArray() ? meals.size() : 0,
                    "meals", meals.isArray() ? meals : objectMapper.createArrayNode()
            ));
        }

        private ToolResult weeklyReport(JsonNode arguments) {
            rejectUnknownArguments(arguments, Set.of());
            if ("WEEKLY_REPORT_UNAVAILABLE".equals(toolState())) {
                return unavailable("weekly report is unavailable");
            }
            JsonNode report = context.get("weeklyReport");
            return jsonResult(Map.of(
                    "report", report == null || report.isNull()
                            ? objectMapper.createObjectNode()
                            : report
            ));
        }

        private ToolResult currentGoal(JsonNode arguments) {
            rejectUnknownArguments(arguments, Set.of());
            if ("CURRENT_GOAL_UNAVAILABLE".equals(toolState())) {
                return unavailable("current goal is unavailable");
            }
            JsonNode goal = context.get("currentGoal");
            return jsonResult(Map.of(
                    "goal", goal == null || goal.isNull() ? "" : goal.asText()
            ));
        }

        private ToolResult unavailable(String message) {
            return new ToolResult(toJson(Map.of("error", message)), List.of(), "UNAVAILABLE");
        }

        private ToolResult jsonResult(Object value) {
            return new ToolResult(toJson(value), List.of(), "SUCCESS");
        }

        private String toolState() {
            return context.path("toolState").asText("AVAILABLE");
        }

        private JsonNode parseArguments(String raw) {
            try {
                JsonNode parsed = objectMapper.readTree(raw == null || raw.isBlank() ? "{}" : raw);
                if (parsed == null || !parsed.isObject()) {
                    throw new IllegalArgumentException("Tool arguments must be a JSON object");
                }
                return parsed;
            } catch (IllegalArgumentException e) {
                throw e;
            } catch (Exception e) {
                throw new IllegalArgumentException("Tool arguments are not valid JSON", e);
            }
        }

        private void rejectUnknownArguments(JsonNode arguments, Set<String> allowed) {
            Iterator<String> names = arguments.fieldNames();
            while (names.hasNext()) {
                String name = names.next();
                if (!allowed.contains(name)) {
                    throw new IllegalArgumentException("Unknown tool argument: " + name);
                }
            }
        }

        private String toJson(Object value) {
            try {
                return objectMapper.writeValueAsString(value);
            } catch (Exception e) {
                throw new IllegalStateException("Unable to serialize evaluation tool result", e);
            }
        }
    }
}
