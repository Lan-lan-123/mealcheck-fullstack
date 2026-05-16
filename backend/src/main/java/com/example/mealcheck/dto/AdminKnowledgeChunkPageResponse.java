package com.example.mealcheck.dto;

import java.util.List;

public class AdminKnowledgeChunkPageResponse {
    private List<AdminKnowledgeChunkResponse> content;
    private long totalElements;
    private int totalPages;
    private int page;
    private int size;

    public AdminKnowledgeChunkPageResponse(List<AdminKnowledgeChunkResponse> content,
                                           long totalElements,
                                           int totalPages,
                                           int page,
                                           int size) {
        this.content = content;
        this.totalElements = totalElements;
        this.totalPages = totalPages;
        this.page = page;
        this.size = size;
    }

    public List<AdminKnowledgeChunkResponse> getContent() {
        return content;
    }

    public long getTotalElements() {
        return totalElements;
    }

    public int getTotalPages() {
        return totalPages;
    }

    public int getPage() {
        return page;
    }

    public int getSize() {
        return size;
    }
}
