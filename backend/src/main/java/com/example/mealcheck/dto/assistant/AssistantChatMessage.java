package com.example.mealcheck.dto.assistant;

public class AssistantChatMessage {
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
