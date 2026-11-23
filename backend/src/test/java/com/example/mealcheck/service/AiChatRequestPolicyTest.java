package com.example.mealcheck.service;

import com.example.mealcheck.config.AppProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.net.URI;
import static org.assertj.core.api.Assertions.*;

class AiChatRequestPolicyTest {
    @Test void aliyunKimiJudgeUsesThinkingProfileWithinExistingOutputCap() throws Exception {
        for (String host : new String[]{"dashscope.aliyuncs.com", "workspace.cn-beijing.maas.aliyuncs.com"}) {
            for (String model : new String[]{"kimi-k3", "kimi/kimi-k3"}) {
                var sent = mapper.readTree(policy.prepare("assistant-benchmark-judge",
                        URI.create("https://" + host + "/compatible-mode/v1/chat/completions"),
                        "{\"model\":\"" + model + "\",\"temperature\":0,\"enable_thinking\":false,\"thinking_budget\":512,\"max_tokens\":99999}"));
                assertThat(sent.path("temperature").asDouble()).isEqualTo(1.0);
                assertThat(sent.path("enable_thinking").asBoolean()).isTrue();
                assertThat(sent.has("thinking_budget")).isFalse();
                assertThat(sent.path("max_tokens").asInt()).isEqualTo(2048);
            }
        }
        var otherProvider = mapper.readTree(policy.prepare("assistant-benchmark-judge",
                URI.create("https://other.example/v1/chat/completions"), "{\"model\":\"kimi-k3\",\"temperature\":0}"));
        assertThat(otherProvider.path("temperature").asDouble()).isZero();
        assertThat(otherProvider.has("enable_thinking")).isFalse();
        var otherOperation = mapper.readTree(policy.prepare("meal-advice", endpoint,
                "{\"model\":\"kimi-k3\",\"temperature\":0}"));
        assertThat(otherOperation.path("temperature").asDouble()).isZero();
    }
    @Test void siliconFlowQwenUsesItsOwnVocabularyForTextReviewAndVision() throws Exception {
        var endpoint=URI.create("https://api.siliconflow.cn/v1/chat/completions");
        for(String operation:new String[]{"vision-recognition","meal-advice","diet-assistant","diet-assistant-evidence-review","weekly-report-agent"}) {
            var body="{\"model\":\"Qwen/Qwen3.8-27B\",\"max_completion_tokens\":9000,\"thinking_budget\":10000,\"reasoning_effort\":\"high\"}";
            var sent=mapper.readTree(policy.prepare(operation,endpoint,body));
            assertThat(sent.path("max_tokens").asInt()).isEqualTo(2048);
            assertThat(sent.path("enable_thinking").asBoolean(true)).isFalse();
            assertThat(sent.has("max_completion_tokens") || sent.has("thinking_budget") || sent.has("reasoning_effort")).isFalse();
            assertThat(sent.path("response_format").path("type").asText()).isEqualTo(operation.equals("meal-advice")?"":"json_object");
        }
        properties.getAi().setWeeklyThinkingMode("low");
        var low=mapper.readTree(policy.prepare("weekly-report-agent",endpoint,"{\"model\":\"Qwen/Qwen3.8-27B\",\"max_tokens\":512}"));
        assertThat(low.path("thinking_budget").asInt()).isBetween(128,256);
        var capped=mapper.readTree(policy.prepare("weekly-report-agent",endpoint,"{\"model\":\"Qwen/Qwen3.8-27B\"}"));
        assertThat(capped.path("max_tokens").asInt()+capped.path("thinking_budget").asInt()).isLessThanOrEqualTo(2048);
        var small=mapper.readTree(policy.prepare("weekly-report-agent",endpoint,"{\"model\":\"Qwen/Qwen3.8-27B\",\"max_tokens\":100}"));
        assertThat(small.path("enable_thinking").asBoolean(true)).isFalse();
        assertThat(small.has("thinking_budget")).isFalse();
        var stream=mapper.readTree(policy.prepare("diet-assistant",endpoint,"{\"model\":\"Qwen/Qwen3.8-27B\",\"stream\":true}"));
        assertThat(stream.has("response_format")).isFalse();
        var other=mapper.readTree(policy.prepare("vision-recognition",URI.create("https://other.invalid/v1/chat/completions"),"{\"model\":\"Qwen/Qwen3.8-27B\"}"));
        assertThat(other.has("enable_thinking")).isFalse();
    }
    @Test void qwen3827bUsesExplicitThinkingAndTotalOutputCaps() throws Exception {
        for(String operation:new String[]{"vision-recognition","meal-advice","diet-assistant-function-calling","diet-assistant-evidence-review"}) {
            var sent=mapper.readTree(policy.prepare(operation,endpoint,"{\"model\":\"qwen3.8-27b\",\"max_tokens\":99999,\"thinking_budget\":131072,\"reasoning_effort\":\"xhigh\"}"));
            assertThat(sent.path("enable_thinking").asBoolean(true)).isFalse();
            assertThat(sent.path("max_completion_tokens").asInt()).isEqualTo(2048);
            assertThat(sent.has("max_tokens") || sent.has("thinking_budget") || sent.has("reasoning_effort")).isFalse();
            assertThat(sent.path("response_format").path("type").asText()).isEqualTo(operation.equals("meal-advice")?"":"json_object");
        }
        assertThat(AiChatRequestPolicy.controlledQwen("qwen3.8-27b-other-provider-name")).isTrue();
        assertThat(AiChatRequestPolicy.controlledQwen("qwen3.8-27bigger")).isFalse();
    }
    @Test void qwen37FlashUsesExplicitThinkingOutputAndJsonPolicy() throws Exception {
        for (String model : new String[]{"qwen3.7-flash", "qwen3.7-flash-2026-07-15"}) {
            String body="{\"model\":\""+model+"\",\"max_tokens\":99999,\"thinking_budget\":99999,\"reasoning_effort\":\"high\"}";
            var sent=mapper.readTree(policy.prepare("vision-recognition",endpoint,body));
            assertThat(sent.path("enable_thinking").asBoolean(true)).isFalse();
            assertThat(sent.path("max_completion_tokens").asInt()).isEqualTo(2048);
            assertThat(sent.path("response_format").path("type").asText()).isEqualTo("json_object");
            assertThat(sent.has("max_tokens") || sent.has("thinking_budget") || sent.has("reasoning_effort")).isFalse();
        }
    }
    private final AppProperties properties = new AppProperties();
    private final ObjectMapper mapper = new ObjectMapper();
    private final AiChatRequestPolicy policy = new AiChatRequestPolicy(properties,mapper);
    private final URI endpoint = URI.create("http://localhost/v1/chat/completions");

    @Test void qwenProfilesControlReasoningAndCapTheFullCompletion() throws Exception {
        String body="{\"model\":\"qwen3.8-max\",\"max_tokens\":99999,\"reasoning_effort\":\"xhigh\",\"thinking_budget\":131072}";
        for(String operation:new String[]{"vision-recognition","meal-advice","diet-assistant","diet-assistant-function-calling","diet-assistant-rag-quality-judge","weekly-report-agent"}) {
            var sent=mapper.readTree(policy.prepare(operation,endpoint,body));
            assertThat(sent.path("enable_thinking").asBoolean(true)).as(operation).isFalse();
            assertThat(sent.path("max_completion_tokens").asInt()).isEqualTo(2048);
            assertThat(sent.has("max_tokens") || sent.has("reasoning_effort") || sent.has("thinking_budget")).isFalse();
            assertThat(sent.path("response_format").path("type").asText())
                    .as(operation).isEqualTo(operation.equals("meal-advice") ? "" : "json_object");
        }
        properties.getAi().setWeeklyThinkingMode("low");
        var weekly=mapper.readTree(policy.prepare("weekly-report-plan-agent",endpoint,body));
        assertThat(weekly.path("enable_thinking").asBoolean()).isTrue();
        assertThat(weekly.path("thinking_budget").asInt()).isEqualTo(512);
        assertThat(weekly.has("reasoning_effort")).isFalse();
    }
    @Test void nonStreamingAssistantUsesNativeJsonButTextStreamsKeepTheirExistingFormat() throws Exception {
        for (String operation : new String[]{"diet-assistant", "diet-assistant-evidence-review"}) {
            var body="{\"model\":\"qwen3.8-flash\"}";
            var prepared=mapper.readTree(policy.prepare(operation,endpoint,body));
            assertThat(prepared.path("response_format").path("type").asText()).isEqualTo("json_object");
            var stream=mapper.readTree(policy.prepare(operation,endpoint,"{\"model\":\"qwen3.8-flash\",\"stream\":true}"));
            assertThat(stream.has("response_format")).isFalse();
        }
        var explicit=mapper.readTree(policy.prepare("diet-assistant",endpoint,"{\"model\":\"qwen3.8-flash\",\"response_format\":{\"type\":\"json_schema\"}}"));
        assertThat(explicit.path("response_format").path("type").asText()).isEqualTo("json_schema");
    }
    @Test void respectsSmallerRequestLimitsAndReservesAnswerSpace() throws Exception {
        properties.getAi().setWeeklyThinkingMode("low");
        var sent=mapper.readTree(policy.prepare("weekly-report-agent",endpoint,"{\"model\":\"qwen3.8-max-0902\",\"max_tokens\":100,\"max_completion_tokens\":200}"));
        assertThat(sent.path("max_completion_tokens").asInt()).isEqualTo(100);
        assertThat(sent.path("thinking_budget").asInt()).isEqualTo(50);
    }
    @Test void otherModelsAndNonChatEndpointsDoNotAcquireVendorFields() throws Exception {
        for(String model:new String[]{"gpt-example","qwen2.5-vl-3b-instruct","qwen3-vl-30b-a3b-instruct","qwen3.8-omni-flash","qwen3.8-maximal"}) {
            var sent=mapper.readTree(policy.prepare("vision-recognition",endpoint,"{\"model\":\""+model+"\",\"max_tokens\":99999}"));
            assertThat(sent.path("max_tokens").asInt()).isEqualTo(2048);
            assertThat(sent.has("enable_thinking") || sent.has("thinking_budget")).isFalse();
        }
        String embedding="{\"model\":\"text-embedding-v4\",\"input\":\"hello\"}";
        assertThat(policy.prepare("embedding",URI.create("http://localhost/v1/embeddings"),embedding)).isEqualTo(embedding);
    }
    @Test void providerModePreservesExplicitReasoningSettings() throws Exception {
        properties.getAi().setWeeklyThinkingMode("provider");
        var sent=mapper.readTree(policy.prepare("weekly-report-agent",endpoint,"{\"model\":\"qwen3.8-max\",\"enable_thinking\":true,\"thinking_budget\":200}"));
        assertThat(sent.path("thinking_budget").asInt()).isEqualTo(200);
    }
    @Test void invalidModesFailConfigurationValidation() {
        properties.getAi().setWeeklyThinkingMode("loow");
        assertThatThrownBy(policy::validate).isInstanceOf(IllegalArgumentException.class);
    }
}
