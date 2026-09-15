package com.example.mealcheck.dto;

import java.io.Serializable;

public class KnowledgeSnippet implements Serializable {
    private static final long serialVersionUID = 1L;
    private Long id;
    private String title;
    private String content;
    private String category;
    private double score;
    private double roughScore;
    private int roughRank;
    private Double rerankerRawScore;
    private Integer rerankerRank;
    private boolean reranked;

    public KnowledgeSnippet() {}
    public KnowledgeSnippet(Long id, String title, String content, double score) {
        this(id, title, content, "", score);
    }
    public KnowledgeSnippet(Long id, String title, String content, String category, double score) {
        this.id = id;
        this.title = title;
        this.content = content;
        this.category = category;
        this.score = score;
        this.roughScore = score;
    }
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }
    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }
    public double getScore() { return score; }
    public void setScore(double score) { this.score = score; }
    public double getFinalScore() { return score; }
    public void setFinalScore(double finalScore) { this.score = finalScore; }
    public double getRoughScore() { return roughScore; }
    public void setRoughScore(double roughScore) { this.roughScore = roughScore; }
    public int getRoughRank() { return roughRank; }
    public void setRoughRank(int roughRank) { this.roughRank = roughRank; }
    public Double getRerankerRawScore() { return rerankerRawScore; }
    public void setRerankerRawScore(Double rerankerRawScore) { this.rerankerRawScore = rerankerRawScore; }
    public Integer getRerankerRank() { return rerankerRank; }
    public void setRerankerRank(Integer rerankerRank) { this.rerankerRank = rerankerRank; }
    public boolean isReranked() { return reranked; }
    public void setReranked(boolean reranked) { this.reranked = reranked; }
}
