package com.example.mealcheck.dto.assistant;

public class AssistantReference {
    private final Long id;
    private final String title;
    private final String content;
    private final String category;
    private final double score;

    public AssistantReference(Long id, String title, String content, double score) {
        this(id, title, content, "", score);
    }

    @com.fasterxml.jackson.annotation.JsonCreator
    public AssistantReference(@com.fasterxml.jackson.annotation.JsonProperty("id") Long id,
                              @com.fasterxml.jackson.annotation.JsonProperty("title") String title,
                              @com.fasterxml.jackson.annotation.JsonProperty("content") String content,
                              @com.fasterxml.jackson.annotation.JsonProperty("category") String category,
                              @com.fasterxml.jackson.annotation.JsonProperty("score") double score) {
        this.id = id;
        this.title = title;
        this.content = content;
        this.category = category;
        this.score = score;
    }

    public Long getId() {
        return id;
    }

    public String getTitle() {
        return title;
    }

    public String getContent() {
        return content;
    }

    public String getCategory() {
        return category;
    }

    public double getScore() {
        return score;
    }
}
