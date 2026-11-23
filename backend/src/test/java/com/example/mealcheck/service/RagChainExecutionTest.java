package com.example.mealcheck.service;

import com.example.mealcheck.config.AppProperties;
import com.example.mealcheck.dto.KnowledgeSnippet;
import com.example.mealcheck.service.skill.AssistantSkillPlan;
import com.example.mealcheck.service.skill.AssistantSkillRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.*;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.junit.jupiter.api.*;
import java.util.List;
import java.util.concurrent.Executors;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class RagChainExecutionTest {
    final ObjectMapper json = new ObjectMapper();
    final AppProperties properties = new AppProperties();
    final PgVectorKnowledgeService vectors = mock(PgVectorKnowledgeService.class);
    final RedisCacheService cache = mock(RedisCacheService.class);
    final RagEvaluationService statistics = mock(RagEvaluationService.class);
    final KnowledgeRerankerService reranker = mock(KnowledgeRerankerService.class);
    final AssistantFunctionToolRegistry registry = mock(AssistantFunctionToolRegistry.class);
    final AssistantChatModelFactory models = mock(AssistantChatModelFactory.class);
    final ChatModel model = mock(ChatModel.class);
    final AssistantSkillRegistry skills = mock(AssistantSkillRegistry.class);
    final java.util.concurrent.ExecutorService executor = Executors.newSingleThreadExecutor();
    KnowledgeIndexService index;
    AssistantEvaluationRunner runner;

    @BeforeEach void setup() {
        index = new KnowledgeIndexService(vectors, properties, cache, statistics,
                mock(MarkdownKnowledgeChunker.class), mock(ApplicationObservability.class), reranker,
                new KnowledgeResultPostProcessor(properties));
        when(reranker.rerank(anyString(), anyList(), anyInt())).thenAnswer(call -> call.getArgument(1));
        when(registry.specifications()).thenReturn(List.of(ToolSpecification.builder().name("search_diet_knowledge").build()));
        when(models.create(any())).thenReturn(model);
        when(skills.select(anyString(), anyList())).thenReturn(new AssistantSkillPlan("balanced-diet", "均衡", "balanced-diet", "", "一般知识"));
        var functions = new AssistantFunctionCallingService(properties, registry, models, executor);
        runner = new AssistantEvaluationRunner(functions,
                new AssistantEvaluationToolRuntimeFactory(registry, json, index),
                new AssistantFunctionPromptFactory(), skills, json);
    }
    @AfterEach void close() { executor.shutdownNow(); }

    AssistantEvaluationRunner.EvaluationResult run(String question) throws Exception {
        // Even a leaked old fixture must not replace real retrieval in chain mode.
        return runner.run(question, json.readTree("""
                {"ragChainEvaluation":true,"ragKnowledgeFixtures":[{"id":999,"title":"伪造命中","content":"不能用于检索"}]}
                """), "不能提前提供预期答案");
    }

    @Test void productionOrchestrationRetrievesOnceAndScoresThoseSameResults() throws Exception {
        var source = new KnowledgeSnippet(7L, "减脂目标建议", "保持正常就餐。", "fat_loss", .95);
        when(vectors.search(anyString(), anyInt())).thenReturn(List.of(source));
        when(model.chat(any(ChatRequest.class))).thenReturn(
                ChatResponse.builder().aiMessage(AiMessage.from("""
                {"summary":"保持正常就餐。","refused":false,"suggestions":[],"referenceIds":[7],
                 "claims":[{"text":"保持正常就餐。","quote":"保持正常就餐。","sourceId":7}]}
                """)).build(),
                ChatResponse.builder().aiMessage(AiMessage.from("{\"supported\":true}")).build());
        var result = run("减脂时晚餐怎么吃更合适？");
        assertThat(result.calledTools()).containsExactly("search_diet_knowledge");
        assertThat(result.toolTrace()).singleElement().satisfies(trace -> assertThat(trace.origin()).isEqualTo("ORCHESTRATOR"));
        assertThat(result.retrievals()).singleElement().satisfies(search -> {
            assertThat(search.query()).isEqualTo("减脂时晚餐怎么吃更合适？");
            assertThat(search.requestedLimit()).isEqualTo(5);
            assertThat(search.hits()).extracting(hit -> hit.id()).containsExactly(7L);
        });
        var report = RagChainAssessment.assess(json.readTree("{\"expectedTitleKeywords\":[\"减脂\"]}"), result.retrievals());
        assertThat(report.retrievalPassed()).isTrue();
        assertThat(report.firstRelevantRank()).isEqualTo(1);
        verify(vectors, times(1)).search(eq("减脂时晚餐怎么吃更合适？"), anyInt());
        verifyNoMoreInteractions(vectors);
        verifyNoInteractions(cache, statistics);
        verify(registry, never()).execute(any(), any(), anyLong());
    }

    @Test void emptySearchAndSupplementRemainAMissWithoutInjectingExpectedFixtures() throws Exception {
        when(vectors.search(anyString(), anyInt())).thenReturn(List.of());
        var result = run("减脂时晚餐怎么吃更合适？");
        assertThat(result.retrievals()).hasSize(2).allSatisfy(search -> {
            assertThat(search.status()).isEqualTo("SUCCESS");
            assertThat(search.hits()).isEmpty();
        });
        assertThat(result.retrievals().get(0).query()).isEqualTo("减脂时晚餐怎么吃更合适？");
        assertThat(result.retrievals().get(1).toolCallId()).isEqualTo("evidence-supplement");
        var report = RagChainAssessment.assess(json.readTree("{\"expectedTitleKeywords\":[\"减脂\"]}"), result.retrievals());
        assertThat(report.retrievalStatus()).isEqualTo("FAILED");
        verify(vectors, times(2)).search(anyString(), anyInt());
        verifyNoInteractions(model, cache, statistics);
    }

    @Test void outOfScopeStopsBeforeRetrievalOrModel() throws Exception {
        var result = run("推荐明天涨停的股票。");
        assertThat(result.calledTools()).isEmpty();
        assertThat(result.retrievals()).isEmpty();
        assertThat(RagChainAssessment.assess(json.readTree("{\"expectNoAnswer\":true}"), result.retrievals()).retrievalStatus())
                .isEqualTo("NOT_REQUIRED");
        verifyNoInteractions(vectors, model, cache, statistics);
    }

    @Test void retrievalFailureKeepsItsEvidenceOnTheExecutionException() {
        when(vectors.search(anyString(), anyInt())).thenThrow(new IllegalStateException("private provider detail"));
        assertThatThrownBy(() -> run("减脂时晚餐怎么吃更合适？"))
                .isInstanceOfSatisfying(AssistantEvaluationRunner.EvaluationExecutionException.class, error -> {
                    assertThat(error.toolTrace()).singleElement().satisfies(trace -> assertThat(trace.status()).isEqualTo("ERROR"));
                    assertThat(error.retrievals()).singleElement().satisfies(search -> {
                        assertThat(search.status()).isEqualTo("ERROR");
                        assertThat(search.errorType()).isEqualTo("IllegalStateException");
                        assertThat(search.query()).contains("减脂");
                    });
                });
        verifyNoInteractions(model, cache, statistics);
    }

    @Test void laterModelFailureDoesNotRewriteTheSuccessfulRetrievalStage() throws Exception {
        when(vectors.search(anyString(), anyInt())).thenReturn(List.of(
                new KnowledgeSnippet(7L, "减脂目标建议", "保持正常就餐。", "fat_loss", .95)));
        when(model.chat(any(ChatRequest.class))).thenThrow(new IllegalStateException("generation unavailable"));
        assertThatThrownBy(() -> run("减脂时晚餐怎么吃更合适？"))
                .isInstanceOfSatisfying(AssistantEvaluationRunner.EvaluationExecutionException.class, error -> {
                    assertThat(error.retrievals()).singleElement().satisfies(search -> assertThat(search.status()).isEqualTo("SUCCESS"));
                    try {
                        assertThat(RagChainAssessment.assess(json.readTree("{\"expectedTitleKeywords\":[\"减脂\"]}"),
                                error.retrievals()).retrievalStatus()).isEqualTo("PASSED");
                    } catch (java.io.IOException impossible) { throw new AssertionError(impossible); }
                });
    }

    @Test void forbiddenPersonalToolsAndMalformedArgumentsNeverReachProductionExecution() {
        var runtime = new RagChainToolRuntime(registry, index, json);
        var deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
        assertThatThrownBy(() -> runtime.execute(null, ToolExecutionRequest.builder().name("get_my_recent_meals").arguments("{}").build(), deadline))
                .isInstanceOf(IllegalArgumentException.class);
        for (String args : List.of("{}", "[]", "bad", "{\"query\":\"面包\",\"limit\":6}",
                "{\"query\":\"面包\",\"limit\":1.5}", "{\"query\":\"面包\",\"userId\":1}")) {
            assertThatThrownBy(() -> runtime.execute(null, ToolExecutionRequest.builder().name("search_diet_knowledge").arguments(args).build(), deadline))
                    .as(args).isInstanceOf(IllegalArgumentException.class);
        }
        assertThat(runtime.retrievals()).isEmpty();
        verifyNoInteractions(vectors, cache, statistics);
        verify(registry, never()).execute(any(), any(), anyLong());
    }
}
