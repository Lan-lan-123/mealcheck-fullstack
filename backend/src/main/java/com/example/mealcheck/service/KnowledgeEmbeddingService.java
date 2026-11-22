package com.example.mealcheck.service;

import com.example.mealcheck.config.AppProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

@Service
public class KnowledgeEmbeddingService {
    public record EmbeddingResult(float[] vector, Long inputTokens, Long totalTokens) { }
    public static final String QWEN_FLASH = "qwen3.7-text-embedding-flash";
    private final AppProperties properties;
    private final ObjectMapper objectMapper;
    private final HashEmbeddingService fallback;
    private final ApplicationObservability observability;
    private final AiHttpTransport transport;
    private KnowledgeEmbeddingSettingsStore settings;
    private KnowledgeEmbeddingProfileStore profiles;

    @org.springframework.beans.factory.annotation.Autowired
    public void setProfiles(KnowledgeEmbeddingProfileStore profiles) { this.profiles = profiles; }

    @org.springframework.beans.factory.annotation.Autowired
    public void setSettings(KnowledgeEmbeddingSettingsStore settings) { this.settings = settings; }

    public KnowledgeEmbeddingService(AppProperties properties,
                                                  ObjectMapper objectMapper,
                                     HashEmbeddingService fallback,
                                     ApplicationObservability observability,
                                     AiHttpTransport transport) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.fallback = fallback;
        this.observability = observability;
        this.transport = transport;
    }

    public float[] embed(String text) {
        return embedForMode(text, activeMode());
    }

    public float[] embedForSignature(String text, String signature) {
        return embedForSignatureWithUsage(text, signature).vector();
    }

    public EmbeddingResult embedForSignatureWithUsage(String text, String signature) {
        String mode = signature.startsWith("hash-v1:") ? "LOCAL" : "SEMANTIC";
        var profile = "SEMANTIC".equals(mode) ? semanticProfile() : null;
        String expected = "LOCAL".equals(mode) ? signatureForMode(mode) : signatureForProfile(profile);
        if (!signature.startsWith(expected + ":")) {
            throw new IllegalStateException("向量配置已变化，请重新发起重建。");
        }
        return embed(text, mode, profile);
    }

    public float[] embedForMode(String text, String mode) {
        return embedForModeWithUsage(text, mode).vector();
    }

    public EmbeddingResult embedForModeWithUsage(String text, String mode) {
        return embed(text, mode, "SEMANTIC".equals(mode) ? semanticProfile() : null);
    }

    public float[] embedWithProfile(String text, KnowledgeEmbeddingProfileStore.Profile profile) {
        return embedWithProfileWithUsage(text, profile).vector();
    }

    public EmbeddingResult embedWithProfileWithUsage(String text, KnowledgeEmbeddingProfileStore.Profile profile) {
        return embed(text, "SEMANTIC", profile);
    }

    private EmbeddingResult embed(String text, String mode, KnowledgeEmbeddingProfileStore.Profile profile) {
        long startedAt = observability.start();
        boolean semantic = "SEMANTIC".equals(mode);
        if (!semantic && !"LOCAL".equals(mode)) throw new IllegalArgumentException("未知向量方式");
        String provider = semantic ? "semantic" : "hash";
        try {
            if (semantic && !profile.configured()) {
                throw new KnowledgeJobFailure.EmbeddingException(new KnowledgeJobFailure("CONFIG_UNAVAILABLE","请先配置向量模型的接口地址和 API Key。",false));
            }
            if (text == null || text.isBlank()) {
                float[] empty = new float[Math.max(1, semantic ? profile.dimension() : properties.getKnowledge().getEmbeddingDim())];
                observability.recordEmbedding(startedAt, provider, "success");
                return new EmbeddingResult(empty, null, null);
            }
            if (!semantic) {
                float[] embedding = fallback.embed(text);
                observability.recordEmbedding(startedAt, provider, "success");
                return new EmbeddingResult(embedding, null, null);
            }
            AppProperties.Knowledge knowledge = properties.getKnowledge();
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("model", profile.model());
            body.put("input", text == null ? "" : text);
            if (profile.sendDimensions()) body.put("dimensions", profile.dimension());

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(AiEndpointResolver.embeddings(profile.baseUrl()))
                    .timeout(Duration.ofSeconds(Math.max(1, knowledge.getEmbeddingTimeoutSeconds())))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + profile.apiKey())
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
                    .build();
            HttpResponse<String> response = transport.send("embedding",request,objectMapper.writeValueAsString(body));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new KnowledgeJobFailure.EmbeddingException(KnowledgeJobFailure.http(response.statusCode()));
            }

            JsonNode payload = objectMapper.readTree(response.body());
            JsonNode embedding = payload.path("data").path(0).path("embedding");
            if (!embedding.isArray() || embedding.size() != profile.dimension()) {
                throw new KnowledgeJobFailure.EmbeddingException(new KnowledgeJobFailure("EMBEDDING_DIMENSION","向量接口返回维度与配置不一致，请检查模型与维度后重试。",false));
            }
            float[] vector = new float[embedding.size()];
            boolean nonzero = false;
            double squaredNorm = 0;
            for (int i = 0; i < embedding.size(); i++) {
                if (!embedding.get(i).isNumber() || !Double.isFinite(embedding.get(i).asDouble())
                        || Math.abs(embedding.get(i).asDouble()) > Float.MAX_VALUE) {
                    throw new KnowledgeJobFailure.EmbeddingException(new KnowledgeJobFailure("EMBEDDING_RESPONSE","向量接口返回了无效数值，请检查模型服务后重试。",false));
                }
                vector[i] = (float) embedding.get(i).asDouble();
                nonzero |= vector[i] != 0.0f;
                squaredNorm += (double) vector[i] * vector[i];
            }
            if (!nonzero) {
                throw new KnowledgeJobFailure.EmbeddingException(new KnowledgeJobFailure("EMBEDDING_RESPONSE",
                        "向量接口返回了全零向量，无法用于语义检索，请检查模型服务后重试。", false));
            }
            // Every consumer uses cosine distance. Unit length preserves that
            // distance and avoids all-zero underflow or overflow in halfvec.
            double norm = Math.sqrt(squaredNorm);
            for (int i = 0; i < vector.length; i++) vector[i] = (float) (vector[i] / norm);
            observability.recordEmbedding(startedAt, provider, "success");
            JsonNode usage = payload.path("usage");
            Long inputTokens = reportedTokens(usage.path("prompt_tokens"));
            if (inputTokens == null) inputTokens = reportedTokens(usage.path("input_tokens"));
            return new EmbeddingResult(vector, inputTokens, reportedTokens(usage.path("total_tokens")));
        } catch (Exception e) {
            observability.recordEmbedding(startedAt, provider, "failure");
            if (e instanceof AiBudgetService.BudgetExceededException budget) throw budget;
            if (e instanceof AiBudgetService.BudgetUnavailableException unavailable) throw unavailable;
            // Provider/parser exceptions can contain response fragments or credentials.
            // Preserve the failure category, but only expose allowlisted summaries.
            throw new KnowledgeJobFailure.EmbeddingException(KnowledgeJobFailure.classify(e));
        }
    }

    private Long reportedTokens(JsonNode value) {
        return value.isIntegralNumber() && value.canConvertToLong() && value.longValue() >= 0 ? value.longValue() : null;
    }

    public String toPgVector(float[] vector) {
        StringBuilder value = new StringBuilder("[");
        for (int i = 0; i < vector.length; i++) {
            if (i > 0) value.append(',');
            if (!Float.isFinite(vector[i])) throw new IllegalArgumentException("向量包含无效数值。");
            value.append(Float.toString(vector[i]));
        }
        return value.append(']').toString();
    }

    public boolean isSemanticConfigured() {
        return "SEMANTIC".equals(activeMode());
    }

    public String activeMode() {
        String selected = settings == null ? null : settings.mode();
        if (selected != null) return selected;
        // Retain legacy behavior until an administrator explicitly chooses a mode.
        AppProperties.Knowledge knowledge = properties.getKnowledge();
        return notBlank(knowledge.getEmbeddingApiKey())
                && notBlank(knowledge.getEmbeddingBaseUrl())
                && notBlank(knowledge.getEmbeddingModel()) ? "SEMANTIC" : "LOCAL";
    }

    public boolean isSemanticAvailable() {
        return semanticProfile().configured();
    }

    public String semanticModel() {
        return semanticProfile().model();
    }

    private String environmentModel() {
        return notBlank(properties.getKnowledge().getEmbeddingModel())
                ? properties.getKnowledge().getEmbeddingModel().trim() : QWEN_FLASH;
    }

    private boolean canReuseAliyunChatCredentials(AppProperties.Ai.Connection connection) {
        try {
            String host = java.net.URI.create(connection.baseUrl()).getHost();
            return host != null && (host.equals("dashscope.aliyuncs.com")
                    || host.equals("dashscope-intl.aliyuncs.com") || host.equals("dashscope-us.aliyuncs.com")
                    || host.endsWith(".maas.aliyuncs.com"));
        } catch (RuntimeException ignored) { return false; }
    }

    public KnowledgeEmbeddingProfileStore.Profile semanticProfile() {
        var active = profiles == null ? null : profiles.active();
        return active == null ? environmentProfile() : active.profile();
    }

    public int activeDimension() { return dimensionForMode(activeMode()); }

    public int dimensionForMode(String mode) {
        return "SEMANTIC".equals(mode) ? semanticProfile().dimension() : properties.getKnowledge().getEmbeddingDim();
    }

    public KnowledgeEmbeddingProfileStore.Profile environmentProfile() {
        var chat = properties.getAi().connection();
        String configuredUrl = properties.getKnowledge().getEmbeddingBaseUrl();
        String url = notBlank(configuredUrl) ? configuredUrl.trim()
                : canReuseAliyunChatCredentials(chat) ? chat.baseUrl().trim() : "";
        String key = properties.getKnowledge().getEmbeddingApiKey();
        // Never forward a chat credential to a separately configured endpoint.
        if (!notBlank(key)) key = !notBlank(configuredUrl) && canReuseAliyunChatCredentials(chat) ? chat.apiKey() : "";
        return new KnowledgeEmbeddingProfileStore.Profile(environmentModel(), url, key,
                properties.getKnowledge().getEmbeddingDim(), properties.getKnowledge().isEmbeddingSendDimensions());
    }

    public String providerName() {
        return isSemanticConfigured()
                ? "SemanticEmbedding / " + semanticModel()
                : "HashEmbeddingService / Development fallback";
    }

    public String signature() {
        return signatureForMode(activeMode());
    }

    public String signatureForMode(String mode) {
        AppProperties.Knowledge knowledge = properties.getKnowledge();
        if ("LOCAL".equals(mode)) {
            return "hash-v1:" + knowledge.getEmbeddingDim();
        }
        if (!"SEMANTIC".equals(mode)) throw new IllegalArgumentException("未知向量方式");
        return signatureForProfile(semanticProfile());
    }

    public String signatureForProfile(KnowledgeEmbeddingProfileStore.Profile profile) {
        String provider = profile.baseUrl() + "|" + profile.model();
        return "semantic-v1:" + profile.model() + ":"
                + profile.dimension() + ":" + sha256(provider).substring(0, 16);
    }

    private boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }

    private String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (Exception e) {
            throw new IllegalStateException("Unable to build embedding signature", e);
        }
    }
}
