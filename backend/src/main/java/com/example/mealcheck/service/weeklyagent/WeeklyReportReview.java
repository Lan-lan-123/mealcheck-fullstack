package com.example.mealcheck.service.weeklyagent;

import java.io.Serializable;
import java.util.List;

public record WeeklyReportReview(boolean approved,
                                 String feedback,
                                 List<String> issues,
                                 boolean aiGenerated) implements Serializable {
    public WeeklyReportReview {
        feedback = feedback == null ? "" : feedback.trim();
        issues = issues == null ? List.of() : List.copyOf(issues);
    }
}
