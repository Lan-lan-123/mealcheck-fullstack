package com.example.mealcheck.service;

import com.example.mealcheck.config.AppProperties;
import com.example.mealcheck.dto.KnowledgeSnippet;
import com.example.mealcheck.dto.WeeklyReportResponse;
import com.example.mealcheck.entity.MealRecord;
import com.example.mealcheck.entity.UserAccount;
import com.example.mealcheck.util.Jsons;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * Allow-listed, read-only tools that can be called by the assistant model.
 *
 * <p>The handlers call the same application services as the MCP server. They do
 * not call MealCheck's own {@code /mcp} endpoint, so authentication and network
 * hops are not duplicated inside the monolith.</p>
 */
@Service
public class AssistantFunctionToolRegistry {
    private static final int MAX_ARGUMENT_LENGTH = 4_000;
    private static final int MAX_RESULT_LENGTH = 24_000;
    private static final int MAX_KNOWLEDGE_LIMIT = 5;
    private static final int MAX_RECENT_MEALS = 10;

    private final KnowledgeIndexService knowledgeIndexService;
    private final MealAnalysisService mealAnalysisService;
    private final WeeklyReportService weeklyReportService;
    private final UserGoalService userGoalService;
    private final ObjectMapper objectMapper;
    private final ExecutorService dbExecutor;
    private final ExecutorService ragExecutor;
    private final AppProperties properties;
    private final ApplicationObservability observability;
    private final Map<String, ToolDefinition> definitions;

    public AssistantFunctionToolRegistry(KnowledgeIndexService knowledgeIndexService,
                                         MealAnalysisService mealAnalysisService,
                                         WeeklyReportService weeklyReportService,
                                         UserGoalService userGoalService,
                                         ObjectMapper objectMapper,
                                         @Qualifier("assistantDbExecutor") ExecutorService dbExecutor,
                                         @Qualifier("assistantRagExecutor") ExecutorService ragExecutor,
                                         AppProperties properties,
                                         ApplicationObservability observability) {
        this.knowledgeIndexService = knowledgeIndexService;
        this.mealAnalysisService = mealAnalysisService;
        this.weeklyReportService = weeklyReportService;
        this.userGoalService = userGoalService;
        this.objectMapper = objectMapper;
        this.dbExecutor = dbExecutor;
        this.ragExecutor = ragExecutor;
        this.properties = properties;
        this.observability = observability;
        this.definitions = buildDefinitions();
    }

    public List<ToolSpecification> specifications() {
        return definitions.values().stream().map(ToolDefinition::specification).toList();
    }

    public ToolResult execute(UserAccount user,
                              ToolExecutionRequest request,
                              long deadlineNanos) {
        if (request == null || request.name() == null) {
            throw new IllegalArgumentException("Tool request must include a name");
        }
        ToolDefinition definition = definitions.get(request.name());
        if (definition == null) {
            throw new IllegalArgumentException("Tool is not allowed: " + request.name());
        }

        JsonNode arguments = parseArguments(request.arguments());
        long remainingMillis = remainingMillis(deadlineNanos);
        long timeoutMillis = Math.min(toolTimeoutMillis(), remainingMillis);
        if (timeoutMillis <= 0L) {
            throw new IllegalStateException("Assistant Function Calling deadline exceeded");
        }

        long startedAt = observability.start();
        Future<ToolResult> task;
        try {
            task = definition.executor().submit(() -> definition.handler().execute(user, arguments));
        } catch (RejectedExecutionException e) {
            observability.recordAssistantTool(startedAt, request.name(), "rejected");
            throw new IllegalStateException("Assistant tool executor is saturated: " + request.name(), e);
        }

        try {
            ToolResult result = task.get(timeoutMillis, TimeUnit.MILLISECONDS);
            observability.recordAssistantTool(startedAt, request.name(), "success");
            return result;
        } catch (TimeoutException e) {
            task.cancel(true);
            purge(definition.executor());
            observability.recordAssistantTool(startedAt, request.name(), "timeout");
            throw new IllegalStateException("Assistant tool timed out: " + request.name(), e);
        } catch (InterruptedException e) {
            task.cancel(true);
            purge(definition.executor());
            Thread.currentThread().interrupt();
            observability.recordAssistantTool(startedAt, request.name(), "interrupted");
            throw new IllegalStateException("Assistant tool was interrupted: " + request.name(), e);
        } catch (ExecutionException e) {
            observability.recordAssistantTool(startedAt, request.name(), "failure");
            Throwable cause = e.getCause() == null ? e : e.getCause();
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new IllegalStateException("Assistant tool failed: " + request.name(), cause);
        }
    }

    private Map<String, ToolDefinition> buildDefinitions() {
        Map<String, ToolDefinition> tools = new LinkedHashMap<>();
        register(tools, ToolSpecification.builder()
                        .name("search_diet_knowledge")
                        .description("Search MealCheck's curated dietary knowledge. Use it for nutrition facts and general guidance.")
                        .parameters(JsonObjectSchema.builder()
                                .addStringProperty("query", "A focused diet or nutrition search query")
                                .addIntegerProperty("limit", "Maximum results from 1 to 5")
                                .required("query")
                                .additionalProperties(false)
                                .build())
                        .build(),
                ragExecutor,
                this::searchKnowledge);
        register(tools, ToolSpecification.builder()
                        .name("get_my_recent_meals")
                        .description("Get up to 10 recent meal records for the current authenticated user.")
                        .parameters(emptyParameters())
                        .build(),
                dbExecutor,
                this::recentMeals);
        register(tools, ToolSpecification.builder()
                        .name("get_my_weekly_report")
                        .description("Get the latest seven-day diet report for the current authenticated user.")
                        .parameters(emptyParameters())
                        .build(),
                dbExecutor,
                this::weeklyReport);
        register(tools, ToolSpecification.builder()
                        .name("get_my_current_goal")
                        .description("Get the current authenticated user's diet goal.")
                        .parameters(emptyParameters())
                        .build(),
                dbExecutor,
                this::currentGoal);
        return Collections.unmodifiableMap(new LinkedHashMap<>(tools));
    }

    private void register(Map<String, ToolDefinition> tools,
                          ToolSpecification specification,
                          ExecutorService executor,
                          ToolHandler handler) {
        tools.put(specification.name(), new ToolDefinition(specification, executor, handler));
    }

    private JsonObjectSchema emptyParameters() {
        return JsonObjectSchema.builder().additionalProperties(false).build();
    }

    private ToolResult searchKnowledge(UserAccount user, JsonNode arguments) {
        rejectUnknownArguments(arguments, List.of("query", "limit"));
        String query = requiredText(arguments, "query", 500);
        int limit = integer(arguments, "limit", 3, 1, MAX_KNOWLEDGE_LIMIT);
        List<KnowledgeSnippet> snippets = knowledgeIndexService.search(query, limit);
        knowledgeIndexService.recordHits(snippets.stream().map(KnowledgeSnippet::getId).toList());
        List<Map<String, Object>> results = snippets.stream().map(this::knowledgeView).toList();
        return new ToolResult(toJson(Map.of("count", results.size(), "results", results)), snippets);
    }

    private ToolResult recentMeals(UserAccount user, JsonNode arguments) {
        rejectUnknownArguments(arguments, List.of());
        List<Map<String, Object>> meals = mealAnalysisService.listSince(user, 30).stream()
                .sorted(Comparator.comparing(MealRecord::getCreatedAt).reversed())
                .limit(MAX_RECENT_MEALS)
                .map(this::mealView)
                .toList();
        return new ToolResult(toJson(Map.of("count", meals.size(), "meals", meals)), List.of());
    }

    private ToolResult weeklyReport(UserAccount user, JsonNode arguments) {
        rejectUnknownArguments(arguments, List.of());
        WeeklyReportResponse report = weeklyReportService.latest(user);
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("days", report.getDays());
        value.put("totalMeals", report.getTotalMeals());
        value.put("averageScore", report.getAverageScore());
        value.put("goalType", report.getGoalType());
        value.put("highlights", report.getHighlights());
        value.put("riskTotals", report.getRiskTotals());
        value.put("nextWeekSuggestions", report.getNextWeekSuggestions());
        value.put("reportText", report.getReportText());
        return new ToolResult(toJson(Map.of("report", value)), List.of());
    }

    private ToolResult currentGoal(UserAccount user, JsonNode arguments) {
        rejectUnknownArguments(arguments, List.of());
        String goal = userGoalService.effectiveGoal(user, "current");
        return new ToolResult(toJson(Map.of("goal", goal)), List.of());
    }

    private Map<String, Object> knowledgeView(KnowledgeSnippet snippet) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("id", snippet.getId());
        value.put("title", snippet.getTitle());
        value.put("category", snippet.getCategory());
        value.put("content", truncate(snippet.getContent(), 1_200));
        value.put("score", snippet.getScore());
        return value;
    }

    private Map<String, Object> mealView(MealRecord meal) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("id", meal.getId());
        value.put("createdAt", meal.getCreatedAt());
        value.put("goal", meal.getGoal());
        value.put("score", meal.getScore());
        value.put("summary", truncate(meal.getSummary(), 500));
        value.put("foods", Jsons.readListOrEmpty(objectMapper, meal.getDetectedFoodsJson()));
        value.put("risks", Jsons.readListOrEmpty(objectMapper, meal.getRiskTagsJson()));
        return value;
    }

    private JsonNode parseArguments(String rawArguments) {
        String value = rawArguments == null || rawArguments.isBlank() ? "{}" : rawArguments;
        if (value.length() > MAX_ARGUMENT_LENGTH) {
            throw new IllegalArgumentException("Tool arguments are too large");
        }
        try {
            JsonNode arguments = objectMapper.readTree(value);
            if (arguments == null || !arguments.isObject()) {
                throw new IllegalArgumentException("Tool arguments must be a JSON object");
            }
            return arguments;
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("Tool arguments are not valid JSON", e);
        }
    }

    private String requiredText(JsonNode arguments, String name, int maxLength) {
        JsonNode node = arguments.get(name);
        if (node == null || !node.isTextual() || node.asText().isBlank()) {
            throw new IllegalArgumentException("Missing required string argument: " + name);
        }
        String value = node.asText().trim();
        if (value.length() > maxLength) {
            throw new IllegalArgumentException(name + " is too long");
        }
        return value;
    }

    private int integer(JsonNode arguments, String name, int defaultValue, int min, int max) {
        JsonNode node = arguments.get(name);
        if (node == null || node.isNull()) {
            return defaultValue;
        }
        if (!node.canConvertToInt()) {
            throw new IllegalArgumentException(name + " must be an integer");
        }
        return Math.max(min, Math.min(max, node.asInt()));
    }

    private String toJson(Object value) {
        try {
            String json = objectMapper.writeValueAsString(value);
            if (json.length() > MAX_RESULT_LENGTH) {
                throw new IllegalStateException("Assistant tool result is too large");
            }
            return json;
        } catch (Exception e) {
            throw new IllegalStateException("Unable to serialize assistant tool result", e);
        }
    }

    private void rejectUnknownArguments(JsonNode arguments, List<String> allowed) {
        arguments.fieldNames().forEachRemaining(name -> {
            if (!allowed.contains(name)) {
                throw new IllegalArgumentException("Unknown tool argument: " + name);
            }
        });
    }

    private void purge(ExecutorService executor) {
        if (executor instanceof ThreadPoolExecutor threadPool) {
            threadPool.purge();
        }
    }

    private long toolTimeoutMillis() {
        return Math.max(200L, Math.min(30_000L, properties.getAssistantTools().getTimeoutMillis()));
    }

    private long remainingMillis(long deadlineNanos) {
        long remaining = deadlineNanos - System.nanoTime();
        return remaining <= 0L ? 0L : Math.max(1L, TimeUnit.NANOSECONDS.toMillis(remaining));
    }

    private String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength) + "...";
    }

    public record ToolResult(String content, List<KnowledgeSnippet> references) {
        public ToolResult {
            references = references == null ? List.of() : List.copyOf(references);
        }
    }

    private record ToolDefinition(ToolSpecification specification,
                                  ExecutorService executor,
                                  ToolHandler handler) {
    }

    @FunctionalInterface
    private interface ToolHandler {
        ToolResult execute(UserAccount user, JsonNode arguments);
    }
}
