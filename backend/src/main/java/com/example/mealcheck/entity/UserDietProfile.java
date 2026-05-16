package com.example.mealcheck.entity;

import jakarta.persistence.Basic;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

@Entity
@Table(name = "user_diet_profiles")
public class UserDietProfile {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", unique = true)
    private UserAccount user;

    private long totalMeals;
    private int averageScore;

    @Column(length = 32)
    private String preferredGoal;

    @Basic(fetch = FetchType.LAZY)
    @Column(columnDefinition = "TEXT")
    private String commonFoodsJson;

    @Basic(fetch = FetchType.LAZY)
    @Column(columnDefinition = "TEXT")
    private String commonRisksJson;

    @Column(length = 1000)
    private String profileSummary;

    private LocalDateTime updatedAt = LocalDateTime.now();

    public Long getId() { return id; }
    public UserAccount getUser() { return user; }
    public void setUser(UserAccount user) { this.user = user; }
    public long getTotalMeals() { return totalMeals; }
    public void setTotalMeals(long totalMeals) { this.totalMeals = totalMeals; }
    public int getAverageScore() { return averageScore; }
    public void setAverageScore(int averageScore) { this.averageScore = averageScore; }
    public String getPreferredGoal() { return preferredGoal; }
    public void setPreferredGoal(String preferredGoal) { this.preferredGoal = preferredGoal; }
    public String getCommonFoodsJson() { return commonFoodsJson; }
    public void setCommonFoodsJson(String commonFoodsJson) { this.commonFoodsJson = commonFoodsJson; }
    public String getCommonRisksJson() { return commonRisksJson; }
    public void setCommonRisksJson(String commonRisksJson) { this.commonRisksJson = commonRisksJson; }
    public String getProfileSummary() { return profileSummary; }
    public void setProfileSummary(String profileSummary) { this.profileSummary = profileSummary; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
