package com.example.mealcheck.entity;

import com.example.mealcheck.util.AppTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.LocalDateTime;

@Entity
@Table(name = "assistant_evaluation_case_reviews",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_assistant_evaluation_case_review",
                columnNames = {"run_id", "case_id"}))
public class AssistantEvaluationCaseReview {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "run_id", nullable = false, length = 36)
    private String runId;

    @Column(name = "case_id", nullable = false, length = 160)
    private String caseId;

    @Column(nullable = false, length = 10)
    private String verdict;

    @Column(length = 2000)
    private String notes;

    @Column(nullable = false, length = 64)
    private String reviewer;

    @Column(nullable = false)
    private LocalDateTime createdAt = AppTime.now();

    @Column(nullable = false)
    private LocalDateTime updatedAt = AppTime.now();

    public Long getId() { return id; }
    public String getRunId() { return runId; }
    public void setRunId(String runId) { this.runId = runId; }
    public String getCaseId() { return caseId; }
    public void setCaseId(String caseId) { this.caseId = caseId; }
    public String getVerdict() { return verdict; }
    public void setVerdict(String verdict) { this.verdict = verdict; }
    public String getNotes() { return notes; }
    public void setNotes(String notes) { this.notes = notes; }
    public String getReviewer() { return reviewer; }
    public void setReviewer(String reviewer) { this.reviewer = reviewer; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
