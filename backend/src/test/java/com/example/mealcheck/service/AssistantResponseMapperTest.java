package com.example.mealcheck.service;

import com.example.mealcheck.dto.assistant.AssistantReference;
import com.example.mealcheck.dto.assistant.AssistantResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class AssistantResponseMapperTest {
    private final AssistantResponseMapper mapper = new AssistantResponseMapper(new ObjectMapper());
    private final AssistantResponse fallback = new AssistantResponse("fallback", List.of());
    private final AssistantReference source = new AssistantReference(7L, "来源", "引用内容", "diet", 0.9);

    @Test
    void partialOutcomeSurvivesResponseAssemblyAndConversationAssignment() {
        var parsed = mapper.parseModelResponse("""
                {"summary":"有依据的部分","answerStatus":"PARTIAL",
                 "answerReasonCodes":["MISSING_INPUT"],"weeklyTrend":"旧趋势",
                 "refused":false,"referenceIds":[7,999]}
                """, fallback, List.of(source), null);
        var response = mapper.withConversationId(mapper.cleanTrend(parsed, "怎么搭配？"), 42L);
        assertThat(response.getConversationId()).isEqualTo(42L);
        assertThat(response.getAnswerStatus()).isEqualTo("PARTIAL");
        assertThat(response.getAnswerReasonCodes()).containsExactly("MISSING_INPUT");
        assertThat(response.getReferences()).containsExactly(source);
        assertThat(response.getAnswer()).doesNotContain("旧趋势");
    }

    @Test
    void degradedOutcomeIsPreservedEvenWhenDeliveryClaimsReviewCompleted() {
        var delivery = new AssistantStreamDelivery(null);
        delivery.knowledgeAnswer = true;
        delivery.semanticReviewCompleted = true;
        var parsed = mapper.parseModelResponse("""
                {"summary":"暂时不可用","answerStatus":"DEGRADED",
                 "failureCode":"MODEL_TIMEOUT","upstreamStatus":504}
                """, fallback, List.of(source), delivery);
        var response = mapper.withConversationId(parsed, 42L);
        assertThat(response.getAnswerStatus()).isEqualTo("DEGRADED");
        assertThat(response.getFailureCode()).isEqualTo("MODEL_TIMEOUT");
        assertThat(response.getUpstreamStatus()).isEqualTo(504);
        assertThat(response.getReviewStatus()).isEqualTo("UNAVAILABLE");
        assertThat(response.getReferences()).isEmpty();
    }

    @Test
    void malformedModelOutputNeverAcquiresRetrievedReferences() {
        var response = mapper.parseModelResponse("invalid json", fallback, List.of(source), null);
        assertThat(response.getAnswer()).isEqualTo("invalid json");
        assertThat(response.getReferences()).isEmpty();
        assertThat(mapper.parseModelResponse(" ", fallback, List.of(source), null)).isSameAs(fallback);
    }

    @Test
    void streamingFooterIsHiddenAndOnlyAvailableCitationIdsAreSelected() {
        var response = mapper.parseStreamResponse("正文\n<mealcheck-citations>{\"refused\":false,\"referenceIds\":[999,7,7]}</mealcheck-citations>", List.of(source));
        assertThat(response.getAnswer()).isEqualTo("正文");
        assertThat(response.getReferences()).containsExactly(source);
        var interrupted = mapper.parseStreamResponse("正文\n<mealcheck-cit", List.of(source));
        assertThat(interrupted.getAnswer()).isEqualTo("正文");
        assertThat(interrupted.getReferences()).isEmpty();
    }
}
