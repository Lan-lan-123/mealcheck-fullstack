package com.example.mealcheck.service;

import com.example.mealcheck.config.AppProperties;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import org.springframework.core.io.ClassPathResource;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Adapts the shared RAG questions into a tool-only suite, without duplicating the questions. */
final class RagAgentEvaluationDataset {
    static final int CASE_COUNT = 200;
    static final String VERSION = "rag-200-v7+" + RagToolEvaluationRules.VERSION;
    private RagAgentEvaluationDataset() { }
    record Definition(List<JsonNode> cases,String version,String fingerprint) { }
    static Definition load(ObjectMapper mapper, AppProperties properties) throws Exception {
        byte[] bytes;
        try(var input=new ClassPathResource("knowledge/rag_eval_cases.json").getInputStream()) {bytes=input.readAllBytes();}
        String markdown;
        try(var input=new ClassPathResource("knowledge/diet_guides.md").getInputStream()) {markdown=new String(input.readAllBytes(),StandardCharsets.UTF_8);}
        var chunks=new MarkdownKnowledgeChunker(properties).split(markdown);
        JsonNode source=mapper.readTree(bytes);
        var cases=new ArrayList<JsonNode>();var ids=new HashSet<String>();
        for(var item:source) {
            String id=item.path("id").asText();
            if(!id.matches("rag-\\d{3}") || !ids.add(id) || item.path("question").asText().isBlank())
                throw new IllegalArgumentException("RAG 用例 ID 或问题缺失/重复");
            boolean noAnswer=item.path("expectNoAnswer").asBoolean();
            RagEvidenceLabels.validate(item, chunks);
            ObjectNode expected=item.path("agentEvaluation").deepCopy();
            RagToolEvaluationRules.validate(expected,noAnswer);
            expected.put("toolEvaluationOnly",true);
            expected.put("expectNoAnswer",noAnswer);
            expected.set("expectedTitleKeywords",item.path("expectedTitleKeywords").deepCopy());
            for (String field : List.of("answerability", "annotationRevision", "annotation", "evidenceTargets", "irrelevantEvidence", "requiredEvidenceGroups", "boundaryExpectation"))
                if (item.has(field)) expected.set(field, item.path(field).deepCopy());
            var context=mapper.createObjectNode();
            context.put("ragToolEvaluation",true);
            ArrayNode fixtures=context.putArray("ragKnowledgeFixtures");
            // Tools expose controlled source fixtures only after the agent chooses to call them.
            if(!noAnswer) for(int index=0;index<chunks.size();index++) {
                var chunk=chunks.get(index);boolean matches=false;
                for(var target:item.path("evidenceTargets"))
                    if(target.path("relevance").asInt()==2 && chunk.contentHash().equals(target.path("knowledgeId").asText())) {matches=true;break;}
                if(matches && fixtures.size()<5) fixtures.addObject().put("id",index+1).put("title",chunk.title()).put("content",chunk.content());
            }
            var row=mapper.createObjectNode();
            row.put("id",id);row.put("question",item.path("question").asText());
            row.put("category",noAnswer?"uncertainty_boundary":"factual_grounding");row.put("riskLevel","LOW");
            row.set("context",context);row.set("expected",expected);
            cases.add(row);
        }
        if(cases.size()!=CASE_COUNT) throw new IllegalArgumentException("RAG 工具评测集必须包含 " + CASE_COUNT + " 题");
        var digest=MessageDigest.getInstance("SHA-256");digest.update(bytes);digest.update(markdown.getBytes(StandardCharsets.UTF_8));
        digest.update(RagToolEvaluationRules.VERSION.getBytes(StandardCharsets.UTF_8));
        digest.update(RagChainAssessment.VERSION.getBytes(StandardCharsets.UTF_8));
        return new Definition(List.copyOf(cases),VERSION,HexFormat.of().formatHex(digest.digest()));
    }
}
