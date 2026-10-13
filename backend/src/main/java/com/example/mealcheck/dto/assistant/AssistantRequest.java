package com.example.mealcheck.dto.assistant;

import java.util.List;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

public class AssistantRequest {
    @Size(max=4000)
    private String question;
    @Size(max=4000)
    private String message;
    @Min(1)
    private Long conversationId;
    @Size(max=20)
    private List<@NotNull @Valid AssistantChatMessage> history;

    public String getQuestion() {
        return question;
    }

    public void setQuestion(String question) {
        this.question = question;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public Long getConversationId() {
        return conversationId;
    }

    public void setConversationId(Long conversationId) {
        this.conversationId = conversationId;
    }

    public List<AssistantChatMessage> getHistory() {
        return history;
    }

    public void setHistory(List<AssistantChatMessage> history) {
        this.history = history;
    }

    public String questionText() {
        String text = question == null || question.isBlank() ? message : question;
        return text == null ? "" : text.trim();
    }
}
