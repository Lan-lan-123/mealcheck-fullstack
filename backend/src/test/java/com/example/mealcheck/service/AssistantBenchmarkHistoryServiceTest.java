package com.example.mealcheck.service;

import com.example.mealcheck.dto.AssistantBenchmarkResponse;
import com.example.mealcheck.dto.AssistantBenchmarkComparisonResponse;
import com.example.mealcheck.entity.AssistantEvaluationRun;
import com.example.mealcheck.repository.AssistantEvaluationRunRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AssistantBenchmarkHistoryServiceTest {

    @Test
    void persistsAndLoadsFullResultDetail() {
        AssistantEvaluationRunRepository repository = mock(AssistantEvaluationRunRepository.class);
        ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        AssistantBenchmarkHistoryService service = new AssistantBenchmarkHistoryService(repository, objectMapper);
        AssistantBenchmarkResponse response = sampleResponse();

        when(repository.findByRunId("run-1")).thenReturn(Optional.empty());
        service.saveSnapshot(response, "admin");

        ArgumentCaptor<AssistantEvaluationRun> captor = ArgumentCaptor.forClass(AssistantEvaluationRun.class);
        verify(repository).save(captor.capture());
        AssistantEvaluationRun saved = captor.getValue();
        assertThat(saved.getMode()).isEqualTo("QUICK");
        assertThat(saved.getStartedBy()).isEqualTo("admin");
        assertThat(saved.isIndependentJudge()).isTrue();
        assertThat(saved.getVerdict()).isEqualTo("PASSED");
        assertThat(saved.getToolTracePassedCount()).isEqualTo(1);
        assertThat(saved.getToolTracePassRate()).isEqualTo(1.0);
        assertThat(saved.getIntentEvaluatedCount()).isEqualTo(1);
        assertThat(saved.getIntentMatchedCount()).isEqualTo(1);
        assertThat(saved.getIntentAccuracy()).isEqualTo(1.0);
        assertThat(saved.getIntentMacroF1()).isEqualTo(1.0);
        assertThat(saved.getDatasetVersion()).isEqualTo("1.0+2.0");
        assertThat(saved.getDatasetFingerprint()).isEqualTo("fingerprint-1");
        assertThat(saved.getResultJson()).contains("case-1", "judge-model");

        when(repository.findByRunId("run-1")).thenReturn(Optional.of(saved));
        AssistantBenchmarkResponse loaded = service.detail("run-1");
        assertThat(loaded.status()).isEqualTo("COMPLETED");
        assertThat(loaded.cases()).hasSize(1);
        assertThat(loaded.cases().get(0).answer()).isEqualTo("测试回答");
        assertThat(loaded.toolTracePassRate()).isEqualTo(1.0);
        assertThat(loaded.intentAccuracy()).isEqualTo(1.0);
        assertThat(loaded.intents()).singleElement()
                .extracting(AssistantBenchmarkResponse.IntentResult::skill)
                .isEqualTo("safety-boundary");
    }

    @Test
    void comparesRunsAndIdentifiesRegressions() {
        AssistantEvaluationRunRepository repository = mock(AssistantEvaluationRunRepository.class);
        AssistantBenchmarkHistoryService service = spy(new AssistantBenchmarkHistoryService(
                repository, new ObjectMapper().registerModule(new JavaTimeModule())));
        doReturn(comparisonResponse("base", true, 1.0, "fingerprint-1"))
                .when(service).detail("base");
        doReturn(comparisonResponse("candidate", false, 0.0, "fingerprint-1"))
                .when(service).detail("candidate");

        AssistantBenchmarkComparisonResponse comparison = service.compare("base", "candidate");

        assertThat(comparison.directlyComparable()).isTrue();
        assertThat(comparison.passRateDelta()).isEqualTo(-1.0);
        assertThat(comparison.regressedCaseCount()).isEqualTo(1);
        assertThat(comparison.fixedCaseCount()).isZero();
        assertThat(comparison.caseChanges()).singleElement().satisfies(change -> {
            assertThat(change.caseId()).isEqualTo("case-1");
            assertThat(change.changeType()).isEqualTo("REGRESSED");
        });
    }

    private AssistantBenchmarkResponse sampleResponse() {
        LocalDateTime startedAt = LocalDateTime.of(2026, 9, 17, 10, 0);
        AssistantBenchmarkResponse.CaseResult caseResult = new AssistantBenchmarkResponse.CaseResult(
                "case-1", "safety", "HIGH", "测试问题", "PASSED", true,
                4.0, Map.of("correctness", 4), List.of(), "符合要求", "测试回答",
                "PRODUCTION_FUNCTION_CALLING", "safety-boundary",
                "safety-boundary", List.of("safety-boundary"), true, true,
                List.of(), List.of(), List.of(), List.of(), 0, false, List.of(), 120L);
        return new AssistantBenchmarkResponse(
                "run-1", "COMPLETED", "QUICK", "PASSED",
                10, 1, 1, 1, 0, 0, 0,
                1, 1, 1,
                1.0, 0.1, 0.1, 1.0, 1.0, 1.0, 1.0, 4.0, 0.4,
                120L, "generation-model", "judge-model", true,
                "1.0+2.0", "fingerprint-1",
                "评测已完成", startedAt, startedAt.plusSeconds(1),
                List.of(new AssistantBenchmarkResponse.IntentResult(
                        "safety-boundary", 1, 1, 1, 1.0, 1.0, 1.0)),
                List.of(new AssistantBenchmarkResponse.CategoryResult(
                        "safety", 1, 1, 1, 0, 0, 1.0, 4.0)),
                List.of(caseResult));
    }

    private AssistantBenchmarkResponse comparisonResponse(
            String runId, boolean passed, double passRate, String fingerprint) {
        LocalDateTime startedAt = LocalDateTime.of(2026, 9, 17, 10, 0);
        AssistantBenchmarkResponse.CaseResult caseResult = new AssistantBenchmarkResponse.CaseResult(
                "case-1", "safety", "HIGH", "测试问题",
                passed ? "PASSED" : "FAILED", passed,
                passed ? 4.0 : 2.0, Map.of("correctness", passed ? 4 : 2),
                List.of(), "原因", "回答", "PRODUCTION_FUNCTION_CALLING",
                "safety-boundary", "safety-boundary", List.of("safety-boundary"),
                true, true, List.of(), List.of(), List.of(), List.of(),
                0, false, List.of(), 100L);
        return new AssistantBenchmarkResponse(
                runId, "COMPLETED", "QUICK", passed ? "PASSED" : "BLOCKED",
                1, 1, 1, passed ? 1 : 0, passed ? 0 : 1, 0, 0,
                1, 1, 1,
                passRate, 1.0, passRate, 1.0, 1.0, 1.0, passRate,
                passed ? 4.0 : 2.0, passed ? 4.0 : 2.0,
                100L, "generation-model", "judge-model", true,
                "1.0+2.0", fingerprint, "完成", startedAt, startedAt.plusSeconds(1),
                List.of(), List.of(new AssistantBenchmarkResponse.CategoryResult(
                "safety", 1, 1, passed ? 1 : 0, passed ? 0 : 1, 0,
                passRate, passed ? 4.0 : 2.0)), List.of(caseResult));
    }
}
