package com.example.mealcheck.repository;

import com.example.mealcheck.entity.AssistantEvaluationCaseReview;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AssistantEvaluationCaseReviewRepository
        extends JpaRepository<AssistantEvaluationCaseReview, Long> {
    Optional<AssistantEvaluationCaseReview> findByRunIdAndCaseId(String runId, String caseId);
    List<AssistantEvaluationCaseReview> findByRunIdOrderByUpdatedAtDesc(String runId);
}
