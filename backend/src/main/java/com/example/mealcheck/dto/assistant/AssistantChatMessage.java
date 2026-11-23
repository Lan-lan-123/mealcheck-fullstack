package com.example.mealcheck.dto.assistant;

public class AssistantChatMessage {
    @com.fasterxml.jackson.annotation.JsonProperty(access = com.fasterxml.jackson.annotation.JsonProperty.Access.READ_ONLY)
    private String reviewStatus="UNKNOWN";
    public String getReviewStatus() {return reviewStatus;}
    @com.fasterxml.jackson.annotation.JsonProperty(access = com.fasterxml.jackson.annotation.JsonProperty.Access.READ_ONLY)
    private String answerStatus="COMPLETE";
    @com.fasterxml.jackson.annotation.JsonProperty(access = com.fasterxml.jackson.annotation.JsonProperty.Access.READ_ONLY)
    private java.util.List<String> answerReasonCodes=java.util.List.of();
    @com.fasterxml.jackson.annotation.JsonProperty(access = com.fasterxml.jackson.annotation.JsonProperty.Access.READ_ONLY)
    private String failureCode;
    @com.fasterxml.jackson.annotation.JsonProperty(access = com.fasterxml.jackson.annotation.JsonProperty.Access.READ_ONLY)
    private Integer upstreamStatus;
    public String getAnswerStatus() {return answerStatus;}
    public java.util.List<String> getAnswerReasonCodes() {return answerReasonCodes;}
    public String getFailureCode() {return failureCode;}
    public Integer getUpstreamStatus() {return upstreamStatus;}
    public void setOutcome(AssistantResponse response) {
        answerStatus=response.getAnswerStatus();answerReasonCodes=response.getAnswerReasonCodes();
        failureCode=response.getFailureCode();upstreamStatus=response.getUpstreamStatus();
        reviewStatus=response.getReviewStatus();
    }
    @com.fasterxml.jackson.annotation.JsonProperty(access = com.fasterxml.jackson.annotation.JsonProperty.Access.READ_ONLY)
    private java.util.List<AssistantReference> references = java.util.List.of();
    public java.util.List<AssistantReference> getReferences() { return references; }
    public void setReferences(java.util.List<AssistantReference> value) { references = value == null ? java.util.List.of() : java.util.List.copyOf(value); }
    @com.fasterxml.jackson.annotation.JsonProperty(access = com.fasterxml.jackson.annotation.JsonProperty.Access.READ_ONLY)
    private java.util.List<AssistantToolCall> toolCalls = java.util.List.of();
    public java.util.List<AssistantToolCall> getToolCalls() { return toolCalls; }
    public void setToolCalls(java.util.List<AssistantToolCall> value) { toolCalls = value == null ? java.util.List.of() : java.util.List.copyOf(value); }
    private Long messageId;
    private String requestId;
    private String deliveryStatus="COMPLETE";
    public Long getMessageId() { return messageId; }
    public void setMessageId(Long v) { messageId=v; }
    public String getRequestId() { return requestId; }
    public void setRequestId(String v) { requestId=v; }
    public String getDeliveryStatus() { return deliveryStatus; }
    public void setDeliveryStatus(String v) { deliveryStatus=v; }
    @jakarta.validation.constraints.NotNull
    @jakarta.validation.constraints.Pattern(regexp="user|assistant")
    private String role;
    @jakarta.validation.constraints.NotBlank
    @jakarta.validation.constraints.Size(max=8000)
    private String text;

    public AssistantChatMessage() {
    }

    public AssistantChatMessage(String role, String text) {
        this.role = role;
        this.text = text;
    }

    public String getRole() {
        return role;
    }

    public void setRole(String role) {
        this.role = role;
    }

    public String getText() {
        return text;
    }

    public void setText(String text) {
        this.text = text;
    }
}
