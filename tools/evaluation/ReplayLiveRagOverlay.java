package com.example.mealcheck.service;

import com.example.mealcheck.dto.RagChainReport;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.util.*;

/** Pure assessment of saved retrieval records. No application context or IO clients. */
public final class ReplayLiveRagOverlay {
    private static final ObjectMapper JSON = new ObjectMapper();
    public static void main(String[] args) throws Exception {
        if (args.length == 1 && "--self-test".equals(args[0])) { selfTest(); return; }
        if (args.length != 5) throw new IllegalArgumentException("Expected baseline, old/new datasets, manifest, fresh output");
        Path output = Path.of(args[4]).toAbsolutePath().normalize();
        if (!output.startsWith(Path.of("logs").toAbsolutePath().normalize()) || Files.exists(output))
            throw new IllegalArgumentException("Preserve original evidence; use a fresh output under logs");
        var baseline = JSON.readTree(Path.of(args[0]).toFile());
        Map<String,JsonNode> oldRows = rows(JSON.readTree(Path.of(args[1]).toFile()));
        Map<String,JsonNode> newRows = rows(JSON.readTree(Path.of(args[2]).toFile()));
        var manifest = JSON.readTree(Path.of(args[3]).toFile());
        var chunks = new ArrayList<PgVectorKnowledgeService.KnowledgeChunk>();
        for (var chunk : manifest.path("chunks")) chunks.add(new PgVectorKnowledgeService.KnowledgeChunk(
            chunk.path("chunkKey").asText(), chunk.path("title").asText(), chunk.path("content").asText(),
            chunk.path("chunkIndex").asInt(), chunk.path("tokenCount").asInt(), chunk.path("contentHash").asText()));
        for (var row : newRows.values()) RagEvidenceLabels.validate(row, chunks);
        var results = new ArrayList<Map<String,Object>>();
        for (var captured : baseline.path("results")) {
            String id = captured.path("caseId").asText();
            var old = oldRows.get(id); var revised = newRows.get(id);
            if (old == null || revised == null) throw new IllegalArgumentException("Unknown captured case");
            var retrievals = new ArrayList<RagChainReport.Retrieval>();
            if (captured.has("capturedRetrieval")) retrievals.add(JSON.treeToValue(captured.get("capturedRetrieval"), RagChainReport.Retrieval.class));
            var before = RagChainAssessment.assess(old, retrievals);
            if (!JSON.readTree(JSON.writeValueAsBytes(before)).equals(captured.path("assessment")))
                throw new IllegalArgumentException("Baseline assessment cannot be reproduced: " + id);
            var row = new LinkedHashMap<String,Object>(); row.put("caseId",id);
            row.put("baselineAssessment",before); row.put("afterAssessment",RagChainAssessment.assess(revised,retrievals));
            if (captured.has("rawTop5Assessment")) {
                var raw = new ArrayList<RagChainReport.Retrieval>();
                for (var saved : captured.path("rawTop5Assessment").path("retrievals"))
                    raw.add(JSON.treeToValue(saved,RagChainReport.Retrieval.class));
                var oldRaw = RagChainAssessment.assess(old,raw);
                if (!JSON.readTree(JSON.writeValueAsBytes(oldRaw)).equals(captured.path("rawTop5Assessment")))
                    throw new IllegalArgumentException("Raw baseline cannot be reproduced: " + id);
                row.put("baselineRawTop5Assessment",oldRaw);
                row.put("afterRawTop5Assessment",RagChainAssessment.assess(revised,raw));
            }
            results.add(row);
        }
        var result = new LinkedHashMap<String,Object>();
        result.put("status","OFFLINE_CAPTURED_RETRIEVAL_LABEL_REPLAY");result.put("scoringVersion",RagChainAssessment.VERSION);
        result.put("providerCalls",0);result.put("retrievalRepeated",false);result.put("independentlyAnnotated",false);
        result.put("results",results);
        Files.writeString(output,JSON.writerWithDefaultPrettyPrinter().writeValueAsString(result)+"\n",StandardOpenOption.CREATE_NEW);
    }
    private static Map<String,JsonNode> rows(JsonNode rows) {
        var result = new LinkedHashMap<String,JsonNode>();
        for (var row : rows) if (result.put(row.path("id").asText(),row)!=null)
            throw new IllegalArgumentException("Duplicate dataset IDs");
        return result;
    }
    private static void selfTest() throws Exception {
        String title="Synthetic replay gate", oldBody="Original direct evidence.", newBody="New retained evidence.";
        String oldId=RagEvidenceLabels.contentId(title,oldBody), newId=RagEvidenceLabels.contentId(title,newBody);
        var expected=JSON.createObjectNode();expected.put("id","rag-001");expected.put("expectNoAnswer",false);
        expected.put("answerability","ANSWERABLE");expected.put("annotationRevision","synthetic-base");
        expected.set("annotation",JSON.createObjectNode().put("status","PENDING_HUMAN_REVIEW").put("independentlyAnnotated",false));
        var original=JSON.createObjectNode().put("knowledgeId",oldId).put("title",title).put("relevance",2).put("rationale","Synthetic original");
        original.putArray("evidenceQuotes").add(oldBody);expected.putArray("evidenceTargets").add(original);
        expected.putArray("requiredEvidenceGroups").addArray().add(oldId);
        var hit=new RagChainReport.Hit(1L,title,"SYNTHETIC",0.9,newId,newBody);
        var retrieval=new RagChainReport.Retrieval("saved","Synthetic query",5,"SUCCESS",7,List.of(hit),null);
        var captured=JSON.treeToValue(JSON.valueToTree(retrieval),RagChainReport.Retrieval.class);
        var before=RagChainAssessment.assess(expected,List.of(captured));
        if(!"UNJUDGED".equals(before.retrievalStatus()) || before.finalEvidenceCovered()) throw new IllegalStateException("Unjudged gate");
        var chunks=List.of(new PgVectorKnowledgeService.KnowledgeChunk("old",title,oldBody,0,3,oldId),
            new PgVectorKnowledgeService.KnowledgeChunk("new",title,newBody,1,3,newId));
        for(int grade=0;grade<=2;grade++) {
            var revised=expected.deepCopy();var target=JSON.createObjectNode().put("knowledgeId",newId).put("title",title)
                .put("relevance",grade).put("rationale","Synthetic explicit review");target.putArray("evidenceQuotes").add(newBody);
            if(grade==0)revised.putArray("irrelevantEvidence").add(target);
            else ((com.fasterxml.jackson.databind.node.ArrayNode)revised.get("evidenceTargets")).add(target);
            RagEvidenceLabels.validate(revised,chunks);
            var ungrouped=RagChainAssessment.assess(revised,List.of(captured));
            if(ungrouped.finalEvidenceCovered() || !"FAILED".equals(ungrouped.retrievalStatus())) throw new IllegalStateException("No implicit coverage gate");
            if(grade==2) {
                ((com.fasterxml.jackson.databind.node.ArrayNode)revised.get("requiredEvidenceGroups").get(0)).add(newId);
                RagEvidenceLabels.validate(revised,chunks);var after=RagChainAssessment.assess(revised,List.of(captured));
                if(!after.finalEvidenceCovered() || !"PASSED".equals(after.retrievalStatus())) throw new IllegalStateException("Explicit alternative gate");
            }
        }
        if(!"UNJUDGED".equals(RagChainAssessment.assess(expected,List.of(captured)).retrievalStatus())) throw new IllegalStateException("Baseline mutated");
        var revised=expected.deepCopy();var target=original.deepCopy().put("knowledgeId",newId);
        target.putArray("evidenceQuotes").add(newBody);((com.fasterxml.jackson.databind.node.ArrayNode)revised.get("evidenceTargets")).add(target);
        ((com.fasterxml.jackson.databind.node.ArrayNode)revised.get("requiredEvidenceGroups").get(0)).add(newId);
        Path gates=Path.of("logs/backend").toAbsolutePath().normalize();Files.createDirectories(gates);
        Path batch=Files.createTempDirectory(gates,"rag-overlay-java-fixture-");
        var savedCase=JSON.createObjectNode().put("caseId","rag-001");savedCase.set("capturedRetrieval",JSON.valueToTree(captured));
        savedCase.set("assessment",JSON.valueToTree(before));
        var baseline=JSON.createObjectNode();baseline.putArray("results").add(savedCase);
        Files.writeString(batch.resolve("baseline.json"),JSON.writeValueAsString(baseline),StandardOpenOption.CREATE_NEW);
        Files.writeString(batch.resolve("old.json"),JSON.writeValueAsString(List.of(expected)),StandardOpenOption.CREATE_NEW);
        Files.writeString(batch.resolve("new.json"),JSON.writeValueAsString(List.of(revised)),StandardOpenOption.CREATE_NEW);
        Files.writeString(batch.resolve("manifest.json"),JSON.writeValueAsString(Map.of("chunks",chunks)),StandardOpenOption.CREATE_NEW);
        main(new String[]{batch.resolve("baseline.json").toString(),batch.resolve("old.json").toString(),
            batch.resolve("new.json").toString(),batch.resolve("manifest.json").toString(),batch.resolve("replay.json").toString()});
        var replay=JSON.readTree(batch.resolve("replay.json").toFile()).path("results").get(0);
        if(!"UNJUDGED".equals(replay.path("baselineAssessment").path("retrievalStatus").asText())
                || !"PASSED".equals(replay.path("afterAssessment").path("retrievalStatus").asText()))
            throw new IllegalStateException("Saved baseline/after replay integration gate");
        System.out.println("PASS: saved DTO roundtrip, immutable baseline, integer/long JSON roundtrip, 0/1/2 labels, explicit groups, file replay integration; providerCalls=0");
    }
}
