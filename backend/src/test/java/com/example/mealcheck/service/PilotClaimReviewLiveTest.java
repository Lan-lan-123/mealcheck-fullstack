package com.example.mealcheck.service;

import com.example.mealcheck.dto.KnowledgeSnippet;
import com.fasterxml.jackson.databind.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

/** Four fixed review calls using only the approved pilot questions and actually retrieved sources. */
@EnabledIfSystemProperty(named="mealcheck.pilotClaimLive",matches="true")
class PilotClaimReviewLiveTest {
    @Test void actualPilotWordingAndCorrectedControls() throws Exception {
        var json=new ObjectMapper();var root=Path.of("..").toAbsolutePath().normalize();
        var output=Path.of(System.getProperty("mealcheck.pilotClaimOutput")).toAbsolutePath().normalize();
        assertThat(output.startsWith(root.resolve("logs"))).isTrue();
        if(System.getProperty("mealcheck.pilotClaimReplay")!=null) {
            var recorded=Path.of(System.getProperty("mealcheck.pilotClaimReplay")).toAbsolutePath().normalize();
            assertThat(recorded.startsWith(root.resolve("logs"))).isTrue();
            var replay=new ArrayList<Map<String,Object>>();
            for(int i=1;i<=4;i++) {
                String prefix=String.format("%02d",i);
                var body=json.readTree(Files.readString(recorded.resolve(prefix+"-request.json")));
                String prompt=body.path("messages").path(0).path("content").asText();
                int start=prompt.lastIndexOf("\n回答：")+4,end=prompt.lastIndexOf("\n来源：");
                var answer=(com.fasterxml.jackson.databind.node.ObjectNode)json.readTree(prompt.substring(start,end));
                var response=json.readTree(Files.readString(recorded.resolve(prefix+"-response.json")));
                var verdict=AssistantGroundingGuard.parse(response.path("choices").path(0).path("message").path("content").asText());
                var checked=AssistantGroundingGuard.reviewedCurrent(answer,verdict);
                boolean expected=i%2==0;
                replay.add(Map.of("case",i,"expectedUsable",expected,"usable",checked.usable(),"passed",checked.usable()==expected,"issues",checked.issues()));
            }
            Files.writeString(output.resolve("replay.json"),json.writerWithDefaultPrettyPrinter().writeValueAsString(Map.of("mode","RECORDED_LIVE_REVIEW_LOCAL_REPLAY","providerRequests",0,"cases",replay)),StandardCharsets.UTF_8,StandardOpenOption.CREATE_NEW);
            assertThat(replay).allSatisfy(r->assertThat(r.get("passed")).as(r.toString()).isEqualTo(true));
            return;
        }
        var captured=json.readTree(Files.readString(root.resolve("logs/backend/full-chain-pilot10-live-20261025-175733-096167/results.json")));
        var env=new HashMap<String,String>();
        for(var line:Files.readAllLines(root.resolve(".env")))if(!line.stripLeading().startsWith("#") && line.contains("=")) {
            int at=line.indexOf('=');env.put(line.substring(0,at).strip(),line.substring(at+1).strip().replaceAll("^[\"']|[\"']$",""));
        }
        assertThat(env.get("MEALCHECK_AI_MODEL")).isEqualTo("qwen3.8-flash");
        assertThat(env.get("MEALCHECK_AI_BASE_URL")).isEqualTo("https://dashscope.aliyuncs.com/compatible-mode/v1");
        System.setProperty("jdk.httpclient.disableRetryConnect","true");
        var client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NEVER).build();
        var results=new ArrayList<Map<String,Object>>();
        var texts=List.of(
                "各人并不平均。",
                "整桌订单按人数均摊只是额外假设，不能当作每个人的实际摄入；各人不一定平均。",
                "用整果重量减去果皮和果核的剩余重量，就是这次实际吃下的重量。",
                "在没有其他损失或添加、没有分享，且所有未吃部分都保留称量的条件下，可用整果重量减去全部未吃部分的重量估算本人食用部分；否则差值不能全部归为本人吃下。");
        for(int i=0;i<4;i++) {
            String id=i<2?"rag-184":"rag-128";long sourceId=i<2?1039L:1409L;
            JsonNode row=null,source=null;
            for(var c:captured.path("cases"))if(id.equals(c.path("id").asText()))row=c;
            assertThat(row).isNotNull();
            for(var cache:row.path("retrievalCaches"))for(var s:cache.path("snippets"))if(s.path("id").asLong()==sourceId)source=s;
            assertThat(source).isNotNull();
            var snippet=new KnowledgeSnippet(sourceId,source.path("title").asText(),source.path("content").asText(),1);
            var input=json.createObjectNode();input.putArray("claims").addObject().put("kind","ANSWER").put("text",texts.get(i))
                    .putArray("evidence").addObject().put("sourceId",sourceId).put("quote",snippet.getContent());
            var prepared=AssistantGroundingGuard.prepare(input,List.of(snippet));assertThat(prepared.usable()).isTrue();
            var body=json.createObjectNode().put("model","qwen3.8-flash").put("temperature",0).put("max_tokens",4000).put("enable_thinking",false);
            body.putArray("messages").addObject().put("role","user").put("content",AssistantGroundingGuard.reviewPrompt(row.path("question").asText(),prepared.answer(),List.of(snippet)));
            String prefix=String.format("%02d",i+1);
            Files.writeString(output.resolve(prefix+"-request.json"),body.toPrettyString(),StandardCharsets.UTF_8,StandardOpenOption.CREATE_NEW);
            var request=HttpRequest.newBuilder(URI.create("https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions"))
                    .timeout(Duration.ofSeconds(60)).header("Content-Type","application/json").header("Authorization","Bearer "+env.get("MEALCHECK_AI_API_KEY"))
                    .POST(HttpRequest.BodyPublishers.ofString(body.toString())).build();
            var response=client.send(request,HttpResponse.BodyHandlers.ofString());
            Files.writeString(output.resolve(prefix+"-response.json"),response.body(),StandardCharsets.UTF_8,StandardOpenOption.CREATE_NEW);
            assertThat(response.statusCode()).isEqualTo(200);
            var verdict=AssistantGroundingGuard.parse(json.readTree(response.body()).path("choices").path(0).path("message").path("content").asText());
            var checked=AssistantGroundingGuard.reviewedCurrent(prepared.answer(),verdict);
            boolean expected=i%2==1;
            var result=Map.<String,Object>of("id",id,"control",expected,"inputClaim",texts.get(i),"verdict",verdict,
                    "usable",checked.usable(),"issues",checked.issues(),"passed",checked.usable()==expected && checked.issues().stream().noneMatch(x->"INCOMPLETE_REVIEW".equals(x.code())));
            results.add(result);Files.writeString(output.resolve(prefix+"-result.json"),json.writerWithDefaultPrettyPrinter().writeValueAsString(result),StandardCharsets.UTF_8,StandardOpenOption.CREATE_NEW);
        }
        Files.writeString(output.resolve("results.json"),json.writerWithDefaultPrettyPrinter().writeValueAsString(results),StandardCharsets.UTF_8,StandardOpenOption.CREATE_NEW);
        assertThat(results).hasSize(4).allSatisfy(r->assertThat(r.get("passed")).as(r.get("id").toString()).isEqualTo(true));
    }
}
