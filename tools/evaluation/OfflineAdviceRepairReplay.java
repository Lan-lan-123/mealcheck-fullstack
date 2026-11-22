package com.example.mealcheck.service;

import com.example.mealcheck.dto.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** Reuses saved provider answers via a mocked client; no HTTP transport, API key or new model generation. */
public final class OfflineAdviceRepairReplay {
    public static void main(String[] args) throws Exception {
        var source = Path.of(args[0]); var destination = Path.of(args[1]);
        if (Files.exists(destination)) throw new IllegalArgumentException("Use a new output path");
        var mapper = new ObjectMapper().findAndRegisterModules();
        var run = mapper.readTree(Files.readString(source));
        var rows = new ArrayList<Map<String,Object>>();
        int scoreDifferences = 0, nonfood = 0;
        for (var sample : run.path("cases")) {
            var node = sample.has("scoredRecognition") ? sample.path("scoredRecognition") : sample.path("recognition");
            if (node.isMissingNode()) continue;
            var recognition = mapper.treeToValue(node,RecognitionResult.class);
            if (!recognition.isFoodImage()) { nonfood++; continue; }
            String id = sample.path("id").asText(), raw = sample.path("advice").asText();
            for (var call : run.path("calls")) if (id.equals(call.path("caseId").asText()) && "meal-advice".equals(call.path("operation").asText())
                    && call.has("answer")) raw = call.path("answer").asText();
            if (raw.isBlank()) throw new IllegalArgumentException("No saved advice for " + id);
            var client = mock(AiChatClient.class);
            when(client.isConfigured()).thenReturn(true);
            when(client.complete(anyString(),anyList(),anyDouble())).thenReturn(raw);
            var scoring = new MealScoringService();
            var evaluation = scoring.evaluate(recognition,"balanced");
            if (evaluation.getScore() != sample.path("evaluation").path("score").asInt()) scoreDifferences++;
            String displayed = new AdviceGenerationService(client).generate(recognition,evaluation,List.of(),"balanced");
            verify(client,times(1)).complete(anyString(),anyList(),anyDouble());
            var row = new LinkedHashMap<String,Object>();
            row.put("id",id); row.put("savedStatus",sample.path("status").asText());
            row.put("rawAdvice",raw); row.put("previousDisplayedAdvice",sample.path("advice").asText());
            row.put("replayedAdvice",displayed); row.put("rawAccepted",MealAdviceConsistency.consistent(raw,recognition));
            row.put("ruleFallback",displayed.contains("当前展示的是本地规则建议"));
            row.put("rawPreserved",displayed.equals(raw)); row.put("score",evaluation.getScore());
            rows.add(row);
        }
        var output = new LinkedHashMap<String,Object>();
        output.put("scope","Offline replay of saved model answers through compiled service with mocked client; no new model quality measurement");
        output.put("source",source.toString()); output.put("providerCalls",0); output.put("providerTokens",0);
        output.put("foodCases",rows.size()); output.put("nonfoodSkipped",nonfood); output.put("sameInputScoreDifferences",scoreDifferences);
        output.put("ruleFallbackCases",rows.stream().filter(row -> (Boolean)row.get("ruleFallback")).map(row -> row.get("id")).toList());
        output.put("filteredCases",rows.stream().filter(row -> !(Boolean)row.get("ruleFallback") && !(Boolean)row.get("rawPreserved")).map(row -> row.get("id")).toList());
        output.put("cases",rows);
        Files.writeString(destination,mapper.writerWithDefaultPrettyPrinter().writeValueAsString(output));
        System.out.println(mapper.writeValueAsString(output.entrySet().stream().filter(entry -> !entry.getKey().equals("cases")).collect(java.util.stream.Collectors.toMap(Map.Entry::getKey,Map.Entry::getValue))));
        if (scoreDifferences != 0) throw new IllegalStateException("Same-input scoring changed");
    }
}
