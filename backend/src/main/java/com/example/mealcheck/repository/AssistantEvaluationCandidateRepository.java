package com.example.mealcheck.repository;

import com.example.mealcheck.entity.AssistantEvaluationCandidate;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AssistantEvaluationCandidateRepository
        extends JpaRepository<AssistantEvaluationCandidate, Long> {
    List<AssistantEvaluationCandidate> findTop100ByOrderByCreatedAtDesc();
    boolean existsByQuestionIgnoreCase(String question);
}
