package com.example.mealcheck.service;

import com.example.mealcheck.config.AppProperties;
import com.example.mealcheck.dto.KnowledgeSnippet;
import com.example.mealcheck.dto.assistant.AssistantChatMessage;
import com.example.mealcheck.entity.UserAccount;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AssistantFunctionCallingServiceTest {
    private final ExecutorService httpExecutor = Executors.newSingleThreadExecutor();

    @AfterEach
    void tearDown() {
        httpExecutor.shutdownNow();
    }

    @Test
    void executesModelRequestedToolAndReturnsFinalAnswer() {
        AppProperties properties = new AppProperties();
        AssistantFunctionToolRegistry registry = mock(AssistantFunctionToolRegistry.class);
        AssistantChatModelFactory modelFactory = mock(AssistantChatModelFactory.class);
        ChatModel model = mock(ChatModel.class);
        when(modelFactory.create(any())).thenReturn(model);
        when(registry.specifications()).thenReturn(List.of());

        ToolExecutionRequest toolRequest = ToolExecutionRequest.builder()
                .id("call-1")
                .name("search_diet_knowledge")
                .arguments("{\"query\":\"蛋白质\"}")
                .build();
        when(model.chat(any(ChatRequest.class))).thenReturn(
                ChatResponse.builder().aiMessage(AiMessage.from(List.of(toolRequest))).build(),
                ChatResponse.builder().aiMessage(AiMessage.from("{\"summary\":\"答案\"}")).build()
        );
        KnowledgeSnippet snippet = new KnowledgeSnippet(7L, "蛋白质", "内容", 0.9);
        when(registry.execute(any(), any(), anyLong()))
                .thenReturn(new AssistantFunctionToolRegistry.ToolResult(
                        "{\"count\":1}", List.of(snippet)));

        AssistantFunctionCallingService service = new AssistantFunctionCallingService(
                properties, registry, modelFactory, new RemoteCallGuard(properties), httpExecutor);
        UserAccount user = user("demo");

        AssistantFunctionCallingService.FunctionCallingResult result = service.ask(
                user, "system", List.of(new AssistantChatMessage("assistant", "上一轮")), "问题");

        assertThat(result.answer()).contains("答案");
        assertThat(result.calledTools()).containsExactly("search_diet_knowledge");
        assertThat(result.references()).containsExactly(snippet);
        assertThat(result.toolRounds()).isEqualTo(1);

        ArgumentCaptor<ChatRequest> requests = ArgumentCaptor.forClass(ChatRequest.class);
        verify(model, times(2)).chat(requests.capture());
        assertThat(requests.getAllValues().get(1).messages())
                .anyMatch(message -> message instanceof ToolExecutionResultMessage resultMessage
                        && resultMessage.id().equals("call-1")
                        && resultMessage.text().contains("count"));
    }

    @Test
    void rejectsCallsBeyondConfiguredLimit() {
        AppProperties properties = new AppProperties();
        properties.getAssistantFunctionCalling().setMaxToolCalls(1);
        AssistantFunctionToolRegistry registry = mock(AssistantFunctionToolRegistry.class);
        AssistantChatModelFactory modelFactory = mock(AssistantChatModelFactory.class);
        ChatModel model = mock(ChatModel.class);
        when(modelFactory.create(any())).thenReturn(model);
        when(registry.specifications()).thenReturn(List.of());

        ToolExecutionRequest first = ToolExecutionRequest.builder()
                .id("call-1").name("get_my_current_goal").arguments("{}").build();
        ToolExecutionRequest second = ToolExecutionRequest.builder()
                .id("call-2").name("get_my_weekly_report").arguments("{}").build();
        when(model.chat(any(ChatRequest.class))).thenReturn(ChatResponse.builder()
                .aiMessage(AiMessage.from(List.of(first, second))).build());
        when(registry.execute(any(), any(), anyLong()))
                .thenReturn(new AssistantFunctionToolRegistry.ToolResult("{}", List.of()));

        AssistantFunctionCallingService service = new AssistantFunctionCallingService(
                properties, registry, modelFactory, new RemoteCallGuard(properties), httpExecutor);

        assertThatThrownBy(() -> service.ask(user("demo"), "system", List.of(), "问题"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("maximum tool calls");
    }

    private UserAccount user(String username) {
        UserAccount user = new UserAccount();
        user.setId(1L);
        user.setUsername(username);
        user.setPasswordHash("hash");
        return user;
    }
}
