package com.example.mealcheck.dto.assistant;

import java.time.LocalDateTime;
import java.util.List;

public class AssistantConversationDetailResponse {
    private Long id;
    private String title;
    private LocalDateTime updatedAt;
    private List<AssistantChatMessage> messages;
    private boolean hasMore;
    private String nextBefore;

    public AssistantConversationDetailResponse(Long id,
                                               String title,
                                               LocalDateTime updatedAt,
                                               List<AssistantChatMessage> messages) {
        this.id = id;
        this.title = title;
        this.updatedAt = updatedAt;
        this.messages = messages;
    }

    public Long getId() { return id; }
    public String getTitle() { return title; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public List<AssistantChatMessage> getMessages() { return messages; }
    public boolean isHasMore() { return hasMore; }
    public String getNextBefore() { return nextBefore; }
    public void setPage(boolean hasMore, String nextBefore) { this.hasMore = hasMore; this.nextBefore = nextBefore; }
}
