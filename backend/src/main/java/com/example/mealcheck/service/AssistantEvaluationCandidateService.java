package com.example.mealcheck.service;

import com.example.mealcheck.util.AppTime;

import com.example.mealcheck.dto.AssistantEvaluationCandidateRequest;
import com.example.mealcheck.dto.AssistantEvaluationCandidateResponse;
import com.example.mealcheck.entity.AssistantEvaluationCandidate;
import com.example.mealcheck.repository.AssistantEvaluationCandidateRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Service
public class AssistantEvaluationCandidateService {
    private static final Set<String> ISSUE_TYPES = Set.of(
            "INTENT", "TOOL_SELECTION", "TOOL_ARGUMENTS", "ANSWER_QUALITY",
            "SAFETY", "PRIVACY", "MULTI_TURN", "OTHER");
    private static final Set<String> TOOLS = Set.of(
            "search_diet_knowledge", "get_my_recent_meals",
            "get_my_weekly_report", "get_my_current_goal");

    private final AssistantEvaluationCandidateRepository repository;
    private final ObjectMapper objectMapper;

    public AssistantEvaluationCandidateService(AssistantEvaluationCandidateRepository repository,
                                               ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public AssistantEvaluationCandidateResponse.Candidate create(
            AssistantEvaluationCandidateRequest request, String submittedBy) {
        if (request == null || !request.sanitizedConfirmed()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "必须确认样本已经脱敏");
        }
        String question = normalizeRequired(request.question(), "问题不能为空");
        if (repository.existsByQuestionIgnoreCase(question)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "候选样本中已存在相同问题");
        }
        String issueType = normalizeIssueType(request.issueType());
        List<String> expectedTools = normalizeTools(request.expectedTools());
        AssistantEvaluationCandidate candidate = new AssistantEvaluationCandidate();
        candidate.setQuestion(question);
        candidate.setObservedAnswer(normalizeRequired(request.observedAnswer(), "问题回答不能为空"));
        candidate.setIssueType(issueType);
        candidate.setSanitizedContext(normalizeOptional(request.sanitizedContext()));
        candidate.setExpectedSkill(normalizeOptional(request.expectedSkill()));
        candidate.setExpectedToolsJson(writeTools(expectedTools));
        candidate.setNotes(normalizeOptional(request.notes()));
        candidate.setSourceReference(normalizeOptional(request.sourceReference()));
        candidate.setStatus("PENDING");
        candidate.setSubmittedBy(normalizeActor(submittedBy));
        LocalDateTime now = AppTime.now();
        candidate.setCreatedAt(now);
        candidate.setUpdatedAt(now);
        return toResponse(repository.save(candidate));
    }

    @Transactional(readOnly = true)
    public AssistantEvaluationCandidateResponse list() {
        return new AssistantEvaluationCandidateResponse(repository
                .findTop100ByOrderByCreatedAtDesc().stream().map(this::toResponse).toList());
    }

    @Transactional
    public AssistantEvaluationCandidateResponse.Candidate updateStatus(
            Long id, String requestedStatus, String reviewedBy) {
        AssistantEvaluationCandidate candidate = repository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "失败样本候选不存在"));
        String status = requestedStatus == null
                ? "" : requestedStatus.trim().toUpperCase(Locale.ROOT);
        if (!Set.of("PENDING", "APPROVED", "REJECTED").contains(status)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "候选状态无效");
        }
        candidate.setStatus(status);
        candidate.setReviewedBy("PENDING".equals(status) ? null : normalizeActor(reviewedBy));
        candidate.setUpdatedAt(AppTime.now());
        return toResponse(repository.save(candidate));
    }

    private AssistantEvaluationCandidateResponse.Candidate toResponse(
            AssistantEvaluationCandidate item) {
        return new AssistantEvaluationCandidateResponse.Candidate(
                item.getId(), item.getQuestion(), item.getObservedAnswer(), item.getIssueType(),
                item.getSanitizedContext(), item.getExpectedSkill(), readTools(item.getExpectedToolsJson()),
                item.getNotes(), item.getSourceReference(), item.getStatus(), item.getSubmittedBy(),
                item.getReviewedBy(), item.getCreatedAt(), item.getUpdatedAt());
    }

    private String normalizeIssueType(String issueType) {
        String normalized = issueType == null ? "" : issueType.trim().toUpperCase(Locale.ROOT);
        if (!ISSUE_TYPES.contains(normalized)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "失败类型无效");
        }
        return normalized;
    }

    private List<String> normalizeTools(List<String> tools) {
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        if (tools != null) {
            for (String tool : tools) {
                if (tool != null && !tool.isBlank()) {
                    normalized.add(tool.trim());
                }
            }
        }
        if (!TOOLS.containsAll(normalized)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "候选样本包含未知工具");
        }
        return List.copyOf(normalized);
    }

    private String normalizeRequired(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
        }
        return value.trim();
    }

    private String normalizeOptional(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private String normalizeActor(String value) {
        return value == null || value.isBlank() ? "unknown" : value.trim();
    }

    private String writeTools(List<String> tools) {
        try {
            return objectMapper.writeValueAsString(tools);
        } catch (Exception e) {
            throw new IllegalStateException("无法序列化候选样本工具", e);
        }
    }

    private List<String> readTools(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(value, new TypeReference<>() { });
        } catch (Exception e) {
            throw new IllegalStateException("无法解析候选样本工具", e);
        }
    }
}
