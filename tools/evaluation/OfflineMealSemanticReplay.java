package com.example.mealcheck.service;

import com.example.mealcheck.dto.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.util.*;

/** Replay saved provider outputs through local checks only. No API clients, credentials or network calls. */
public class OfflineMealSemanticReplay {
    public static void main(String[] args) throws Exception {
        var mapper = new ObjectMapper();
        var source = Path.of(args[0]); var destination = Path.of(args[1]);
        if (Files.exists(destination)) throw new IllegalArgumentException("Use a new output path; evidence is immutable");
        var run = mapper.readTree(Files.readString(source));
        var scoring = new MealScoringService();
        var rows = new ArrayList<Map<String,Object>>();
        int rawScoreDifferences = 0, foodCount = 0, nonfoodCount = 0;
        for (var item : run.path("cases")) {
            if (!item.path("status").asText().equals("COMPLETED")) continue;
            var before = mapper.treeToValue(item.path("recognition"),RecognitionResult.class);
            if (!before.isFoodImage()) { nonfoodCount++; continue; }
            foodCount++;
            int rawScore = scoring.evaluate(before, "balanced").getScore();
            if (rawScore != item.path("evaluation").path("score").asInt()) rawScoreDifferences++;
            var reviewed = new RecognitionResult();
            reviewed.setFoods(before.getFoods().stream().map(RecognitionSemanticValidator::review).toList());
            var evaluation = scoring.evaluate(reviewed,"balanced");
            if (evaluation.getScoreBreakdown().items().stream().mapToInt(ScoreBreakdown.Line::points).sum() != evaluation.getScore())
                throw new IllegalStateException("Score details do not reconcile");
            var row = new LinkedHashMap<String,Object>();
            row.put("id",item.path("id").asText()); row.put("originalScore",item.path("evaluation").path("score").asInt());
            row.put("sameInputCurrentScore",rawScore); row.put("reviewedInputScore",evaluation.getScore());
            row.put("conflicts",reviewed.getFoods().stream().filter(food -> !food.getReviewIssues().isEmpty()).toList());
            String advice = item.path("advice").asText();
            row.put("originalAdviceRejected",!MealAdviceConsistency.consistent(advice,before));
            row.put("adviceRejectedAfterInputReview",!MealAdviceConsistency.consistent(advice,reviewed));
            rows.add(row);
        }
        var output = new LinkedHashMap<String,Object>();
        output.put("scope","Offline replay of saved model outputs; no new model generation, no accuracy claim");
        output.put("providerCalls",0); output.put("source",source.toString());
        output.put("foodImages",foodCount); output.put("nonfoodImagesSkipped",nonfoodCount);
        output.put("sameInputScoreDifferences",rawScoreDifferences);
        output.put("casesWithSemanticConflicts",rows.stream().filter(row -> !((List<?>)row.get("conflicts")).isEmpty()).map(row -> row.get("id")).toList());
        output.put("originalAdviceRejectedCases",rows.stream().filter(row -> (Boolean)row.get("originalAdviceRejected")).map(row -> row.get("id")).toList());
        output.put("cases",rows);
        Files.writeString(destination,mapper.writerWithDefaultPrettyPrinter().writeValueAsString(output));
        System.out.println(mapper.writeValueAsString(output.entrySet().stream().filter(entry -> !entry.getKey().equals("cases")).collect(java.util.stream.Collectors.toMap(Map.Entry::getKey,Map.Entry::getValue))));
        if (rawScoreDifferences != 0) throw new IllegalStateException("Same-input scores changed");
    }
}
