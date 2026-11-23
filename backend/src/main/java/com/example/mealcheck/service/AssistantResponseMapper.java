package com.example.mealcheck.service;

import com.example.mealcheck.dto.assistant.AssistantResponse;
import com.example.mealcheck.dto.assistant.AssistantReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Converts model output into API responses, preserving citation and outcome rules. */
final class AssistantResponseMapper {
    private final ObjectMapper objectMapper;

    AssistantResponseMapper(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    List<com.example.mealcheck.dto.assistant.AssistantToolCall> toolCalls(List<AssistantFunctionCallingService.ToolCallTrace> traces) {
        return traces.stream().map(trace -> new com.example.mealcheck.dto.assistant.AssistantToolCall(
                trace.name(), trace.arguments(), trace.status(), trace.round(), trace.durationMs(), trace.origin(), trace.resultSummary())).toList();
    }

    AssistantResponse respectPreferences(AssistantResponse response, com.example.mealcheck.dto.DietPreferences preferences) {
        if (!preferences.active()) return response;
        String summary=DietPreferenceService.guard(response.getSummary(),preferences);
        String answer=DietPreferenceService.guard(response.getAnswer(),preferences);
        var suggestions=response.getSuggestions().stream().map(s->DietPreferenceService.guard(s,preferences)).distinct().toList();
        String trend=DietPreferenceService.guard(response.getWeeklyTrend(),preferences);
        boolean changed=!java.util.Objects.equals(summary,response.getSummary()) || !suggestions.equals(response.getSuggestions())
                || !java.util.Objects.equals(trend,response.getWeeklyTrend()) || !java.util.Objects.equals(answer,response.getAnswer());
        return new AssistantResponse(answer,summary,suggestions,response.getRiskLevel(),trend,
                changed?List.of():response.getReferences(),response.getConversationId()).copyOutcomeFrom(response);
    }

    AssistantResponse cleanTrend(AssistantResponse response,String question) {
        String trend=AssistantAnswerFormatting.wantsTrend(question)?AssistantAnswerFormatting.trend(response.getWeeklyTrend()):"";
        return new AssistantResponse(composeAnswer(response.getSummary(),response.getSuggestions(),trend),response.getSummary(),
                response.getSuggestions(),response.getRiskLevel(),trend,response.getReferences(),response.getConversationId()).copyOutcomeFrom(response);
    }

    AssistantResponse parseModelResponse(String rawAnswer,
                                         AssistantResponse fallback,
                                         List<AssistantReference> references,
                                         AssistantStreamDelivery delivery) {
        if (rawAnswer == null || rawAnswer.isBlank()) {
            return fallback;
        }

        try {
            String json = extractJsonObject(rawAnswer);
            JsonNode node = objectMapper.readTree(json);
            String summary = textOrDefault(node.get("summary"), fallback.getSummary());
            List<String> suggestions = readStringList(node.get("suggestions"));
            String riskLevel = normalizeRiskLevel(textOrDefault(node.get("riskLevel"), fallback.getRiskLevel()));
            String weeklyTrend = textOrDefault(node.get("weeklyTrend"), fallback.getWeeklyTrend());
            String answer = composeAnswer(summary, suggestions, weeklyTrend);
            List<AssistantReference> usedReferences = node.path("summary").isTextual()
                    && !node.path("summary").asText().isBlank()
                    ? AssistantCitationBinding.select(node, references) : List.of();
            var response=new AssistantResponse(answer, summary, suggestions, riskLevel, weeklyTrend, usedReferences);
            response.setReviewStatus(node.path("reviewStatus").asText("NOT_APPLICABLE"));
            String failure=node.path("failureCode").asText("");
            if("DEGRADED".equals(node.path("answerStatus").asText()) && AiFailure.known(failure))
                response.degraded(failure,node.path("upstreamStatus").isInt()?node.path("upstreamStatus").asInt():null);
            else if("PARTIAL".equals(node.path("answerStatus").asText()))
                response.partial(readStringList(node.get("answerReasonCodes")).stream().filter(code->code.matches("[A-Z][A-Z0-9_]{0,63}")).distinct().limit(12).toList());
            if(delivery!=null)response.setReviewStatus(!delivery.knowledgeAnswer?"NOT_APPLICABLE":
                    delivery.reviewMode==com.example.mealcheck.dto.assistant.AssistantReviewMode.FAST?"NOT_REVIEWED":
                    delivery.semanticReviewCompleted && !"DEGRADED".equals(response.getAnswerStatus())?"VERIFIED":"UNAVAILABLE");
            return response;
        } catch (Exception e) {
            String summary = rawAnswer.trim();
            return new AssistantResponse(summary, summary, List.of(), fallback.getRiskLevel(), fallback.getWeeklyTrend(), List.of());
        }
    }

    AssistantResponse parseStreamResponse(String rawAnswer, List<AssistantReference> candidates) {
        String raw = rawAnswer == null ? "" : rawAnswer;
        int marker = AssistantCitationBinding.streamMetadataStart(raw);
        if (marker < 0) return new AssistantResponse(raw.trim(), List.of());
        String answer = raw.substring(0, marker).stripTrailing();
        int metadataStart = marker + AssistantCitationBinding.STREAM_START.length();
        int metadataEnd = raw.indexOf(AssistantCitationBinding.STREAM_END, metadataStart);
        if (metadataEnd < 0 || answer.isBlank()) return new AssistantResponse(answer, List.of());
        try {
            JsonNode metadata = objectMapper.readTree(raw.substring(metadataStart, metadataEnd));
            return new AssistantResponse(answer, AssistantCitationBinding.select(metadata, candidates));
        } catch (Exception invalidMetadata) {
            return new AssistantResponse(answer, List.of());
        }
    }

    private String extractJsonObject(String text) {
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return text.substring(start, end + 1);
        }
        return text;
    }

    private List<String> readStringList(JsonNode node) {
        if (node == null || !node.isArray()) {
            return List.of();
        }

        List<String> values = new ArrayList<>();
        node.forEach(item -> {
            String value = item.asText("");
            if (!value.isBlank()) {
                values.add(value.trim());
            }
        });
        return values;
    }

    AssistantResponse withConversationId(AssistantResponse response, Long conversationId) {
        var copy = new AssistantResponse(
                response.getAnswer(),
                response.getSummary(),
                response.getSuggestions(),
                response.getRiskLevel(),
                response.getWeeklyTrend(),
                response.getReferences(),
                conversationId
        );
        copy.setToolCalls(response.getToolCalls());
        copy.copyOutcomeFrom(response);
        return copy;
    }

    private String normalizeRiskLevel(String riskLevel) {
        String value = riskLevel == null ? "" : riskLevel.trim().toUpperCase(Locale.ROOT);
        if ("HIGH".equals(value) || "MEDIUM".equals(value) || "LOW".equals(value)) {
            return value;
        }
        return "LOW";
    }

    String composeAnswer(String summary, List<String> suggestions, String weeklyTrend) {
        StringBuilder answer = new StringBuilder(summary == null ? "" : summary.trim());
        if (!AssistantAnswerFormatting.trend(weeklyTrend).isBlank()) {
            answer.append("\n\n本周趋势：").append(AssistantAnswerFormatting.trend(weeklyTrend));
        }
        if (suggestions != null && !suggestions.isEmpty()) {
            answer.append("\n\n建议：");
            for (String suggestion : suggestions) {
                answer.append("\n- ").append(suggestion);
            }
        }
        return answer.toString().trim();
    }

    private String textOrDefault(JsonNode node, String fallback) {
        if (node == null || node.asText("").isBlank()) {
            return fallback == null ? "" : fallback;
        }
        return node.asText().trim();
    }
}
