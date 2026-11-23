package com.example.mealcheck.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Locale;

/** Admission estimate only; settlement always uses provider usage when available. */
final class AiRequestTokenEstimate {
    private AiRequestTokenEstimate() {}

    static long inputTokens(ObjectMapper mapper, String body) {
        if (body == null) return 0;
        try {
            JsonNode root = mapper.readTree(body);
            // Verified Model Studio Qwen3.8 and Qwen3.7-Flash image limits: 32x32 pixels/token,
            // 2560 tokens by default, at most 16384 with high resolution/custom max_pixels.
            // https://www.alibabacloud.com/help/en/model-studio/vision/
            if (!AiChatRequestPolicy.controlledQwen(root.path("model").asText())) return body.length();
            long imageTokens = 0;
            for (var message : root.path("messages")) {
                for (var part : message.path("content")) {
                    if (!"image_url".equals(part.path("type").asText()) || !(part instanceof ObjectNode object)) continue;
                    JsonNode image = part.path("image_url");
                    if (!image.isTextual() && !image.path("url").isTextual()) continue;
                    long limit = root.path("vl_high_resolution_images").asBoolean(false) ? 16384 : 2560;
                    if (!root.path("vl_high_resolution_images").asBoolean(false) && part.has("max_pixels")) {
                        long pixels = part.path("max_pixels").asLong(-1);
                        limit = pixels > 0 ? Math.max(4, (Math.min(pixels, 16777216L) + 1023) / 1024) : 16384;
                    }
                    // Include the image boundary tokens. Never fetch URLs or decode Base64 here.
                    imageTokens += limit + 2;
                    if (image instanceof ObjectNode imageObject) imageObject.put("url", "[image]");
                    else object.put("image_url", "[image]");
                }
            }
            // Preserve the conservative character-based estimate for all remaining text and tools.
            return imageTokens == 0 ? body.length() : mapper.writeValueAsString(root).length() + imageTokens;
        } catch (java.io.IOException | RuntimeException invalid) {
            return body.length();
        }
    }
}
