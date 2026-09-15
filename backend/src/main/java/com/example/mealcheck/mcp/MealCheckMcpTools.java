package com.example.mealcheck.mcp;

import com.example.mealcheck.dto.KnowledgeSnippet;
import com.example.mealcheck.security.UserPrincipal;
import com.example.mealcheck.service.KnowledgeIndexService;
import com.example.mealcheck.service.MealAnalysisService;
import com.example.mealcheck.service.ApplicationObservability;
import com.example.mealcheck.service.WeeklyReportService;
import com.example.mealcheck.service.skill.AssistantSkillPlan;
import com.example.mealcheck.service.skill.AssistantSkillRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
public class MealCheckMcpTools {
    private final KnowledgeIndexService knowledgeIndexService;
    private final MealAnalysisService mealAnalysisService;
    private final WeeklyReportService weeklyReportService;
    private final AssistantSkillRegistry skillRegistry;
    private final ObjectMapper objectMapper;
    private final ApplicationObservability observability;

    public MealCheckMcpTools(KnowledgeIndexService knowledgeIndexService,
                             MealAnalysisService mealAnalysisService,
                             WeeklyReportService weeklyReportService,
                             AssistantSkillRegistry skillRegistry,
                             ObjectMapper objectMapper,
                             ApplicationObservability observability) {
        this.knowledgeIndexService = knowledgeIndexService;
        this.mealAnalysisService = mealAnalysisService;
        this.weeklyReportService = weeklyReportService;
        this.skillRegistry = skillRegistry;
        this.objectMapper = objectMapper;
        this.observability = observability;
    }

    public List<McpServerFeatures.SyncToolSpecification> specifications() {
        return List.of(
                knowledgeSearchTool(),
                routeSkillTool(),
                listSkillsTool(),
                recentMealsTool(),
                weeklyReportTool()
        );
    }

    private McpServerFeatures.SyncToolSpecification knowledgeSearchTool() {
        McpSchema.Tool tool = McpSchema.Tool.builder()
                .name("search_diet_knowledge")
                .description("Search MealCheck dietary knowledge using hybrid vector and keyword retrieval")
                .inputSchema(schema(Map.of(
                        "query", property("string", "Diet or nutrition question"),
                        "limit", property("integer", "Maximum results from 1 to 10")
                ), List.of("query")))
                .build();
        return specification(tool, arguments -> {
            String query = requiredString(arguments, "query");
            int limit = safeInt(arguments.get("limit"), 5, 1, 10);
            List<KnowledgeSnippet> snippets = knowledgeIndexService.search(query, limit);
            return success(Map.of("results", snippets, "count", snippets.size()));
        });
    }

    private McpServerFeatures.SyncToolSpecification routeSkillTool() {
        McpSchema.Tool tool = McpSchema.Tool.builder()
                .name("route_assistant_skill")
                .description("Select the MealCheck business skill that should handle a user question")
                .inputSchema(schema(Map.of(
                        "question", property("string", "User's diet question")
                ), List.of("question")))
                .build();
        return specification(tool, arguments -> {
            AssistantSkillPlan plan = skillRegistry.select(requiredString(arguments, "question"));
            return success(Map.of(
                    "skill", plan.skillName(),
                    "description", plan.description(),
                    "intent", plan.intentCode(),
                    "instruction", plan.promptInstruction()));
        });
    }

    private McpServerFeatures.SyncToolSpecification listSkillsTool() {
        McpSchema.Tool tool = McpSchema.Tool.builder()
                .name("list_assistant_skills")
                .description("List registered MealCheck assistant skills")
                .inputSchema(schema(Map.of(), List.of()))
                .build();
        return specification(tool, arguments -> success(Map.of("skills", skillRegistry.descriptors())));
    }

    private McpServerFeatures.SyncToolSpecification recentMealsTool() {
        McpSchema.Tool tool = McpSchema.Tool.builder()
                .name("get_my_recent_meals")
                .description("Get recent meal records for the user authenticated by the MCP request JWT")
                .inputSchema(schema(Map.of(), List.of()))
                .build();
        return specification(tool, arguments -> {
            UserPrincipal principal = currentPrincipal();
            return success(Map.of("meals", mealAnalysisService.listRecent(principal)));
        });
    }

    private McpServerFeatures.SyncToolSpecification weeklyReportTool() {
        McpSchema.Tool tool = McpSchema.Tool.builder()
                .name("get_my_weekly_report")
                .description("Get the latest weekly diet report for the user authenticated by the MCP request JWT")
                .inputSchema(schema(Map.of(), List.of()))
                .build();
        return specification(tool, arguments -> success(Map.of(
                "report", weeklyReportService.latest(currentPrincipal()))));
    }

    private McpServerFeatures.SyncToolSpecification specification(McpSchema.Tool tool,
                                                                   ToolHandler handler) {
        return McpServerFeatures.SyncToolSpecification.builder()
                .tool(tool)
                .callHandler((exchange, request) -> {
                    long startedAt = observability.start();
                    try {
                        McpSchema.CallToolResult result = handler.handle(request.arguments());
                        observability.recordMcpTool(startedAt, tool.name(), "success");
                        return result;
                    } catch (IllegalArgumentException | IllegalStateException e) {
                        observability.recordMcpTool(startedAt, tool.name(), "rejected");
                        return McpSchema.CallToolResult.builder()
                                .addTextContent(e.getMessage())
                                .isError(true)
                                .build();
                    } catch (Exception e) {
                        observability.recordMcpTool(startedAt, tool.name(), "failure");
                        throw new IllegalStateException("MCP tool execution failed: " + tool.name(), e);
                    }
                })
                .build();
    }

    private McpSchema.CallToolResult success(Object value) throws Exception {
        String json = objectMapper.writeValueAsString(value);
        return McpSchema.CallToolResult.builder()
                .addTextContent(json)
                .structuredContent(value)
                .build();
    }

    private McpSchema.JsonSchema schema(Map<String, Object> properties, List<String> required) {
        return new McpSchema.JsonSchema("object", properties, required, false, null, null);
    }

    private Map<String, Object> property(String type, String description) {
        return Map.of("type", type, "description", description);
    }

    private String requiredString(Map<String, Object> arguments, String name) {
        Object value = arguments == null ? null : arguments.get(name);
        if (value == null || value.toString().isBlank()) {
            throw new IllegalArgumentException("Missing required argument: " + name);
        }
        return value.toString().trim();
    }

    private int safeInt(Object value, int defaultValue, int min, int max) {
        if (value == null) {
            return defaultValue;
        }
        try {
            return Math.max(min, Math.min(Integer.parseInt(value.toString()), max));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("limit must be an integer", e);
        }
    }

    private UserPrincipal currentPrincipal() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof UserPrincipal principal)) {
            throw new IllegalStateException("A valid MealCheck JWT is required for this MCP tool");
        }
        return principal;
    }

    @FunctionalInterface
    private interface ToolHandler {
        McpSchema.CallToolResult handle(Map<String, Object> arguments) throws Exception;
    }
}
