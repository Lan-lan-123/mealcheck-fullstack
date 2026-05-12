package com.example.mealcheck.dto;

import java.time.LocalDateTime;

public class AdminKnowledgeChunkResponse {

    private Long id;
    private String source;
    private String title;
    private String content;
    private String contentPreview;
    private int vectorDimension;
    private int contentLength;
    private long hitCount;
    private LocalDateTime lastHitAt;

    public AdminKnowledgeChunkResponse() {
    }

    public AdminKnowledgeChunkResponse(Long id,
                                       String source,
                                       String title,
                                       String content,
                                       String contentPreview,
                                       int vectorDimension,
                                       int contentLength,
                                       long hitCount,
                                       LocalDateTime lastHitAt) {
        this.id = id;
        this.source = source;
        this.title = title;
        this.content = content;
        this.contentPreview = contentPreview;
        this.vectorDimension = vectorDimension;
        this.contentLength = contentLength;
        this.hitCount = hitCount;
        this.lastHitAt = lastHitAt;
    }

    public Long getId() {
        return id;
    }

    public String getTitle() {
        return title;
    }

    public String getSource() {
        return source;
    }

    public String getContent() {
        return content;
    }

    public String getContentPreview() {
        return contentPreview;
    }

    public int getVectorDimension() {
        return vectorDimension;
    }

    public int getContentLength() {
        return contentLength;
    }

    public long getHitCount() {
        return hitCount;
    }

    public LocalDateTime getLastHitAt() {
        return lastHitAt;
    }
}
