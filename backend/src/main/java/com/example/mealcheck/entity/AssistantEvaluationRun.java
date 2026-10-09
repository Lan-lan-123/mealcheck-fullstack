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
@Table(name = "assistant_evaluation_runs")
public class AssistantEvaluationRun {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 36)
    private String runId;

    @Column(nullable = false, length = 16)
    private String mode;

    @Column(nullable = false, length = 20)
    private String status;

    @Column(nullable = false, length = 20)
    private String verdict;

    @Column(nullable = false)
    private int caseCount;

    @Column(nullable = false)
    private int completedCount;

    @Column(nullable = false)
    private int judgedCount;

    @Column(nullable = false)
    private int passedCount;

    @Column(nullable = false)
    private int failedCount;

    @Column(nullable = false)
    private int errorCount;

    @Column(nullable = false)
    private int hardGateFailureCount;

    @Column(nullable = false)
    private int toolTracePassedCount;

    @Column(nullable = false)
    private int intentEvaluatedCount;

    @Column(nullable = false)
    private int intentMatchedCount;

    @Column(nullable = false)
    private double passRate;

    @Column(nullable = false)
    private double executionSuccessRate;

    @Column(nullable = false)
    private double endToEndPassRate;

    @Column(nullable = false)
    private double toolTracePassRate;

    @Column(nullable = false)
    private double intentAccuracy;

    @Column(name = "intent_macro_f1", nullable = false)
    private double intentMacroF1;

    @Column(nullable = false)
    private double macroCategoryPassRate;

    @Column(nullable = false)
    private double averageScore;

    @Column(nullable = false)
    private double endToEndAverageScore;

    @Column(nullable = false)
    private long elapsedMs;

    @Column(length = 160)
    private String generationModel;

    @Column(length = 160)
    private String judgeModel;

    @Column(nullable = false)
    private boolean independentJudge;

    @Column(length = 40)
    private String datasetVersion;

    @Column(length = 64)
    private String datasetFingerprint;

    @Column(length = 64)
    private String startedBy;

    @Column(length = 1000)
    private String message;

    @Column(columnDefinition = "TEXT")
    private String resultJson;

    @Column(nullable = false)
    private LocalDateTime startedAt;

    private LocalDateTime finishedAt;

    @Column(nullable = false)
    private LocalDateTime updatedAt = AppTime.now();

    public Long getId() { return id; }
    public String getRunId() { return runId; }
    public void setRunId(String runId) { this.runId = runId; }
    public String getMode() { return mode; }
    public void setMode(String mode) { this.mode = mode; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getVerdict() { return verdict; }
    public void setVerdict(String verdict) { this.verdict = verdict; }
    public int getCaseCount() { return caseCount; }
    public void setCaseCount(int caseCount) { this.caseCount = caseCount; }
    public int getCompletedCount() { return completedCount; }
    public void setCompletedCount(int completedCount) { this.completedCount = completedCount; }
    public int getJudgedCount() { return judgedCount; }
    public void setJudgedCount(int judgedCount) { this.judgedCount = judgedCount; }
    public int getPassedCount() { return passedCount; }
    public void setPassedCount(int passedCount) { this.passedCount = passedCount; }
    public int getFailedCount() { return failedCount; }
    public void setFailedCount(int failedCount) { this.failedCount = failedCount; }
    public int getErrorCount() { return errorCount; }
    public void setErrorCount(int errorCount) { this.errorCount = errorCount; }
    public int getHardGateFailureCount() { return hardGateFailureCount; }
    public void setHardGateFailureCount(int hardGateFailureCount) { this.hardGateFailureCount = hardGateFailureCount; }
    public int getToolTracePassedCount() { return toolTracePassedCount; }
    public void setToolTracePassedCount(int toolTracePassedCount) { this.toolTracePassedCount = toolTracePassedCount; }
    public int getIntentEvaluatedCount() { return intentEvaluatedCount; }
    public void setIntentEvaluatedCount(int intentEvaluatedCount) { this.intentEvaluatedCount = intentEvaluatedCount; }
    public int getIntentMatchedCount() { return intentMatchedCount; }
    public void setIntentMatchedCount(int intentMatchedCount) { this.intentMatchedCount = intentMatchedCount; }
    public double getPassRate() { return passRate; }
    public void setPassRate(double passRate) { this.passRate = passRate; }
    public double getExecutionSuccessRate() { return executionSuccessRate; }
    public void setExecutionSuccessRate(double executionSuccessRate) { this.executionSuccessRate = executionSuccessRate; }
    public double getEndToEndPassRate() { return endToEndPassRate; }
    public void setEndToEndPassRate(double endToEndPassRate) { this.endToEndPassRate = endToEndPassRate; }
    public double getToolTracePassRate() { return toolTracePassRate; }
    public void setToolTracePassRate(double toolTracePassRate) { this.toolTracePassRate = toolTracePassRate; }
    public double getIntentAccuracy() { return intentAccuracy; }
    public void setIntentAccuracy(double intentAccuracy) { this.intentAccuracy = intentAccuracy; }
    public double getIntentMacroF1() { return intentMacroF1; }
    public void setIntentMacroF1(double intentMacroF1) { this.intentMacroF1 = intentMacroF1; }
    public double getMacroCategoryPassRate() { return macroCategoryPassRate; }
    public void setMacroCategoryPassRate(double macroCategoryPassRate) { this.macroCategoryPassRate = macroCategoryPassRate; }
    public double getAverageScore() { return averageScore; }
    public void setAverageScore(double averageScore) { this.averageScore = averageScore; }
    public double getEndToEndAverageScore() { return endToEndAverageScore; }
    public void setEndToEndAverageScore(double endToEndAverageScore) { this.endToEndAverageScore = endToEndAverageScore; }
    public long getElapsedMs() { return elapsedMs; }
    public void setElapsedMs(long elapsedMs) { this.elapsedMs = elapsedMs; }
    public String getGenerationModel() { return generationModel; }
    public void setGenerationModel(String generationModel) { this.generationModel = generationModel; }
    public String getJudgeModel() { return judgeModel; }
    public void setJudgeModel(String judgeModel) { this.judgeModel = judgeModel; }
    public boolean isIndependentJudge() { return independentJudge; }
    public void setIndependentJudge(boolean independentJudge) { this.independentJudge = independentJudge; }
    public String getDatasetVersion() { return datasetVersion; }
    public void setDatasetVersion(String datasetVersion) { this.datasetVersion = datasetVersion; }
    public String getDatasetFingerprint() { return datasetFingerprint; }
    public void setDatasetFingerprint(String datasetFingerprint) { this.datasetFingerprint = datasetFingerprint; }
    public String getStartedBy() { return startedBy; }
    public void setStartedBy(String startedBy) { this.startedBy = startedBy; }
    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }
    public String getResultJson() { return resultJson; }
    public void setResultJson(String resultJson) { this.resultJson = resultJson; }
    public LocalDateTime getStartedAt() { return startedAt; }
    public void setStartedAt(LocalDateTime startedAt) { this.startedAt = startedAt; }
    public LocalDateTime getFinishedAt() { return finishedAt; }
    public void setFinishedAt(LocalDateTime finishedAt) { this.finishedAt = finishedAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
