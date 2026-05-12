package com.example.mealcheck.entity;

import jakarta.persistence.Basic;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

@Entity
@Table(name = "meal_records")
public class MealRecord {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id")
    private UserAccount user;

    @Column(length = 255)
    private String originalFileName;

    @Column(length = 500)
    private String storedImagePath;

    @Column(length = 32)
    private String goal;

    @Column(nullable = false)
    private Integer score;

    @Column(length = 800)
    private String summary;

    @Basic(fetch = FetchType.LAZY)
    @Column(columnDefinition = "TEXT")
    private String detectedFoodsJson;

    @Basic(fetch = FetchType.LAZY)
    @Column(columnDefinition = "TEXT")
    private String categoryCountsJson;

    @Basic(fetch = FetchType.LAZY)
    @Column(columnDefinition = "TEXT")
    private String riskTagsJson;

    @Basic(fetch = FetchType.LAZY)
    @Column(columnDefinition = "TEXT")
    private String advice;

    @Column(nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public UserAccount getUser() { return user; }
    public void setUser(UserAccount user) { this.user = user; }
    public String getOriginalFileName() { return originalFileName; }
    public void setOriginalFileName(String originalFileName) { this.originalFileName = originalFileName; }
    public String getStoredImagePath() { return storedImagePath; }
    public void setStoredImagePath(String storedImagePath) { this.storedImagePath = storedImagePath; }
    public String getGoal() { return goal; }
    public void setGoal(String goal) { this.goal = goal; }
    public Integer getScore() { return score; }
    public void setScore(Integer score) { this.score = score; }
    public String getSummary() { return summary; }
    public void setSummary(String summary) { this.summary = summary; }
    public String getDetectedFoodsJson() { return detectedFoodsJson; }
    public void setDetectedFoodsJson(String detectedFoodsJson) { this.detectedFoodsJson = detectedFoodsJson; }
    public String getCategoryCountsJson() { return categoryCountsJson; }
    public void setCategoryCountsJson(String categoryCountsJson) { this.categoryCountsJson = categoryCountsJson; }
    public String getRiskTagsJson() { return riskTagsJson; }
    public void setRiskTagsJson(String riskTagsJson) { this.riskTagsJson = riskTagsJson; }
    public String getAdvice() { return advice; }
    public void setAdvice(String advice) { this.advice = advice; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
