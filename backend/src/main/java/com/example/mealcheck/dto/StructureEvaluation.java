package com.example.mealcheck.dto;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class StructureEvaluation {
    private int score;
    private String summary;
    private Map<String, Integer> categoryCounts = new LinkedHashMap<>();
    private List<String> positivePoints = new ArrayList<>();
    private List<String> riskTags = new ArrayList<>();

    public int getScore() { return score; }
    public void setScore(int score) { this.score = score; }
    public String getSummary() { return summary; }
    public void setSummary(String summary) { this.summary = summary; }
    public Map<String, Integer> getCategoryCounts() { return categoryCounts; }
    public void setCategoryCounts(Map<String, Integer> categoryCounts) { this.categoryCounts = categoryCounts; }
    public List<String> getPositivePoints() { return positivePoints; }
    public void setPositivePoints(List<String> positivePoints) { this.positivePoints = positivePoints; }
    public List<String> getRiskTags() { return riskTags; }
    public void setRiskTags(List<String> riskTags) { this.riskTags = riskTags; }
}
