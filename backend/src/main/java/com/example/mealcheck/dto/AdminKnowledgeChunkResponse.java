package com.example.mealcheck.dto;

import java.time.LocalDateTime;

public class AdminKnowledgeChunkResponse {

    private Long id;
    private long contentRevision;
    public long getContentRevision() { return contentRevision; }
    public void setContentRevision(long value) { contentRevision = value; }
    private String source;
    private String title;
    private String content;
    private String contentPreview;
    private String category;
    private int vectorDimension;
    private int contentLength;
    private int tokenCount;
    private Long embeddingInputTokens;
    private Long embeddingTotalTokens;
    private int chunkIndex;
    private String contentHash;
    private String sourceVersion;
    private LocalDateTime sourceUpdatedAt;
    private LocalDateTime updatedAt;
    private long hitCount;
    private LocalDateTime lastHitAt;

    public AdminKnowledgeChunkResponse() {
    }

    public AdminKnowledgeChunkResponse(Long id,
                                       String source,
                                       String title,
                                       String content,
                                       String contentPreview,
                                       String category,
                                       int vectorDimension,
                                       int contentLength,
                                       int tokenCount,
                                       int chunkIndex,
                                       String contentHash,
                                       String sourceVersion,
                                       LocalDateTime sourceUpdatedAt,
                                       LocalDateTime updatedAt,
                                       long hitCount,
                                       LocalDateTime lastHitAt) {
        this.id = id;
        this.source = source;
        this.title = title;
        this.content = content;
        this.contentPreview = contentPreview;
        this.category = category;
        this.vectorDimension = vectorDimension;
        this.contentLength = contentLength;
        this.tokenCount = tokenCount;
        this.chunkIndex = chunkIndex;
        this.contentHash = contentHash;
        this.sourceVersion = sourceVersion;
        this.sourceUpdatedAt = sourceUpdatedAt;
        this.updatedAt = updatedAt;
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

    public String getCategory() {
        return category;
    }

    public int getVectorDimension() {
        return vectorDimension;
    }

    public int getContentLength() {
        return contentLength;
    }

    public int getTokenCount() { return tokenCount; }

    public Long getEmbeddingInputTokens() { return embeddingInputTokens; }
    public void setEmbeddingInputTokens(Long tokens) { this.embeddingInputTokens = tokens; }
    public Long getEmbeddingTotalTokens() { return embeddingTotalTokens; }
    public void setEmbeddingTotalTokens(Long tokens) { this.embeddingTotalTokens = tokens; }

    public int getChunkIndex() { return chunkIndex; }

    public String getContentHash() { return contentHash; }

    public String getSourceVersion() { return sourceVersion; }

    public LocalDateTime getSourceUpdatedAt() { return sourceUpdatedAt; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }

    public long getHitCount() {
        return hitCount;
    }

    public LocalDateTime getLastHitAt() {
        return lastHitAt;
    }
}
