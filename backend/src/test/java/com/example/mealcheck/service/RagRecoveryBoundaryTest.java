package com.example.mealcheck.service;

import com.example.mealcheck.config.AppProperties;
import com.example.mealcheck.dto.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class RagRecoveryBoundaryTest {
    final ObjectMapper json=new ObjectMapper();

    @Test void cancellationAndExpiredDeadlinesDoNotBecomeRerankFallback() {
        var props=new AppProperties();props.getKnowledge().setRerankEnabled(true);
        props.getKnowledge().setRerankApiKey("test");props.getKnowledge().setRerankModel("test");
        props.getKnowledge().setRerankUrl("https://example.test/rerank");
        var transport=mock(AiHttpTransport.class);var metrics=mock(ApplicationObservability.class);
        var rerank=new KnowledgeRerankerService(props,json,metrics,transport);
        when(transport.send(anyString(),any(),any())).thenThrow(new CancellationException("cancelled"));
        assertThatThrownBy(()->rerank.rerank("q",List.of(new KnowledgeSnippet(1L,"t","c",1)),1)).isInstanceOf(CancellationException.class);
        verify(metrics,never()).recordRerank(anyLong(),anyString(),eq("fallback"),anyInt());
        try(var scope=AiCallContext.open(1L,Duration.ofNanos(-1))) {
            assertThatThrownBy(AiCallContext::checkExecution).isInstanceOf(RemoteCallGuard.RemoteCallDeadlineException.class);
        }
        Thread.currentThread().interrupt();
        try {assertThatThrownBy(AiCallContext::checkExecution).isInstanceOf(RemoteCallGuard.RemoteCallRejectedException.class);}
        finally {Thread.interrupted();}
    }

    @Test void degradedAndEmptyAfterFilteringResultsAreNeverCachedAndNextQueryReranksAgain() {
        for(boolean empty:List.of(false,true)) {
            var props=new AppProperties();if(empty)props.getKnowledge().setResultMinScore(1);
            var vector=mock(PgVectorKnowledgeService.class);var redis=mock(RedisCacheService.class);
            var rerank=mock(KnowledgeRerankerService.class);when(rerank.isConfigured()).thenReturn(true);
            var snippets=List.of(new KnowledgeSnippet(1L,"t","c","general",.8));
            when(vector.search(anyString(),anyInt())).thenReturn(snippets);
            when(rerank.rerank(anyString(),anyList(),anyInt())).thenReturn(snippets);
            when(redis.getJson(anyString(),any())).thenReturn(Optional.empty());
            when(redis.singleFlight(anyString(),any(),any())).thenAnswer(c->((Supplier<?>)c.getArgument(1)).get());
            var index=new KnowledgeIndexService(vector,props,redis,mock(RagEvaluationService.class),new MarkdownKnowledgeChunker(props),
                    mock(ApplicationObservability.class),rerank,new KnowledgeResultPostProcessor(props));
            index.search("q",5);index.search("q",5);
            verify(rerank,times(2)).rerank(anyString(),anyList(),anyInt());
            verify(redis,never()).setJsonWithJitter(anyString(),any(),any(),any());
        }
    }

    @Test void timedOutToolCannotPublishCacheEvenWhenDependencySwallowsInterrupt() throws Exception {
        var props=new AppProperties();props.getAssistantTools().setTimeoutMillis(200);
        var vector=mock(PgVectorKnowledgeService.class);var redis=mock(RedisCacheService.class);
        var rerank=mock(KnowledgeRerankerService.class);
        when(redis.getJson(anyString(),any())).thenReturn(Optional.empty());
        when(redis.singleFlight(anyString(),any(),any())).thenAnswer(c->((Supplier<?>)c.getArgument(1)).get());
        var observed=new java.util.concurrent.atomic.AtomicReference<AiCallContext.Context>();
        when(vector.search(anyString(),anyInt())).thenAnswer(c->{
            observed.set(AiCallContext.capture());
            try {new CountDownLatch(1).await(3,TimeUnit.SECONDS);} catch(InterruptedException ignored) { }
            return List.of(new KnowledgeSnippet(1L,"t","c",1));
        });
        var index=new KnowledgeIndexService(vector,props,redis,mock(RagEvaluationService.class),new MarkdownKnowledgeChunker(props),
                mock(ApplicationObservability.class),rerank,new KnowledgeResultPostProcessor(props));
        var executor=Executors.newSingleThreadExecutor();
        try {
            var registry=new AssistantFunctionToolRegistry(index,mock(MealAnalysisService.class),mock(WeeklyReportService.class),
                    mock(UserGoalService.class),mock(NextMealService.class),json,executor,executor,props,mock(ApplicationObservability.class));
            var user=new com.example.mealcheck.entity.UserAccount();user.setId(42L);
            var request=dev.langchain4j.agent.tool.ToolExecutionRequest.builder().name("search_diet_knowledge").arguments("{\"query\":\"q\"}").build();
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);
            assertThatThrownBy(()->registry.execute(user,request,deadline)).hasMessageContaining("timed out");
            executor.submit(()->{}).get(2,TimeUnit.SECONDS); // Wait until the cancelled handler has exited.
            assertThat(observed.get().userId()).isEqualTo(42L);
            assertThat(observed.get().deadlineNanos()).isLessThan(deadline);
            verify(rerank,never()).rerank(anyString(),anyList(),anyInt());
            verify(redis,never()).setJsonWithJitter(anyString(),any(),any(),any());
            assertThat(executor.submit(AiCallContext::capture).get()).isNull();
        } finally {executor.shutdownNow();}
    }

    ObjectNode prepared() {
        var source=new KnowledgeSnippet(7L,"t","请记录食品名称。",1);
        var input=json.createObjectNode();input.putArray("claims").addObject().put("kind","ANSWER").put("text","请记录食品名称。")
                .putArray("evidence").addObject().put("sourceId",7).put("quote",source.getContent());
        return AssistantGroundingGuard.prepare(input,List.of(source)).answer();
    }
    @Test void independentGapsDriveFourStatesAndTransitionAfterUserAndEvidenceAreSupplied() {
        var answer=prepared();
        // Same session: both missing -> input supplied -> knowledge supplied.
        for(int flags:new int[]{3,2,0,1}) {
            var verdict=(ObjectNode)GroundingReviewFixture.verdict(answer,Set.of());
            var inputs=verdict.putArray("inputMissing");var knowledge=verdict.putArray("knowledgeMissing");
            if((flags&1)!=0)inputs.add("用户输入：用户输入：实际冷藏温度");
            if((flags&2)!=0)knowledge.add("保存温度核对原则");
            // Deliberately inconsistent model enum/flag cannot override independent assessments.
            verdict.put("informationStatus","MISSING_INPUT").put("needsMoreEvidence",false);
            var checked=AssistantGroundingGuard.reviewedCurrent(answer,verdict);
            assertThat(checked.answer().path("informationStatus").asText()).isEqualTo(switch(flags){case 3->"MISSING_BOTH";case 2->"MISSING_KNOWLEDGE";case 1->"MISSING_INPUT";default->"SUFFICIENT";});
            assertThat(checked.issues()).isEmpty();
            assertThat(checked.answer().path("missingInformation").toString()).doesNotContain("用户输入：用户输入：");
        }
        var incomplete=(ObjectNode)GroundingReviewFixture.verdict(answer,Set.of());incomplete.remove("knowledgeMissing");
        assertThat(AssistantGroundingGuard.reviewedCurrent(answer,incomplete).usable()).isFalse();
    }

    @Test void informationLimitationIsNotMistakenForAnEmpiricalCause() {
        assertThat(AssistantClaimCoverage.strengthGap("因此无法根据照片确定沙拉酱里用了多少油。","照片无法可靠提供实际调味油量。")).isNull();
        assertThat(AssistantClaimCoverage.strengthGap("因此会导致体重下降。","两者存在相关。")).isNotNull();
        assertThat(AssistantClaimCoverage.strengthGap("因此无法确定是否会导致疾病。","照片无法判断。")).isNotNull();
        assertThat(AssistantClaimCoverage.strengthGap("因此显著降低风险。","目前证据不足。")).isNotNull();
        assertThat(AssistantClaimCoverage.strengthGap("各人并不平均。","每人实际取用未必相同，各人不一定平均。")).isNotNull();
        assertThat(AssistantClaimCoverage.strengthGap("各人不一定平均。","每人实际取用未必相同，各人不一定平均。")).isNull();
    }

    @Test void hiddenFoodCannotScoreEvenAtHighConfidenceButReadableLabelsCan() {
        var unknown=new FoodItem("外带热饮","drink",.95,"封盖纸杯");unknown.setIdentityEvidence("UNKNOWN");
        var visible=new FoodItem("米饭","staple",.9,"米粒可见");visible.setIdentityEvidence("CONTENT_VISIBLE");
        var label=new FoodItem("牛奶","dairy",.9,"标签：纯牛奶");label.setIdentityEvidence("LABEL_READABLE");
        var recognition=new RecognitionResult();recognition.setFoodImage(true);recognition.setFoods(List.of(unknown,visible,label));
        assertThat(new MealScoringService().acceptedFoods(recognition)).extracting(FoodItem::getName).containsExactly("米饭","牛奶");
        assertThat(MealScoringService.uncertainFoods(recognition.getFoods())).containsExactly(unknown);
        assertThat(RecognitionSemanticValidator.review(unknown).getIdentityEvidence()).isEqualTo("UNKNOWN");
    }
    @Test void rejectedEqualityCannotLeaveAnUnfinishedCalculationAsAnAnswer() {
        var source=new KnowledgeSnippet(7L,"称量","没有其他损失或添加时可作差；若有洒出或分享，差值不能全部归为本人吃下。",1);
        var input=json.createObjectNode();
        input.putArray("claims").addObject().put("kind","ANSWER").put("text","用整果重量减去果皮和果核的剩余重量，就是这次实际吃下的重量。")
                .putArray("evidence").addObject().put("sourceId",7).put("quote",source.getContent());
        var p=AssistantGroundingGuard.prepare(input,List.of(source));
        assertThat(p.usable()).isFalse();
        assertThat(p.issues()).anySatisfy(i->assertThat(i.code()).isEqualTo("MISSING_SOURCE_CONDITIONS"));
    }
}
