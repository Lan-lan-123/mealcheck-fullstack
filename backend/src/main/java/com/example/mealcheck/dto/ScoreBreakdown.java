package com.example.mealcheck.dto;

import java.util.List;

/** Immutable explanation of the rules actually executed, including caps and clamps. */
public record ScoreBreakdown(String scoringVersion, int total, List<Line> items) {
    public record Line(String rule, String label, int points, List<String> foods) { }
}
