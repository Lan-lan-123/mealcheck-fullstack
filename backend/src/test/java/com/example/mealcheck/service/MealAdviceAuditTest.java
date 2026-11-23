package com.example.mealcheck.service;

import com.example.mealcheck.dto.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.nio.file.*;
import java.time.Duration;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

class MealAdviceAuditTest {
    final ObjectMapper mapper=new ObjectMapper();
    Path directory() throws Exception {
        Path logs=Path.of("../logs/backend").toAbsolutePath().normalize();Files.createDirectories(logs);
        return Files.createTempDirectory(logs,"advice-audit-test-");
    }
    MealAdviceConsistency.Assessment assessment(String text) {
        var r=new RecognitionResult();r.setFoods(List.of(new FoodItem("番茄炒蛋","protein",.9,"炒制",new FoodAttributes(false,null))));
        return MealAdviceConsistency.assess(text,r);
    }
    @Test void defaultAndUnapprovedContextsNeverWriteCandidateText() throws Exception {
        Path dir=directory();String text="番茄炒蛋存在高油风险。";
        var disabled=new MealAdviceAudit(mapper,false,"",dir.toString(),10);
        var enabled=new MealAdviceAudit(mapper,true,"7",dir.toString(),10);
        try(var context=AiCallContext.open(7L,Duration.ofSeconds(5))) {disabled.record(text,assessment(text),false);}
        enabled.record(text,assessment(text),false);
        try(var context=AiCallContext.open(8L,Duration.ofSeconds(5))) {enabled.record(text,assessment(text),false);}
        try(var files=Files.list(dir)) {assertThat(files.count()).isZero();}
    }
    @Test void authorizedEvidenceIsBoundedRedactedAndKeepsTheTriggerAttributes() throws Exception {
        Path dir=directory();var audit=new MealAdviceAudit(mapper,true,"7",dir.toString(),1);
        String text="番茄炒蛋存在高油风险 sk-fakecredentialfortestonly。";
        try(var context=AiCallContext.open(7L,Duration.ofSeconds(5))) {
            audit.record(text,assessment(text),false);audit.record(text,assessment(text),false);
        }
        try(var files=Files.list(dir)) {
            var paths=files.toList();assertThat(paths).hasSize(1);
            String raw=Files.readString(paths.get(0));assertThat(raw).doesNotContain("sk-fakecredentialfortestonly").contains("[REDACTED]");
            var json=mapper.readTree(raw);assertThat(json.path("disposition").asText()).isEqualTo("RULE_FALLBACK");
            assertThat(json.path("findings").get(0).path("knownValue").isNull()).isTrue();
            assertThat(json.path("findings").get(0).path("food").asText()).isEqualTo("番茄炒蛋");
            assertThat(json.path("findings").get(0).path("attribute").asText()).isEqualTo("HIGH_OIL");
            assertThat(json.has("candidate") || json.has("prompt") || json.has("image")).isFalse();
        }
    }
    @Test void requiresExplicitFixtureUsersAndLogsLocationAndWriteFailureIsNonFatal() throws Exception {
        Path dir=directory();
        assertThatThrownBy(()->new MealAdviceAudit(mapper,true,"",dir.toString(),10)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->new MealAdviceAudit(mapper,true,"-1",dir.toString(),10)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->new MealAdviceAudit(mapper,true,"7",Path.of("../audit-outside-logs").toAbsolutePath().normalize().toString(),10)).isInstanceOf(IllegalArgumentException.class);
        Path file=dir.resolve("occupied");Files.writeString(file,"file");
        var audit=new MealAdviceAudit(mapper,true,"7",file.resolve("child").toString(),10);
        try(var context=AiCallContext.open(7L,Duration.ofSeconds(5))) {
            assertThatCode(()->audit.record("番茄炒蛋存在高油风险。",assessment("番茄炒蛋存在高油风险。"),false)).doesNotThrowAnyException();
        }
    }
}
