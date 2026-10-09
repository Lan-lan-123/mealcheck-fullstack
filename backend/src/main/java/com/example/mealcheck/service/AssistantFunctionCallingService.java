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
    private final ExecutorService httpExecutor;

    public AssistantFunctionCallingService(AppProperties properties,
                                           AssistantFunctionToolRegistry toolRegistry,
                                           AssistantChatModelFactory modelFactory,
                                           @Qualifier("assistantHttpExecutor") ExecutorService httpExecutor) {
        this.properties = properties;
        this.toolRegistry = toolRegistry;
        this.modelFactory = modelFactory;
        this.httpExecutor = httpExecutor;
    }

    public FunctionCallingResult ask(UserAccount user,
                                     String systemPrompt,
                                     List<AssistantChatMessage> history,
                                     String question) {
        try (var scope=AiCallContext.open(user.getId(),Duration.ofSeconds(totalTimeoutSeconds()))) {
            return run(user, systemPrompt, history, question, toolRegistry);
        }
    }

    public FunctionCallingResult askWithRuntime(String systemPrompt,
                                                List<AssistantChatMessage> history,
                                                String question,
                                                AssistantFunctionToolRuntime runtime) {
        if (runtime == null) {
            throw new IllegalArgumentException("Function Calling runtime is required");
        }
        return run(null, systemPrompt, history, question, runtime);
    }

    private FunctionCallingResult run(UserAccount user,
                                      String systemPrompt,
                                      List<AssistantChatMessage> history,
                                      String question,
                                      AssistantFunctionToolRuntime runtime) {
        long deadlineNanos = System.nanoTime()
                + TimeUnit.SECONDS.toNanos(totalTimeoutSeconds());
        var context=AiCallContext.capture();
        if (context!=null) deadlineNanos=Math.min(deadlineNanos,context.deadlineNanos());
        List<ChatMessage> messages = initialMessages(systemPrompt, history, question);
        Map<Long, KnowledgeSnippet> references = new LinkedHashMap<>();
        List<String> calledTools = new ArrayList<>();
        List<ToolCallTrace> toolTrace = new ArrayList<>();
        Set<String> executedRequests = new HashSet<>();
        int toolCallCount = 0;

        for (int round = 0; round <= maxToolRounds(); round++) {
            ChatResponse response;
            try {
                response = callModel(messages, deadlineNanos, runtime);
            } catch (FunctionCallingExecutionException e) {
                throw e;
            } catch (RuntimeException e) {
                throw new FunctionCallingExecutionException(
                        e.getMessage(), e, toolTrace, round);
            }
            AiMessage aiMessage = response.aiMessage();
            if (aiMessage == null) {
                throw new FunctionCallingExecutionException(
                        "Function Calling model returned no assistant message", null, toolTrace, round);
            }
            messages.add(aiMessage);

            if (!aiMessage.hasToolExecutionRequests()) {
                String text = aiMessage.text();
                if (text == null || text.isBlank()) {
                    throw new FunctionCallingExecutionException(
                            "Function Calling model returned an empty answer", null, toolTrace, round);
                }
                return new FunctionCallingResult(
                        text,
                        List.copyOf(references.values()),
                        List.copyOf(calledTools),
                        round,
                        List.copyOf(toolTrace)
                );
            }
            if (round >= maxToolRounds()) {
                throw new FunctionCallingExecutionException(
                        "Function Calling exceeded the maximum tool rounds", null, toolTrace, round);
            }

            for (ToolExecutionRequest request : aiMessage.toolExecutionRequests()) {
                toolCallCount++;
                if (toolCallCount > maxToolCalls()) {
                    toolTrace.add(new ToolCallTrace(
                            request.name(), safeArguments(request.arguments()), "REJECTED", round + 1,
                            0L));
                    throw new FunctionCallingExecutionException(
                            "Function Calling exceeded the maximum tool calls", null, toolTrace, round + 1);
                }
                String signature = request.name() + "\n" + request.arguments();
                if (!executedRequests.add(signature)) {
                    toolTrace.add(new ToolCallTrace(
                            request.name(), safeArguments(request.arguments()), "REJECTED", round + 1,
                            0L));
                    throw new FunctionCallingExecutionException(
                            "Function Calling repeated an equivalent tool request: " + request.name(),
                            null, toolTrace, round + 1);
                }
                long toolStartedAt = System.nanoTime();
                AssistantFunctionToolRuntime.ToolResult result;
                try {
                    result = runtime.execute(user, request, deadlineNanos);
                    toolTrace.add(new ToolCallTrace(
                            request.name(), safeArguments(request.arguments()), result.status(),
                            round + 1,
                            elapsedMs(toolStartedAt)));
                } catch (RuntimeException e) {
                    toolTrace.add(new ToolCallTrace(
                            request.name(), safeArguments(request.arguments()), "ERROR",
                            round + 1,
                            elapsedMs(toolStartedAt)));
                    throw new FunctionCallingExecutionException(
                            "Assistant tool failed: " + request.name(), e, toolTrace, round + 1);
                }
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
        throw new FunctionCallingExecutionException(
                "Function Calling did not produce a final answer", null, toolTrace, maxToolRounds());
    }

    private ChatResponse callModel(List<ChatMessage> messages,
                                   long deadlineNanos,
                                   AssistantFunctionToolRuntime runtime) {
        long remainingMillis = remainingMillis(deadlineNanos);
        if (remainingMillis <= 0L) {
            throw new IllegalStateException("Assistant Function Calling deadline exceeded");
        }
        ChatModel model = modelFactory.create(Duration.ofMillis(remainingMillis));
        ChatRequest request = ChatRequest.builder()
                .messages(List.copyOf(messages))
                .toolSpecifications(runtime.specifications())
                .toolChoice(ToolChoice.AUTO)
                .build();
        Future<ChatResponse> task;
        var context=AiCallContext.capture();
        var callContext=new AiCallContext.Context(context==null?null:context.userId(),deadlineNanos,context==null?null:context.dispatchGuard());
        try {
            task = httpExecutor.submit(() -> {
                try (var scope=AiCallContext.restore(callContext)) { return model.chat(request); }
            });
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

    private long elapsedMs(long startedNanos) {
        return Duration.ofNanos(System.nanoTime() - startedNanos).toMillis();
    }

    private String safeArguments(String arguments) {
        String value = arguments == null || arguments.isBlank() ? "{}" : arguments.trim();
        return value.length() <= 1_000 ? value : value.substring(0, 1_000) + "...";
    }

    private void purgeHttpExecutor() {
        if (httpExecutor instanceof ThreadPoolExecutor threadPool) {
            threadPool.purge();
        }
    }

    public record FunctionCallingResult(String answer,
                                        List<KnowledgeSnippet> references,
                                        List<String> calledTools,
                                        int toolRounds,
                                        List<ToolCallTrace> toolTrace) {
        public FunctionCallingResult {
            references = references == null ? List.of() : List.copyOf(references);
            calledTools = calledTools == null ? List.of() : List.copyOf(calledTools);
            toolTrace = toolTrace == null ? List.of() : List.copyOf(toolTrace);
        }
    }

    public record ToolCallTrace(String name,
                                String arguments,
                                String status,
                                int round,
                                long durationMs) {
    }

    public static class FunctionCallingExecutionException extends IllegalStateException {
        private final List<ToolCallTrace> toolTrace;
        private final int toolRounds;

        public FunctionCallingExecutionException(String message,
                                                 Throwable cause,
                                                 List<ToolCallTrace> toolTrace,
                                                 int toolRounds) {
            super(message, cause);
            this.toolTrace = List.copyOf(toolTrace);
            this.toolRounds = toolRounds;
        }

        public List<ToolCallTrace> getToolTrace() {
            return toolTrace;
        }

        public int getToolRounds() {
            return toolRounds;
        }
    }
}
