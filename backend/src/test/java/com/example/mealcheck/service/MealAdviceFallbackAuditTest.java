package com.example.mealcheck.service;

import com.example.mealcheck.dto.RecognitionResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import java.nio.file.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

/** Optional local audit. Constructed probes are never presented as recovered provider output. */
@EnabledIfSystemProperty(named="mealcheck.adviceAuditInput",matches=".+")
class MealAdviceFallbackAuditTest {
    record Probe(String text,boolean shouldAccept,String rationale) { }

    @Test void auditCapturedAttributesAndRuleBoundariesWithoutProviderCalls() throws Exception {
        var json=new ObjectMapper();
        Path root=Path.of("..").toAbsolutePath().normalize();
        Path input=Path.of(System.getProperty("mealcheck.adviceAuditInput")).toAbsolutePath().normalize();
        Path output=Path.of(System.getProperty("mealcheck.adviceAuditOutput")).toAbsolutePath().normalize();
        assertThat(input.startsWith(root.resolve("logs"))).isTrue();assertThat(output.startsWith(root.resolve("logs"))).isTrue();
        byte[] bytes=Files.readAllBytes(input);var source=json.readTree(bytes);
        var report=json.createObjectNode().put("scope","Local rule audit; rejected model candidates were not captured. Constructed probes demonstrate possible rule errors, not the causes of each historical fallback.");
        report.put("sourceSha256",java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes)));
        var captured=report.putArray("capturedImages");RecognitionResult fixture=null;
        int fallback=0,accepted=0,acceptedReplay=0,unknownOil=0;
        for(var row:source.path("cases")) {
            if(!"upload".equals(row.path("kind").asText()))continue;
            var result=row.path("response").path("result");var recognition=json.treeToValue(result.path("recognition"),RecognitionResult.class);
            if(fixture==null)fixture=recognition;
            boolean usedFallback=row.path("usedRuleFallback").asBoolean();
            if(usedFallback)fallback++;else {accepted++;if(MealAdviceConsistency.consistent(result.path("advice").asText(),recognition))acceptedReplay++;}
            var item=captured.addObject().put("ordinal",row.path("ordinal").asInt()).put("id",row.path("id").asText()).put("phase",row.path("phase").asText());
            item.put("fallback",usedFallback).put("rejectedCandidateAvailable",false);
            item.set("riskTags",result.path("evaluation").path("riskTags"));
            var facts=item.putArray("foods");
            for(var food:recognition.getFoods()) {
                var fact=facts.addObject().put("name",food.getName());
                Boolean oil=MealAdviceRiskPolicy.value(food,MealAdviceRiskPolicy.Risk.HIGH_OIL);
                if(oil==null){fact.putNull("highOil");unknownOil++;}else fact.put("highOil",oil);
                fact.put("oilReviewEligible",MealAdviceRiskPolicy.shouldReview(food,MealAdviceRiskPolicy.Risk.HIGH_OIL));
            }
        }
        assertThat(fixture).isNotNull();
        assertThat(MealAdviceConsistency.consistent("番茄炒蛋存在高油风险。",fixture)).isFalse();
        assertThat(MealAdviceConsistency.consistent("番茄炒蛋的高油风险未知。",fixture)).isTrue();
        assertThat(acceptedReplay).isEqualTo(accepted);
        var probes=List.of(
            new Probe("番茄炒蛋的高油风险未知。",true,"明确保留未知状态，作为对照"),
            new Probe("番茄炒蛋的高油风险属性目前未知。",true,"目前只是时间限定，不是确定高油"),
            new Probe("番茄炒蛋是否高油目前无法确定。",true,"是否与无法确定保持不确定性"),
            new Probe("番茄炒蛋的高油风险尚不清楚。",true,"尚不清楚保持不确定性"),
            new Probe("建议核对番茄炒蛋的高油风险。",true,"炒制线索允许核对，核对请求不是已确认事实"),
            new Probe("建议核对番茄炒蛋是否高油。",true,"相同核对意图的是否表达，作为对照"),
            new Probe("本餐的高油风险属性目前未知。",true,"整餐的不确定性表述也不应判为确定高油"),
            new Probe("本餐的高油风险未知。",true,"整餐明确保留未知，作为对照"),
            new Probe("番茄炒蛋存在高油风险。",false,"未知属性不能写成确定存在，作为拦截对照"),
            new Probe("番茄炒蛋没有高油风险。",false,"未知同样不能直接写成确定不存在"));
        var observations=report.putArray("constructedProbes");int falseRejects=0,falseAccepts=0;
        for(var probe:probes) {
            String cleaned=MealAdviceConsistency.removeUnnecessaryReviews(probe.text(),fixture);
            boolean actual=MealAdviceConsistency.consistent(cleaned,fixture);
            var item=observations.addObject().put("text",probe.text()).put("rationale",probe.rationale()).put("expectedAccept",probe.shouldAccept()).put("actualAccept",actual);
            item.set("reasons",json.valueToTree(MealAdviceConsistency.assess(probe.text(),fixture).reasons()));
            if(probe.shouldAccept()&&!actual)falseRejects++;if(!probe.shouldAccept()&&actual)falseAccepts++;
        }
        report.put("completedImages",captured.size()).put("historicalFallbacks",fallback).put("acceptedCandidatesAvailable",accepted)
                .put("acceptedCandidatesReplayPass",acceptedReplay).put("unknownOilAttributes",unknownOil)
                .put("constructedFalseRejects",falseRejects).put("constructedFalseAccepts",falseAccepts).put("liveModelCalls",0);
        Files.createDirectories(output.getParent());json.writerWithDefaultPrettyPrinter().writeValue(output.toFile(),report);
        assertThat(falseRejects).as("Constructed legitimate uncertainty/review probes").isZero();
        assertThat(falseAccepts).as("Constructed unsupported certainty probes").isZero();
    }
}
