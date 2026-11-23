package com.example.mealcheck.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import java.nio.file.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

/** Optional offline replay. A rule warning is a review candidate, never an accuracy score. */
@EnabledIfSystemProperty(named="mealcheck.capturedReplay",matches=".+")
class AssistantCapturedAnswerReplayTest {
    @Test void replaysCapturedAnswersAndDetectsTheThreeConfirmedDefects() throws Exception {
        var json=new ObjectMapper();
        Path input=Path.of(System.getProperty("mealcheck.capturedReplay")).toAbsolutePath().normalize();
        Path output=Path.of(System.getProperty("mealcheck.capturedReplayOutput")).toAbsolutePath().normalize();
        Path root=Path.of("..").toAbsolutePath().normalize();
        assertThat(input.startsWith(root.resolve("logs"))).isTrue();
        assertThat(output.startsWith(root.resolve("logs"))).isTrue();
        byte[] bytes=Files.readAllBytes(input);
        var report=json.createObjectNode();
        report.put("scope","Offline rule replay against captured final answers and cited contexts; no original internal claim/quote mapping, no new model output, not an accuracy score.");
        report.put("sourceSha256",java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes)));
        var cases=report.putArray("cases");var detected=new HashSet<String>();int referenced=0;
        for(var row:json.readTree(bytes).path("cases")) {
            var response=row.path("response");var sources=new HashMap<Long,String>();var selected=new LinkedHashSet<Long>();
            for(var ref:response.path("references")) {selected.add(ref.path("id").asLong());sources.put(ref.path("id").asLong(),ref.path("content").asText());}
            for(var cache:row.path("retrievalCaches"))for(var source:cache.path("snippets"))
                if(selected.contains(source.path("id").asLong()))sources.put(source.path("id").asLong(),source.path("content").asText());
            String evidence=String.join("\n",sources.values());
            var result=cases.addObject().put("ordinal",row.path("ordinal").asInt()).put("id",row.path("id").asText());
            result.put("referenced",!selected.isEmpty());var warnings=result.putArray("warnings");
            if(selected.isEmpty())continue;
            referenced++;
            var texts=new ArrayList<String>();texts.add(response.path("summary").asText());
            for(var suggestion:response.path("suggestions"))texts.add(suggestion.asText());
            for(String text:texts) {
                String contextGap=AssistantClaimCoverage.contextGap(text,evidence);
                if(contextGap!=null)warnings.addObject().put("code","MISSING_SOURCE_CONDITIONS").put("reason",contextGap);
                for(var assertion:AssistantClaimCoverage.of("replay",text)) {
                    if(assertion.text().strip().startsWith("且") && (assertion.start()==0 || text.substring(0,assertion.start()).stripTrailing().matches("(?s).*[。！？；;]$")))
                        warnings.addObject().put("code","ORPHAN_FRAGMENT").put("text",assertion.text());
                    String gap=AssistantClaimCoverage.strengthGap(assertion.text(),evidence);
                    if(gap!=null)warnings.addObject().put("code","UNSUPPORTED_STRENGTH").put("text",assertion.text()).put("reason",gap);
                }
            }
            if(!warnings.isEmpty())detected.add(row.path("id").asText());
        }
        report.put("capturedReplies",cases.size()).put("referencedReplies",referenced).put("warningCases",detected.size());
        Files.createDirectories(output.getParent());json.writerWithDefaultPrettyPrinter().writeValue(output.toFile(),report);
        assertThat(cases.size()).isEqualTo(73);assertThat(referenced).isEqualTo(70);
        assertThat(detected).contains("rag-076","rag-073","rag-183");
    }
}
