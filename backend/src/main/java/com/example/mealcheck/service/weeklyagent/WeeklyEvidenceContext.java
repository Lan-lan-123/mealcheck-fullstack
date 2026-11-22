package com.example.mealcheck.service.weeklyagent;

import com.example.mealcheck.dto.KnowledgeSnippet;
import com.example.mealcheck.util.Jsons;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Objects;

/** The planner and reviewer must see exactly the same bounded retrieval excerpts. */
final class WeeklyEvidenceContext {
    private WeeklyEvidenceContext() {}
    private record Excerpt(Long id, String title, String content) {}
    static String format(ObjectMapper mapper, List<KnowledgeSnippet> evidence) {
        var excerpts = evidence == null ? List.<Excerpt>of() : evidence.stream()
                .filter(Objects::nonNull).limit(5)
                .map(item -> new Excerpt(item.getId(), bounded(item.getTitle(), 120), bounded(item.getContent(), 500)))
                .toList();
        return Jsons.toJson(mapper, excerpts);
    }
    private static String bounded(String value, int limit) {
        return value == null ? "" : value.substring(0, Math.min(value.length(), limit));
    }
}
