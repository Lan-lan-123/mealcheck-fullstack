package com.example.mealcheck.dto.assistant;

import java.util.List;

public class AssistantRequest {
    private String question;
    private String message;
    private List<AssistantChatMessage> history;

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
