package com.example.mealcheck.repository;

import com.example.mealcheck.entity.AssistantEvaluationRun;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AssistantEvaluationRunRepository extends JpaRepository<AssistantEvaluationRun, Long> {
    Optional<AssistantEvaluationRun> findByRunId(String runId);
    List<AssistantEvaluationRun> findTop20ByOrderByStartedAtDesc();
    List<AssistantEvaluationRun> findByStatus(String status);
}
