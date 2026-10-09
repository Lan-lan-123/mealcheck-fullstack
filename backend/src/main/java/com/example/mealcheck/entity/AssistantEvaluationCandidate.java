package com.example.mealcheck.entity;

import com.example.mealcheck.util.AppTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

@Entity
@Table(name = "assistant_evaluation_candidates")
public class AssistantEvaluationCandidate {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 1000)
    private String question;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String observedAnswer;

    @Column(nullable = false, length = 40)
    private String issueType;

    @Column(length = 2000)
    private String sanitizedContext;

    @Column(length = 80)
    private String expectedSkill;

    @Column(columnDefinition = "TEXT")
    private String expectedToolsJson;

    @Column(length = 2000)
    private String notes;

    @Column(length = 200)
    private String sourceReference;

    @Column(nullable = false, length = 16)
    private String status = "PENDING";

    @Column(nullable = false, length = 64)
    private String submittedBy;

    @Column(length = 64)
    private String reviewedBy;

    @Column(nullable = false)
    private LocalDateTime createdAt = AppTime.now();

    @Column(nullable = false)
    private LocalDateTime updatedAt = AppTime.now();

    public Long getId() { return id; }
    public String getQuestion() { return question; }
    public void setQuestion(String question) { this.question = question; }
    public String getObservedAnswer() { return observedAnswer; }
    public void setObservedAnswer(String observedAnswer) { this.observedAnswer = observedAnswer; }
    public String getIssueType() { return issueType; }
    public void setIssueType(String issueType) { this.issueType = issueType; }
    public String getSanitizedContext() { return sanitizedContext; }
    public void setSanitizedContext(String sanitizedContext) { this.sanitizedContext = sanitizedContext; }
    public String getExpectedSkill() { return expectedSkill; }
    public void setExpectedSkill(String expectedSkill) { this.expectedSkill = expectedSkill; }
    public String getExpectedToolsJson() { return expectedToolsJson; }
    public void setExpectedToolsJson(String expectedToolsJson) { this.expectedToolsJson = expectedToolsJson; }
    public String getNotes() { return notes; }
    public void setNotes(String notes) { this.notes = notes; }
    public String getSourceReference() { return sourceReference; }
    public void setSourceReference(String sourceReference) { this.sourceReference = sourceReference; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getSubmittedBy() { return submittedBy; }
    public void setSubmittedBy(String submittedBy) { this.submittedBy = submittedBy; }
    public String getReviewedBy() { return reviewedBy; }
    public void setReviewedBy(String reviewedBy) { this.reviewedBy = reviewedBy; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
