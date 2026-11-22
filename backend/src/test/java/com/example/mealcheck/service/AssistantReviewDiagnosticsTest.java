package com.example.mealcheck.service;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.nio.file.*;
import java.time.Duration;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
class AssistantReviewDiagnosticsTest {
    final ObjectMapper json=new ObjectMapper();
    @Test void filesystemRootWorkingDirectoryDoesNotRequireAFileName() {
        Path filesystemRoot=Path.of("").toAbsolutePath().getRoot();
        assertThat(filesystemRoot.getFileName()).isNull();
        assertThat(AssistantReviewDiagnostics.diagnosticRoot(filesystemRoot)).isEqualTo(filesystemRoot.resolve("logs"));
    }
    @Test void operationalSummaryExcludesCandidateReasonAndIssueText() throws Exception {
        var verdict=json.readTree("{\"answersQuestion\":false,\"inputMissing\":[\"PRIVATE_INPUT\"],\"knowledgeMissing\":[],\"claims\":[{\"assertions\":[{\"supported\":false,\"reason\":\"PRIVATE_REASON\",\"checks\":{\"fact\":false,\"degree\":true,\"causality\":true,\"conditions\":false}}]}]}");
        var summary=AssistantReviewDiagnostics.summary("REVIEW",1,verdict,List.of(new AssistantGroundingGuard.Issue("PRIVATE_PATH","SEMANTIC_UNSUPPORTED","PRIVATE_TEXT")));
        assertThat(json.writeValueAsString(summary)).contains("SEMANTIC_UNSUPPORTED","fact","conditions").doesNotContain("PRIVATE");
        assertThat(summary.get("unsupportedAssertions")).isEqualTo(1);assertThat(summary.get("answersQuestion")).isEqualTo("false");
    }
    @Test void fixtureTextRequiresEnabledAndExactAuthorizedUserAndRedactsCredentials() throws Exception {
        Path root=Path.of("..").toAbsolutePath().normalize().resolve("logs");Files.createDirectories(root);
        Path parent=Files.createTempDirectory(root,"review-audit-test-");
        var disabled=new AssistantReviewDiagnostics(json,false,"",parent.toString(),10);
        var enabled=new AssistantReviewDiagnostics(json,true,"123",parent.toString(),10);
        var content=json.readTree("{\"text\":\"sk-fakecredentials Bearer fakecredentials\"}");
        var summary=AssistantReviewDiagnostics.summary("REVIEW",1,null,List.of());
        try(var scope=AiCallContext.open(124L,Duration.ofSeconds(2))){disabled.record(summary,content,null,List.of());enabled.record(summary,content,null,List.of());}
        assertThat(Files.exists(parent.resolve("review"))).isFalse();
        try(var scope=AiCallContext.open(123L,Duration.ofSeconds(2))){enabled.record(summary,content,null,List.of());}
        try(var files=Files.list(parent.resolve("review"))) {
            var paths=files.toList();assertThat(paths).hasSize(1);
            String saved=Files.readString(paths.get(0));assertThat(saved).doesNotContain("sk-fakecredentials","Bearer fakecredentials");assertThat(json.readTree(saved)).isNotNull();
        }
    }
}
