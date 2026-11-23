package com.example.mealcheck.service;

import com.example.mealcheck.dto.KnowledgeSnippet;
import com.example.mealcheck.service.skill.AssistantSkillPlan;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import java.net.*;
import java.net.http.*;
import java.util.List;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;

class ProtocolObservationRegressionTest {
    @Test void temporalPrefaceAndFollowingActionCannotEscapeTheirCompleteClaim() {
        var parts=AssistantClaimCoverage.of("c0","点单或购买蔬菜沙拉前，先看菜单是否能明确选择主食和蛋白质食物。");
        assertThat(parts).hasSize(2).allMatch(p->!p.standalone());
        assertThat(AssistantClaimCoverage.of("c0","米饭是主食。鸡蛋是蛋类。")).allMatch(AssistantClaimCoverage.Assertion::standalone);
    }
    @Test void taskPromptHasOneOutputSchema() {
        var plan=new AssistantSkillPlan("knowledge","test","KNOWLEDGE","","尊重证据");
        String raw=new AssistantFunctionPromptFactory().build("KNOWLEDGE",plan,"","",null);
        String knowledge=AssistantFunctionPromptFactory.forTask(raw,true);
        assertThat(knowledge).contains("passageIds","Long-term user diet profile").doesNotContain("\"summary\":","\"quote\":","replace the above output schema");
        String personal=AssistantFunctionPromptFactory.forTask(raw,false);
        assertThat(personal).contains("\"summary\":").doesNotContain("\"passageIds\":");
    }
    @Test void compactReviewDerivesGapStatusWithoutRedundantProviderFields() throws Exception {
        var json=new ObjectMapper();var source=new KnowledgeSnippet(1L,"原文","无法从照片判断实际用油量。",1);
        var input=json.readTree("{\"claims\":[{\"kind\":\"ANSWER\",\"text\":\"无法从照片判断实际用油量。\",\"evidence\":[{\"sourceId\":1,\"passageIds\":[0]}]}]}");
        var prepared=AssistantGroundingGuard.prepare(input,List.of(source));
        var verdict=GroundingReviewFixture.verdict(prepared.answer(),java.util.Set.of());
        ((com.fasterxml.jackson.databind.node.ObjectNode)verdict).putArray("inputMissing").add("实际用油量");
        ((com.fasterxml.jackson.databind.node.ObjectNode)verdict).putArray("knowledgeMissing");
        ((com.fasterxml.jackson.databind.node.ObjectNode)verdict).remove(List.of("informationStatus","missingInformation","needsMoreEvidence"));
        var checked=AssistantGroundingGuard.reviewedCurrent(prepared.answer(),verdict);
        assertThat(checked.usable()).isTrue();assertThat(checked.answer().path("informationStatus").asText()).isEqualTo("MISSING_INPUT");
        String prompt=AssistantGroundingGuard.reviewPrompt("能看出用油量吗",prepared.answer(),List.of(source));
        assertThat(prompt).doesNotContain("\"informationStatus\":","\"missingInformation\":","\"needsMoreEvidence\":");
    }
    @Test void responseHeadersAndFirstByteAreObservedBeforeCompleteBodyWithoutChangingIt() throws Exception {
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        var executor=Executors.newSingleThreadExecutor();server.setExecutor(executor);
        var release=new CountDownLatch(1);var first=new CountDownLatch(1);
        server.createContext("/",exchange->{
            try {
                exchange.getResponseHeaders().add("Content-Type","application/json;charset=UTF-8");
                exchange.getResponseHeaders().add("x-siliconcloud-trace-id","test-trace");
                exchange.sendResponseHeaders(200,0);exchange.getResponseBody().write("{\"ok\":".getBytes());exchange.getResponseBody().flush();first.countDown();
                release.await(3,TimeUnit.SECONDS);exchange.getResponseBody().write("true}".getBytes());
            } catch(InterruptedException e){Thread.currentThread().interrupt();} finally {exchange.close();}
        });server.start();
        try {
            var observation=new AiHttpObservation();var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+server.getAddress().getPort()+"/")).build();
            var pending=HttpClient.newHttpClient().sendAsync(request,observation.handler());
            assertThat(first.await(3,TimeUnit.SECONDS)).isTrue();
            long limit=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);while(observation.firstByte<0 && System.nanoTime()<limit)Thread.sleep(5);
            assertThat(observation.headers).isGreaterThanOrEqualTo(0);assertThat(observation.firstByte).isGreaterThanOrEqualTo(observation.headers);
            assertThat(pending.isDone()).isFalse();release.countDown();
            assertThat(pending.get(3,TimeUnit.SECONDS).body()).isEqualTo("{\"ok\":true}");assertThat(observation.providerTrace).isEqualTo("test-trace");
        } finally {release.countDown();server.stop(0);executor.shutdownNow();}
    }
}
