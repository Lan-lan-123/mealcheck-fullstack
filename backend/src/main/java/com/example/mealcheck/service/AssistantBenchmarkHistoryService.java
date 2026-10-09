package com.example.mealcheck.service;

import com.example.mealcheck.util.AppTime;

import com.example.mealcheck.dto.AssistantBenchmarkHistoryResponse;
import com.example.mealcheck.dto.AssistantBenchmarkResponse;
import com.example.mealcheck.dto.AssistantBenchmarkComparisonResponse;
import com.example.mealcheck.entity.AssistantEvaluationRun;
import com.example.mealcheck.repository.AssistantEvaluationRunRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class AssistantBenchmarkHistoryService {
    private static final Logger log = LoggerFactory.getLogger(AssistantBenchmarkHistoryService.class);

    private final AssistantEvaluationRunRepository repository;
    private final ObjectMapper objectMapper;

    public AssistantBenchmarkHistoryService(AssistantEvaluationRunRepository repository,
                                            ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public void saveSnapshot(AssistantBenchmarkResponse response, String startedBy) {
        if (response == null || response.runId() == null || response.runId().isBlank()) {
            return;
        }
        AssistantEvaluationRun run = repository.findByRunId(response.runId())
                .orElseGet(AssistantEvaluationRun::new);
        run.setRunId(response.runId());
        run.setMode(response.mode());
        run.setStatus(response.status());
        run.setVerdict(response.verdict());
        run.setCaseCount(response.caseCount());
        run.setCompletedCount(response.completedCount());
        run.setJudgedCount(response.judgedCount());
        run.setPassedCount(response.passedCount());
        run.setFailedCount(response.failedCount());
        run.setErrorCount(response.errorCount());
        run.setHardGateFailureCount(response.hardGateFailureCount());
        run.setToolTracePassedCount(response.toolTracePassedCount());
        run.setIntentEvaluatedCount(response.intentEvaluatedCount());
        run.setIntentMatchedCount(response.intentMatchedCount());
        run.setPassRate(response.passRate());
        run.setExecutionSuccessRate(response.executionSuccessRate());
        run.setEndToEndPassRate(response.endToEndPassRate());
        run.setToolTracePassRate(response.toolTracePassRate());
        run.setIntentAccuracy(response.intentAccuracy());
        run.setIntentMacroF1(response.intentMacroF1());
        run.setMacroCategoryPassRate(response.macroCategoryPassRate());
        run.setAverageScore(response.averageScore());
        run.setEndToEndAverageScore(response.endToEndAverageScore());
        run.setElapsedMs(response.elapsedMs());
        run.setGenerationModel(response.model());
        run.setJudgeModel(response.judgeModel());
        run.setIndependentJudge(response.independentJudge());
        run.setDatasetVersion(response.datasetVersion());
        run.setDatasetFingerprint(response.datasetFingerprint());
        if (startedBy != null && !startedBy.isBlank()) {
            run.setStartedBy(startedBy);
        }
        run.setMessage(response.message());
        run.setStartedAt(response.startedAt());
        run.setFinishedAt(response.finishedAt());
        run.setUpdatedAt(AppTime.now());
        try {
            run.setResultJson(objectMapper.writeValueAsString(response));
        } catch (Exception e) {
            throw new IllegalStateException("无法序列化助手评测结果", e);
        }
        repository.save(run);
    }

    @Transactional(readOnly = true)
    public AssistantBenchmarkHistoryResponse recent() {
        List<AssistantBenchmarkHistoryResponse.RunSummary> runs = repository
                .findTop20ByOrderByStartedAtDesc()
                .stream()
                .map(this::toSummary)
                .toList();
        return new AssistantBenchmarkHistoryResponse(runs);
    }

    @Transactional(readOnly = true)
    public AssistantBenchmarkResponse detail(String runId) {
        AssistantEvaluationRun run = repository.findByRunId(runId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "助手评测历史不存在"));
        AssistantBenchmarkResponse stored = readStoredResult(run);
        return new AssistantBenchmarkResponse(
                run.getRunId(),
                run.getStatus(),
                run.getMode(),
                run.getVerdict(),
                run.getCaseCount(),
                run.getCompletedCount(),
                run.getJudgedCount(),
                run.getPassedCount(),
                run.getFailedCount(),
                run.getErrorCount(),
                run.getHardGateFailureCount(),
                run.getToolTracePassedCount(),
                run.getIntentEvaluatedCount(),
                run.getIntentMatchedCount(),
                run.getPassRate(),
                run.getExecutionSuccessRate(),
                run.getEndToEndPassRate(),
                run.getToolTracePassRate(),
                run.getIntentAccuracy(),
                run.getIntentMacroF1(),
                run.getMacroCategoryPassRate(),
                run.getAverageScore(),
                run.getEndToEndAverageScore(),
                run.getElapsedMs(),
                run.getGenerationModel(),
                run.getJudgeModel(),
                run.isIndependentJudge(),
                stored == null ? run.getDatasetVersion() : stored.datasetVersion(),
                stored == null ? run.getDatasetFingerprint() : stored.datasetFingerprint(),
                run.getMessage(),
                run.getStartedAt(),
                run.getFinishedAt(),
                stored == null ? List.of() : stored.intents(),
                stored == null ? List.of() : stored.categories(),
                stored == null ? List.of() : stored.cases()
        );
    }

    @Transactional(readOnly = true)
    public AssistantBenchmarkComparisonResponse compare(String baseRunId, String candidateRunId) {
        if (Objects.equals(baseRunId, candidateRunId)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请选择两次不同的评测运行");
        }
        AssistantBenchmarkResponse base = detail(baseRunId);
        AssistantBenchmarkResponse candidate = detail(candidateRunId);
        List<String> warnings = new ArrayList<>();
        if (!Objects.equals(base.mode(), candidate.mode())) {
            warnings.add("两次运行模式不同，题量和风险结构可能不可直接比较");
        }
        if (base.datasetFingerprint() == null || candidate.datasetFingerprint() == null) {
            warnings.add("至少一条历史记录缺少数据集指纹");
        } else if (!Objects.equals(base.datasetFingerprint(), candidate.datasetFingerprint())) {
            warnings.add("两次运行使用的数据集内容不同");
        }

        Map<String, AssistantBenchmarkResponse.CaseResult> baseCases = base.cases().stream()
                .collect(Collectors.toMap(AssistantBenchmarkResponse.CaseResult::id, Function.identity()));
        Map<String, AssistantBenchmarkResponse.CaseResult> candidateCases = candidate.cases().stream()
                .collect(Collectors.toMap(AssistantBenchmarkResponse.CaseResult::id, Function.identity()));
        Set<String> commonCaseIds = new TreeSet<>(baseCases.keySet());
        commonCaseIds.retainAll(candidateCases.keySet());
        if (commonCaseIds.size() != baseCases.size() || commonCaseIds.size() != candidateCases.size()) {
            warnings.add("两次运行的逐题集合不同，仅比较共同用例");
        }

        List<AssistantBenchmarkComparisonResponse.CaseChange> caseChanges = new ArrayList<>();
        for (String caseId : commonCaseIds) {
            AssistantBenchmarkResponse.CaseResult before = baseCases.get(caseId);
            AssistantBenchmarkResponse.CaseResult after = candidateCases.get(caseId);
            String changeType = changeType(before, after);
            if (changeType != null) {
                caseChanges.add(new AssistantBenchmarkComparisonResponse.CaseChange(
                        caseId, after.question(), changeType,
                        before.status(), after.status(), before.passed(), after.passed(),
                        before.selectedSkill(), after.selectedSkill(),
                        before.toolTracePassed(), after.toolTracePassed()));
            }
        }

        Map<String, AssistantBenchmarkResponse.CategoryResult> baseCategories = base.categories().stream()
                .collect(Collectors.toMap(AssistantBenchmarkResponse.CategoryResult::category, Function.identity()));
        Map<String, AssistantBenchmarkResponse.CategoryResult> candidateCategories = candidate.categories().stream()
                .collect(Collectors.toMap(AssistantBenchmarkResponse.CategoryResult::category, Function.identity()));
        Set<String> categoryNames = new TreeSet<>(baseCategories.keySet());
        categoryNames.addAll(candidateCategories.keySet());
        List<AssistantBenchmarkComparisonResponse.CategoryDelta> categoryDeltas = categoryNames.stream()
                .map(category -> categoryDelta(
                        category, baseCategories.get(category), candidateCategories.get(category)))
                .toList();

        int fixed = (int) caseChanges.stream()
                .filter(item -> "FIXED".equals(item.changeType())).count();
        int regressed = (int) caseChanges.stream()
                .filter(item -> "REGRESSED".equals(item.changeType())).count();
        return new AssistantBenchmarkComparisonResponse(
                base.runId(), candidate.runId(), base.mode(), candidate.mode(),
                base.datasetVersion(), candidate.datasetVersion(),
                base.datasetFingerprint(), candidate.datasetFingerprint(),
                warnings.isEmpty(), warnings,
                delta(base.passRate(), candidate.passRate()),
                delta(base.endToEndPassRate(), candidate.endToEndPassRate()),
                delta(base.intentAccuracy(), candidate.intentAccuracy()),
                delta(base.toolTracePassRate(), candidate.toolTracePassRate()),
                delta(base.averageScore(), candidate.averageScore()),
                candidate.elapsedMs() - base.elapsedMs(),
                fixed, regressed, categoryDeltas, caseChanges);
    }

    private String changeType(AssistantBenchmarkResponse.CaseResult before,
                              AssistantBenchmarkResponse.CaseResult after) {
        if (!before.passed() && after.passed()) {
            return "FIXED";
        }
        if (before.passed() && !after.passed()) {
            return "REGRESSED";
        }
        if (!Objects.equals(before.status(), after.status())
                || !Objects.equals(before.selectedSkill(), after.selectedSkill())
                || before.toolTracePassed() != after.toolTracePassed()) {
            return "CHANGED";
        }
        return null;
    }

    private AssistantBenchmarkComparisonResponse.CategoryDelta categoryDelta(
            String category,
            AssistantBenchmarkResponse.CategoryResult before,
            AssistantBenchmarkResponse.CategoryResult after) {
        double basePassRate = before == null ? 0.0 : before.passRate();
        double candidatePassRate = after == null ? 0.0 : after.passRate();
        double baseAverageScore = before == null ? 0.0 : before.averageScore();
        double candidateAverageScore = after == null ? 0.0 : after.averageScore();
        return new AssistantBenchmarkComparisonResponse.CategoryDelta(
                category, basePassRate, candidatePassRate,
                delta(basePassRate, candidatePassRate),
                baseAverageScore, candidateAverageScore,
                delta(baseAverageScore, candidateAverageScore));
    }

    private double delta(double before, double after) {
        return Math.round((after - before) * 10_000.0) / 10_000.0;
    }

    private AssistantBenchmarkResponse readStoredResult(AssistantEvaluationRun run) {
        if (run.getResultJson() == null || run.getResultJson().isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(run.getResultJson(), AssistantBenchmarkResponse.class);
        } catch (Exception e) {
            log.warn("Unable to parse assistant evaluation result. runId={}", run.getRunId(), e);
            return null;
        }
    }

    private AssistantBenchmarkHistoryResponse.RunSummary toSummary(AssistantEvaluationRun run) {
        return new AssistantBenchmarkHistoryResponse.RunSummary(
                run.getRunId(),
                run.getMode(),
                run.getStatus(),
                run.getVerdict(),
                run.getCaseCount(),
                run.getCompletedCount(),
                run.getJudgedCount(),
                run.getPassedCount(),
                run.getFailedCount(),
                run.getErrorCount(),
                run.getHardGateFailureCount(),
                run.getToolTracePassedCount(),
                run.getIntentEvaluatedCount(),
                run.getIntentMatchedCount(),
                run.getPassRate(),
                run.getExecutionSuccessRate(),
                run.getEndToEndPassRate(),
                run.getToolTracePassRate(),
                run.getIntentAccuracy(),
                run.getIntentMacroF1(),
                run.getMacroCategoryPassRate(),
                run.getAverageScore(),
                run.getEndToEndAverageScore(),
                run.getElapsedMs(),
                run.getGenerationModel(),
                run.getJudgeModel(),
                run.isIndependentJudge(),
                run.getDatasetVersion(),
                run.getDatasetFingerprint(),
                run.getStartedBy(),
                run.getMessage(),
                run.getStartedAt(),
                run.getFinishedAt()
        );
    }
}
