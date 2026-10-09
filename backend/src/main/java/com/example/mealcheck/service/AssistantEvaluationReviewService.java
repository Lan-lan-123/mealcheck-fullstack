package com.example.mealcheck.service;

import com.example.mealcheck.util.AppTime;

import com.example.mealcheck.dto.AssistantBenchmarkResponse;
import com.example.mealcheck.dto.AssistantEvaluationReviewRequest;
import com.example.mealcheck.dto.AssistantEvaluationReviewResponse;
import com.example.mealcheck.entity.AssistantEvaluationCaseReview;
import com.example.mealcheck.repository.AssistantEvaluationCaseReviewRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class AssistantEvaluationReviewService {
    private final AssistantBenchmarkHistoryService historyService;
    private final AssistantEvaluationCaseReviewRepository reviewRepository;

    public AssistantEvaluationReviewService(AssistantBenchmarkHistoryService historyService,
                                            AssistantEvaluationCaseReviewRepository reviewRepository) {
        this.historyService = historyService;
        this.reviewRepository = reviewRepository;
    }

    @Transactional
    public AssistantEvaluationReviewResponse review(String runId,
                                                    String caseId,
                                                    AssistantEvaluationReviewRequest request,
                                                    String reviewer) {
        AssistantBenchmarkResponse run = historyService.detail(runId);
        AssistantBenchmarkResponse.CaseResult result = findCase(run, caseId);
        if (java.util.Set.of("PENDING", "RUNNING").contains(run.status())) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "评测仍在运行，暂时不能提交人工复核");
        }
        String verdict = request == null || request.verdict() == null
                ? "" : request.verdict().trim().toUpperCase(Locale.ROOT);
        if (!Set.of("PASSED", "FAILED").contains(verdict)) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "人工复核结论仅支持 PASSED 或 FAILED");
        }
        LocalDateTime now = AppTime.now();
        AssistantEvaluationCaseReview review = reviewRepository
                .findByRunIdAndCaseId(runId, result.id())
                .orElseGet(() -> {
                    AssistantEvaluationCaseReview created = new AssistantEvaluationCaseReview();
                    created.setRunId(runId);
                    created.setCaseId(result.id());
                    created.setCreatedAt(now);
                    return created;
                });
        review.setVerdict(verdict);
        review.setNotes(normalizeNotes(request.notes()));
        review.setReviewer(reviewer == null || reviewer.isBlank() ? "unknown" : reviewer.trim());
        review.setUpdatedAt(now);
        reviewRepository.save(review);
        return buildReport(run, reviewRepository.findByRunIdOrderByUpdatedAtDesc(runId));
    }

    @Transactional(readOnly = true)
    public AssistantEvaluationReviewResponse report(String runId) {
        AssistantBenchmarkResponse run = historyService.detail(runId);
        return buildReport(run, reviewRepository.findByRunIdOrderByUpdatedAtDesc(runId));
    }

    private AssistantEvaluationReviewResponse buildReport(
            AssistantBenchmarkResponse run,
            List<AssistantEvaluationCaseReview> storedReviews) {
        Map<String, AssistantBenchmarkResponse.CaseResult> casesById = run.cases().stream()
                .collect(Collectors.toMap(AssistantBenchmarkResponse.CaseResult::id, item -> item));
        Set<String> requiredIds = run.cases().stream()
                .filter(this::requiresReview)
                .map(AssistantBenchmarkResponse.CaseResult::id)
                .collect(Collectors.toSet());
        List<AssistantEvaluationReviewResponse.CaseReview> reviews = storedReviews.stream()
                .filter(item -> casesById.containsKey(item.getCaseId()))
                .map(item -> toResponse(item, casesById.get(item.getCaseId()),
                        requiredIds.contains(item.getCaseId())))
                .toList();

        int agreementCount = (int) reviews.stream()
                .filter(item -> item.automaticPassed() == "PASSED".equals(item.humanVerdict()))
                .count();
        int falsePassCount = (int) reviews.stream()
                .filter(AssistantEvaluationReviewResponse.CaseReview::automaticPassed)
                .filter(item -> "FAILED".equals(item.humanVerdict()))
                .count();
        int falseFailCount = (int) reviews.stream()
                .filter(item -> !item.automaticPassed())
                .filter(item -> "PASSED".equals(item.humanVerdict()))
                .count();
        int reviewedRequired = (int) reviews.stream()
                .filter(AssistantEvaluationReviewResponse.CaseReview::requiredReview)
                .count();
        int disagreementCount = reviews.size() - agreementCount;
        return new AssistantEvaluationReviewResponse(
                run.runId(),
                requiredIds.size(),
                reviews.size(),
                reviewedRequired,
                Math.max(0, requiredIds.size() - reviewedRequired),
                agreementCount,
                disagreementCount,
                falsePassCount,
                falseFailCount,
                ratio(agreementCount, reviews.size()),
                reviews
        );
    }

    private AssistantBenchmarkResponse.CaseResult findCase(
            AssistantBenchmarkResponse run, String caseId) {
        if (caseId == null || caseId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "评测用例 ID 不能为空");
        }
        return run.cases().stream()
                .filter(item -> caseId.equals(item.id()))
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "该运行中不存在指定评测用例"));
    }

    private AssistantEvaluationReviewResponse.CaseReview toResponse(
            AssistantEvaluationCaseReview review,
            AssistantBenchmarkResponse.CaseResult result,
            boolean requiredReview) {
        return new AssistantEvaluationReviewResponse.CaseReview(
                result.id(), result.status(), result.passed(), review.getVerdict(),
                review.getNotes(), review.getReviewer(), requiredReview, review.getUpdatedAt());
    }

    private boolean requiresReview(AssistantBenchmarkResponse.CaseResult item) {
        return "HIGH".equals(item.riskLevel())
                || "CRITICAL".equals(item.riskLevel())
                || "FAILED".equals(item.status())
                || "ERROR".equals(item.status());
    }

    private String normalizeNotes(String notes) {
        if (notes == null || notes.isBlank()) {
            return null;
        }
        String normalized = notes.trim();
        if (normalized.length() > 2000) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "人工复核备注不能超过 2000 字");
        }
        return normalized;
    }

    private double ratio(int numerator, int denominator) {
        if (denominator <= 0) {
            return 0.0;
        }
        return Math.round((double) numerator / denominator * 10_000.0) / 10_000.0;
    }
}
