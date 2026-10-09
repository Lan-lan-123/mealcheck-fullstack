package com.example.mealcheck.service;

import com.example.mealcheck.dto.assistant.AssistantChatMessage;
import com.example.mealcheck.service.skill.AssistantSkillPlan;
import com.example.mealcheck.service.skill.AssistantSkillRegistry;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@Service
public class AssistantEvaluationRunner {

    private final AssistantFunctionCallingService functionCallingService;
    private final AssistantEvaluationToolRuntimeFactory toolRuntimeFactory;
    private final AssistantFunctionPromptFactory promptFactory;
    private final AssistantSkillRegistry skillRegistry;
    private final ObjectMapper objectMapper;

    public AssistantEvaluationRunner(AssistantFunctionCallingService functionCallingService,
                                     AssistantEvaluationToolRuntimeFactory toolRuntimeFactory,
                                     AssistantFunctionPromptFactory promptFactory,
                                     AssistantSkillRegistry skillRegistry,
                                     ObjectMapper objectMapper) {
        this.functionCallingService = functionCallingService;
        this.toolRuntimeFactory = toolRuntimeFactory;
        this.promptFactory = promptFactory;
        this.skillRegistry = skillRegistry;
        this.objectMapper = objectMapper;
    }

    public EvaluationResult run(String question, JsonNode context, String knowledgeContext) {
        JsonNode safeContext = context == null ? objectMapper.createObjectNode() : context;
        List<AssistantChatMessage> history = history(safeContext);
        AssistantSkillPlan selectedSkill = skillRegistry.select(
                question,
                history.stream()
                        .filter(message -> "user".equalsIgnoreCase(message.getRole()))
                        .map(AssistantChatMessage::getText)
                        .toList());
        String systemPrompt = promptFactory.build(
                selectedSkill.intentCode(),
                selectedSkill,
                profileContext(safeContext),
                "No older conversation summary.",
                objectMapper.createObjectNode()
        );
        AssistantFunctionToolRuntime runtime = toolRuntimeFactory.create(safeContext, knowledgeContext);
        try {
            AssistantFunctionCallingService.FunctionCallingResult result = functionCallingService.askWithRuntime(
                    systemPrompt,
                    history,
                    question,
                    runtime
            );
            return new EvaluationResult(
                    normalizeAnswer(result.answer()),
                    "PRODUCTION_FUNCTION_CALLING",
                    selectedSkill.intentCode(),
                    result.toolRounds(),
                    result.calledTools(),
                    result.toolTrace()
            );
        } catch (AssistantFunctionCallingService.FunctionCallingExecutionException ex) {
            throw new EvaluationExecutionException(
                    ex.getMessage(),
                    ex,
                    "PRODUCTION_FUNCTION_CALLING",
                    selectedSkill.intentCode(),
                    ex.getToolRounds(),
                    ex.getToolTrace()
            );
        }
    }

    private List<AssistantChatMessage> history(JsonNode context) {
        List<AssistantChatMessage> messages = new ArrayList<>();
        JsonNode history = context.path("history");
        if (!history.isArray()) {
            return messages;
        }
        for (JsonNode item : history) {
            String role = item.path("role").asText("user").toLowerCase(Locale.ROOT);
            String text = item.path("text").asText("").trim();
            if (!text.isBlank() && ("user".equals(role) || "assistant".equals(role))) {
                messages.add(new AssistantChatMessage(role, text));
            }
        }
        return messages;
    }

    private String profileContext(JsonNode context) {
        JsonNode facts = context.path("profileFacts");
        if (!facts.isArray() || facts.isEmpty()) {
            return "No durable user profile facts.";
        }
        List<String> lines = new ArrayList<>();
        for (JsonNode fact : facts) {
            String value = fact.asText("").trim();
            if (!value.isBlank()) {
                lines.add("- " + value);
            }
        }
        return lines.isEmpty() ? "No durable user profile facts." : String.join("\n", lines);
    }

    private String normalizeAnswer(String rawAnswer) {
        String raw = rawAnswer == null ? "" : rawAnswer.trim();
        if (raw.isBlank()) {
            return raw;
        }
        try {
            JsonNode root = objectMapper.readTree(extractJson(raw));
            List<String> parts = new ArrayList<>();
            String summary = root.path("summary").asText("").trim();
            if (!summary.isBlank()) {
                parts.add(summary);
            }
            String trend = root.path("weeklyTrend").asText("").trim();
            if (!trend.isBlank() && !"null".equalsIgnoreCase(trend)) {
                parts.add("本周趋势：" + trend);
            }
            JsonNode suggestions = root.path("suggestions");
            if (suggestions.isArray()) {
                List<String> items = new ArrayList<>();
                for (JsonNode suggestion : suggestions) {
                    String text = suggestion.asText("").trim();
                    if (!text.isBlank()) {
                        items.add("- " + text);
                    }
                }
                if (!items.isEmpty()) {
                    parts.add("建议：\n" + String.join("\n", items));
                }
            }
            return parts.isEmpty() ? raw : String.join("\n\n", parts);
        } catch (Exception ignored) {
            return raw;
        }
    }

    private String extractJson(String raw) {
        int start = raw.indexOf('{');
        int end = raw.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return raw.substring(start, end + 1);
        }
        return raw;
    }

    public record EvaluationResult(String answer,
                                   String executionPath,
                                   String selectedSkill,
                                   int toolRounds,
                                   List<String> calledTools,
                                   List<AssistantFunctionCallingService.ToolCallTrace> toolTrace) {
        public EvaluationResult {
            calledTools = calledTools == null ? List.of() : List.copyOf(calledTools);
            toolTrace = toolTrace == null ? List.of() : List.copyOf(toolTrace);
        }
    }

    public static final class EvaluationExecutionException extends RuntimeException {
        private final String executionPath;
        private final String selectedSkill;
        private final int toolRounds;
        private final List<AssistantFunctionCallingService.ToolCallTrace> toolTrace;

        public EvaluationExecutionException(String message,
                                            Throwable cause,
                                            String executionPath,
                                            String selectedSkill,
                                            int toolRounds,
                                            List<AssistantFunctionCallingService.ToolCallTrace> toolTrace) {
            super(message, cause);
            this.executionPath = executionPath;
            this.selectedSkill = selectedSkill;
            this.toolRounds = toolRounds;
            this.toolTrace = toolTrace == null ? List.of() : List.copyOf(toolTrace);
        }

        public String executionPath() {
            return executionPath;
        }

        public String selectedSkill() {
            return selectedSkill;
        }

        public int toolRounds() {
            return toolRounds;
        }

        public List<AssistantFunctionCallingService.ToolCallTrace> toolTrace() {
            return toolTrace;
        }
    }
}
