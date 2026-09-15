package com.example.mealcheck.service.weeklyagent;

import java.io.Serializable;
import java.util.List;

public record WeeklyReportDraft(String reportText,
                                List<String> highlights,
                                List<String> nextWeekSuggestions,
                                boolean aiGenerated) implements Serializable {
    public WeeklyReportDraft {
        reportText = reportText == null ? "" : reportText.trim();
        highlights = highlights == null ? List.of() : List.copyOf(highlights);
        nextWeekSuggestions = nextWeekSuggestions == null ? List.of() : List.copyOf(nextWeekSuggestions);
    }
}
