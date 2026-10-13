package com.example.mealcheck.dto;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

public class MealAnalysisResponse {
    private java.time.LocalDateTime occurredAt;
    private String mealType,recordSource,note;
    private boolean timeConfirmed;
    private long metadataVersion;
    public java.time.LocalDateTime getOccurredAt() { return occurredAt; }
    public void setOccurredAt(java.time.LocalDateTime v) { occurredAt=v; }
    public String getMealType() { return mealType; }
    public void setMealType(String v) { mealType=v; }
    public String getRecordSource() { return recordSource; }
    public void setRecordSource(String v) { recordSource=v; }
    public String getNote() { return note; }
    public void setNote(String v) { note=v; }
    public boolean isTimeConfirmed() { return timeConfirmed; }
    public void setTimeConfirmed(boolean v) { timeConfirmed=v; }
    public long getMetadataVersion() { return metadataVersion; }
    public void setMetadataVersion(long v) { metadataVersion=v; }

    private Long recordId;
    private LocalDateTime createdAt;
    private RecognitionResult recognition;
    private StructureEvaluation evaluation;
    private List<KnowledgeSnippet> references = new ArrayList<>();
    private String advice;
    private long revision;
    private String scoringVersion;
    public long getRevision() { return revision; }
    public void setRevision(long value) { revision = value; }
    public String getScoringVersion() { return scoringVersion; }
    public void setScoringVersion(String value) { scoringVersion = value; }

    public Long getRecordId() { return recordId; }
    public void setRecordId(Long recordId) { this.recordId = recordId; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public RecognitionResult getRecognition() { return recognition; }
    public void setRecognition(RecognitionResult recognition) { this.recognition = recognition; }
    public StructureEvaluation getEvaluation() { return evaluation; }
    public void setEvaluation(StructureEvaluation evaluation) { this.evaluation = evaluation; }
    public List<KnowledgeSnippet> getReferences() { return references; }
    public void setReferences(List<KnowledgeSnippet> references) { this.references = references; }
    public String getAdvice() { return advice; }
    public void setAdvice(String advice) { this.advice = advice; }
}
