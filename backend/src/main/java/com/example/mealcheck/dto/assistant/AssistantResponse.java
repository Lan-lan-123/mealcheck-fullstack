package com.example.mealcheck.dto.assistant;

import java.util.List;

public class AssistantResponse {
    private Long messageId;
    private String requestId;
    private String deliveryStatus="COMPLETE";
    public Long getMessageId() { return messageId; }
    public void setMessageId(Long v) { messageId=v; }
    public String getRequestId() { return requestId; }
    public void setRequestId(String v) { requestId=v; }
    public String getDeliveryStatus() { return deliveryStatus; }
    public void setDeliveryStatus(String v) { deliveryStatus=v; }
    private final String answer;
    private final String summary;
    private final List<String> suggestions;
    private final String riskLevel;
    private final String weeklyTrend;
    private final List<AssistantReference> references;
    private final Long conversationId;

    public AssistantResponse(String answer, List<AssistantReference> references) {
        this(answer, answer, List.of(), "LOW", "", references, null);
    }

    public AssistantResponse(String answer,
                             String summary,
                             List<String> suggestions,
                             String riskLevel,
                             String weeklyTrend,
                             List<AssistantReference> references) {
        this(answer, summary, suggestions, riskLevel, weeklyTrend, references, null);
    }

    public AssistantResponse(String answer,
                             String summary,
                             List<String> suggestions,
                             String riskLevel,
                             String weeklyTrend,
                             List<AssistantReference> references,
                             Long conversationId) {
        this.answer = answer;
        this.summary = summary;
        this.suggestions = suggestions == null ? List.of() : List.copyOf(suggestions);
        this.riskLevel = riskLevel;
        this.weeklyTrend = weeklyTrend;
        this.references = references == null ? List.of() : List.copyOf(references);
        this.conversationId = conversationId;
    }

    public String getAnswer() {
        return answer;
    }

    public String getSummary() {
        return summary;
    }

    public List<String> getSuggestions() {
        return suggestions;
    }

    public String getRiskLevel() {
        return riskLevel;
    }

    public String getWeeklyTrend() {
        return weeklyTrend;
    }

    public List<AssistantReference> getReferences() {
        return references;
    }

    public Long getConversationId() {
        return conversationId;
    }
}
