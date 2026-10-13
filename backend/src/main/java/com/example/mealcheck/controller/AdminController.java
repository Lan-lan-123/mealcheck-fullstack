package com.example.mealcheck.controller;

import com.example.mealcheck.dto.AdminAuditLogPageResponse;
import com.example.mealcheck.dto.AdminDashboardResponse;
import com.example.mealcheck.dto.AdminKnowledgeChunkPageResponse;
import com.example.mealcheck.dto.AdminKnowledgeChunkRequest;
import com.example.mealcheck.dto.AdminKnowledgeChunkResponse;
import com.example.mealcheck.dto.AdminMealAnalyticsResponse;
import com.example.mealcheck.dto.AdminMealPageResponse;
import com.example.mealcheck.dto.AdminMealRecordResponse;
import com.example.mealcheck.dto.AdminNonFoodUploadEventPageResponse;
import com.example.mealcheck.dto.AdminStatsResponse;
import com.example.mealcheck.dto.AdminUserPageResponse;
import com.example.mealcheck.dto.AdminUserResponse;
import com.example.mealcheck.dto.AssistantBenchmarkResponse;
import com.example.mealcheck.dto.AssistantBenchmarkHistoryResponse;
import com.example.mealcheck.dto.AssistantBenchmarkRequest;
import com.example.mealcheck.dto.AssistantBenchmarkComparisonResponse;
import com.example.mealcheck.dto.AssistantEvaluationReviewRequest;
import com.example.mealcheck.dto.AssistantEvaluationReviewResponse;
import com.example.mealcheck.dto.AssistantEvaluationCandidateRequest;
import com.example.mealcheck.dto.AssistantEvaluationCandidateResponse;
import com.example.mealcheck.dto.AssistantEvaluationCandidateStatusRequest;
import com.example.mealcheck.dto.RagBenchmarkResponse;
import com.example.mealcheck.security.UserPrincipal;
import com.example.mealcheck.service.AdminService;
import com.example.mealcheck.service.AdminMealQueryService;
import com.example.mealcheck.service.AdminAuditService;
import com.example.mealcheck.service.AssistantBenchmarkService;
import com.example.mealcheck.service.AssistantBenchmarkHistoryService;
import com.example.mealcheck.service.AssistantEvaluationReviewService;
import com.example.mealcheck.service.AssistantEvaluationCandidateService;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/admin")
@PreAuthorize("hasRole('ADMIN')")
public class AdminController {

    private final AdminService adminService;
    private final AdminAuditService adminAuditService;
    private final AssistantBenchmarkService assistantBenchmarkService;
    private final AssistantBenchmarkHistoryService assistantBenchmarkHistoryService;
    private final AssistantEvaluationReviewService assistantEvaluationReviewService;
    private final AssistantEvaluationCandidateService assistantEvaluationCandidateService;

    public AdminController(AdminService adminService,
                           AdminAuditService adminAuditService,
                           AssistantBenchmarkService assistantBenchmarkService,
                           AssistantBenchmarkHistoryService assistantBenchmarkHistoryService,
                           AssistantEvaluationReviewService assistantEvaluationReviewService,
                           AssistantEvaluationCandidateService assistantEvaluationCandidateService) {
        this.adminService = adminService;
        this.adminAuditService = adminAuditService;
        this.assistantBenchmarkService = assistantBenchmarkService;
        this.assistantBenchmarkHistoryService = assistantBenchmarkHistoryService;
        this.assistantEvaluationReviewService = assistantEvaluationReviewService;
        this.assistantEvaluationCandidateService = assistantEvaluationCandidateService;
    }

    @GetMapping("/stats")
    public AdminStatsResponse stats() {
        return adminService.stats();
    }

    @GetMapping("/dashboard")
    public AdminDashboardResponse dashboard() {
        return adminService.dashboard();
    }

    @GetMapping("/audit-logs")
    public AdminAuditLogPageResponse auditLogs(@RequestParam(value = "page", defaultValue = "0") int page,
                                               @RequestParam(value = "size", defaultValue = "20") int size) {
        return adminService.auditLogs(page, size);
    }

    @GetMapping("/non-food-uploads")
    public AdminNonFoodUploadEventPageResponse nonFoodUploads(@RequestParam(value = "page", defaultValue = "0") int page,
                                                              @RequestParam(value = "size", defaultValue = "20") int size,
                                                              @RequestParam(value = "username", required = false) String username,
                                                              @RequestParam(value = "blockedOnly", defaultValue = "false") boolean blockedOnly) {
        return adminService.nonFoodUploads(page, size, username, blockedOnly);
    }

    @GetMapping("/users")
    public AdminUserPageResponse users(@RequestParam(value = "page", defaultValue = "0") int page,
                                       @RequestParam(value = "size", defaultValue = "20") int size,
                                       @RequestParam(value = "username", required = false) String username,
                                       @RequestParam(value = "role", required = false) String role) {
        return adminService.users(page, size, username, role);
    }

    @DeleteMapping("/users/{id}")
    public ResponseEntity<Void> deleteUser(@PathVariable Long id,
                                           @AuthenticationPrincipal UserPrincipal principal) {
        adminService.deleteUser(id, principal);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/meals")
    public AdminMealPageResponse meals(@RequestParam(value = "page", defaultValue = "0") int page,
                                       @RequestParam(value = "size", defaultValue = "20") int size,
                                       @RequestParam(value = "username", required = false) String username,
                                       @RequestParam(value = "from", required = false) LocalDate from,
                                       @RequestParam(value = "to", required = false) LocalDate to,
                                       @RequestParam(value = "minScore", required = false) Integer minScore,
                                       @RequestParam(value = "maxScore", required = false) Integer maxScore) {
        return adminService.meals(page, size, username, from, to, minScore, maxScore);
    }

    @GetMapping("/meals/cursor")
    public AdminMealQueryService.CursorPage mealsCursor(
            @RequestParam(value = "size", defaultValue = "20") int size,
            @RequestParam(value = "username", required = false) String username,
            @RequestParam(value = "from", required = false) LocalDate from,
            @RequestParam(value = "to", required = false) LocalDate to,
            @RequestParam(value = "minScore", required = false) Integer minScore,
            @RequestParam(value = "maxScore", required = false) Integer maxScore,
            @RequestParam(value = "beforeTime", required = false) String beforeTime,
            @RequestParam(value = "beforeId", required = false) Long beforeId) {
        return adminService.mealsCursor(size, username, from, to, minScore, maxScore,
                AdminMealQueryService.parseCursorTime(beforeTime), beforeId);
    }

    @GetMapping("/meals/analytics")
    public AdminMealAnalyticsResponse mealAnalytics(@RequestParam(value = "username", required = false) String username,
                                                   @RequestParam(value = "from", required = false) LocalDate from,
                                                   @RequestParam(value = "to", required = false) LocalDate to,
                                                   @RequestParam(value = "minScore", required = false) Integer minScore,
                                                   @RequestParam(value = "maxScore", required = false) Integer maxScore) {
        return adminService.mealAnalytics(username, from, to, minScore, maxScore);
    }

    @DeleteMapping("/meals/{id}")
    public ResponseEntity<Void> deleteMeal(@PathVariable Long id,
                                           @AuthenticationPrincipal UserPrincipal principal) {
        adminService.deleteMealRecord(id, principal);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/knowledge/chunks")
    public AdminKnowledgeChunkPageResponse knowledgeChunks(@RequestParam(value = "keyword", required = false) String keyword,
                                                          @RequestParam(value = "title", required = false) String title,
                                                          @RequestParam(value = "source", required = false) String source,
                                                          @RequestParam(value = "sort", required = false) String sort,
                                                          @RequestParam(value = "page", defaultValue = "0") int page,
                                                          @RequestParam(value = "size", defaultValue = "8") int size) {
        String search = keyword == null || keyword.isBlank() ? title : keyword;
        return adminService.knowledgeChunks(search, source, sort, page, size);
    }

    @PostMapping("/knowledge/chunks")
    public ResponseEntity<Void> addKnowledgeChunk(@Valid @RequestBody AdminKnowledgeChunkRequest request,
                                                  @AuthenticationPrincipal UserPrincipal principal) {
        adminService.addKnowledgeChunk(request, principal);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/knowledge/chunks/{id}")
    public ResponseEntity<Void> deleteKnowledgeChunk(@PathVariable Long id,
                                                     @AuthenticationPrincipal UserPrincipal principal) {
        adminService.deleteKnowledgeChunk(id, principal);
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/knowledge/chunks/{id}")
    public ResponseEntity<Void> updateKnowledgeChunk(@PathVariable Long id,
                                                     @Valid @RequestBody AdminKnowledgeChunkRequest request,
                                                     @AuthenticationPrincipal UserPrincipal principal) {
        adminService.updateKnowledgeChunk(id, request, principal);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/knowledge/reindex")
    public Object reindexKnowledge() {
        return adminService.reindexKnowledge();
    }

    @GetMapping("/knowledge/evaluation")
    public RagBenchmarkResponse ragEvaluation() {
        return adminService.ragBenchmark();
    }

    @PostMapping("/assistant/evaluation")
    public AssistantBenchmarkResponse startAssistantEvaluation(
            @RequestBody(required = false) AssistantBenchmarkRequest request,
            @AuthenticationPrincipal UserPrincipal principal) {
        String mode = request == null ? "QUICK" : request.mode();
        AssistantBenchmarkResponse response = assistantBenchmarkService.start(
                mode, principal.getUsername());
        adminAuditService.log(principal, "RUN_ASSISTANT_EVALUATION", "assistant_evaluation",
                null, "runId=" + response.runId() + ", mode=" + response.mode()
                        + ", cases=" + response.caseCount());
        return response;
    }

    @GetMapping("/assistant/evaluation")
    public AssistantBenchmarkResponse assistantEvaluationStatus() {
        return assistantBenchmarkService.status();
    }

    @GetMapping("/assistant/evaluation/history")
    public AssistantBenchmarkHistoryResponse assistantEvaluationHistory() {
        return assistantBenchmarkHistoryService.recent();
    }

    @GetMapping("/assistant/evaluation/history/{runId}")
    public AssistantBenchmarkResponse assistantEvaluationHistoryDetail(@PathVariable String runId) {
        return assistantBenchmarkHistoryService.detail(runId);
    }

    @GetMapping("/assistant/evaluation/history/compare")
    public AssistantBenchmarkComparisonResponse compareAssistantEvaluationHistory(
            @RequestParam String baseRunId,
            @RequestParam String candidateRunId) {
        return assistantBenchmarkHistoryService.compare(baseRunId, candidateRunId);
    }

    @GetMapping("/assistant/evaluation/history/{runId}/reviews")
    public AssistantEvaluationReviewResponse assistantEvaluationReviews(@PathVariable String runId) {
        return assistantEvaluationReviewService.report(runId);
    }

    @PutMapping("/assistant/evaluation/history/{runId}/cases/{caseId}/review")
    public AssistantEvaluationReviewResponse reviewAssistantEvaluationCase(
            @PathVariable String runId,
            @PathVariable String caseId,
            @Valid @RequestBody AssistantEvaluationReviewRequest request,
            @AuthenticationPrincipal UserPrincipal principal) {
        AssistantEvaluationReviewResponse response = assistantEvaluationReviewService.review(
                runId, caseId, request, principal.getUsername());
        adminAuditService.log(principal, "REVIEW_ASSISTANT_EVALUATION_CASE",
                "assistant_evaluation_case", null,
                "runId=" + runId + ", caseId=" + caseId + ", verdict=" + request.verdict());
        return response;
    }

    @GetMapping("/assistant/evaluation/candidates")
    public AssistantEvaluationCandidateResponse assistantEvaluationCandidates() {
        return assistantEvaluationCandidateService.list();
    }

    @PostMapping("/assistant/evaluation/candidates")
    public AssistantEvaluationCandidateResponse.Candidate addAssistantEvaluationCandidate(
            @Valid @RequestBody AssistantEvaluationCandidateRequest request,
            @AuthenticationPrincipal UserPrincipal principal) {
        AssistantEvaluationCandidateResponse.Candidate candidate =
                assistantEvaluationCandidateService.create(request, principal.getUsername());
        adminAuditService.log(principal, "ADD_ASSISTANT_EVALUATION_CANDIDATE",
                "assistant_evaluation_candidate", candidate.id(),
                "issueType=" + candidate.issueType() + ", sanitized=true");
        return candidate;
    }

    @PutMapping("/assistant/evaluation/candidates/{id}/status")
    public AssistantEvaluationCandidateResponse.Candidate updateAssistantEvaluationCandidateStatus(
            @PathVariable Long id,
            @Valid @RequestBody AssistantEvaluationCandidateStatusRequest request,
            @AuthenticationPrincipal UserPrincipal principal) {
        AssistantEvaluationCandidateResponse.Candidate candidate =
                assistantEvaluationCandidateService.updateStatus(
                        id, request.status(), principal.getUsername());
        adminAuditService.log(principal, "REVIEW_ASSISTANT_EVALUATION_CANDIDATE",
                "assistant_evaluation_candidate", id, "status=" + candidate.status());
        return candidate;
    }
}
