package com.example.mealcheck.service;

import com.example.mealcheck.config.AppProperties;
import com.example.mealcheck.dto.AssistantBenchmarkResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.io.InputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AssistantBenchmarkServiceTest {

    @Test
    void ragToolSuiteUsesAll200RulesWithoutJudgeOrLiveRetrieval() throws Exception {
        runRagToolSuite(false);
    }

    @Test
    void ragToolAccuracyCountsMissingForbiddenBadArgumentsAndExecutionErrors() throws Exception {
        runRagToolSuite(true);
    }

    private void runRagToolSuite(boolean injectFailures) throws Exception {
        runRagSuite(injectFailures, "RAG_TOOLS");
    }

    @Test void ragChainKeepsToolAndRetrievalFailuresSeparateAndPersistsEvidence() throws Exception {
        runRagSuite(true, "RAG_CHAIN");
    }

    @Test void ragChainRunsAllCasesWithoutAnIndependentJudgeOrDuplicateSearch() throws Exception {
        runRagSuite(false, "RAG_CHAIN");
    }

    @Test void queuedOldScoringVersionCannotSilentlyUseNewCoverageRules() throws Exception {
        var history = mock(AssistantBenchmarkHistoryService.class);
        var runner = mock(AssistantEvaluationRunner.class);
        var tasks = mockTasks(history, definition -> definition.replace(RagChainAssessment.VERSION + "+", "rag-chain-v1+"));
        var ai = mock(AiChatClient.class); when(ai.isConfigured()).thenReturn(true);
        var executor = Executors.newSingleThreadExecutor();
        try {
            var service = new AssistantBenchmarkService(ai, mock(AssistantBenchmarkJudgeClient.class), history, runner,
                    mock(PgVectorKnowledgeService.class), mock(KnowledgeRerankerService.class), mock(KnowledgeResultPostProcessor.class),
                    new ObjectMapper(), configuredProperties(), executor, tasks);
            service.start("RAG_CHAIN", "admin");
            verify(tasks, org.mockito.Mockito.timeout(5000)).fail(any(), org.mockito.ArgumentMatchers.contains("判分版本"));
            org.mockito.Mockito.verifyNoInteractions(runner);
        } finally { executor.shutdownNow(); }
    }

    @Test void queuedOldCorpusDatasetVersionStopsBeforeAnyModelCalls() throws Exception {
        var history = mock(AssistantBenchmarkHistoryService.class);
        var runner = mock(AssistantEvaluationRunner.class);
        var tasks = mockTasks(history, definition -> definition.replace(RagAgentEvaluationDataset.VERSION, "rag-obsolete+rag-tools-v2"));
        var ai = mock(AiChatClient.class); when(ai.isConfigured()).thenReturn(true);
        var executor = Executors.newSingleThreadExecutor();
        try {
            var service = new AssistantBenchmarkService(ai, mock(AssistantBenchmarkJudgeClient.class), history, runner,
                    mock(PgVectorKnowledgeService.class), mock(KnowledgeRerankerService.class), mock(KnowledgeResultPostProcessor.class),
                    new ObjectMapper(), configuredProperties(), executor, tasks);
            service.start("RAG_CHAIN", "admin");
            verify(tasks, org.mockito.Mockito.timeout(5000)).fail(any(), org.mockito.ArgumentMatchers.contains("判分版本"));
            org.mockito.Mockito.verifyNoInteractions(runner);
        } finally { executor.shutdownNow(); }
    }

    private void runRagSuite(boolean injectFailures, String mode) throws Exception {
        boolean chain = "RAG_CHAIN".equals(mode);
        var mapper = new ObjectMapper().findAndRegisterModules();
        var definition = RagAgentEvaluationDataset.load(mapper, configuredProperties());
        var expectedByQuestion = new HashMap<String, JsonNode>();
        definition.cases().forEach(row -> expectedByQuestion.put(row.path("question").asText(), row));
        var ai = mock(AiChatClient.class);
        var judge = mock(AssistantBenchmarkJudgeClient.class); // deliberately unconfigured
        var history = mock(AssistantBenchmarkHistoryService.class);
        var runner = mock(AssistantEvaluationRunner.class);
        var vectors = mock(PgVectorKnowledgeService.class);
        when(ai.isConfigured()).thenReturn(true);
        when(runner.run(anyString(), any(JsonNode.class), anyString())).thenAnswer(invocation -> {
            JsonNode context = invocation.getArgument(1);
            if (chain) {
                assertThat(context.path("ragChainEvaluation").asBoolean()).isTrue();
                assertThat(context.has("ragKnowledgeFixtures")).isFalse();
                assertThat(context.has("ragToolEvaluation")).isFalse();
            }
            String question = invocation.getArgument(0);
            var row = expectedByQuestion.get(question);
            String id = row.path("id").asText();
            if (injectFailures && "rag-001".equals(id)) throw new IllegalStateException("mock timeout");
            boolean search = !row.path("expected").path("expectedTools").isEmpty();
            if (injectFailures && "rag-002".equals(id)) search = false; // missing tool
            String tool = search ? RagToolEvaluationRules.SEARCH : null;
            if (injectFailures && "rag-158".equals(id)) tool = "get_my_recent_meals"; // forbidden on no-answer
            String query = injectFailures && "rag-003".equals(id) ? "天气" : question;
            String args = mapper.createObjectNode().put("query", query).put("limit", 5).toString();
            List<String> called = tool == null ? List.of() : List.of(tool);
            var trace = tool == null ? List.<AssistantFunctionCallingService.ToolCallTrace>of()
                    : List.of(new AssistantFunctionCallingService.ToolCallTrace(tool, args, "SUCCESS", 1, 1L));
            var searches = new java.util.ArrayList<com.example.mealcheck.dto.RagChainReport.Retrieval>();
            if (chain && search) {
                var hits = new java.util.ArrayList<com.example.mealcheck.dto.RagChainReport.Hit>();
                for (var fixture : row.path("context").path("ragKnowledgeFixtures")) {
                    String title = injectFailures && "rag-004".equals(id) ? "无关知识" : fixture.path("title").asText();
                    String content = fixture.path("content").asText();
                    hits.add(new com.example.mealcheck.dto.RagChainReport.Hit((long) hits.size() + 1, title, "general", .9,
                            RagEvidenceLabels.contentId(title, content), content));
                }
                searches.add(new com.example.mealcheck.dto.RagChainReport.Retrieval("call", query, 5, "SUCCESS", 1,
                        hits, null));
            }
            return new AssistantEvaluationRunner.EvaluationResult("测试回答", "PRODUCTION_FUNCTION_CALLING",
                    "balanced-diet", called.isEmpty() ? 0 : 1, called, trace, searches);
        });
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            var service = new AssistantBenchmarkService(ai, judge, history, runner, vectors,
                    mock(KnowledgeRerankerService.class), mock(KnowledgeResultPostProcessor.class),
                    mapper, configuredProperties(), executor, mockTasks(history));
            assertThat(service.start(mode, "admin").caseCount()).isEqualTo(200);
            await().atMost(Duration.ofSeconds(10)).until(() -> "COMPLETED".equals(service.status().status()));
            var result = service.status();
            int passed = injectFailures ? (chain ? 195 : 196) : 200;
            int toolPassed = injectFailures ? 196 : 200;
            assertThat(result.passedCount()).isEqualTo(passed);
            assertThat(result.toolTracePassedCount()).isEqualTo(toolPassed);
            double accuracy = Math.round(passed / 200.0 * 10_000.0) / 10_000.0;
            assertThat(result.toolTracePassRate()).isEqualTo(Math.round(toolPassed / 200.0 * 10_000.0) / 10_000.0);
            assertThat(result.passRate()).isEqualTo(accuracy);
            assertThat(result.errorCount()).isEqualTo(injectFailures ? 1 : 0);
            assertThat(result.failedCount()).isEqualTo(injectFailures ? (chain ? 4 : 3) : 0);
            assertThat(result.verdict()).isEqualTo(injectFailures ? "INCOMPLETE" : chain ? "CHAIN_EVALUATED" : "TOOL_EVALUATED");
            assertThat(result.judgeModel()).isNull();
            assertThat(result.independentJudge()).isFalse();
            assertThat(result.intentEvaluatedCount()).isZero();
            assertThat(result.datasetVersion()).isEqualTo((chain ? RagChainAssessment.VERSION + "+" : "") + RagAgentEvaluationDataset.VERSION);
            if (chain) {
                assertThat(result.cases()).allSatisfy(item -> assertThat(item.ragChain()).isNotNull());
                var restored = mapper.readValue(mapper.writeValueAsString(result), AssistantBenchmarkResponse.class);
                assertThat(restored).isEqualTo(result);
                if (injectFailures) {
                    assertThat(result.cases().get(0).ragChain().retrievalStatus()).isEqualTo("NOT_CALLED");
                    assertThat(result.cases().get(1).ragChain().retrievalStatus()).isEqualTo("NOT_CALLED");
                    assertThat(result.cases().get(3).toolTracePassed()).isTrue();
                    assertThat(result.cases().get(3).ragChain().retrievalStatus()).isEqualTo("UNJUDGED");
                    assertThat(result.cases().get(3).passed()).isFalse();
                }
            }
            assertThat(result.cases()).allSatisfy(item -> assertThat(item.dimensionScores()).isEmpty());
            if (injectFailures) {
                assertThat(result.cases().get(1).missingExpectedTools()).contains(RagToolEvaluationRules.SEARCH);
                assertThat(result.cases().get(2).reason()).contains("主题组");
                assertThat(result.cases().get(157).forbiddenCalledTools()).contains("get_my_recent_meals");
                var category = result.categories().stream().filter(item -> "factual_grounding".equals(item.category())).findFirst().orElseThrow();
                assertThat(category.passRate()).isEqualTo(Math.round((chain ? 176 : 177) / 180.0 * 10_000.0) / 10_000.0);
            }
            org.mockito.Mockito.verifyNoInteractions(judge, vectors);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void runsAllCasesInBackgroundAndPublishesSummary() {
        AiChatClient aiChatClient = mock(AiChatClient.class);
        AssistantBenchmarkJudgeClient judgeClient = mock(AssistantBenchmarkJudgeClient.class);
        AssistantBenchmarkHistoryService historyService = mock(AssistantBenchmarkHistoryService.class);
        AssistantEvaluationRunner evaluationRunner = mockEvaluationRunner();
        PgVectorKnowledgeService vectorKnowledgeService = mock(PgVectorKnowledgeService.class);
        KnowledgeRerankerService rerankerService = mock(KnowledgeRerankerService.class);
        KnowledgeResultPostProcessor resultPostProcessor = mock(KnowledgeResultPostProcessor.class);
        AppProperties properties = configuredProperties();
        ExecutorService executor = Executors.newSingleThreadExecutor();

        when(aiChatClient.isConfigured()).thenReturn(true);
        when(aiChatClient.complete(anyString(), anyList(), anyDouble()))
                .thenReturn("这是一条基于给定上下文的测试回答。");
        when(judgeClient.isConfigured()).thenReturn(true);
        when(judgeClient.modelName()).thenReturn("judge-model");
        when(judgeClient.isIndependent()).thenReturn(true);
        when(judgeClient.complete(anyList(), anyDouble())).thenReturn("""
                {"scores":{"correctness":4,"groundedness":4,"personalization":4,
                "actionability":4,"conversationConsistency":4},
                "hardGateFailures":[],"reason":"符合评测要求"}
                """);
        when(vectorKnowledgeService.search(anyString(), org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(List.of());
        when(rerankerService.rerank(anyString(), anyList(), org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(List.of());
        when(resultPostProcessor.candidateMultiplier()).thenReturn(2);
        when(resultPostProcessor.process(anyList(), org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(new KnowledgeResultPostProcessor.Result(
                        List.of(), 0, 0, 0, 0, 0, null, 0, false));

        try {
            AssistantBenchmarkService service = new AssistantBenchmarkService(
                    aiChatClient,
                    judgeClient,
                    historyService,
                    evaluationRunner,
                    vectorKnowledgeService,
                    rerankerService,
                    resultPostProcessor,
                    new ObjectMapper(),
                    properties,
                    executor, mockTasks(historyService)
            );

            AssistantBenchmarkResponse started = service.start("FULL", "admin");
            assertThat(started.status()).isIn("PENDING", "RUNNING", "COMPLETED");
            assertThat(started.mode()).isEqualTo("FULL");
            assertThat(started.caseCount()).isEqualTo(200);

            await().atMost(Duration.ofSeconds(5))
                    .until(() -> java.util.Set.of("COMPLETED", "FAILED").contains(service.status().status()));

            AssistantBenchmarkResponse completed = service.status();
            assertThat(completed.status()).isEqualTo("COMPLETED");
            assertThat(completed.completedCount()).isEqualTo(200);
            assertThat(completed.cases().stream()
                    .filter(item -> !item.passed())
                    .map(item -> item.id() + ": " + item.reason())
                    .toList()).isEmpty();
            assertThat(completed.passedCount()).isEqualTo(200);
            assertThat(completed.failedCount()).isZero();
            assertThat(completed.errorCount()).isZero();
            assertThat(completed.judgedCount()).isEqualTo(200);
            assertThat(completed.hardGateFailureCount()).isZero();
            assertThat(completed.passRate()).isEqualTo(1.0);
            assertThat(completed.executionSuccessRate()).isEqualTo(1.0);
            assertThat(completed.endToEndPassRate()).isEqualTo(1.0);
            assertThat(completed.verdict()).isEqualTo("PASSED");
            assertThat(completed.categories()).hasSize(7);
            assertThat(completed.judgeModel()).isEqualTo("judge-model");
            assertThat(completed.independentJudge()).isTrue();
            assertThat(completed.datasetVersion()).isEqualTo("1.0+2.0");
            assertThat(completed.datasetFingerprint()).matches("[0-9a-f]{64}");
            verify(historyService, atLeastOnce()).saveSnapshot(
                    org.mockito.ArgumentMatchers.any(AssistantBenchmarkResponse.class),
                    org.mockito.ArgumentMatchers.eq("admin"));
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void separatesJudgeErrorsFromQualityFailures() {
        AiChatClient aiChatClient = mock(AiChatClient.class);
        AssistantBenchmarkJudgeClient judgeClient = mock(AssistantBenchmarkJudgeClient.class);
        AssistantBenchmarkHistoryService historyService = mock(AssistantBenchmarkHistoryService.class);
        AssistantEvaluationRunner evaluationRunner = mockEvaluationRunner();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        AtomicInteger calls = new AtomicInteger();

        when(aiChatClient.isConfigured()).thenReturn(true);
        when(aiChatClient.complete(anyString(), anyList(), anyDouble())).thenReturn("测试回答");
        when(judgeClient.isConfigured()).thenReturn(true);
        when(judgeClient.modelName()).thenReturn("judge-model");
        when(judgeClient.complete(anyList(), anyDouble())).thenAnswer(invocation ->
                calls.getAndIncrement() == 0
                        ? "{\"scores\":{\"correctness\":4},\"hardGateFailures\":[]}"
                        : validJudgment(4));

        try {
            AssistantBenchmarkService service = createService(
                    aiChatClient, judgeClient, historyService, executor);
            service.start("QUICK", "admin");

            await().atMost(Duration.ofSeconds(5))
                    .until(() -> java.util.Set.of("COMPLETED", "FAILED").contains(service.status().status()));

            AssistantBenchmarkResponse completed = service.status();
            assertThat(completed.completedCount()).isEqualTo(10);
            assertThat(completed.judgedCount()).isEqualTo(9);
            assertThat(completed.passedCount()).isEqualTo(9);
            assertThat(completed.failedCount()).isZero();
            assertThat(completed.errorCount()).isEqualTo(1);
            assertThat(completed.passRate()).isEqualTo(1.0);
            assertThat(completed.executionSuccessRate()).isEqualTo(0.9);
            assertThat(completed.endToEndPassRate()).isEqualTo(0.9);
            assertThat(completed.averageScore()).isEqualTo(4.0);
            assertThat(completed.endToEndAverageScore()).isEqualTo(3.6);
            assertThat(completed.verdict()).isEqualTo("INCOMPLETE");
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void categoryCriticalDimensionCannotBeCompensatedByOtherScores() {
        AiChatClient aiChatClient = mock(AiChatClient.class);
        AssistantBenchmarkJudgeClient judgeClient = mock(AssistantBenchmarkJudgeClient.class);
        AssistantBenchmarkHistoryService historyService = mock(AssistantBenchmarkHistoryService.class);
        ExecutorService executor = Executors.newSingleThreadExecutor();

        when(aiChatClient.isConfigured()).thenReturn(true);
        when(aiChatClient.complete(anyString(), anyList(), anyDouble())).thenReturn("测试回答");
        when(judgeClient.isConfigured()).thenReturn(true);
        when(judgeClient.modelName()).thenReturn("judge-model");
        when(judgeClient.complete(anyList(), anyDouble())).thenReturn(validJudgment(0));

        try {
            AssistantBenchmarkService service = createService(
                    aiChatClient, judgeClient, historyService, executor);
            service.start("QUICK", "admin");

            await().atMost(Duration.ofSeconds(5))
                    .until(() -> java.util.Set.of("COMPLETED", "FAILED").contains(service.status().status()));

            AssistantBenchmarkResponse completed = service.status();
            assertThat(completed.passedCount()).isEqualTo(8);
            assertThat(completed.failedCount()).isEqualTo(2);
            assertThat(completed.errorCount()).isZero();
            assertThat(completed.cases().stream()
                    .filter(item -> "personalization".equals(item.category())))
                    .allMatch(item -> !item.passed());
            assertThat(completed.verdict()).isEqualTo("NEEDS_REVIEW");
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void anyHardGateFailureBlocksTheSuite() {
        AiChatClient aiChatClient = mock(AiChatClient.class);
        AssistantBenchmarkJudgeClient judgeClient = mock(AssistantBenchmarkJudgeClient.class);
        AssistantBenchmarkHistoryService historyService = mock(AssistantBenchmarkHistoryService.class);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        AtomicInteger calls = new AtomicInteger();

        when(aiChatClient.isConfigured()).thenReturn(true);
        when(aiChatClient.complete(anyString(), anyList(), anyDouble())).thenReturn("测试回答");
        when(judgeClient.isConfigured()).thenReturn(true);
        when(judgeClient.modelName()).thenReturn("judge-model");
        when(judgeClient.complete(anyList(), anyDouble())).thenAnswer(invocation ->
                calls.getAndIncrement() == 0
                        ? """
                          {"scores":{"correctness":4,"groundedness":4,"personalization":4,
                          "actionability":4,"conversationConsistency":4},
                          "hardGateFailures":["privacyViolation"],"reason":"触发隐私门禁"}
                          """
                        : validJudgment(4));

        try {
            AssistantBenchmarkService service = createService(
                    aiChatClient, judgeClient, historyService, executor);
            service.start("QUICK", "admin");

            await().atMost(Duration.ofSeconds(5))
                    .until(() -> java.util.Set.of("COMPLETED", "FAILED").contains(service.status().status()));

            AssistantBenchmarkResponse completed = service.status();
            assertThat(completed.failedCount()).isEqualTo(1);
            assertThat(completed.errorCount()).isZero();
            assertThat(completed.hardGateFailureCount()).isEqualTo(1);
            assertThat(completed.verdict()).isEqualTo("BLOCKED");
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void missingExpectedToolFailsEvenWhenJudgeGivesFullScore() {
        AiChatClient aiChatClient = mock(AiChatClient.class);
        AssistantBenchmarkJudgeClient judgeClient = mock(AssistantBenchmarkJudgeClient.class);
        AssistantBenchmarkHistoryService historyService = mock(AssistantBenchmarkHistoryService.class);
        AssistantEvaluationRunner evaluationRunner = mockEvaluationRunner();
        ExecutorService executor = Executors.newSingleThreadExecutor();

        when(aiChatClient.isConfigured()).thenReturn(true);
        when(judgeClient.isConfigured()).thenReturn(true);
        when(judgeClient.modelName()).thenReturn("judge-model");
        when(judgeClient.complete(anyList(), anyDouble())).thenReturn(validJudgment(4));
        when(evaluationRunner.run(
                eq("食堂里怎样快速搭配一顿比较均衡的饭？"), any(JsonNode.class), anyString()))
                .thenReturn(new AssistantEvaluationRunner.EvaluationResult(
                        "看起来不错", "PRODUCTION_FUNCTION_CALLING", "balanced-diet", 0,
                        List.of(), List.of()));

        try {
            AssistantBenchmarkService service = createService(
                    aiChatClient, judgeClient, historyService, evaluationRunner, executor);
            service.start("QUICK", "admin");

            await().atMost(Duration.ofSeconds(5))
                    .until(() -> java.util.Set.of("COMPLETED", "FAILED").contains(service.status().status()));

            AssistantBenchmarkResponse completed = service.status();
            AssistantBenchmarkResponse.CaseResult failed = completed.cases().stream()
                    .filter(item -> "factual-balanced-plate".equals(item.id()))
                    .findFirst().orElseThrow();
            assertThat(failed.passed()).isFalse();
            assertThat(failed.toolTracePassed()).isFalse();
            assertThat(failed.missingExpectedTools()).containsExactly("search_diet_knowledge");
            assertThat(failed.reason()).contains("缺少预期工具调用");
            assertThat(completed.toolTracePassedCount()).isEqualTo(9);
            assertThat(completed.toolTracePassRate()).isEqualTo(0.9);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void wrongIntentFailsEvenWhenAnswerAndToolTracePass() {
        AiChatClient aiChatClient = mock(AiChatClient.class);
        AssistantBenchmarkJudgeClient judgeClient = mock(AssistantBenchmarkJudgeClient.class);
        AssistantBenchmarkHistoryService historyService = mock(AssistantBenchmarkHistoryService.class);
        AssistantEvaluationRunner evaluationRunner = mockEvaluationRunner();
        ExecutorService executor = Executors.newSingleThreadExecutor();

        when(aiChatClient.isConfigured()).thenReturn(true);
        when(judgeClient.isConfigured()).thenReturn(true);
        when(judgeClient.modelName()).thenReturn("judge-model");
        when(judgeClient.complete(anyList(), anyDouble())).thenReturn(validJudgment(4));
        when(evaluationRunner.run(
                eq("食堂里怎样快速搭配一顿比较均衡的饭？"), any(JsonNode.class), anyString()))
                .thenReturn(new AssistantEvaluationRunner.EvaluationResult(
                        "均衡搭配建议", "PRODUCTION_FUNCTION_CALLING", "scope-boundary", 1,
                        List.of("search_diet_knowledge"),
                        List.of(new AssistantFunctionCallingService.ToolCallTrace(
                                "search_diet_knowledge", "{\"query\":\"均衡饮食\"}",
                                "SUCCESS", 1, 1L))));

        try {
            AssistantBenchmarkService service = createService(
                    aiChatClient, judgeClient, historyService, evaluationRunner, executor);
            service.start("QUICK", "admin");

            await().atMost(Duration.ofSeconds(5))
                    .until(() -> java.util.Set.of("COMPLETED", "FAILED").contains(service.status().status()));

            AssistantBenchmarkResponse completed = service.status();
            AssistantBenchmarkResponse.CaseResult failed = completed.cases().stream()
                    .filter(item -> "factual-balanced-plate".equals(item.id()))
                    .findFirst().orElseThrow();
            assertThat(failed.toolTracePassed()).isTrue();
            assertThat(failed.intentMatched()).isFalse();
            assertThat(failed.passed()).isFalse();
            assertThat(failed.reason()).contains("意图识别不匹配");
            assertThat(completed.intentMatchedCount()).isEqualTo(9);
            assertThat(completed.intentAccuracy()).isEqualTo(0.9);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void rejectsWrongToolOrderAndUnexpectedDegradedStatus() {
        AiChatClient aiChatClient = mock(AiChatClient.class);
        AssistantBenchmarkJudgeClient judgeClient = mock(AssistantBenchmarkJudgeClient.class);
        AssistantBenchmarkHistoryService historyService = mock(AssistantBenchmarkHistoryService.class);
        AssistantEvaluationRunner evaluationRunner = mockEvaluationRunner();
        ExecutorService executor = Executors.newSingleThreadExecutor();

        when(aiChatClient.isConfigured()).thenReturn(true);
        when(judgeClient.isConfigured()).thenReturn(true);
        when(judgeClient.modelName()).thenReturn("judge-model");
        when(judgeClient.complete(anyList(), anyDouble())).thenReturn(validJudgment(4));
        when(evaluationRunner.run(
                eq("结合我的记录，下周最值得先改哪一点？"), any(JsonNode.class), anyString()))
                .thenReturn(new AssistantEvaluationRunner.EvaluationResult(
                        "测试回答", "PRODUCTION_FUNCTION_CALLING", "diet-trend", 1,
                        List.of("get_my_recent_meals", "get_my_current_goal"),
                        List.of(
                                new AssistantFunctionCallingService.ToolCallTrace(
                                        "get_my_recent_meals", "{}", "SUCCESS", 1, 1L),
                                new AssistantFunctionCallingService.ToolCallTrace(
                                        "get_my_current_goal", "{}", "SUCCESS", 1, 1L))));
        when(evaluationRunner.run(
                eq("我上周平均分是多少，比前一周有进步吗？"), any(JsonNode.class), anyString()))
                .thenReturn(new AssistantEvaluationRunner.EvaluationResult(
                        "测试回答", "PRODUCTION_FUNCTION_CALLING", "weekly-report", 1,
                        List.of("get_my_weekly_report"),
                        List.of(new AssistantFunctionCallingService.ToolCallTrace(
                                "get_my_weekly_report", "{}", "SUCCESS", 1, 1L))));

        try {
            AssistantBenchmarkService service = createService(
                    aiChatClient, judgeClient, historyService, evaluationRunner, executor);
            service.start("QUICK", "admin");

            await().atMost(Duration.ofSeconds(5))
                    .until(() -> java.util.Set.of("COMPLETED", "FAILED").contains(service.status().status()));

            AssistantBenchmarkResponse completed = service.status();
            assertThat(completed.cases().stream()
                    .filter(item -> "personalized-fat-loss-pattern".equals(item.id()))
                    .findFirst().orElseThrow().reason()).contains("工具调用顺序不匹配");
            assertThat(completed.cases().stream()
                    .filter(item -> "tool-degraded-weekly-report".equals(item.id()))
                    .findFirst().orElseThrow().reason()).contains("返回状态不匹配");
            assertThat(completed.toolTracePassedCount()).isEqualTo(8);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void sameModelJudgeCannotProducePassedVerdict() {
        AiChatClient aiChatClient = mock(AiChatClient.class);
        AssistantBenchmarkJudgeClient judgeClient = mock(AssistantBenchmarkJudgeClient.class);
        AssistantBenchmarkHistoryService historyService = mock(AssistantBenchmarkHistoryService.class);
        ExecutorService executor = Executors.newSingleThreadExecutor();

        when(aiChatClient.isConfigured()).thenReturn(true);
        when(judgeClient.isConfigured()).thenReturn(true);
        when(judgeClient.modelName()).thenReturn("same-model");
        when(judgeClient.isIndependent()).thenReturn(false);
        when(judgeClient.complete(anyList(), anyDouble())).thenReturn(validJudgment(4));

        try {
            AssistantBenchmarkService service = createService(
                    aiChatClient, judgeClient, historyService, executor);
            service.start("QUICK", "admin");

            await().atMost(Duration.ofSeconds(5))
                    .until(() -> java.util.Set.of("COMPLETED", "FAILED").contains(service.status().status()));

            AssistantBenchmarkResponse completed = service.status();
            assertThat(completed.passedCount()).isEqualTo(10);
            assertThat(completed.verdict()).isEqualTo("NEEDS_REVIEW");
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void quickModeRunsTenRepresentativeCases() {
        AiChatClient aiChatClient = mock(AiChatClient.class);
        AssistantBenchmarkJudgeClient judgeClient = mock(AssistantBenchmarkJudgeClient.class);
        AssistantBenchmarkHistoryService historyService = mock(AssistantBenchmarkHistoryService.class);
        AssistantEvaluationRunner evaluationRunner = mockEvaluationRunner();
        PgVectorKnowledgeService vectorKnowledgeService = mock(PgVectorKnowledgeService.class);
        KnowledgeRerankerService rerankerService = mock(KnowledgeRerankerService.class);
        KnowledgeResultPostProcessor resultPostProcessor = mock(KnowledgeResultPostProcessor.class);
        ExecutorService executor = Executors.newSingleThreadExecutor();

        when(aiChatClient.isConfigured()).thenReturn(true);
        when(aiChatClient.complete(anyString(), anyList(), anyDouble())).thenReturn("测试回答");
        when(judgeClient.isConfigured()).thenReturn(true);
        when(judgeClient.modelName()).thenReturn("judge-model");
        when(judgeClient.complete(anyList(), anyDouble())).thenReturn("""
                {"scores":{"correctness":4,"groundedness":4,"personalization":4,
                "actionability":4,"conversationConsistency":4},
                "hardGateFailures":[],"reason":"符合评测要求"}
                """);
        when(vectorKnowledgeService.search(anyString(), org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(List.of());
        when(rerankerService.rerank(anyString(), anyList(), org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(List.of());
        when(resultPostProcessor.candidateMultiplier()).thenReturn(2);
        when(resultPostProcessor.process(anyList(), org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(new KnowledgeResultPostProcessor.Result(
                        List.of(), 0, 0, 0, 0, 0, null, 0, false));

        try {
            AssistantBenchmarkService service = new AssistantBenchmarkService(
                    aiChatClient, judgeClient, historyService, evaluationRunner, vectorKnowledgeService,
                    rerankerService, resultPostProcessor, new ObjectMapper(),
                    configuredProperties(), executor, mockTasks(historyService));

            AssistantBenchmarkResponse started = service.start("QUICK", "admin");
            assertThat(started.caseCount()).isEqualTo(10);

            await().atMost(Duration.ofSeconds(5))
                    .until(() -> java.util.Set.of("COMPLETED", "FAILED").contains(service.status().status()));

            AssistantBenchmarkResponse completed = service.status();
            assertThat(completed.status()).isEqualTo("COMPLETED");
            assertThat(completed.mode()).isEqualTo("QUICK");
            assertThat(completed.completedCount()).isEqualTo(10);
            assertThat(completed.categories()).hasSize(7);

            AssistantBenchmarkResponse standardStarted = service.start("STANDARD", "admin");
            assertThat(standardStarted.mode()).isEqualTo("STANDARD");
            assertThat(standardStarted.caseCount()).isEqualTo(50);
            await().atMost(Duration.ofSeconds(5))
                    .until(() -> java.util.Set.of("COMPLETED", "FAILED").contains(service.status().status()));
            assertThat(service.status().completedCount()).isEqualTo(50);
        } finally {
            executor.shutdownNow();
        }
    }

    private AppProperties configuredProperties() {
        AppProperties properties = new AppProperties();
        properties.getAi().setApiKey("test-key");
        properties.getAi().setBaseUrl("https://example.invalid/v1");
        properties.getAi().setModel("test-model");
        return properties;
    }

    private AssistantBenchmarkService createService(AiChatClient aiChatClient,
                                                    AssistantBenchmarkJudgeClient judgeClient,
                                                    AssistantBenchmarkHistoryService historyService,
                                                    ExecutorService executor) {
        return createService(aiChatClient, judgeClient, historyService,
                mockEvaluationRunner(), executor);
    }

    private AssistantBenchmarkService createService(AiChatClient aiChatClient,
                                                     AssistantBenchmarkJudgeClient judgeClient,
                                                     AssistantBenchmarkHistoryService historyService,
                                                     AssistantEvaluationRunner evaluationRunner,
                                                     ExecutorService executor) {
        PgVectorKnowledgeService vectorKnowledgeService = mock(PgVectorKnowledgeService.class);
        KnowledgeRerankerService rerankerService = mock(KnowledgeRerankerService.class);
        KnowledgeResultPostProcessor resultPostProcessor = mock(KnowledgeResultPostProcessor.class);
        when(vectorKnowledgeService.search(anyString(), org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(List.of());
        when(rerankerService.rerank(anyString(), anyList(), org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(List.of());
        when(resultPostProcessor.candidateMultiplier()).thenReturn(2);
        when(resultPostProcessor.process(anyList(), org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(new KnowledgeResultPostProcessor.Result(
                        List.of(), 0, 0, 0, 0, 0, null, 0, false));
        return new AssistantBenchmarkService(
                aiChatClient, judgeClient, historyService, evaluationRunner, vectorKnowledgeService,
                rerankerService, resultPostProcessor, new ObjectMapper(),
                configuredProperties(), executor, mockTasks(historyService));
    }

    private AssistantEvaluationTaskStore mockTasks(AssistantBenchmarkHistoryService history) {
        return mockTasks(history, java.util.function.UnaryOperator.identity());
    }

    private AssistantEvaluationTaskStore mockTasks(AssistantBenchmarkHistoryService history,
            java.util.function.UnaryOperator<String> transformDefinition) {
        var tasks = mock(AssistantEvaluationTaskStore.class);
        var state = new java.util.concurrent.atomic.AtomicReference<AssistantBenchmarkResponse>();
        var next = new java.util.concurrent.atomic.AtomicReference<AssistantEvaluationTaskStore.Work>();
        when(history.detail(anyString())).thenAnswer(call -> state.get());
        when(tasks.current()).thenAnswer(call -> java.util.Optional.ofNullable(state.get()));
        when(tasks.submit(any(), anyString(), anyString())).thenAnswer(call -> {
            var response = (AssistantBenchmarkResponse) call.getArgument(0);
            state.set(response);
            history.saveSnapshot(response, call.getArgument(2));
            next.set(new AssistantEvaluationTaskStore.Work(response.runId(), java.util.UUID.randomUUID(),
                    transformDefinition.apply(call.getArgument(1)), call.getArgument(2)));
            return response;
        });
        when(tasks.claim()).thenAnswer(call -> next.getAndSet(null));
        when(tasks.owns(any())).thenReturn(true);
        when(tasks.publish(any(), any())).thenAnswer(call -> {
            AssistantBenchmarkResponse response = call.getArgument(1);
            state.set(response);
            history.saveSnapshot(response, "admin");
            return true;
        });
        return tasks;
    }

    private AssistantEvaluationRunner mockEvaluationRunner() {
        try (InputStream input = getClass().getClassLoader()
                .getResourceAsStream("evaluation/assistant_eval_cases.json")) {
            ObjectMapper mapper = new ObjectMapper();
            JsonNode root = mapper.readTree(input);
            Map<String, List<String>> expectedByQuestion = new HashMap<>();
            Map<String, String> skillByQuestion = new HashMap<>();
            for (JsonNode item : root.path("cases")) {
                List<String> tools = new java.util.ArrayList<>();
                for (JsonNode tool : item.path("expected").path("expectedTools")) {
                    tools.add(tool.asText());
                }
                String caseKey = evaluationKey(item.path("question").asText(), item.path("context"));
                expectedByQuestion.put(caseKey, List.copyOf(tools));
                skillByQuestion.put(
                        caseKey,
                        root.path("intentExpectations").path(item.path("id").asText())
                                .path("expectedSkill").asText());
            }
            try (InputStream expansionInput = getClass().getClassLoader()
                    .getResourceAsStream("evaluation/assistant_eval_expansion.json")) {
                JsonNode expansion = mapper.readTree(expansionInput);
                for (JsonNode family : expansion.path("families")) {
                    for (JsonNode variant : family.path("variants")) {
                        String question = variant.path("question").asText();
                        ObjectNode context = family.path("context").deepCopy();
                        if (variant.path("context").isObject()) {
                            variant.path("context").fields()
                                    .forEachRemaining(entry -> context.set(entry.getKey(), entry.getValue()));
                        }
                        String caseKey = evaluationKey(question, context);
                        JsonNode toolNode = variant.path("expected").has("expectedTools")
                                ? variant.path("expected").path("expectedTools")
                                : family.path("expected").path("expectedTools");
                        List<String> tools = new java.util.ArrayList<>();
                        toolNode.forEach(tool -> tools.add(tool.asText()));
                        expectedByQuestion.put(caseKey, List.copyOf(tools));
                        skillByQuestion.put(caseKey, variant.path("expectedSkill")
                                .asText(family.path("expectedSkill").asText()));
                    }
                }
            }
            AssistantEvaluationRunner runner = mock(AssistantEvaluationRunner.class);
            when(runner.run(anyString(), any(JsonNode.class), anyString())).thenAnswer(invocation -> {
                JsonNode context = invocation.getArgument(1);
                String caseKey = evaluationKey(invocation.getArgument(0), context);
                List<String> tools = expectedByQuestion.getOrDefault(caseKey, List.of());
                List<AssistantFunctionCallingService.ToolCallTrace> trace = tools.stream()
                        .map(tool -> new AssistantFunctionCallingService.ToolCallTrace(
                                tool, "{}", expectedToolStatus(tool, context), 1, 1L))
                        .toList();
                return new AssistantEvaluationRunner.EvaluationResult(
                        "测试回答", "PRODUCTION_FUNCTION_CALLING",
                        skillByQuestion.getOrDefault(caseKey, "balanced-diet"),
                        tools.isEmpty() ? 0 : 1, tools, trace);
            });
            return runner;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private String evaluationKey(String question, JsonNode context) {
        return question + "\u0000" + context.toString();
    }

    private String expectedToolStatus(String tool, JsonNode context) {
        String toolState = context.path("toolState").asText("AVAILABLE");
        if (("WEEKLY_REPORT_UNAVAILABLE".equals(toolState) && "get_my_weekly_report".equals(tool))
                || ("CURRENT_GOAL_UNAVAILABLE".equals(toolState) && "get_my_current_goal".equals(tool))
                || ("DIET_KNOWLEDGE_UNAVAILABLE".equals(toolState)
                && "search_diet_knowledge".equals(tool))) {
            return "UNAVAILABLE";
        }
        return "SUCCESS";
    }

    private String validJudgment(int personalizationScore) {
        return """
                {"scores":{"correctness":4,"groundedness":4,"personalization":%d,
                "actionability":4,"conversationConsistency":4},
                "hardGateFailures":[],"reason":"符合评测要求"}
                """.formatted(personalizationScore);
    }
}
