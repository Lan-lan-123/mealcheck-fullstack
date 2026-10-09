package com.example.mealcheck.service;

import com.example.mealcheck.dto.AssistantBenchmarkResponse;
import com.example.mealcheck.dto.AssistantEvaluationReviewRequest;
import com.example.mealcheck.dto.AssistantEvaluationReviewResponse;
import com.example.mealcheck.entity.AssistantEvaluationCaseReview;
import com.example.mealcheck.repository.AssistantEvaluationCaseReviewRepository;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AssistantEvaluationReviewServiceTest {

    @Test
    void persistsHumanReviewAndBuildsCalibrationReport() {
        AssistantBenchmarkHistoryService historyService = mock(AssistantBenchmarkHistoryService.class);
        AssistantEvaluationCaseReviewRepository repository =
                mock(AssistantEvaluationCaseReviewRepository.class);
        AssistantEvaluationReviewService service = new AssistantEvaluationReviewService(
                historyService, repository);
        AtomicReference<AssistantEvaluationCaseReview> stored = new AtomicReference<>();

        when(historyService.detail("run-1")).thenReturn(sampleRun());
        when(repository.findByRunIdAndCaseId("run-1", "high-pass"))
                .thenReturn(Optional.empty());
        doAnswer(invocation -> {
            AssistantEvaluationCaseReview review = invocation.getArgument(0);
            stored.set(review);
            return review;
        }).when(repository).save(any(AssistantEvaluationCaseReview.class));
        when(repository.findByRunIdOrderByUpdatedAtDesc("run-1"))
                .thenAnswer(invocation -> List.of(stored.get()));

        AssistantEvaluationReviewResponse response = service.review(
                "run-1", "high-pass",
                new AssistantEvaluationReviewRequest("FAILED", " 自动回答忽略了风险提示 "),
                "admin");

        verify(repository).save(any(AssistantEvaluationCaseReview.class));
        assertThat(stored.get().getNotes()).isEqualTo("自动回答忽略了风险提示");
        assertThat(stored.get().getReviewer()).isEqualTo("admin");
        assertThat(response.requiredReviewCount()).isEqualTo(2);
        assertThat(response.reviewedCount()).isEqualTo(1);
        assertThat(response.reviewedRequiredCount()).isEqualTo(1);
        assertThat(response.pendingRequiredCount()).isEqualTo(1);
        assertThat(response.agreementCount()).isZero();
        assertThat(response.falsePassCount()).isEqualTo(1);
        assertThat(response.falseFailCount()).isZero();
        assertThat(response.agreementRate()).isZero();
    }

    @Test
    void reportsAgreementAndFalseFailuresWithoutChangingAutomaticResult() {
        AssistantBenchmarkHistoryService historyService = mock(AssistantBenchmarkHistoryService.class);
        AssistantEvaluationCaseReviewRepository repository =
                mock(AssistantEvaluationCaseReviewRepository.class);
        AssistantEvaluationReviewService service = new AssistantEvaluationReviewService(
                historyService, repository);
        when(historyService.detail("run-1")).thenReturn(sampleRun());
        when(repository.findByRunIdOrderByUpdatedAtDesc("run-1"))
                .thenReturn(List.of(
                        review("run-1", "high-pass", "PASSED"),
                        review("run-1", "low-fail", "PASSED")));

        AssistantEvaluationReviewResponse response = service.report("run-1");

        assertThat(response.agreementCount()).isEqualTo(1);
        assertThat(response.disagreementCount()).isEqualTo(1);
        assertThat(response.falsePassCount()).isZero();
        assertThat(response.falseFailCount()).isEqualTo(1);
        assertThat(response.agreementRate()).isEqualTo(0.5);
        assertThat(response.reviews()).extracting(
                AssistantEvaluationReviewResponse.CaseReview::automaticStatus)
                .containsExactly("PASSED", "FAILED");
    }

    @Test
    void rejectsUnknownCase() {
        AssistantBenchmarkHistoryService historyService = mock(AssistantBenchmarkHistoryService.class);
        AssistantEvaluationCaseReviewRepository repository =
                mock(AssistantEvaluationCaseReviewRepository.class);
        when(historyService.detail("run-1")).thenReturn(sampleRun());
        AssistantEvaluationReviewService service = new AssistantEvaluationReviewService(
                historyService, repository);

        assertThatThrownBy(() -> service.review(
                "run-1", "missing", new AssistantEvaluationReviewRequest("PASSED", null), "admin"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("不存在指定评测用例");
    }

    private AssistantEvaluationCaseReview review(String runId, String caseId, String verdict) {
        AssistantEvaluationCaseReview review = new AssistantEvaluationCaseReview();
        review.setRunId(runId);
        review.setCaseId(caseId);
        review.setVerdict(verdict);
        review.setReviewer("reviewer");
        review.setUpdatedAt(LocalDateTime.of(2026, 9, 26, 10, 0));
        return review;
    }

    private AssistantBenchmarkResponse sampleRun() {
        LocalDateTime now = LocalDateTime.of(2026, 9, 26, 9, 0);
        List<AssistantBenchmarkResponse.CaseResult> cases = List.of(
                caseResult("high-pass", "HIGH", "PASSED", true),
                caseResult("low-fail", "LOW", "FAILED", false),
                caseResult("low-pass", "LOW", "PASSED", true));
        return new AssistantBenchmarkResponse(
                "run-1", "COMPLETED", "QUICK", "NEEDS_REVIEW",
                3, 3, 3, 2, 1, 0, 0,
                3, 3, 3,
                2.0 / 3.0, 1.0, 2.0 / 3.0, 1.0, 1.0, 1.0,
                2.0 / 3.0, 3.5, 3.5,
                300L, "generation", "judge", true,
                "1.0+2.0", "fingerprint-1",
                "完成", now, now.plusSeconds(1), List.of(), List.of(), cases);
    }

    private AssistantBenchmarkResponse.CaseResult caseResult(
            String id, String risk, String status, boolean passed) {
        return new AssistantBenchmarkResponse.CaseResult(
                id, "safety", risk, "问题", status, passed,
                passed ? 4.0 : 2.0, Map.of("correctness", passed ? 4 : 2),
                List.of(), "原因", "回答", "PRODUCTION_FUNCTION_CALLING",
                "safety-boundary", "safety-boundary", List.of("safety-boundary"),
                true, true, List.of(), List.of(), List.of(), List.of(),
                0, false, List.of(), 100L);
    }
}
