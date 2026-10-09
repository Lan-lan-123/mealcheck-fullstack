package com.example.mealcheck.dto;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

public class MealAnalysisResponse {
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
