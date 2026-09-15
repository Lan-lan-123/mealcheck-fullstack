package com.example.mealcheck.config;

import com.example.mealcheck.mcp.MealCheckMcpTools;
import com.example.mealcheck.mcp.Jackson2McpJsonMapper;
import com.example.mealcheck.mcp.SimpleMcpJsonSchemaValidator;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.schema.JsonSchemaValidator;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.HttpServletStreamableServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConditionalOnProperty(prefix = "mealcheck.mcp", name = "enabled", havingValue = "true", matchIfMissing = true)
public class McpServerConfig {

    @Bean
    public McpJsonMapper mcpJsonMapper(ObjectMapper objectMapper) {
        return new Jackson2McpJsonMapper(objectMapper);
    }

    @Bean
    public JsonSchemaValidator mcpJsonSchemaValidator() {
        return new SimpleMcpJsonSchemaValidator();
    }

    @Bean
    public HttpServletStreamableServerTransportProvider mcpTransportProvider(
            McpJsonMapper jsonMapper,
            AppProperties properties) {
        return HttpServletStreamableServerTransportProvider.builder()
                .jsonMapper(jsonMapper)
                .mcpEndpoint(properties.getMcp().getEndpoint())
                .build();
    }

    @Bean
    public ServletRegistrationBean<HttpServletStreamableServerTransportProvider> mcpServlet(
            HttpServletStreamableServerTransportProvider transportProvider,
            AppProperties properties) {
        ServletRegistrationBean<HttpServletStreamableServerTransportProvider> registration =
                new ServletRegistrationBean<>(transportProvider, properties.getMcp().getEndpoint());
        registration.setName("mealcheckMcpServlet");
        registration.setLoadOnStartup(1);
        return registration;
    }

    @Bean(destroyMethod = "close")
    public McpSyncServer mealCheckMcpServer(HttpServletStreamableServerTransportProvider transportProvider,
                                           MealCheckMcpTools tools,
                                           AppProperties properties,
                                           McpJsonMapper jsonMapper,
                                           JsonSchemaValidator jsonSchemaValidator) {
        return McpServer.sync(transportProvider)
                .jsonMapper(jsonMapper)
                .jsonSchemaValidator(jsonSchemaValidator)
                .serverInfo(properties.getMcp().getServerName(), properties.getMcp().getServerVersion())
                .instructions("Read-only MealCheck tools for dietary knowledge, recent meals, weekly reports and skill routing.")
                .capabilities(McpSchema.ServerCapabilities.builder().tools(false).logging().build())
                .immediateExecution(true)
                .tools(tools.specifications())
                .build();
    }
}
