package com.example.mealcheck.service;

import com.example.mealcheck.config.AppProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.URI;
import java.util.List;
import java.util.Locale;

/** The same final request policy for direct, SDK and streaming chat calls. */
final class AiChatRequestPolicy {
    // Verified Model Studio chat/vision families; do not generalize to all Qwen models.
    static boolean controlledQwen(String model) {
        return model != null && model.toLowerCase(Locale.ROOT)
                .matches("(?:qwen3\\.8-(?:max|flash|27b)|qwen3\\.7-flash)(?:-.*)?");
    }
    private final AppProperties properties;
    private final ObjectMapper mapper;

    AiChatRequestPolicy(AppProperties properties, ObjectMapper mapper) {
        this.properties = properties;
        this.mapper = mapper;
    }

    void validate() {
        var ai = properties.getAi();
        for (String mode : new String[]{ai.getVisionThinkingMode(), ai.getAdviceThinkingMode(),
                ai.getAssistantThinkingMode(), ai.getWeeklyThinkingMode()}) {
            if (mode == null || !List.of("off", "low", "provider").contains(mode))
                throw new IllegalArgumentException("AI thinking mode must be off, low or provider");
        }
        if (ai.getThinkingBudgetTokens() < 1 || ai.getThinkingBudgetTokens() > 8192)
            throw new IllegalArgumentException("AI thinking token budget must be 1..8192");
    }

    String prepare(String operation, URI uri, String body) {
        if (body == null || !uri.getPath().endsWith("/chat/completions")) return body;
        try {
            ObjectNode node = (ObjectNode) mapper.readTree(body);
            int cap = Math.max(1, Math.min(32768, properties.getAiBudget().getMaxOutputTokens()));
            int requested = cap;
            for (String field : List.of("max_tokens", "max_completion_tokens")) {
                if (node.has(field)) requested = Math.min(requested, Math.max(1, node.path(field).asInt(cap)));
            }
            if ("ai-configuration-test".equals(operation)) requested = Math.min(requested, 32);
            String model = node.path("model").asText("").toLowerCase(Locale.ROOT);
            if ("assistant-benchmark-judge".equals(operation) && aliyunKimiK3(uri, model)) {
                // Model Studio K3 is thinking-only; keep this profile isolated from production Qwen calls.
                node.put("temperature", 1.0);
                node.put("enable_thinking", true);
                node.remove("thinking_budget");
            }
            boolean siliconQwen="api.siliconflow.cn".equalsIgnoreCase(uri.getHost()) && model.equals("qwen/qwen3.8-27b");
            // These families have verified support for both thinking controls and total-output caps.
            if (controlledQwen(model) || siliconQwen) {
                node.remove(siliconQwen?"max_completion_tokens":"max_tokens");
                node.put(siliconQwen?"max_tokens":"max_completion_tokens", requested);
                String mode = mode(operation);
                if (!"provider".equals(mode)) {
                    node.remove("reasoning_effort");
                    node.remove("thinking_budget");
                    boolean low="low".equals(mode) && (!siliconQwen || requested>=256);
                    node.put("enable_thinking", low);
                    if (low) {
                        // Reserve at least half of the total output allowance for the final answer.
                        int thinking=Math.max(siliconQwen?128:1, Math.min(properties.getAi().getThinkingBudgetTokens(), requested / 2));
                        node.put("thinking_budget",thinking);
                        // SiliconFlow max_tokens excludes reasoning; reserve reasoning inside the shared cap.
                        if(siliconQwen)node.put("max_tokens",Math.min(requested,cap-thinking));
                    }
                }
                if (operation != null && (operation.equals("vision-recognition") || operation.startsWith("weekly-report")
                        || operation.startsWith("diet-assistant") && !node.path("stream").asBoolean(false))
                        && !node.hasNonNull("response_format")) {
                    node.putObject("response_format").put("type", "json_object");
                }
            } else {
                // Preserve other providers' request vocabulary; do not inject vendor-only fields.
                String field = node.has("max_completion_tokens") ? "max_completion_tokens" : "max_tokens";
                node.put(field, requested);
                if (node.has("max_tokens") && node.has("max_completion_tokens")) node.remove("max_tokens");
            }
            return mapper.writeValueAsString(node);
        } catch (Exception invalid) {
            throw new RemoteCallGuard.NonRetryableRemoteCallException("Invalid AI request JSON");
        }
    }

    private static boolean aliyunKimiK3(URI uri, String model) {
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
        return (model.equals("kimi-k3") || model.equals("kimi/kimi-k3"))
                && (host.equals("dashscope.aliyuncs.com") || host.equals("dashscope-intl.aliyuncs.com")
                    || host.endsWith(".maas.aliyuncs.com"));
    }

    private String mode(String operation) {
        if (operation == null) return "provider";
        if (operation.equals("ai-configuration-test")) return "off";
        var ai = properties.getAi();
        if (operation.equals("vision-recognition")) return ai.getVisionThinkingMode();
        if (operation.equals("meal-advice")) return ai.getAdviceThinkingMode();
        if (operation.startsWith("diet-assistant")) return ai.getAssistantThinkingMode();
        if (operation.startsWith("weekly-report")) return ai.getWeeklyThinkingMode();
        return "provider";
    }
}
