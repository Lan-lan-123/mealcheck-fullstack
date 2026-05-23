package com.example.mealcheck.dto;

import java.util.ArrayList;
import java.util.List;

public class AdminAssistantStatsResponse {
    private long conversationCount;
    private long questionCount;
    private double averageTurns;
    private long proactiveTriggerCount;
    private List<MetricItem> questionsByUser = new ArrayList<>();
    private List<MetricItem> questionTrend7d = new ArrayList<>();
    private List<MetricItem> questionCategories = new ArrayList<>();
    private List<MetricItem> topRagReferences = new ArrayList<>();

    public AdminAssistantStatsResponse() {
    }

    public AdminAssistantStatsResponse(long conversationCount,
                                       long questionCount,
                                       double averageTurns,
                                       long proactiveTriggerCount,
                                       List<MetricItem> questionsByUser,
                                       List<MetricItem> questionTrend7d,
                                       List<MetricItem> questionCategories,
                                       List<MetricItem> topRagReferences) {
        this.conversationCount = conversationCount;
        this.questionCount = questionCount;
        this.averageTurns = averageTurns;
        this.proactiveTriggerCount = proactiveTriggerCount;
        this.questionsByUser = questionsByUser;
        this.questionTrend7d = questionTrend7d;
        this.questionCategories = questionCategories;
        this.topRagReferences = topRagReferences;
    }

    public long getConversationCount() {
        return conversationCount;
    }

    public void setConversationCount(long conversationCount) {
        this.conversationCount = conversationCount;
    }

    public long getQuestionCount() {
        return questionCount;
    }

    public void setQuestionCount(long questionCount) {
        this.questionCount = questionCount;
    }

    public double getAverageTurns() {
        return averageTurns;
    }

    public void setAverageTurns(double averageTurns) {
        this.averageTurns = averageTurns;
    }

    public long getProactiveTriggerCount() {
        return proactiveTriggerCount;
    }

    public void setProactiveTriggerCount(long proactiveTriggerCount) {
        this.proactiveTriggerCount = proactiveTriggerCount;
    }

    public List<MetricItem> getQuestionsByUser() {
        return questionsByUser;
    }

    public void setQuestionsByUser(List<MetricItem> questionsByUser) {
        this.questionsByUser = questionsByUser;
    }

    public List<MetricItem> getQuestionTrend7d() {
        return questionTrend7d;
    }

    public void setQuestionTrend7d(List<MetricItem> questionTrend7d) {
        this.questionTrend7d = questionTrend7d;
    }

    public List<MetricItem> getQuestionCategories() {
        return questionCategories;
    }

    public void setQuestionCategories(List<MetricItem> questionCategories) {
        this.questionCategories = questionCategories;
    }

    public List<MetricItem> getTopRagReferences() {
        return topRagReferences;
    }

    public void setTopRagReferences(List<MetricItem> topRagReferences) {
        this.topRagReferences = topRagReferences;
    }

    public static class MetricItem {
        private String label;
        private int value;

        public MetricItem() {
        }

        public MetricItem(String label, int value) {
            this.label = label;
            this.value = value;
        }

        public String getLabel() {
            return label;
        }

        public void setLabel(String label) {
            this.label = label;
        }

        public int getValue() {
            return value;
        }

        public void setValue(int value) {
            this.value = value;
        }
    }
}
