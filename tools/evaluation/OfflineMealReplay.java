package com.example.mealcheck.service;

import com.example.mealcheck.dto.*;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** No API configuration or transport is created. Replays saved recognition and captures prompts only. */
public final class OfflineMealReplay {
    public static void main(String[] args) throws Exception {
        if (args.length != 3) throw new IllegalArgumentException("Usage: baseline-results.json output.json audited-dataset.json");
        Path source = Path.of(args[0]).toAbsolutePath().normalize();
        Path output = Path.of(args[1]).toAbsolutePath().normalize();
        Path labels = Path.of(args[2]).toAbsolutePath().normalize();
        if (output.equals(source) || Files.exists(output)) throw new IllegalArgumentException("Preserve existing evidence; choose a new output file");
        var mapper = new ObjectMapper().findAndRegisterModules();
        var baseline = mapper.readTree(source.toFile());
        var dataset = mapper.readTree(labels.toFile());
        if (!"FINISHED".equals(baseline.path("state").asText()) || baseline.path("cases").size() != 100)
            throw new IllegalArgumentException("Expected the completed 100-image baseline");
        Map<String,JsonNode> reviewed = new HashMap<>();
        for (var sample : dataset.path("cases"))
            if (reviewed.put(sample.path("id").asText(), sample) != null) throw new IllegalArgumentException("Duplicate label ID");
        List<KnowledgeSnippet> snippets = mapper.readValue(source.resolveSibling("evidence-snippets.json").toFile(), new TypeReference<>() {});
        var scoring = new MealScoringService();
        var client = mock(AiChatClient.class);
        when(client.isConfigured()).thenReturn(true);
        List<Map<String,Object>> prompts = new ArrayList<>();
        when(client.complete(eq("meal-advice"), anyList(), eq(.2))).thenAnswer(invocation -> {
            prompts.add(Map.of("operation", "meal-advice", "messages", invocation.getArgument(1)));
            return "OFFLINE_PROMPT_CAPTURE_ONLY";
        });
        var advice = new AdviceGenerationService(client);
        List<Map<String,Object>> rows = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        int foods = 0, nonfoods = 0, riskChanges = 0, scoreChanges = 0;
        Map<String,Boolean> checks = new LinkedHashMap<>();
        for (var previous : baseline.path("cases")) {
            String id = previous.path("id").asText();
            if (!ids.add(id) || !reviewed.containsKey(id)) throw new IllegalArgumentException("Missing/duplicate reviewed case");
            JsonNode revised = reviewed.get(id);
            if (!previous.path("expected").path("sha256").equals(revised.path("sha256")))
                throw new IllegalArgumentException("Labels must describe the exact baseline image");
            var row = new LinkedHashMap<String,Object>(); row.put("id", id);
            var recognition = mapper.treeToValue(previous.path("recognition"), RecognitionResult.class);
            if (!recognition.isFoodImage()) {
                row.put("status", "SKIPPED_NON_FOOD"); nonfoods++; rows.add(row); continue;
            }
            recognition.setUncertainFoods(MealScoringService.uncertainFoods(recognition.getFoods()));
            recognition.setFoods(scoring.acceptedFoods(recognition));
            var evaluation = scoring.evaluate(recognition, baseline.path("goal").asText("balanced"));
            var expectedRecognition = new RecognitionResult();
            expectedRecognition.setFoods(mapper.convertValue(revised.path("foods"), new TypeReference<List<FoodItem>>() {}));
            expectedRecognition.getFoods().forEach(f -> f.setConfidence(1));
            row.put("status", "REPLAYED"); row.put("before", previous.path("evaluation"));
            row.put("after", evaluation); row.put("auditedLabelEvaluation", scoring.evaluate(expectedRecognition, "balanced"));
            if (previous.path("evaluation").path("score").asInt() != evaluation.getScore()) scoreChanges++;
            if (!previous.path("evaluation").path("riskTags").equals(mapper.valueToTree(evaluation.getRiskTags()))) riskChanges++;
            if (!"OFFLINE_PROMPT_CAPTURE_ONLY".equals(advice.generate(recognition, evaluation, snippets, "balanced")))
                throw new IllegalStateException("Prompt capture unexpectedly fell back");
            row.put("advicePrompt", prompts.get(prompts.size() - 1));
            row.put("newAdviceGenerated", false); rows.add(row); foods++;
        }
        verify(client, times(80)).complete(eq("meal-advice"), anyList(), eq(.2));
        checks.put("all100CasesAccountedFor", rows.size() == 100 && foods == 80 && nonfoods == 20);
        checks.put("tenContradictoryStructureTagsRemoved", rows.stream().filter(r -> {
            String id = r.get("id").toString(); int n = Integer.parseInt(id.substring(6));
            return (n >= 56 && n <= 60) || (n >= 76 && n <= 80);
        }).allMatch(r -> !((StructureEvaluation)r.get("after")).getRiskTags().stream().anyMatch(t -> t.contains("结构虽完整"))));
        checks.put("fiveOrangeJuicesNoLongerUnknown", rows.stream().filter(r -> r.get("id").toString().compareTo("synth-076") >= 0
                && r.get("id").toString().compareTo("synth-080") <= 0).allMatch(r -> {
                    var tags = ((StructureEvaluation)r.get("after")).getRiskTags();
                    return tags.contains("含糖饮料/甜品风险") && tags.stream().noneMatch(t -> t.contains("饮品类型不够明确"));
                }));
        checks.put("all80PromptsCapturedWithoutProvider", prompts.size() == 80);
        var result = new LinkedHashMap<String,Object>();
        result.put("createdAtUtc", Instant.now().toString()); result.put("mode", "offline-saved-recognition-and-prompt-capture");
        result.put("baselineSha256", HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(source))));
        result.put("baselineScoringVersion", baseline.path("scoringVersion").asText());
        result.put("currentScoringVersion", MealScoringService.VERSION); result.put("auditedLabelVersion", dataset.path("version").asText());
        result.put("realModelCalls", 0); result.put("newProviderTokens", 0); result.put("adviceOutputQualityRetested", false);
        result.put("foodReplays", foods); result.put("nonfoodSkipped", nonfoods);
        result.put("numericScoreChanges", scoreChanges); result.put("riskTagChanges", riskChanges);
        result.put("checks", checks); result.put("cases", rows);
        Files.createDirectories(output.getParent()); mapper.writerWithDefaultPrettyPrinter().writeValue(output.toFile(), result);
        System.out.println("REPLAY foods=" + foods + " nonfoods=" + nonfoods + " changedScores=" + scoreChanges + " changedRiskTags=" + riskChanges + " checks=" + checks);
        if (checks.values().stream().anyMatch(ok -> !ok)) throw new IllegalStateException("Replay verification failed; inspect evidence");
    }
}
