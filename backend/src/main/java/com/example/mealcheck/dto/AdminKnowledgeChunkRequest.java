package com.example.mealcheck.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public class AdminKnowledgeChunkRequest {

    @NotBlank
    @Size(max = 255)
    private String title;

    @NotBlank
    private String content;

    @jakarta.validation.constraints.Positive
    private Long expectedRevision;

    @Size(max = 500)
    private String reason;

    public Long getExpectedRevision() { return expectedRevision; }
    public void setExpectedRevision(Long value) { expectedRevision = value; }
    public String getReason() { return reason; }
    public void setReason(String value) { reason = value; }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }
}
