package com.example.mealcheck.service.weeklyagent;

import java.io.Serializable;
import java.util.List;

public record WeeklyTrendAnalysis(String summary,
                                  List<String> findings,
                                  List<String> focusKeywords,
                                  boolean aiGenerated) implements Serializable {
    public WeeklyTrendAnalysis {
        summary = summary == null ? "" : summary.trim();
        findings = findings == null ? List.of() : List.copyOf(findings);
        focusKeywords = focusKeywords == null ? List.of() : List.copyOf(focusKeywords);
    }
}
