package com.example.mealcheck.dto;

import java.util.ArrayList;
import java.util.List;

public class AdminDashboardResponse {

    private AdminStatsResponse overview;
    private AdminSystemResponse system;
    private List<NonFoodUploadAlert> nonFoodUploadAlerts = new ArrayList<>();
    private AdminOperationsResponse operations;
    private RagEvaluationSummaryResponse ragEvaluation;
    private AdminUploadTrendResponse uploadTrends;
    private AdminAssistantStatsResponse assistantStats;

    public AdminDashboardResponse() {
    }

    public AdminDashboardResponse(AdminStatsResponse overview,
                                  AdminSystemResponse system,
                                  List<NonFoodUploadAlert> nonFoodUploadAlerts,
                                  AdminOperationsResponse operations,
                                  RagEvaluationSummaryResponse ragEvaluation,
                                  AdminUploadTrendResponse uploadTrends,
                                  AdminAssistantStatsResponse assistantStats) {
        this.overview = overview;
        this.system = system;
        this.nonFoodUploadAlerts = nonFoodUploadAlerts;
        this.operations = operations;
        this.ragEvaluation = ragEvaluation;
        this.uploadTrends = uploadTrends;
        this.assistantStats = assistantStats;
    }

    public AdminStatsResponse getOverview() {
        return overview;
    }

    public void setOverview(AdminStatsResponse overview) {
        this.overview = overview;
    }

    public AdminSystemResponse getSystem() {
        return system;
    }

    public void setSystem(AdminSystemResponse system) {
        this.system = system;
    }

    public List<NonFoodUploadAlert> getNonFoodUploadAlerts() {
        return nonFoodUploadAlerts;
    }

    public void setNonFoodUploadAlerts(List<NonFoodUploadAlert> nonFoodUploadAlerts) {
        this.nonFoodUploadAlerts = nonFoodUploadAlerts;
    }

    public AdminOperationsResponse getOperations() {
        return operations;
    }

    public void setOperations(AdminOperationsResponse operations) {
        this.operations = operations;
    }

    public RagEvaluationSummaryResponse getRagEvaluation() {
        return ragEvaluation;
    }

    public void setRagEvaluation(RagEvaluationSummaryResponse ragEvaluation) {
        this.ragEvaluation = ragEvaluation;
    }

    public AdminUploadTrendResponse getUploadTrends() {
        return uploadTrends;
    }

    public void setUploadTrends(AdminUploadTrendResponse uploadTrends) {
        this.uploadTrends = uploadTrends;
    }

    public AdminAssistantStatsResponse getAssistantStats() {
        return assistantStats;
    }

    public void setAssistantStats(AdminAssistantStatsResponse assistantStats) {
        this.assistantStats = assistantStats;
    }
}
