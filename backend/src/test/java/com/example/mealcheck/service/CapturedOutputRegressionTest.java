package com.example.mealcheck.service;

import com.example.mealcheck.dto.KnowledgeSnippet;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** Captured provider failures remain unchanged; expected handling is asserted separately. */
class CapturedOutputRegressionTest {
    final ObjectMapper json = new ObjectMapper();
    JsonNode fixture(String id) throws Exception {
        try (var stream = new ClassPathResource("evaluation/no_embedding_captured_regressions.json").getInputStream()) {
            for (var item : json.readTree(stream).path("cases"))
                if (id.equals(item.path("caseId").asText())) return item;
        }
        throw new IllegalArgumentException(id);
    }
    List<KnowledgeSnippet> sources(JsonNode fixture) {
        var sources = new ArrayList<KnowledgeSnippet>();
        for (var item : fixture.path("sources")) sources.add(new KnowledgeSnippet(item.path("sourceId").asLong(),
                item.path("title").asText(), item.path("content").asText(), 1));
        return sources;
    }
    @Test void capturedEllipsisCitationIsRemovedFromProductionDisplay() throws Exception {
        var fixture = fixture("rag-187");
        var prepared = AssistantGroundingGuard.prepare(fixture.path("capturedAnswer"), sources(fixture));
        assertThat(prepared.usable()).isTrue();
        assertThat(prepared.answer().path("claims")).hasSize(2);
        assertThat(prepared.issues()).anySatisfy(issue -> {
            assertThat(issue.code()).isEqualTo("QUOTE_NOT_FOUND");
            assertThat(issue.path()).startsWith("claims[2]");
        });
        var finalAnswer = json.readTree(AssistantGroundingGuard.finish(prepared, prepared.issues()));
        assertThat(finalAnswer.path("partial").asBoolean()).isTrue();
        assertThat(finalAnswer.path("summary").asText()).doesNotContain("默认长期作为唯一餐食");
        assertThat(finalAnswer.path("claims").toString()).doesNotContain("...");
    }
    @Test void passageIdsCanBindDiscontinuousEvidenceWithoutFabricatingAQuote() throws Exception {
        var fixture = fixture("rag-187");
        var sources = sources(fixture);
        ObjectNode input = fixture.path("capturedAnswer").deepCopy();
        ObjectNode last = (ObjectNode) input.path("claims").path(2);
        last.remove(List.of("quote", "sourceId"));
        var ids = last.putArray("evidence").addObject().put("sourceId", 1).putArray("passageIds");
        for (var passage : AssistantEvidencePassages.of(sources.get(0).getContent()))
            if (passage.text().contains("记录产品名称")) ids.add(passage.id());
        assertThat(ids).isNotEmpty();
        var prepared = AssistantGroundingGuard.prepare(input, sources,6,12);
        assertThat(prepared.issues()).isEmpty();
        assertThat(prepared.answer().path("claims")).hasSize(3);
        for (var quote : prepared.answer().path("claims").path(2).path("evidence").path(0).path("quotes"))
            assertThat(sources.get(0).getContent()).contains(quote.asText());
    }
    @Test void capturedUnknownOilAnswerRemainsUsefulWithoutClaimingTheQuantityIsKnown() throws Exception {
        var fixture = fixture("rag-172");
        var prepared = AssistantGroundingGuard.prepare(fixture.path("capturedAnswer"), sources(fixture));
        var verdict = (ObjectNode) GroundingReviewFixture.verdict(prepared.answer(), Set.of());
        verdict.put("informationStatus", "MISSING_INPUT");
        verdict.putArray("missingInformation").add("沙拉酱配方").add("实际用量");
        var checked = AssistantGroundingGuard.reviewed(prepared.answer(), verdict);
        assertThat(checked.usable()).isTrue();
        assertThat(checked.issues()).isEmpty();
        var answer = json.readTree(AssistantGroundingGuard.finish(checked, checked.issues(), false));
        assertThat(answer.path("informationStatus").asText()).isEqualTo("MISSING_INPUT");
        assertThat(answer.path("missingInformation")).hasSize(2);
        assertThat(answer.path("partial").asBoolean()).isTrue();
        assertThat(answer.path("refused").asBoolean()).isFalse();
        assertThat(answer.path("summary").asText()).contains("不能可靠提供", "留空");
        assertThat(answer.has("answerability")).isFalse(); // Do not trust the prototype's ambiguous label.
    }
    @Test void conflictingOrIncompleteInformationAssessmentsFailClosed() throws Exception {
        var fixture = fixture("rag-172");
        var prepared = AssistantGroundingGuard.prepare(fixture.path("capturedAnswer"), sources(fixture));
        for (String status : List.of("SUFFICIENT", "MISSING_INPUT", "MISSING_KNOWLEDGE", "UNKNOWN")) {
            var verdict = (ObjectNode) GroundingReviewFixture.verdict(prepared.answer(), Set.of());
            verdict.put("informationStatus", status);
            verdict.putArray("missingInformation").add("实际用量");
            // SUFFICIENT cannot have missing fields; MISSING_INPUT cannot request retrieval;
            // MISSING_KNOWLEDGE must request retrieval; UNKNOWN is not a status.
            verdict.put("needsMoreEvidence", "MISSING_INPUT".equals(status));
            assertThat(AssistantGroundingGuard.reviewed(prepared.answer(), verdict).usable()).isFalse();
        }
        var incomplete = (ObjectNode) GroundingReviewFixture.verdict(prepared.answer(), Set.of());
        incomplete.put("informationStatus", "MISSING_INPUT");
        incomplete.remove("missingInformation");
        assertThat(AssistantGroundingGuard.reviewed(prepared.answer(), incomplete).usable()).isFalse();
    }
    @Test void sufficientAndMissingKnowledgeReviewsRemainDistinctAndHistoricalRecordsStillReplay() throws Exception {
        var fixture = fixture("rag-172");
        var prepared = AssistantGroundingGuard.prepare(fixture.path("capturedAnswer"), sources(fixture));
        var historical = (ObjectNode) GroundingReviewFixture.verdict(prepared.answer(), Set.of());
        historical.remove(List.of("informationStatus","missingInformation"));
        assertThat(AssistantGroundingGuard.reviewed(prepared.answer(), historical).usable()).isTrue();
        for (String status : List.of("SUFFICIENT", "MISSING_KNOWLEDGE")) {
            var verdict = (ObjectNode) historical.deepCopy();
            verdict.put("informationStatus", status).put("needsMoreEvidence", "MISSING_KNOWLEDGE".equals(status));
            var fields = verdict.putArray("missingInformation");
            if ("MISSING_KNOWLEDGE".equals(status)) fields.add("支持核心解释的来源");
            var checked = AssistantGroundingGuard.reviewed(prepared.answer(), verdict);
            assertThat(checked.usable()).isTrue();
            assertThat(checked.issues()).isEmpty();
            assertThat(checked.answer().path("informationStatus").asText()).isEqualTo(status);
            var finished = json.readTree(AssistantGroundingGuard.finish(checked, List.of(), false));
            assertThat(finished.path("partial").asBoolean()).isEqualTo("MISSING_KNOWLEDGE".equals(status));
        }
    }
    @Test void newReviewCannotUseHistoricalCompatibilityToOmitInformationAssessment() throws Exception {
        var fixture=fixture("rag-187");
        var prepared=AssistantGroundingGuard.prepare(fixture.path("capturedAnswer"),sources(fixture));
        var verdict=(ObjectNode)GroundingReviewFixture.verdict(prepared.answer(),Set.of());
        assertThat(AssistantGroundingGuard.reviewedCurrent(prepared.answer(),verdict).usable()).isTrue();
        verdict.remove(List.of("informationStatus","missingInformation","inputMissing","knowledgeMissing"));
        assertThat(AssistantGroundingGuard.reviewed(prepared.answer(),verdict).usable()).isTrue();
        var current=AssistantGroundingGuard.reviewedCurrent(prepared.answer(),verdict);
        assertThat(current.usable()).isFalse();
        assertThat(current.issues()).anySatisfy(issue->assertThat(issue.code()).isEqualTo("INCOMPLETE_REVIEW"));
    }
    @Test void capturedEmptyTrayConflictIsRetryableRatherThanNonFoodOrBlur() throws Exception {
        var raw = fixture("synth-081").path("capturedAnswer");
        var ai = mock(AiChatClient.class);
        when(ai.isConfigured()).thenReturn(true);
        when(ai.complete(eq("vision-recognition"), anyList(), anyDouble())).thenReturn(raw.toString());
        var service = new VisionFoodRecognitionService(ai, json);
        assertThatThrownBy(() -> service.recognize(image())).isInstanceOfSatisfying(RecognitionFailureException.class, error -> {
            assertThat(error.code()).isEqualTo("RECOGNITION_INCONSISTENT");
            var failure = TaskFailureClassifier.classify(error, "ANALYZE");
            assertThat(failure.recoverable()).isTrue();
            assertThat(failure.code()).isNotIn("NON_FOOD", "IMAGE_UNCLEAR");
        });
        verify(ai).complete(eq("vision-recognition"), anyList(), anyDouble());
    }
    static ValidatedImage image() throws Exception {
        var pixels = new java.awt.image.BufferedImage(32, 24, java.awt.image.BufferedImage.TYPE_INT_RGB);
        var graphics = pixels.createGraphics();
        graphics.setColor(java.awt.Color.GRAY); graphics.fillRect(0, 0, 32, 24); graphics.dispose();
        var stream = new java.io.ByteArrayOutputStream(); javax.imageio.ImageIO.write(pixels, "png", stream);
        return new ValidatedImage(stream.toByteArray(), "image/png", ".png", 32, 24, "fixture.png");
    }
}
