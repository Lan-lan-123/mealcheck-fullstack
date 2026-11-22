package com.example.mealcheck.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;

class AiRequestTokenEstimateTest {
    @Test void qwen37FlashSharesVerifiedImageBoundsWithoutCountingBase64AsText() throws Exception {
        String small=body("qwen3.7-flash","data:image/png;base64,AAAA",false,2621440);
        String large=body("qwen3.7-flash","data:image/png;base64,"+"A".repeat(100000),false,2621440);
        assertThat(AiRequestTokenEstimate.inputTokens(mapper,small)).isEqualTo(AiRequestTokenEstimate.inputTokens(mapper,large)).isBetween(2562L,3200L);
        assertThat(AiRequestTokenEstimate.inputTokens(mapper,body("qwen3.7-flash-2026-07-15","x",true,2621440))).isBetween(16386L,17000L);
    }
    private final ObjectMapper mapper = new ObjectMapper();
    private String body(String model, String image, boolean high, long pixels) throws Exception {
        return mapper.writeValueAsString(Map.of("model",model,"vl_high_resolution_images",high,"messages",List.of(
                Map.of("role","user","content",List.of(Map.of("type","text","text","请识别餐食"),
                        Map.of("type","image_url","max_pixels",pixels,"image_url",Map.of("url",image)))))));
    }
    @Test void encodingSizeDoesNotChangeTheImageTokenReservation() throws Exception {
        String small=body("qwen3.8-max","data:image/png;base64,AAAA",false,2621440);
        String large=body("qwen3.8-max","data:image/png;base64,"+"A".repeat(4_000_000),false,2621440);
        assertThat(AiRequestTokenEstimate.inputTokens(mapper,large)).isEqualTo(AiRequestTokenEstimate.inputTokens(mapper,small)).isBetween(2562L,3200L);
        assertThat(mapper.readTree(large).path("messages").get(0).path("content").get(1).path("image_url").path("url").asText()).hasSize(4_000_022);
    }
    @Test void resolutionLimitsAndMultipleImagesRemainConservative() throws Exception {
        String normal=body("qwen3.8-flash","https://unreachable.invalid/image.jpg",false,2621440);
        String high=body("qwen3.8-flash","https://unreachable.invalid/image.jpg",true,2621440);
        assertThat(AiRequestTokenEstimate.inputTokens(mapper,high)-AiRequestTokenEstimate.inputTokens(mapper,normal)).isGreaterThan(13_000);
        var node=mapper.readTree(normal);var content=(com.fasterxml.jackson.databind.node.ArrayNode)node.path("messages").get(0).path("content");content.add(content.get(1).deepCopy());
        assertThat(AiRequestTokenEstimate.inputTokens(mapper,mapper.writeValueAsString(node))-AiRequestTokenEstimate.inputTokens(mapper,normal)).isGreaterThanOrEqualTo(2562);
        assertThat(AiRequestTokenEstimate.inputTokens(mapper,body("qwen3.8-max","x",false,Long.MAX_VALUE))).isBetween(16386L,17000L);
    }
    @Test void textOnlyUnknownModelsAndMalformedBodiesKeepTheExistingEstimate() throws Exception {
        for(String value:List.of("invalid json", "{\"model\":\"qwen3.8-max\",\"messages\":[{\"content\":\"hello\"}]}",body("other-model","data:image/png;base64,"+"A".repeat(1000),false,2621440)))
            assertThat(AiRequestTokenEstimate.inputTokens(mapper,value)).isEqualTo(value.length());
    }
}
