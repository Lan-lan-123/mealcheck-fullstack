package com.example.mealcheck.service;

import com.example.mealcheck.config.AppProperties;
import com.example.mealcheck.dto.KnowledgeSnippet;
import com.example.mealcheck.dto.assistant.AssistantChatMessage;
import com.example.mealcheck.entity.UserAccount;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ToolChoice;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.ThreadPoolExecutor;

@Service
public class AssistantFunctionCallingService {
    private final AppProperties properties;
    private final AssistantFunctionToolRegistry toolRegistry;
    private final AssistantChatModelFactory modelFactory;
    private final RemoteCallGuard remoteCallGuard;
    private final ExecutorService httpExecutor;

    public AssistantFunctionCallingService(AppProperties properties,
                                           AssistantFunctionToolRegistry toolRegistry,
                                           AssistantChatModelFactory modelFactory,
                                           RemoteCallGuard remoteCallGuard,
                                           @Qualifier("assistantHttpExecutor") ExecutorService httpExecutor) {
        this.properties = properties;
        this.toolRegistry = toolRegistry;
        this.modelFactory = modelFactory;
        this.remoteCallGuard = remoteCallGuard;
        this.httpExecutor = httpExecutor;
    }

    public FunctionCallingResult ask(UserAccount user,
                                     String systemPrompt,
                                     List<AssistantChatMessage> history,
                                     String question) {
        long deadlineNanos = System.nanoTime()
                + TimeUnit.SECONDS.toNanos(totalTimeoutSeconds());
        List<ChatMessage> messages = initialMessages(systemPrompt, history, question);
        Map<Long, KnowledgeSnippet> references = new LinkedHashMap<>();
        List<String> calledTools = new ArrayList<>();
        Set<String> executedRequests = new HashSet<>();
        int toolCallCount = 0;

        for (int round = 0; round <= maxToolRounds(); round++) {
            ChatResponse response = callModel(messages, deadlineNanos);
            AiMessage aiMessage = response.aiMessage();
            if (aiMessage == null) {
                throw new IllegalStateException("Function Calling model returned no assistant message");
            }
            messages.add(aiMessage);

            if (!aiMessage.hasToolExecutionRequests()) {
                String text = aiMessage.text();
                if (text == null || text.isBlank()) {
                    throw new IllegalStateException("Function Calling model returned an empty answer");
                }
                return new FunctionCallingResult(
                        text,
                        List.copyOf(references.values()),
                        List.copyOf(calledTools),
                        round
                );
            }
            if (round >= maxToolRounds()) {
                throw new IllegalStateException("Function Calling exceeded the maximum tool rounds");
            }

            for (ToolExecutionRequest request : aiMessage.toolExecutionRequests()) {
                toolCallCount++;
                if (toolCallCount > maxToolCalls()) {
                    throw new IllegalStateException("Function Calling exceeded the maximum tool calls");
                }
                String signature = request.name() + "\n" + request.arguments();
                if (!executedRequests.add(signature)) {
                    throw new IllegalStateException("Function Calling repeated an equivalent tool request: "
                            + request.name());
                }
                AssistantFunctionToolRegistry.ToolResult result = toolRegistry.execute(
                        user, request, deadlineNanos);
                calledTools.add(request.name());
                result.references().forEach(snippet -> {
                    if (snippet.getId() != null) {
                        references.putIfAbsent(snippet.getId(), snippet);
                    }
                });
                messages.add(ToolExecutionResultMessage.builder()
                        .id(request.id())
                        .toolName(request.name())
                        .text(result.content())
                        .isError(false)
                        .build());
            }
        }
        throw new IllegalStateException("Function Calling did not produce a final answer");
    }

    private ChatResponse callModel(List<ChatMessage> messages, long deadlineNanos) {
        long remainingMillis = remainingMillis(deadlineNanos);
        if (remainingMillis <= 0L) {
            throw new IllegalStateException("Assistant Function Calling deadline exceeded");
        }
        ChatModel model = modelFactory.create(Duration.ofMillis(remainingMillis));
        ChatRequest request = ChatRequest.builder()
                .messages(List.copyOf(messages))
                .toolSpecifications(toolRegistry.specifications())
                .toolChoice(ToolChoice.AUTO)
                .build();
        Future<ChatResponse> task;
        try {
            task = httpExecutor.submit(() -> remoteCallGuard.execute(
                    "diet-assistant", () -> model.chat(request)));
        } catch (RejectedExecutionException e) {
            throw new IllegalStateException("Assistant HTTP executor is saturated", e);
        }

        try {
            return task.get(remainingMillis, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            task.cancel(true);
            purgeHttpExecutor();
            throw new IllegalStateException("Assistant Function Calling timed out", e);
        } catch (InterruptedException e) {
            task.cancel(true);
            purgeHttpExecutor();
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Assistant Function Calling was interrupted", e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new IllegalStateException("Assistant Function Calling model call failed", cause);
        }
    }

    private List<ChatMessage> initialMessages(String systemPrompt,
                                              List<AssistantChatMessage> history,
                                              String question) {
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(SystemMessage.from(systemPrompt));
        if (history != null) {
            history.stream()
                    .filter(message -> message != null
                            && message.getText() != null
                            && !message.getText().isBlank())
                    .forEach(message -> {
                        String role = message.getRole() == null
                                ? "user"
                                : message.getRole().toLowerCase(Locale.ROOT);
                        if ("assistant".equals(role)) {
                            messages.add(AiMessage.from(message.getText().trim()));
                        } else {
                            messages.add(UserMessage.from(message.getText().trim()));
                        }
                    });
        }
        messages.add(UserMessage.from(question));
        return messages;
    }

    private int maxToolRounds() {
        return Math.max(1, Math.min(5,
                properties.getAssistantFunctionCalling().getMaxToolRounds()));
    }

    private int maxToolCalls() {
        return Math.max(1, Math.min(12,
                properties.getAssistantFunctionCalling().getMaxToolCalls()));
    }

    private int totalTimeoutSeconds() {
        return Math.max(5, Math.min(120,
                properties.getAssistantFunctionCalling().getTotalTimeoutSeconds()));
    }

    private long remainingMillis(long deadlineNanos) {
        long remaining = deadlineNanos - System.nanoTime();
        return remaining <= 0L ? 0L : Math.max(1L, TimeUnit.NANOSECONDS.toMillis(remaining));
    }

    private void purgeHttpExecutor() {
        if (httpExecutor instanceof ThreadPoolExecutor threadPool) {
            threadPool.purge();
        }
    }

    public record FunctionCallingResult(String answer,
                                        List<KnowledgeSnippet> references,
                                        List<String> calledTools,
                                        int toolRounds) {
    }
}
