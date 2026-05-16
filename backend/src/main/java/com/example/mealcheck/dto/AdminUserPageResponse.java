package com.example.mealcheck.dto;

import java.util.List;

public class AdminUserPageResponse {
    private List<AdminUserResponse> content;
    private long totalElements;
    private int totalPages;
    private int page;
    private int size;

    public AdminUserPageResponse() {
    }

    public AdminUserPageResponse(List<AdminUserResponse> content,
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

    public List<AdminUserResponse> getContent() {
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
