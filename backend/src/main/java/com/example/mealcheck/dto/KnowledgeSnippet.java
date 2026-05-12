package com.example.mealcheck.dto;

public class KnowledgeSnippet {
    private Long id;
    private String title;
    private String content;
    private double score;

    public KnowledgeSnippet() {}
    public KnowledgeSnippet(Long id, String title, String content, double score) {
        this.id = id;
        this.title = title;
        this.content = content;
        this.score = score;
    }
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }
    public double getScore() { return score; }
    public void setScore(double score) { this.score = score; }
}
