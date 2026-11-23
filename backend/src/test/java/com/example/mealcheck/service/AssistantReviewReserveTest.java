package com.example.mealcheck.service;

import com.example.mealcheck.config.AppProperties;
import com.example.mealcheck.dto.KnowledgeSnippet;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AssistantReviewReserveTest {
    final ObjectMapper json=new ObjectMapper();
    final AppProperties props=new AppProperties();
    final ExecutorService executor=Executors.newSingleThreadExecutor();
    final AssistantFunctionToolRegistry tools=mock(AssistantFunctionToolRegistry.class);
    final AssistantChatModelFactory factory=mock(AssistantChatModelFactory.class);
    final ChatModel generation=mock(ChatModel.class), review=mock(ChatModel.class);
    final KnowledgeSnippet source=new KnowledgeSnippet(7L,"标签","请核对实际标签。",.95);
    final AtomicLong clock=new AtomicLong(System.nanoTime());

    @BeforeEach void setup() {
        when(factory.create(any())).thenReturn(generation);
        when(factory.create(any(),eq(0.0),eq("diet-assistant-evidence-review"))).thenReturn(review);
        when(tools.specifications()).thenReturn(List.of(ToolSpecification.builder().name("search_diet_knowledge").build()));
        when(tools.execute(any(),any(),anyLong())).thenReturn(new AssistantFunctionToolRuntime.ToolResult("evidence",List.of(source)));
    }
    @AfterEach void close(){executor.shutdownNow();Thread.interrupted();}
    AssistantFunctionCallingService service(boolean realTime) {
        return new AssistantFunctionCallingService(props,tools,factory,executor,realTime?System::nanoTime:clock::get);
    }
    String candidate() {
        return "{\"claims\":[{\"kind\":\"ANSWER\",\"text\":\"请核对实际标签。\",\"evidence\":[{\"sourceId\":7,\"quote\":\"请核对实际标签。\"}]}]}";
    }
    String verdict(boolean supported,boolean missingKnowledge) throws Exception {
        var prepared=AssistantGroundingGuard.prepare(json.readTree(candidate()),List.of(source));
        var result=(ObjectNode)GroundingReviewFixture.verdict(prepared.answer(),supported?Set.of():Set.of("c0"));
        if(missingKnowledge) result.putArray("knowledgeMissing").add("保存条件依据");
        result.put("searchQuery","酸奶保存条件");return result.toString();
    }
    ChatResponse response(String text){return ChatResponse.builder().aiMessage(AiMessage.from(text)).build();}
    void advance(long seconds){clock.addAndGet(TimeUnit.SECONDS.toNanos(seconds));}
    ObjectNode ask(AssistantFunctionCallingService service) throws Exception {
        return (ObjectNode)json.readTree(service.askWithRuntime("system",List.of(),"酸奶标签怎么记录？",tools).answer());
    }

    @Test void reviewReserveStopsSupplementAndKeepsOnlyReviewedAnswer() throws Exception {
        when(generation.chat(any(ChatRequest.class))).thenAnswer(c->{advance(45);return response(candidate());});
        when(review.chat(any(ChatRequest.class))).thenAnswer(c->{advance(15);return response(verdict(true,true));});
        var answer=ask(service(false));
        assertThat(answer.path("summary").asText()).contains("请核对实际标签","剩余时间不足");
        assertThat(answer.path("partial").asBoolean()).isTrue();
        assertThat(answer.path("informationStatus").asText()).isEqualTo("MISSING_KNOWLEDGE");
        assertThat(answer.path("groundingIssues").toString()).contains("REVIEW_BUDGET_RESERVED");
        verify(tools).execute(any(),any(),anyLong());verify(generation).chat(any(ChatRequest.class));verify(review).chat(any(ChatRequest.class));
        var generationLimit=ArgumentCaptor.forClass(Duration.class);verify(factory).create(generationLimit.capture());
        assertThat(generationLimit.getValue()).isEqualTo(Duration.ofSeconds(60));
        var reviewLimit=ArgumentCaptor.forClass(Duration.class);verify(factory).create(reviewLimit.capture(),eq(0.0),anyString());
        assertThat(reviewLimit.getValue()).isEqualTo(Duration.ofSeconds(44)); // 1s finalization is inside the reserved tail.
    }

    @Test void unreviewedCandidateIsNotReleasedWhenRepairHasNoBudget() throws Exception {
        when(generation.chat(any(ChatRequest.class))).thenAnswer(c->{advance(45);return response(candidate());});
        when(review.chat(any(ChatRequest.class))).thenAnswer(c->{advance(15);return response(verdict(false,false));});
        var answer=ask(service(false));
        assertThat(answer.path("refused").asBoolean()).isTrue();
        assertThat(answer.path("summary").asText()).contains("时限内完成证据核验").doesNotContain("请核对实际标签");
        assertThat(answer.path("claims")).isEmpty();verify(generation).chat(any(ChatRequest.class));
    }

    @Test void sufficientReviewedAnswerNeedsNoExpansionAndIsNotMarkedAsTimeFailure() throws Exception {
        when(generation.chat(any(ChatRequest.class))).thenAnswer(c->{advance(45);return response(candidate());});
        when(review.chat(any(ChatRequest.class))).thenAnswer(c->{advance(15);return response(verdict(true,false));});
        var answer=ask(service(false));
        assertThat(answer.path("partial").asBoolean()).isFalse();
        assertThat(answer.path("groundingIssues").toString()).doesNotContain("REVIEW_BUDGET_RESERVED");
        verify(generation).chat(any(ChatRequest.class));verify(review).chat(any(ChatRequest.class));
    }

    @Test void supplementalReadAndRepairShareExpansionDeadlineAndLeaveSecondReviewTime() throws Exception {
        when(generation.chat(any(ChatRequest.class))).thenAnswer(c->{advance(10);return response(candidate());});
        when(review.chat(any(ChatRequest.class))).thenAnswer(c->{advance(10);return response(verdict(true,true));})
                .thenAnswer(c->response(verdict(true,false)));
        var answer=ask(service(false));
        assertThat(answer.path("informationStatus").asText()).isEqualTo("SUFFICIENT");
        var limits=ArgumentCaptor.forClass(Duration.class);verify(factory,times(2)).create(limits.capture());
        assertThat(limits.getAllValues()).containsExactly(Duration.ofSeconds(60),Duration.ofSeconds(40));
        var toolLimits=ArgumentCaptor.forClass(Long.class);verify(tools,times(2)).execute(any(),any(),toolLimits.capture());
        assertThat(toolLimits.getAllValues().get(1)).isLessThanOrEqualTo(clock.get()+TimeUnit.SECONDS.toNanos(30));
        verify(review,times(2)).chat(any(ChatRequest.class));
    }

    @Test void exhaustedBudgetDoesNotEvenConstructOrDispatchAModel() throws Exception {
        try(var context=AiCallContext.open(null,Duration.ofSeconds(25))) {
            var answer=ask(service(true));
            assertThat(answer.path("summary").asText()).contains("时限内完成证据核验");
        }
        verifyNoInteractions(factory,generation,review);verify(tools,never()).execute(any(),any(),anyLong());
    }

    @Test void generationTimeoutInterruptsWorkerAndNeverStartsReviewOrRepair() throws Exception {
        props.getAssistantFunctionCalling().setTotalTimeoutSeconds(5);
        props.getAssistantFunctionCalling().setReviewReserveSeconds(3);
        var interrupted=new CountDownLatch(1);
        when(generation.chat(any(ChatRequest.class))).thenAnswer(c->{
            try {new CountDownLatch(1).await(6,TimeUnit.SECONDS);}catch(InterruptedException ignored){interrupted.countDown();}
            return response(candidate()); // Dependency swallowed interruption: still must not publish or continue.
        });
        var started=System.nanoTime();var answer=ask(service(true));
        assertThat(Duration.ofNanos(System.nanoTime()-started)).isLessThan(Duration.ofSeconds(4));
        assertThat(answer.path("claims")).isEmpty();assertThat(interrupted.await(1,TimeUnit.SECONDS)).isTrue();
        executor.submit(()->{}).get(1,TimeUnit.SECONDS);
        verify(generation).chat(any(ChatRequest.class));verifyNoInteractions(review);verify(tools).execute(any(),any(),anyLong());
    }

    @Test void cancellationFromDispatchGuardPropagatesAndPreventsFollowupCalls() throws Exception {
        var cancelled=new AtomicBoolean();
        when(generation.chat(any(ChatRequest.class))).thenAnswer(c->{cancelled.set(true);return response(candidate());});
        var context=new AiCallContext.Context(null,System.nanoTime()+TimeUnit.SECONDS.toNanos(90),()->{
            if(cancelled.get())throw new CancellationException("user cancelled");
        });
        try(var scope=AiCallContext.restore(context)) {
            assertThatThrownBy(()->ask(service(true))).isInstanceOf(CancellationException.class);
        }
        verify(generation).chat(any(ChatRequest.class));verifyNoInteractions(review);verify(tools).execute(any(),any(),anyLong());
    }

    @Test void repairTimeoutPreservesReviewedSubsetAndNeverStartsASecondReview() throws Exception {
        props.getAssistantFunctionCalling().setTotalTimeoutSeconds(5);
        props.getAssistantFunctionCalling().setReviewReserveSeconds(2);
        when(generation.chat(any(ChatRequest.class))).thenReturn(response(candidate())).thenAnswer(c->{
            try {new CountDownLatch(1).await(6,TimeUnit.SECONDS);}catch(InterruptedException ignored) { }
            return response("UNREVIEWED_REPAIR");
        });
        when(review.chat(any(ChatRequest.class))).thenReturn(response(verdict(true,true)));
        var answer=ask(service(true));
        assertThat(answer.path("summary").asText()).contains("请核对实际标签","剩余时间不足").doesNotContain("UNREVIEWED_REPAIR");
        assertThat(answer.path("partial").asBoolean()).isTrue();
        verify(generation,times(2)).chat(any(ChatRequest.class));verify(review).chat(any(ChatRequest.class));
        verify(tools,times(2)).execute(any(),any(),anyLong());
    }

    @Test void reviewTimeoutLeavesFinalizationTimeAndDoesNotReleaseUnverifiedClaims() throws Exception {
        props.getAssistantFunctionCalling().setTotalTimeoutSeconds(5);
        props.getAssistantFunctionCalling().setReviewReserveSeconds(2);
        when(generation.chat(any(ChatRequest.class))).thenReturn(response(candidate()));
        when(review.chat(any(ChatRequest.class))).thenAnswer(c->{
            try {new CountDownLatch(1).await(6,TimeUnit.SECONDS);}catch(InterruptedException ignored) { }
            return response(verdict(true,false));
        });
        var started=System.nanoTime();var answer=ask(service(true));
        assertThat(Duration.ofNanos(System.nanoTime()-started)).isLessThan(Duration.ofSeconds(5));
        assertThat(answer.path("summary").asText()).contains("时限内完成证据核验");
        assertThat(answer.path("claims")).isEmpty();
        verify(generation).chat(any(ChatRequest.class));verify(review).chat(any(ChatRequest.class));
        verify(tools).execute(any(),any(),anyLong());
    }

    @Test void cancellingWaitingCallerInterruptsInFlightModelAndPreventsNextDispatch() throws Exception {
        var started=new CountDownLatch(1);var interrupted=new CountDownLatch(1);
        when(generation.chat(any(ChatRequest.class))).thenAnswer(c->{
            started.countDown();
            try {new CountDownLatch(1).await(6,TimeUnit.SECONDS);}catch(InterruptedException ignored){interrupted.countDown();}
            return response(candidate());
        });
        var caller=Executors.newSingleThreadExecutor();
        try {
            var pending=caller.submit(()->ask(service(true)));
            assertThat(started.await(2,TimeUnit.SECONDS)).isTrue();
            pending.cancel(true);
            assertThat(interrupted.await(2,TimeUnit.SECONDS)).isTrue();
            caller.submit(()->{}).get(2,TimeUnit.SECONDS);
            executor.submit(()->{}).get(2,TimeUnit.SECONDS);
            verify(generation).chat(any(ChatRequest.class));verifyNoInteractions(review);
            verify(tools).execute(any(),any(),anyLong());
        } finally {caller.shutdownNow();}
    }

    @Test void budgetConsumedBySupplementStopsRepairAndKeepsPreviouslyReviewedSubset() throws Exception {
        props.getAssistantFunctionCalling().setTotalTimeoutSeconds(60);
        when(generation.chat(any(ChatRequest.class))).thenAnswer(c->{advance(15);return response(candidate());});
        when(review.chat(any(ChatRequest.class))).thenAnswer(c->{advance(10);return response(verdict(true,true));});
        when(tools.execute(any(),any(),anyLong())).thenReturn(new AssistantFunctionToolRuntime.ToolResult("primary",List.of(source)))
                .thenAnswer(c->{advance(5);return new AssistantFunctionToolRuntime.ToolResult("supplement",List.of(source));});
        var answer=ask(service(false));
        assertThat(answer.path("summary").asText()).contains("请核对实际标签","剩余时间不足");
        verify(generation).chat(any(ChatRequest.class));verify(review).chat(any(ChatRequest.class));
        verify(tools,times(2)).execute(any(),any(),anyLong());
    }

    @Test void emptyPrimaryResultWithExpiredExpansionDoesNotPretendTheKnowledgeIsMissing() throws Exception {
        when(tools.execute(any(),any(),anyLong())).thenAnswer(c->{
            advance(6);return new AssistantFunctionToolRuntime.ToolResult("empty",List.of());
        });
        try(var scope=AiCallContext.open(null,Duration.ofSeconds(35))) {
            var answer=ask(service(false));
            assertThat(answer.path("summary").asText()).contains("时限内完成证据核验").doesNotContain("知识库证据不足");
        }
        verify(tools).execute(any(),any(),anyLong());verifyNoInteractions(factory,generation,review);
    }
}
