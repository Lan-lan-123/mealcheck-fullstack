package com.example.mealcheck.service;

import com.example.mealcheck.config.AppProperties;
import com.example.mealcheck.dto.KnowledgeSnippet;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class KnowledgeResultPostProcessor {
    private static final Pattern LATIN_TERM = Pattern.compile("[a-z0-9]+", Pattern.CASE_INSENSITIVE);

    private final AppProperties properties;

    public KnowledgeResultPostProcessor(AppProperties properties) {
        this.properties = properties;
    }

    public Result process(List<KnowledgeSnippet> candidates, int limit) {
        int safeLimit = Math.max(1, limit);
        List<KnowledgeSnippet> input = candidates == null
                ? List.of()
                : candidates.stream().filter(java.util.Objects::nonNull).toList();
        if (input.isEmpty()) {
            return new Result(List.of(), 0, 0, 0, 0.0,
                    0.0, null, 0.0, false);
        }
        if (!properties.getKnowledge().isResultFilterEnabled()) {
            return result(input.stream().limit(safeLimit).toList(), input,
                    input.size(), 0, 0, 0.0);
        }

        double topScore = input.stream()
                .mapToDouble(KnowledgeSnippet::getScore)
                .filter(Double::isFinite)
                .max()
                .orElse(0.0);
        double absoluteThreshold = unitInterval(properties.getKnowledge().getResultMinScore());
        double relativeThreshold = unitInterval(properties.getKnowledge().getResultRelativeScore());
        double effectiveThreshold = Math.max(absoluteThreshold, topScore * relativeThreshold);
        double rerankerMinimum = unitInterval(properties.getKnowledge().getRerankerMinScore());

        List<KnowledgeSnippet> thresholded = input.stream()
                .filter(snippet -> Double.isFinite(snippet.getScore()))
                .filter(snippet -> snippet.getScore() + 1e-9 >= effectiveThreshold)
                .filter(snippet -> !snippet.isReranked()
                        || rerankerMinimum <= 0.0
                        || (snippet.getRerankerRawScore() != null
                        && Double.isFinite(snippet.getRerankerRawScore())
                        && snippet.getRerankerRawScore() + 1e-9 >= rerankerMinimum))
                .toList();

        double duplicateThreshold = unitInterval(properties.getKnowledge().getResultDedupSimilarity());
        List<KnowledgeSnippet> unique = new ArrayList<>();
        List<Set<String>> selectedTerms = new ArrayList<>();
        int duplicateFiltered = 0;
        for (KnowledgeSnippet candidate : thresholded) {
            Set<String> terms = terms(candidate);
            boolean duplicate = selectedTerms.stream()
                    .anyMatch(selected -> jaccard(terms, selected) >= duplicateThreshold);
            if (!duplicate) {
                unique.add(candidate);
                selectedTerms.add(terms);
            } else {
                duplicateFiltered++;
            }
        }

        int thresholdFiltered = input.size() - thresholded.size();
        return result(unique.stream().limit(safeLimit).toList(), input,
                input.size(), thresholdFiltered, duplicateFiltered, effectiveThreshold);
    }

    public String cacheSignature() {
        AppProperties.Knowledge knowledge = properties.getKnowledge();
        return String.format(Locale.ROOT, "filter:%s:%.4f:%.4f:%.4f:%.4f:%d",
                knowledge.isResultFilterEnabled(),
                unitInterval(knowledge.getResultMinScore()),
                unitInterval(knowledge.getResultRelativeScore()),
                unitInterval(knowledge.getResultDedupSimilarity()),
                unitInterval(knowledge.getRerankerMinScore()),
                candidateMultiplier());
    }

    public int candidateMultiplier() {
        return Math.max(1, Math.min(10, properties.getKnowledge().getResultCandidateMultiplier()));
    }

    private Set<String> terms(KnowledgeSnippet snippet) {
        String text = ((snippet.getTitle() == null ? "" : snippet.getTitle()) + " "
                + (snippet.getContent() == null ? "" : snippet.getContent())).toLowerCase(Locale.ROOT);
        Set<String> terms = new LinkedHashSet<>();
        Matcher latin = LATIN_TERM.matcher(text);
        while (latin.find()) {
            terms.add(latin.group());
        }

        StringBuilder cjk = new StringBuilder();
        text.codePoints().forEach(codePoint -> {
            if (isCjk(codePoint)) {
                cjk.appendCodePoint(codePoint);
            } else {
                addCjkBigrams(cjk, terms);
                cjk.setLength(0);
            }
        });
        addCjkBigrams(cjk, terms);
        if (terms.isEmpty()) {
            terms.add(text.replaceAll("\\s+", " ").trim());
        }
        return terms;
    }

    private void addCjkBigrams(StringBuilder text, Set<String> terms) {
        int[] codePoints = text.codePoints().toArray();
        if (codePoints.length == 1) {
            terms.add(new String(codePoints, 0, 1));
        }
        for (int index = 0; index + 1 < codePoints.length; index++) {
            terms.add(new String(codePoints, index, 2));
        }
    }

    private boolean isCjk(int codePoint) {
        Character.UnicodeScript script = Character.UnicodeScript.of(codePoint);
        return script == Character.UnicodeScript.HAN
                || script == Character.UnicodeScript.HIRAGANA
                || script == Character.UnicodeScript.KATAKANA
                || script == Character.UnicodeScript.HANGUL;
    }

    private double jaccard(Set<String> left, Set<String> right) {
        if (left.isEmpty() && right.isEmpty()) {
            return 1.0;
        }
        Set<String> intersection = new HashSet<>(left);
        intersection.retainAll(right);
        Set<String> union = new HashSet<>(left);
        union.addAll(right);
        return union.isEmpty() ? 0.0 : (double) intersection.size() / union.size();
    }

    private double unitInterval(double value) {
        if (!Double.isFinite(value)) {
            return 0.0;
        }
        return Math.max(0.0, Math.min(1.0, value));
    }

    private Result result(List<KnowledgeSnippet> snippets,
                          List<KnowledgeSnippet> input,
                          int inputCount,
                          int thresholdFilteredCount,
                          int duplicateFilteredCount,
                          double effectiveThreshold) {
        double topRoughScore = input.stream().mapToDouble(this::roughScore).max().orElse(0.0);
        Double topRerankerRawScore = input.stream()
                .map(KnowledgeSnippet::getRerankerRawScore)
                .filter(java.util.Objects::nonNull)
                .max(Double::compareTo)
                .orElse(null);
        double topFinalScore = input.stream().mapToDouble(KnowledgeSnippet::getScore).max().orElse(0.0);
        boolean rerankerApplied = input.stream().anyMatch(KnowledgeSnippet::isReranked);
        return new Result(snippets, inputCount, thresholdFilteredCount, duplicateFilteredCount,
                effectiveThreshold, topRoughScore, topRerankerRawScore, topFinalScore, rerankerApplied);
    }

    private double roughScore(KnowledgeSnippet snippet) {
        return snippet.getRoughRank() > 0 || snippet.getRoughScore() != 0.0
                ? snippet.getRoughScore()
                : snippet.getScore();
    }

    public record Result(List<KnowledgeSnippet> snippets,
                         int inputCount,
                         int thresholdFilteredCount,
                         int duplicateFilteredCount,
                         double effectiveThreshold,
                         double topRoughScore,
                         Double topRerankerRawScore,
                         double topFinalScore,
                         boolean rerankerApplied) {
    }
}
