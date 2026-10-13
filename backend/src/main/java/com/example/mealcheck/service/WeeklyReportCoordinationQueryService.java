package com.example.mealcheck.service;

import com.example.mealcheck.dto.WeeklyReportCoordinationResponse;
import com.example.mealcheck.dto.WeeklyReportCoordinationPageResponse;
import com.example.mealcheck.entity.WeeklyReportCoordinationRun;
import com.example.mealcheck.entity.WeeklyReportCoordinationStep;
import com.example.mealcheck.repository.WeeklyReportCoordinationRunRepository;
import com.example.mealcheck.repository.WeeklyReportCoordinationStepRepository;
import org.springframework.http.HttpStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Locale;

@Service
public class WeeklyReportCoordinationQueryService {
    private final WeeklyReportCoordinationRunRepository runRepository;
    private final WeeklyReportCoordinationStepRepository stepRepository;

    public WeeklyReportCoordinationQueryService(WeeklyReportCoordinationRunRepository runRepository,
                                                WeeklyReportCoordinationStepRepository stepRepository) {
        this.runRepository = runRepository;
        this.stepRepository = stepRepository;
    }

    @Transactional(readOnly = true)
    public WeeklyReportCoordinationPageResponse recent(int page,
                                                       int size,
                                                       String status,
                                                       Long userId,
                                                       Long jobId) {
        int safePage = Math.max(0, page);
        int safeSize = Math.max(1, Math.min(100, size));
        Specification<WeeklyReportCoordinationRun> specification = Specification.where(null);
        if (status != null && !status.isBlank() && !"ALL".equalsIgnoreCase(status)) {
            String normalizedStatus = status.trim().toUpperCase(Locale.ROOT);
            specification = specification.and((root, query, criteria) ->
                    criteria.equal(root.get("status"), normalizedStatus));
        }
        if (userId != null) {
            specification = specification.and((root, query, criteria) ->
                    criteria.equal(root.get("userId"), userId));
        }
        if (jobId != null) {
            specification = specification.and((root, query, criteria) ->
                    criteria.equal(root.get("jobId"), jobId));
        }
        Page<WeeklyReportCoordinationRun> result = runRepository.findAll(
                specification,
                PageRequest.of(safePage, safeSize, Sort.by(Sort.Direction.DESC, "startedAt")));
        return new WeeklyReportCoordinationPageResponse(
                result.getContent().stream().map(this::toRunSummary).toList(),
                result.getNumber(), result.getSize(), result.getTotalElements(), result.getTotalPages());
    }

    @Transactional(readOnly = true)
    public WeeklyReportCoordinationResponse detail(String runId) {
        WeeklyReportCoordinationRun run = runRepository.findByRunId(runId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "周报 Agent 协调运行记录不存在"));
        List<WeeklyReportCoordinationResponse.StepSummary> steps = stepRepository
                .findByRunIdOrderByStartedAtAscIdAsc(runId)
                .stream()
                .map(this::toStepSummary)
                .toList();
        return new WeeklyReportCoordinationResponse(toRunSummary(run), steps);
    }

    private WeeklyReportCoordinationResponse.RunSummary toRunSummary(WeeklyReportCoordinationRun run) {
        return new WeeklyReportCoordinationResponse.RunSummary(
                run.getRunId(), run.getJobId(), run.getUserId(), run.getStatus(),
                run.getGenerationMode(), run.getReviewStatus(), run.getRevisionCount(),
                run.getStepCount(), run.getDegradedStepCount(), run.getFailedStepCount(),
                run.getElapsedMs(), run.getErrorMessage(), run.getStartedAt(),
                run.getFinishedAt(), run.getUpdatedAt());
    }

    private WeeklyReportCoordinationResponse.StepSummary toStepSummary(WeeklyReportCoordinationStep step) {
        return new WeeklyReportCoordinationResponse.StepSummary(
                step.getId(), step.getNodeName(), step.getNodeType(), step.getAttempt(),
                step.getStatus(), step.getOutcome(), step.getAiGenerated(), step.isFallbackUsed(),
                step.getLatencyMs(), step.getErrorMessage(), step.getStartedAt(), step.getFinishedAt());
    }
}
