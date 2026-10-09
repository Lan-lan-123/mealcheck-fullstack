package com.example.mealcheck.service;

import com.example.mealcheck.util.AppTime;

import com.example.mealcheck.entity.WeeklyReportCoordinationRun;
import com.example.mealcheck.entity.WeeklyReportCoordinationStep;
import com.example.mealcheck.repository.WeeklyReportCoordinationRunRepository;
import com.example.mealcheck.repository.WeeklyReportCoordinationStepRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

@Service
public class WeeklyReportCoordinationRecorder {
    private static final Logger log = LoggerFactory.getLogger(WeeklyReportCoordinationRecorder.class);
    private static final String INTERRUPTED_MESSAGE = "Coordination heartbeat expired before completion";
    private static final Pattern BEARER_TOKEN = Pattern.compile(
            "(?i)(bearer\\s+)[a-z0-9._~+/-]+=*");
    private static final Pattern API_KEY = Pattern.compile(
            "(?i)((?:api[_-]?key|token)\\s*[=:]\\s*)[^\\s,;]+");
    private static final Pattern OPENAI_STYLE_KEY = Pattern.compile("(?i)sk-[a-z0-9_-]{8,}");

    private final WeeklyReportCoordinationRunRepository runRepository;
    private final WeeklyReportCoordinationStepRepository stepRepository;
    private final ApplicationObservability observability;
    private final Set<String> activeRunIds = ConcurrentHashMap.newKeySet();

    @Value("${mealcheck.weekly-report.coordination-stale-seconds:900}")
    private long coordinationStaleSeconds;

    @Value("${mealcheck.weekly-report.coordination-heartbeat-millis:30000}")
    private long coordinationHeartbeatMillis;

    public WeeklyReportCoordinationRecorder(WeeklyReportCoordinationRunRepository runRepository,
                                            WeeklyReportCoordinationStepRepository stepRepository,
                                            ApplicationObservability observability) {
        this.runRepository = runRepository;
        this.stepRepository = stepRepository;
        this.observability = observability;
    }

    public String startRun(Long userId, Long jobId) {
        if (userId == null) {
            return null;
        }
        String runId = UUID.randomUUID().toString();
        LocalDateTime now = AppTime.now();
        WeeklyReportCoordinationRun run = new WeeklyReportCoordinationRun();
        run.setRunId(runId);
        run.setJobId(jobId);
        run.setUserId(userId);
        run.setStatus("RUNNING");
        run.setStartedAt(now);
        run.setUpdatedAt(now);
        try {
            runRepository.save(run);
            activeRunIds.add(runId);
            return runId;
        } catch (RuntimeException error) {
            log.warn("Unable to persist weekly report coordination run. userId={}, jobId={}, reason={}",
                    userId, jobId, safeErrorMessage(error));
            return null;
        }
    }

    public StepHandle startStep(String runId, String nodeName, String nodeType, int attempt) {
        long startedNanos = System.nanoTime();
        if (runId == null || runId.isBlank()) {
            return new StepHandle(null, nodeName, nodeType, attempt, startedNanos);
        }
        WeeklyReportCoordinationStep step = new WeeklyReportCoordinationStep();
        step.setRunId(runId);
        step.setNodeName(nodeName);
        step.setNodeType(nodeType);
        step.setAttempt(Math.max(1, attempt));
        step.setStatus("RUNNING");
        step.setStartedAt(AppTime.now());
        try {
            Long stepId = stepRepository.save(step).getId();
            return new StepHandle(stepId, nodeName, nodeType, Math.max(1, attempt), startedNanos);
        } catch (RuntimeException error) {
            log.warn("Unable to persist weekly report coordination step. runId={}, node={}, reason={}",
                    runId, nodeName, safeErrorMessage(error));
            return new StepHandle(null, nodeName, nodeType, Math.max(1, attempt), startedNanos);
        }
    }

    public void completeStep(StepHandle handle,
                             String outcome,
                             Boolean aiGenerated,
                             boolean fallbackUsed) {
        finishStep(handle, fallbackUsed ? "DEGRADED" : "SUCCEEDED", outcome,
                aiGenerated, fallbackUsed, null);
    }

    public void degradeStep(StepHandle handle,
                            String outcome,
                            Boolean aiGenerated,
                            Throwable error) {
        finishStep(handle, "DEGRADED", outcome, aiGenerated, true, error);
    }

    public void failStep(StepHandle handle, Throwable error) {
        finishStep(handle, "FAILED", "ERROR", null, false, error);
    }

    public void completeRun(String runId,
                            String generationMode,
                            String reviewStatus,
                            int revisionCount) {
        String status = "MULTI_AGENT".equals(generationMode) ? "SUCCEEDED" : "DEGRADED";
        finishRun(runId, status, generationMode, reviewStatus, revisionCount, null);
    }

    public void failRun(String runId,
                        String generationMode,
                        String reviewStatus,
                        int revisionCount,
                        Throwable error) {
        finishRun(runId, "FAILED", generationMode, reviewStatus, revisionCount, error);
    }

    private void finishStep(StepHandle handle,
                            String status,
                            String outcome,
                            Boolean aiGenerated,
                            boolean fallbackUsed,
                            Throwable error) {
        if (handle == null) {
            return;
        }
        long latencyMs = Duration.ofNanos(
                Math.max(0L, System.nanoTime() - handle.startedNanos())).toMillis();
        String errorMessage = error == null ? null : safeErrorMessage(error);
        if (handle.stepId() != null) {
            try {
                stepRepository.finish(handle.stepId(), status, safeOutcome(outcome), aiGenerated,
                        fallbackUsed, latencyMs, errorMessage, AppTime.now());
            } catch (RuntimeException persistenceError) {
                log.warn("Unable to finish weekly report coordination step. stepId={}, reason={}",
                        handle.stepId(), safeErrorMessage(persistenceError));
            }
        }
        observability.recordWeeklyReportCoordinationStep(
                latencyMs, handle.nodeName(), handle.nodeType(), status, fallbackUsed);
    }

    private void finishRun(String runId,
                           String status,
                           String generationMode,
                           String reviewStatus,
                           int revisionCount,
                           Throwable error) {
        if (runId == null || runId.isBlank()) {
            return;
        }
        try {
            WeeklyReportCoordinationRun run = runRepository.findByRunId(runId).orElse(null);
            if (run == null) {
                return;
            }
            LocalDateTime finishedAt = AppTime.now();
            long elapsedMs = Math.max(0L, Duration.between(run.getStartedAt(), finishedAt).toMillis());
            int stepCount = safeCount(stepRepository.countByRunId(runId));
            int degradedSteps = safeCount(stepRepository.countByRunIdAndStatus(runId, "DEGRADED"));
            int failedSteps = safeCount(stepRepository.countByRunIdAndStatus(runId, "FAILED"));
            int completedRevisions = Math.max(0, revisionCount - 1);
            runRepository.finish(runId, status, generationMode, reviewStatus,
                    completedRevisions, stepCount, degradedSteps, failedSteps,
                    elapsedMs, error == null ? null : safeErrorMessage(error), finishedAt);
            observability.recordWeeklyReportCoordinationRun(elapsedMs, status, generationMode);
            observability.recordWeeklyReportCoordinationRevisions(completedRevisions);
        } catch (RuntimeException persistenceError) {
            log.warn("Unable to finish weekly report coordination run. runId={}, reason={}",
                    runId, safeErrorMessage(persistenceError));
        } finally {
            activeRunIds.remove(runId);
        }
    }

    @Scheduled(fixedDelayString = "${mealcheck.weekly-report.coordination-heartbeat-millis:30000}")
    public void maintainRunLeases() {
        LocalDateTime now = AppTime.now();
        heartbeatActiveRuns(now);
        recoverStaleRuns(now);
    }

    void heartbeatActiveRuns(LocalDateTime now) {
        if (activeRunIds.isEmpty()) {
            return;
        }
        try {
            runRepository.heartbeat(Set.copyOf(activeRunIds), now);
        } catch (RuntimeException error) {
            log.warn("Unable to heartbeat weekly report coordination runs. activeRuns={}, reason={}",
                    activeRunIds.size(), safeErrorMessage(error));
        }
    }

    void recoverStaleRuns(LocalDateTime now) {
        long safeHeartbeatMillis = Math.max(1_000L,
                Math.min(3_600_000L, coordinationHeartbeatMillis));
        long minimumStaleSeconds = Math.max(60L,
                (safeHeartbeatMillis * 3L + 999L) / 1_000L);
        long effectiveStaleSeconds = Math.max(minimumStaleSeconds,
                Math.min(86_400L, coordinationStaleSeconds));
        LocalDateTime staleBefore = now.minusSeconds(effectiveStaleSeconds);
        List<WeeklyReportCoordinationRun> staleRuns;
        try {
            staleRuns = runRepository.findByStatusAndUpdatedAtBefore("RUNNING", staleBefore);
        } catch (RuntimeException error) {
            log.warn("Unable to scan stale weekly report coordination runs. reason={}",
                    safeErrorMessage(error));
            return;
        }

        int recoveredRuns = 0;
        int interruptedSteps = 0;
        for (WeeklyReportCoordinationRun run : staleRuns) {
            try {
                int stepCount = safeCount(stepRepository.countByRunId(run.getRunId()));
                int degradedSteps = safeCount(stepRepository.countByRunIdAndStatus(
                        run.getRunId(), "DEGRADED"));
                int failedSteps = safeCount(stepRepository.countByRunIdAndStatus(
                        run.getRunId(), "FAILED"));
                int runningSteps = safeCount(stepRepository.countByRunIdAndStatus(
                        run.getRunId(), "RUNNING"));
                long elapsedMs = Math.max(0L, Duration.between(run.getStartedAt(), now).toMillis());
                int updated = runRepository.interruptStale(run.getRunId(), staleBefore,
                        stepCount, degradedSteps, failedSteps + runningSteps,
                        elapsedMs, INTERRUPTED_MESSAGE, now);
                if (updated == 1) {
                    activeRunIds.remove(run.getRunId());
                    recoveredRuns++;
                    observability.recordWeeklyReportCoordinationRun(
                            elapsedMs, "INTERRUPTED", "INTERRUPTED");
                    interruptedSteps += stepRepository.interruptRunning(
                            run.getRunId(), INTERRUPTED_MESSAGE, now);
                }
            } catch (RuntimeException error) {
                log.warn("Unable to recover stale weekly report coordination run. runId={}, reason={}",
                        run.getRunId(), safeErrorMessage(error));
            }
        }
        try {
            interruptedSteps += stepRepository.interruptOrphaned(INTERRUPTED_MESSAGE, now);
        } catch (RuntimeException error) {
            log.warn("Unable to recover orphaned weekly report coordination steps. reason={}",
                    safeErrorMessage(error));
        }
        if (recoveredRuns > 0 || interruptedSteps > 0) {
            observability.recordWeeklyReportCoordinationRecovery(recoveredRuns, interruptedSteps);
            log.warn("Recovered {} stale weekly report coordination run(s) and {} running step(s)",
                    recoveredRuns, interruptedSteps);
        }
    }

    private int safeCount(long count) {
        return (int) Math.min(Integer.MAX_VALUE, Math.max(0L, count));
    }

    private String safeOutcome(String outcome) {
        return outcome == null || outcome.isBlank() ? "UNKNOWN" : truncate(outcome, 40);
    }

    private String truncate(String value, int maxLength) {
        if (value == null) return null;
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }

    private String rootMessage(Throwable error) {
        if (error == null) return "unknown failure";
        Throwable current = error;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }

    private String safeErrorMessage(Throwable error) {
        String value = truncate(rootMessage(error), 1000);
        value = BEARER_TOKEN.matcher(value).replaceAll("$1[REDACTED]");
        value = API_KEY.matcher(value).replaceAll("$1[REDACTED]");
        return OPENAI_STYLE_KEY.matcher(value).replaceAll("[REDACTED]");
    }

    public record StepHandle(Long stepId,
                             String nodeName,
                             String nodeType,
                             int attempt,
                             long startedNanos) {}
}
