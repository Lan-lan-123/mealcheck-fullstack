package com.example.mealcheck.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Set;

/** Existing mocks follow the versioned production review contract; this is not a quality judge. */
final class GroundingReviewFixture {
    private static final ObjectMapper JSON=new ObjectMapper();
    private GroundingReviewFixture() { }
    static JsonNode verdict(JsonNode answer,Set<String> rejected) {
        boolean hasAnswer=false;
        for(var claim:answer.path("claims"))if("ANSWER".equals(claim.path("kind").asText()) && !rejected.contains(claim.path("id").asText()))hasAnswer=true;
        var result=JSON.createObjectNode().put("answersQuestion",hasAnswer).put("needsMoreEvidence",false).put("searchQuery","");
        result.put("informationStatus","SUFFICIENT").putArray("missingInformation");
        result.putArray("inputMissing");result.putArray("knowledgeMissing");
        var claims=result.putArray("claims");
        for(var claim:answer.path("claims")) {
            boolean supported=!rejected.contains(claim.path("id").asText());
            var assertions=claims.addObject().put("id",claim.path("id").asText()).putArray("assertions");
            for(var assertion:AssistantClaimCoverage.of(claim.path("id").asText(),claim.path("text").asText())) {
                var decision=assertions.addObject().put("id",assertion.id()).put("supported",supported)
                        .put("standalone",assertion.standalone()).put("reason",supported?"OFFLINE_FIXTURE_ONLY":"材料不支持");
                decision.putObject("checks").put("fact",supported).put("degree",supported).put("causality",supported).put("conditions",supported);
                var evidence=decision.putArray("evidenceIds");claim.path("evidence").forEach(e->evidence.add(e.path("id").asText()));
            }
        }
        return result;
    }
}
