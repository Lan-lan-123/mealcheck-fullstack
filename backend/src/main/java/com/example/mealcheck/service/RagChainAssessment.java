package com.example.mealcheck.service;

import com.example.mealcheck.dto.RagChainReport;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;

/** Scores captured evidence only. Never repeats a search or injects an expected source. */
final class RagChainAssessment {
    static final String VERSION = "rag-chain-v3";
    private RagChainAssessment() { }
    static RagChainReport assess(JsonNode expected, List<RagChainReport.Retrieval> retrievals) {
        var keywords = strings(expected.path("expectedTitleKeywords"));
        boolean noAnswer = expected.path("expectNoAnswer").asBoolean();
        boolean boundLabels = expected.path("evidenceTargets").isArray();
        var targets = expected.path("evidenceTargets");
        List<RagChainReport.Retrieval> searches = retrievals == null ? List.of() : List.copyOf(retrievals);
        List<RagChainReport.Hit> primary = !searches.isEmpty() && "SUCCESS".equals(searches.get(0).status())
                ? searches.get(0).hits() : List.of();
        var seen = new HashSet<String>();
        var grades = new ArrayList<Integer>();
        int rank = 0;
        for (int index = 0; index < primary.size(); index++) {
            var hit = primary.get(index);
            int grade = grade(hit, targets, keywords, boundLabels);
            if (!seen.add(key(hit, boundLabels))) grade = 0;
            grades.add(grade);
            if (rank == 0 && grade > 0) rank = index + 1;
        }
        double ndcg = boundLabels ? ndcg(grades, targets) : rank > 0 && rank <= 5 ? discount(1, rank) : 0.0;
        var matched = new LinkedHashSet<String>();
        for (var search : searches) if ("SUCCESS".equals(search.status())) {
            for (var hit : search.hits()) if (grade(hit, targets, keywords, boundLabels) > 0) matched.add(key(hit, boundLabels));
        }
        var primaryMatched = new HashSet<String>();
        for (var hit : primary) if (grade(hit, targets, keywords, boundLabels) > 0) primaryMatched.add(key(hit, boundLabels));
        double finalCoverage = coverage(expected, matched, boundLabels);
        double primaryCoverage = coverage(expected, primaryMatched, boundLabels);
        int primaryUnjudged = 0, totalUnjudged = 0, totalHits = 0, invalid = 0;
        if (boundLabels) for (int index = 0; index < searches.size(); index++) {
            var search = searches.get(index);
            if (!"SUCCESS".equals(search.status())) continue;
            for (var hit : search.hits()) {
                totalHits++;
                String judgment = RagEvidenceLabels.judgment(hit, expected);
                if ("UNJUDGED".equals(judgment)) { totalUnjudged++; if (index == 0) primaryUnjudged++; }
                if ("INVALID_CONTENT".equals(judgment)) invalid++;
            }
        }
        boolean covered = !noAnswer && finalCoverage == 1.0;
        String status;
        if (searches.stream().anyMatch(search -> !"SUCCESS".equals(search.status()))) status = "ERROR";
        else if (noAnswer) status = searches.isEmpty() ? "NOT_REQUIRED" : "FAILED";
        else if (searches.isEmpty()) status = "NOT_CALLED";
        else status = covered ? "PASSED" : totalUnjudged > 0 ? "UNJUDGED" : "FAILED";
        var evidence = new ArrayList<RagChainReport.EvidenceTarget>();
        if (boundLabels) for (var target : targets) evidence.add(new RagChainReport.EvidenceTarget(
                target.path("knowledgeId").asText(), target.path("title").asText(), target.path("relevance").asInt(),
                strings(target.path("evidenceQuotes")), target.path("rationale").asText()));
        return new RagChainReport(noAnswer, keywords, status,
                "PASSED".equals(status) || "NOT_REQUIRED".equals(status), rank,
                rank > 0 && rank <= 3, rank > 0 && rank <= 5,
                rank > 0 ? 1.0 / rank : 0.0, ndcg, searches,
                boundLabels ? expected.path("annotationRevision").asText("rag-evidence-v1") : "legacy-title-v2",
                expected.path("answerability").asText(noAnswer ? "OUT_OF_SCOPE" : "ANSWERABLE"),
                expected.path("annotation").path("status").asText("UNKNOWN"), covered, finalCoverage,
                covered && primaryCoverage < 1.0 && searches.size() > 1, List.copyOf(matched), evidence,
                boundLabels ? new RagChainReport.JudgmentCoverage(primary.size(), primaryUnjudged,
                        totalHits, totalUnjudged, invalid, primaryUnjudged == 0) : null);
    }

    private static List<String> strings(JsonNode node) {
        var values = new ArrayList<String>(); node.forEach(item -> values.add(item.asText())); return values;
    }
    private static int grade(RagChainReport.Hit hit, JsonNode targets, List<String> keywords, boolean bound) {
        return bound ? RagEvidenceLabels.grade(hit, targets)
                : hit.title() != null && keywords.stream().anyMatch(hit.title()::contains) ? 1 : 0;
    }
    private static String key(RagChainReport.Hit hit, boolean bound) {
        return bound ? Objects.toString(hit.knowledgeId(), "") : Objects.toString(hit.title(), "");
    }
    private static double coverage(JsonNode expected, Set<String> matched, boolean bound) {
        if (!bound) return matched.isEmpty() ? 0.0 : 1.0;
        var groups = expected.path("requiredEvidenceGroups");
        if (!groups.isArray() || groups.isEmpty()) return 0.0;
        int covered = 0;
        for (var group : groups) if (strings(group).stream().anyMatch(matched::contains)) covered++;
        return (double) covered / groups.size();
    }
    private static double ndcg(List<Integer> grades, JsonNode targets) {
        double actual = 0.0, ideal = 0.0;
        for (int index = 0; index < Math.min(5, grades.size()); index++) actual += discount(grades.get(index), index + 1);
        var idealGrades = new ArrayList<Integer>();
        for (var target : targets) idealGrades.add(target.path("relevance").asInt());
        idealGrades.sort(Comparator.reverseOrder());
        for (int index = 0; index < Math.min(5, idealGrades.size()); index++) ideal += discount(idealGrades.get(index), index + 1);
        return ideal == 0 ? 0.0 : actual / ideal;
    }
    private static double discount(int grade, int rank) {
        return (Math.pow(2.0, grade) - 1.0) / (Math.log(rank + 1.0) / Math.log(2.0));
    }
}
