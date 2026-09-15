package com.example.mealcheck.mcp;

import com.example.mealcheck.service.ApplicationObservability;
import com.example.mealcheck.service.KnowledgeIndexService;
import com.example.mealcheck.service.MealAnalysisService;
import com.example.mealcheck.service.WeeklyReportService;
import com.example.mealcheck.service.skill.AssistantSkillPlan;
import com.example.mealcheck.service.skill.AssistantSkillRegistry;
import com.example.mealcheck.service.skill.KeywordAssistantSkill;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class MealCheckMcpToolsTest {

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void exposesSkillRoutingAsAnMcpToolAndRecordsSuccess() {
        ApplicationObservability observability = mock(ApplicationObservability.class);
        MealCheckMcpTools tools = tools(observability);
        McpServerFeatures.SyncToolSpecification specification = find(tools, "route_assistant_skill");

        McpSchema.CallToolResult result = specification.callHandler().apply(
                null,
                new McpSchema.CallToolRequest("route_assistant_skill", Map.of("question", "我想减脂"))
        );

        assertThat(result.isError()).isNotEqualTo(true);
        assertThat(result.structuredContent()).isInstanceOf(Map.class);
        assertThat(((Map<?, ?>) result.structuredContent()).get("skill")).isEqualTo("fat-loss");
        verify(observability).recordMcpTool(org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.eq("route_assistant_skill"),
                org.mockito.ArgumentMatchers.eq("success"));
    }

    @Test
    void rejectsUserSpecificToolWithoutJwtPrincipal() {
        ApplicationObservability observability = mock(ApplicationObservability.class);
        McpServerFeatures.SyncToolSpecification specification = find(tools(observability), "get_my_recent_meals");

        McpSchema.CallToolResult result = specification.callHandler().apply(
                null,
                new McpSchema.CallToolRequest("get_my_recent_meals", Map.of())
        );

        assertThat(result.isError()).isTrue();
        assertThat(result.content().toString()).contains("JWT");
        verify(observability).recordMcpTool(org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.eq("get_my_recent_meals"),
                org.mockito.ArgumentMatchers.eq("rejected"));
    }

    private MealCheckMcpTools tools(ApplicationObservability observability) {
        KeywordAssistantSkill fatLoss = new KeywordAssistantSkill(
                new AssistantSkillPlan("fat-loss", "减脂", "FAT_LOSS", "减脂", "减脂策略"),
                List.of("减脂"), 10, false);
        KeywordAssistantSkill fallback = new KeywordAssistantSkill(
                new AssistantSkillPlan("balanced-diet", "均衡", "GENERAL", "均衡", "均衡策略"),
                List.of(), 0, true);
        AssistantSkillRegistry registry = new AssistantSkillRegistry(List.of(fallback, fatLoss));
        return new MealCheckMcpTools(
                mock(KnowledgeIndexService.class),
                mock(MealAnalysisService.class),
                mock(WeeklyReportService.class),
                registry,
                new ObjectMapper(),
                observability
        );
    }

    private McpServerFeatures.SyncToolSpecification find(MealCheckMcpTools tools, String name) {
        return tools.specifications().stream()
                .filter(specification -> name.equals(specification.tool().name()))
                .findFirst()
                .orElseThrow();
    }
}
