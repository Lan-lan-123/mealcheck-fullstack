package com.example.mealcheck.service;

import com.example.mealcheck.dto.AssistantEvaluationCandidateRequest;
import com.example.mealcheck.dto.AssistantEvaluationCandidateResponse;
import com.example.mealcheck.entity.AssistantEvaluationCandidate;
import com.example.mealcheck.repository.AssistantEvaluationCandidateRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AssistantEvaluationCandidateServiceTest {

    @Test
    void storesOnlyConfirmedSanitizedCandidatesAndNormalizesTools() {
        AssistantEvaluationCandidateRepository repository =
                mock(AssistantEvaluationCandidateRepository.class);
        when(repository.existsByQuestionIgnoreCase("晚餐应该怎么吃？")).thenReturn(false);
        when(repository.save(any(AssistantEvaluationCandidate.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        AssistantEvaluationCandidateService service = new AssistantEvaluationCandidateService(
                repository, new ObjectMapper());

        AssistantEvaluationCandidateResponse.Candidate result = service.create(
                new AssistantEvaluationCandidateRequest(
                        " 晚餐应该怎么吃？ ", " 回答忽略了用户目标 ", "intent",
                        "目标已脱敏", "fat-loss",
                        List.of("get_my_current_goal", "get_my_current_goal"),
                        "应先读取目标", "feedback-42", true),
                "admin");

        assertThat(result.status()).isEqualTo("PENDING");
        assertThat(result.issueType()).isEqualTo("INTENT");
        assertThat(result.expectedTools()).containsExactly("get_my_current_goal");
        assertThat(result.submittedBy()).isEqualTo("admin");
        verify(repository).save(any(AssistantEvaluationCandidate.class));
    }

    @Test
    void rejectsUnconfirmedOrUnknownToolCandidates() {
        AssistantEvaluationCandidateRepository repository =
                mock(AssistantEvaluationCandidateRepository.class);
        AssistantEvaluationCandidateService service = new AssistantEvaluationCandidateService(
                repository, new ObjectMapper());

        assertThatThrownBy(() -> service.create(request(false, List.of()), "admin"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("脱敏");
        assertThatThrownBy(() -> service.create(request(true, List.of("delete_user")), "admin"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("未知工具");
    }

    @Test
    void approvalMarksReviewerButDoesNotPublishCandidate() {
        AssistantEvaluationCandidateRepository repository =
                mock(AssistantEvaluationCandidateRepository.class);
        AssistantEvaluationCandidate candidate = new AssistantEvaluationCandidate();
        candidate.setQuestion("问题");
        candidate.setObservedAnswer("回答");
        candidate.setIssueType("ANSWER_QUALITY");
        candidate.setExpectedToolsJson("[]");
        candidate.setStatus("PENDING");
        candidate.setSubmittedBy("submitter");
        when(repository.findById(1L)).thenReturn(Optional.of(candidate));
        when(repository.save(candidate)).thenReturn(candidate);
        AssistantEvaluationCandidateService service = new AssistantEvaluationCandidateService(
                repository, new ObjectMapper());

        AssistantEvaluationCandidateResponse.Candidate result =
                service.updateStatus(1L, "APPROVED", "reviewer");

        assertThat(result.status()).isEqualTo("APPROVED");
        assertThat(result.reviewedBy()).isEqualTo("reviewer");
    }

    private AssistantEvaluationCandidateRequest request(boolean sanitized, List<String> tools) {
        return new AssistantEvaluationCandidateRequest(
                "问题", "错误回答", "ANSWER_QUALITY", null,
                null, tools, null, null, sanitized);
    }
}
