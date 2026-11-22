package com.example.mealcheck.service;

import com.example.mealcheck.config.AppProperties;
import com.example.mealcheck.dto.KnowledgeSnippet;
import com.example.mealcheck.dto.assistant.AssistantChatMessage;
import com.fasterxml.jackson.databind.*;
import dev.langchain4j.agent.tool.*;
import dev.langchain4j.data.message.*;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;
import org.springframework.core.io.ClassPathResource;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class AssistantRagSafetyTest {
    final ObjectMapper json=new ObjectMapper();
    final ExecutorService executor=Executors.newSingleThreadExecutor();
    final AssistantFunctionToolRegistry runtime=mock(AssistantFunctionToolRegistry.class);
    final AssistantChatModelFactory factory=mock(AssistantChatModelFactory.class);
    final ChatModel model=mock(ChatModel.class);
    final KnowledgeSnippet source=new KnowledgeSnippet(7L,"标签核对","请核对实际标签。缺少分项数据时保留未知。",.95);
    final AssistantFunctionCallingService service=new AssistantFunctionCallingService(new AppProperties(),runtime,factory,executor);
    @BeforeEach void prepare() {
        when(factory.create(any())).thenReturn(model);
        when(factory.create(any(),eq(0.0),eq("diet-assistant-evidence-review"))).thenReturn(model);
        when(runtime.specifications()).thenReturn(List.of(ToolSpecification.builder().name("search_diet_knowledge").build(),
                ToolSpecification.builder().name("get_my_recent_meals").build()));
        when(runtime.execute(any(),any(),anyLong())).thenReturn(new AssistantFunctionToolRuntime.ToolResult("提供标签知识",List.of(source)));
    }
    @AfterEach void close(){executor.shutdownNow();}
    ChatResponse response(String text){return ChatResponse.builder().aiMessage(AiMessage.from(text)).build();}
    String answer(String text,String quote) {
        var node=json.createObjectNode().put("summary",text).put("refused",false).put("weeklyTrend","records are insufficient");
        node.putArray("suggestions");node.putArray("referenceIds").add(7);
        node.putArray("claims").addObject().put("text",text).put("quote",quote).put("sourceId",7);return node.toString();
    }
    String valid(){return answer("请核对实际标签。","请核对实际标签。");}
    String review(boolean supported) {
        try {
            var prepared=AssistantGroundingGuard.prepare(json.readTree(valid()),List.of(source));
            return GroundingReviewFixture.verdict(prepared.answer(),supported?Set.of():Set.of("c0")).toString();
        } catch(Exception error){throw new IllegalStateException(error);}
    }
    AssistantFunctionCallingService.FunctionCallingResult ask(String question) {
        return service.askWithRuntime("system",List.of(),question,runtime);
    }

    @Test void missingPersonalInputsKeepSupportedExplanationsWithoutSupplementalRetrieval() throws Exception {
        var loader = new CapturedOutputRegressionTest();
        var fixture = loader.fixture("rag-172");
        var sources = loader.sources(fixture);
        when(runtime.execute(any(),any(),anyLong())).thenReturn(new AssistantFunctionToolRuntime.ToolResult("固定来源", sources));
        var prepared = AssistantGroundingGuard.prepare(fixture.path("capturedAnswer"), sources);
        var review = (com.fasterxml.jackson.databind.node.ObjectNode) GroundingReviewFixture.verdict(prepared.answer(),Set.of());
        review.put("informationStatus","MISSING_INPUT");
        review.putArray("missingInformation").add("沙拉酱配方").add("实际用量");
        review.putArray("inputMissing").add("沙拉酱配方").add("实际用量");
        when(model.chat(any(ChatRequest.class))).thenReturn(response(fixture.path("capturedAnswer").toString()),response(review.toString()));
        var result = json.readTree(ask(fixture.path("question").asText()).answer());
        assertThat(result.path("informationStatus").asText()).isEqualTo("MISSING_INPUT");
        assertThat(result.path("refused").asBoolean()).isFalse();
        assertThat(result.path("partial").asBoolean()).isTrue();
        assertThat(result.path("summary").asText()).contains("不能可靠提供");
        verify(model,times(2)).chat(any(ChatRequest.class));
        verify(runtime).execute(any(),any(),anyLong());
    }

    String informationReview(String status) throws Exception {
        var prepared=AssistantGroundingGuard.prepare(json.readTree(valid()),List.of(source));
        var verdict=(com.fasterxml.jackson.databind.node.ObjectNode)GroundingReviewFixture.verdict(prepared.answer(),Set.of());
        boolean knowledge=Set.of("MISSING_KNOWLEDGE","MISSING_BOTH").contains(status);
        verdict.put("informationStatus",status).put("needsMoreEvidence",knowledge).put("searchQuery",knowledge?"酸奶标签保存条件":"");
        var missing=verdict.putArray("missingInformation");
        if(Set.of("MISSING_INPUT","MISSING_BOTH").contains(status))missing.add("用户输入：实际产品标签");
        if(knowledge)missing.add("知识证据：保存条件依据");
        if(Set.of("MISSING_INPUT","MISSING_BOTH").contains(status))verdict.putArray("inputMissing").add("实际产品标签");
        if(knowledge)verdict.putArray("knowledgeMissing").add("保存条件依据");
        return verdict.toString();
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"MISSING_KNOWLEDGE,SUFFICIENT","MISSING_BOTH,MISSING_INPUT","MISSING_BOTH,MISSING_BOTH"})
    void supplementalEvidenceCanResolveKnowledgeButCannotInventMissingUserFacts(String initial,String after) throws Exception {
        var extra=new KnowledgeSnippet(8L,"补充保存条件","保存条件应核对包装。",.9);
        when(runtime.execute(any(),any(),anyLong())).thenReturn(new AssistantFunctionToolRuntime.ToolResult("原问来源",List.of(source)),
                new AssistantFunctionToolRuntime.ToolResult("补充来源",List.of(extra)));
        when(model.chat(any(ChatRequest.class))).thenReturn(response(valid()),response(informationReview(initial)),
                response(valid()),response(informationReview(after)));
        var response=ask("酸奶标签怎么核对？");var answer=json.readTree(response.answer());
        assertThat(answer.path("informationStatus").asText()).isEqualTo(after);
        assertThat(answer.path("partial").asBoolean()).isEqualTo(!"SUFFICIENT".equals(after));
        assertThat(answer.path("refused").asBoolean()).isFalse();
        verify(runtime,times(2)).execute(any(),any(),anyLong());
        assertThat(response.toolTrace()).hasSize(2).allSatisfy(t->assertThat(t.origin()).isEqualTo("ORCHESTRATOR"));
    }

    @Test void missingInputNeverTriggersDocumentSearchEvenWhenAClaimConditionWasRejected() throws Exception {
        var rejected=(com.fasterxml.jackson.databind.node.ObjectNode)json.readTree(informationReview("MISSING_INPUT"));
        ((com.fasterxml.jackson.databind.node.ObjectNode)rejected.path("claims").path(0).path("assertions").path(0).path("checks"))
                .put("conditions",false);
        when(model.chat(any(ChatRequest.class))).thenReturn(response(valid()),response(rejected.toString()),
                response(valid()),response(informationReview("MISSING_INPUT")));
        assertThat(json.readTree(ask("酸奶实际份量不知道怎么记录？").answer()).path("informationStatus").asText()).isEqualTo("MISSING_INPUT");
        verify(runtime).execute(any(),any(),anyLong());
    }

    @Test void completedUserInputReachesReviewButAssistantGuessesAreNotTreatedAsUserFacts() throws Exception {
        var history=List.of(new AssistantChatMessage("user","我已补齐酸奶的产品标签和实际食用份数。"),
                new AssistantChatMessage("assistant","助手此前猜测用油量为某个精确数字。"));
        when(model.chat(any(ChatRequest.class))).thenReturn(response(valid()),response(informationReview("SUFFICIENT")));
        var result=service.askWithRuntime("system",history,"这个饮食标签怎么记录？",runtime);
        var answer=json.readTree(result.answer());
        assertThat(answer.path("informationStatus").asText()).isEqualTo("SUFFICIENT");
        assertThat(answer.path("missingInformation")).isEmpty();assertThat(answer.path("partial").asBoolean()).isFalse();
        var calls=ArgumentCaptor.forClass(ChatRequest.class);verify(model,times(2)).chat(calls.capture());
        String reviewPrompt=calls.getAllValues().get(1).messages().toString();
        assertThat(reviewPrompt).contains("已补齐酸奶的产品标签和实际食用份数").doesNotContain("助手此前猜测用油量");
        verify(runtime).execute(any(),any(),anyLong());
    }

    @Test void guaranteesOriginalQueryBeforeModelAndMarksOrchestratedTrace() throws Exception {
        when(model.chat(any(ChatRequest.class))).thenReturn(response(valid()),response(review(true)));
        var result=ask("无糖酸奶加蜂蜜和麦片怎么记录？");
        var requests=ArgumentCaptor.forClass(ToolExecutionRequest.class);
        verify(runtime).execute(any(),requests.capture(),anyLong());
        assertThat(json.readTree(requests.getValue().arguments()).path("query").asText()).isEqualTo("无糖酸奶加蜂蜜和麦片怎么记录？");
        assertThat(result.toolTrace()).singleElement().satisfies(trace->assertThat(trace.origin()).isEqualTo("ORCHESTRATOR"));
        assertThat(json.readTree(result.answer()).path("weeklyTrend").asText()).isEmpty();
        var calls=ArgumentCaptor.forClass(ChatRequest.class);verify(model,times(2)).chat(calls.capture());
        assertThat(calls.getAllValues().get(0).messages()).anyMatch(item->item instanceof ToolExecutionResultMessage);
        assertThat(calls.getAllValues().get(0).toolSpecifications()).allMatch(spec->spec.name().equals("search_diet_knowledge"));
        assertThat(calls.getAllValues().get(1).toolSpecifications()).isNullOrEmpty();
    }

    @Test void repeatingOriginalQuestionCannotBecomeANewSearchByAppendingFocusTerms() throws Exception {
        var loader=new CapturedOutputRegressionTest();
        var fixture=loader.fixture("rag-172");var sources=loader.sources(fixture);
        when(runtime.execute(any(),any(),anyLong())).thenReturn(new AssistantFunctionToolRuntime.ToolResult("固定来源",sources));
        var prepared=AssistantGroundingGuard.prepare(fixture.path("capturedAnswer"),sources);
        var verdict=(com.fasterxml.jackson.databind.node.ObjectNode)GroundingReviewFixture.verdict(prepared.answer(),Set.of());
        verdict.put("informationStatus","MISSING_INPUT").putArray("missingInformation").add("实际用量或配方记录");
        verdict.putArray("inputMissing").add("实际用量或配方记录");
        var duplicate=ToolExecutionRequest.builder().id("repeat-primary").name("search_diet_knowledge")
                .arguments(json.createObjectNode().put("query",fixture.path("question").asText()).put("limit",5).toString()).build();
        when(model.chat(any(ChatRequest.class))).thenReturn(ChatResponse.builder().aiMessage(AiMessage.from(List.of(duplicate))).build(),
                response(fixture.path("capturedAnswer").toString()),response(verdict.toString()));
        var result=ask(fixture.path("question").asText());
        assertThat(json.readTree(result.answer()).path("informationStatus").asText()).isEqualTo("MISSING_INPUT");
        assertThat(result.toolTrace()).anySatisfy(trace->{
            assertThat(trace.status()).isEqualTo("SKIPPED");assertThat(trace.origin()).isEqualTo("MODEL");
        });
        verify(runtime).execute(any(),any(),anyLong());
        var calls=ArgumentCaptor.forClass(ChatRequest.class);verify(model,times(3)).chat(calls.capture());
        assertThat(calls.getAllValues().get(1).toolSpecifications()).isNullOrEmpty();
    }

    @Test void domainBoundaryBlocksToolsAndModelBeforeExecution() {
        for(String question:List.of("劳动合同纠纷应该依据哪条法律起诉？","抗生素一次服用多少毫克？","明天股票怎么选？")) {
            var result=ask(question);assertThat(result.calledTools()).isEmpty();assertThat(result.answer()).contains("服务范围");
        }
        verifyNoInteractions(model);verify(runtime,never()).execute(any(),any(),anyLong());
    }

    @Test void rejectsPersonalToolOnKnowledgeRoute() {
        var request=ToolExecutionRequest.builder().id("private").name("get_my_recent_meals").arguments("{}").build();
        when(model.chat(any(ChatRequest.class))).thenReturn(ChatResponse.builder().aiMessage(AiMessage.from(List.of(request))).build());
        assertThatThrownBy(()->ask("酸奶怎么选择？")).isInstanceOfSatisfying(
                AssistantFunctionCallingService.FunctionCallingExecutionException.class,error->{
                    assertThat(error.getToolTrace()).hasSize(2);assertThat(error.getToolTrace().get(1).status()).isEqualTo("REJECTED");});
        verify(runtime,times(1)).execute(any(),any(),anyLong());
    }
    @Test void malformedOptionalSupplementCannotErasePrimaryEvidenceOrExecuteAnInvalidRead() throws Exception {
        var bad=ToolExecutionRequest.builder().id("bad-limit").name("search_diet_knowledge")
                .arguments("{\"query\":\"酸奶\",\"limit\":\"5\"}").build();
        when(model.chat(any(ChatRequest.class))).thenReturn(ChatResponse.builder().aiMessage(AiMessage.from(List.of(bad))).build(),
                response(valid()),response(review(true)));
        var result=ask("酸奶如何选择？");
        assertThat(json.readTree(result.answer()).path("refused").asBoolean()).isFalse();
        assertThat(result.toolTrace()).anySatisfy(trace->assertThat(trace.status()).isEqualTo("REJECTED"));
        verify(runtime).execute(any(),any(),anyLong());
        verify(model,times(3)).chat(any(ChatRequest.class));
    }
    @Test void callsAfterTheSupplementBudgetAreSkippedBeforeTheirMalformedArgumentsCanAbortTheAnswer() throws Exception {
        var first=ToolExecutionRequest.builder().id("first").name("search_diet_knowledge")
                .arguments("{\"query\":\"酸奶标签核对\",\"limit\":5}").build();
        var extra=ToolExecutionRequest.builder().id("extra").name("search_diet_knowledge")
                .arguments("{\"query\":\"酸奶标签核对\",\"limit\":\"5\"}").build();
        when(model.chat(any(ChatRequest.class))).thenReturn(ChatResponse.builder().aiMessage(AiMessage.from(List.of(first))).build(),
                ChatResponse.builder().aiMessage(AiMessage.from(List.of(extra))).build(),response(valid()),response(review(true)));
        var result=ask("酸奶如何选择？");
        assertThat(json.readTree(result.answer()).path("refused").asBoolean()).isFalse();
        assertThat(result.toolTrace()).anySatisfy(trace->assertThat(trace.status()).isEqualTo("SKIPPED"));
        verify(runtime,times(2)).execute(any(),any(),anyLong());
        verify(model,times(4)).chat(any(ChatRequest.class));
    }

    @Test void rewritesUnsupportedNumberOnlyOnceThenReviewsRepairedAnswer() throws Exception {
        when(model.chat(any(ChatRequest.class))).thenReturn(response(answer("每天吃100克。","请核对实际标签。")),
                response(valid()),response(review(true)));
        var result=json.readTree(ask("酸奶一天吃多少？").answer());
        assertThat(result.path("summary").asText()).doesNotContain("100克").contains("请核对实际标签");
        assertThat(result.path("groundingIssues").toString()).contains("UNSUPPORTED_NUMBER");
        assertThat(result.path("partial").asBoolean()).isFalse(); // Successful repair keeps diagnostic history, not a partial-answer label.
        verify(model,times(3)).chat(any(ChatRequest.class));verify(runtime,times(1)).execute(any(),any(),anyLong());
    }

    @Test void preservesSupportedCoreWhenOptionalSuggestionFailsWithoutExtraGeneration() throws Exception {
        var candidate=json.createObjectNode();var claims=candidate.putArray("claims");
        claims.addObject().put("kind","ANSWER").put("text","请核对实际标签。")
                .putArray("evidence").addObject().put("sourceId",7).putArray("passageIds").add(0);
        claims.addObject().put("kind","SUGGESTION").put("text","每天吃100克。")
                .putArray("evidence").addObject().put("sourceId",7).putArray("passageIds").add(1);
        when(model.chat(any(ChatRequest.class))).thenReturn(response(candidate.toString()),
                response(review(true)));
        var result=json.readTree(ask("酸奶怎么选？").answer());
        assertThat(result.path("summary").asText()).contains("请核对实际标签").doesNotContain("100克");
        assertThat(result.path("suggestions")).isEmpty();assertThat(result.path("refused").asBoolean()).isFalse();
        assertThat(result.path("partial").asBoolean()).isTrue();verify(model,times(2)).chat(any(ChatRequest.class));
    }

    @Test void semanticReviewRejectsPlausibleButUnsupportedConclusion() {
        when(model.chat(any(ChatRequest.class))).thenReturn(response(valid()),response(review(false)),
                response(valid()),response(review(false)));
        assertThat(ask("酸奶怎么选？").answer()).contains("证据不足");
        verify(model,times(4)).chat(any(ChatRequest.class));
    }

    @Test void malformedRepairFailsClosedAndEmptyKnowledgeSkipsGeneration() {
        when(model.chat(any(ChatRequest.class))).thenReturn(response("bad"),response("bad"));
        assertThat(ask("酸奶怎么选？").answer()).contains("MODEL_RESPONSE_INVALID").doesNotContain("知识库证据不足");
        verify(model,times(2)).chat(any(ChatRequest.class));
        clearInvocations(model);
        when(runtime.execute(any(),any(),anyLong())).thenReturn(new AssistantFunctionToolRuntime.ToolResult("没有结果",List.of()));
        assertThat(ask("面包怎么选？").answer()).contains("证据不足");verifyNoInteractions(model);
    }

    @Test void routesAll200LabelsAndKeepsRegressionAndHoldoutDisjoint() throws Exception {
        JsonNode cases,splits;
        try(var input=new ClassPathResource("knowledge/rag_eval_cases.json").getInputStream()){cases=json.readTree(input);}
        try(var input=new ClassPathResource("knowledge/rag_eval_splits.json").getInputStream()){splits=json.readTree(input);}
        var softly=new org.assertj.core.api.SoftAssertions();
        for(var row:cases) {
            var plan=AssistantRequestPlan.resolve(row.path("question").asText(),List.of());
            softly.assertThat(plan.knowledge()).as(row.path("id").asText()+" "+row.path("question").asText()).isEqualTo(!row.path("expectNoAnswer").asBoolean());
        }
        softly.assertAll();
        var regression=new HashSet<Integer>();splits.path("regressionCaseIds").forEach(id->regression.add(id.asInt()));
        var holdout=new HashSet<Integer>();splits.path("holdoutCaseIds").forEach(id->holdout.add(id.asInt()));
        assertThat(cases.size()).isEqualTo(200);
        assertThat(splits.path("regressionCaseIds").size()).isEqualTo(20);
        assertThat(splits.path("holdoutCaseIds").size()).isEqualTo(180);
        assertThat(regression).containsExactlyInAnyOrder(25,33,36,37,39,57,61,65,76,91,97,101,102,121,122,128,129,133,134,154);
        assertThat(holdout).hasSize(180).doesNotContainAnyElementsOf(regression);
        var all = new HashSet<>(regression); all.addAll(holdout);
        assertThat(all).containsExactlyInAnyOrderElementsOf(java.util.stream.IntStream.rangeClosed(1,200).boxed().toList());
        assertThat(RagEvaluationSample.selectFrom(new ArrayList<>(holdout),20,20261020)).allMatch(holdout::contains);
        assertThat(AssistantRequestPlan.resolve("我的周报怎么样？",List.of()).kind()).isEqualTo(AssistantRequestPlan.Kind.PERSONAL);
        assertThat(AssistantRequestPlan.resolve("那这个怎么记？",List.of(new AssistantChatMessage("user","无糖酸奶加蜂蜜"))).query()).contains("无糖酸奶加蜂蜜","怎么记");
        assertThat(AssistantRequestPlan.resolve("那这个怎么吃？",List.of()).kind()).isEqualTo(AssistantRequestPlan.Kind.CLARIFY);
    }

    @Test void supplementalQueryCannotReplacePrimaryCandidates() {
        var other=new KnowledgeSnippet(8L,"补充条目","补充知识内容。",.9);
        when(runtime.execute(any(),any(),anyLong())).thenReturn(new AssistantFunctionToolRuntime.ToolResult("原问证据",List.of(source)),
                new AssistantFunctionToolRuntime.ToolResult("补充证据",List.of(other)));
        var query=ToolExecutionRequest.builder().id("supplement").name("search_diet_knowledge").arguments("{\"query\":\"营养热量\",\"limit\":3}").build();
        when(model.chat(any(ChatRequest.class))).thenReturn(ChatResponse.builder().aiMessage(AiMessage.from(List.of(query))).build(),
                response(valid()),response(review(true)));
        var result=ask("豆奶怎么选择和记录？");
        assertThat(result.references()).extracting(KnowledgeSnippet::getId).containsExactly(7L,8L);
        assertThat(result.toolTrace()).extracting(AssistantFunctionCallingService.ToolCallTrace::origin).containsExactly("ORCHESTRATOR","MODEL");
        assertThat(result.toolTrace().get(0).arguments()).contains("豆奶怎么选择和记录");
    }

    @Test void validatesQuotationIdentityNumbersAndUnitsWithoutPretendingToProveSemantics() throws Exception {
        assertThat(AssistantGroundingGuard.validate(json.readTree(answer("请核对实际标签。","不存在的原文片段。")),List.of(source))).isNotEmpty();
        var numericSource=new KnowledgeSnippet(7L,"标签","每份100毫克，每包有10份。",1);
        assertThat(AssistantGroundingGuard.validate(json.readTree(answer("每份10毫克。","每份100毫克，每包有10份。")),List.of(numericSource))).isNotEmpty();
        assertThat(AssistantGroundingGuard.validate(json.readTree(answer("每份100克。","每份100毫克，每包有10份。")),List.of(numericSource))).isNotEmpty();
        var missing=json.readTree(valid());((com.fasterxml.jackson.databind.node.ObjectNode)missing).putArray("claims");
        assertThat(AssistantGroundingGuard.validate(missing,List.of(source))).anyMatch(reason->reason.contains("没有证据"));
    }

    @Test void disabledFunctionCallingStillRetrievesButSendsNoToolProtocol() {
        when(model.chat(any(ChatRequest.class))).thenReturn(response(valid()),response(review(true)));
        var user=new com.example.mealcheck.entity.UserAccount();user.setId(1L);
        assertThat(service.askKnowledge(user,"system",List.of(),"酸奶怎么选？").answer()).contains("核对实际标签");
        var calls=ArgumentCaptor.forClass(ChatRequest.class);verify(model,times(2)).chat(calls.capture());
        assertThat(calls.getAllValues()).allSatisfy(call->{
            assertThat(call.toolSpecifications()).isNullOrEmpty();
            assertThat(call.messages()).noneMatch(message->message instanceof ToolExecutionResultMessage);
        });
        verify(runtime).execute(any(),any(),anyLong());
    }
}
