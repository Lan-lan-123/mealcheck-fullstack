package com.example.mealcheck.service;

import com.example.mealcheck.config.AppProperties;
import com.example.mealcheck.dto.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** Explicitly opt-in, bounded provider evaluation with production prompts/guards and no vector client. */
@EnabledIfSystemProperty(named="mealcheck.boundaryLive",matches="true")
class NoEmbeddingBoundaryLiveTest {
    final ObjectMapper json=new ObjectMapper().findAndRegisterModules();
    final List<Map<String,Object>> ledger=new ArrayList<>(),results=new ArrayList<>();
    final Path root=Path.of("..").toAbsolutePath().normalize();
    Path output;
    String phase="";
    boolean stopped;
    void save(String name,Object value) throws Exception {
        Files.writeString(output.resolve(name),json.writerWithDefaultPrettyPrinter().writeValueAsString(value),StandardCharsets.UTF_8,StandardOpenOption.CREATE_NEW);
    }
    String sha(byte[] bytes) throws Exception { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
    void redact(JsonNode node) {
        if(node instanceof ObjectNode object) {
            if(object.path("url").asText().startsWith("data:image/"))object.put("url","[synthetic fixture image omitted]");
            object.elements().forEachRemaining(this::redact);
        } else if(node.isArray())node.forEach(this::redact);
    }
    List<KnowledgeSnippet> sources(JsonNode c) {
        return List.of(new KnowledgeSnippet(7L,"固定合成测试片段",c.path("source").asText(),1));
    }
    AssistantGroundingGuard.Prepared prepared(JsonNode c) {
        var candidate=json.createObjectNode();
        candidate.putArray("claims").addObject().put("kind","ANSWER").put("text",c.path("claim").asText())
                .putArray("evidence").addObject().put("sourceId",7).put("quote",c.path("source").asText());
        return AssistantGroundingGuard.prepare(candidate,sources(c));
    }
    @Test @SuppressWarnings("unchecked")
    void fixedBoundariesWithoutEmbedding() throws Exception {
        output=Path.of(System.getProperty("mealcheck.boundaryOutput")).toAbsolutePath().normalize();
        assertThat(output.startsWith(root.resolve("logs"))).isTrue();
        try(var files=Files.list(output)) { assertThat(files.findAny()).isEmpty(); }
        byte[] bytes;
        try(var resource=new org.springframework.core.io.ClassPathResource("evaluation/no_embedding_boundaries_v1.json").getInputStream()) {bytes=resource.readAllBytes();}
        var fixture=json.readTree(bytes);
        var images=new ArrayList<Map<String,Object>>();
        for(var c:fixture.path("vision")) {
            String id=c.path("id").asText();
            var path=root.resolve("tools/datasets/synthetic-meals-100/images/"+id+".png");
            images.add(Map.of("id",id,"sha256",sha(Files.readAllBytes(path)),"path",root.relativize(path).toString()));
        }
        for(var c:fixture.path("reviews")) {
            var p=prepared(c);
            assertThat(p.usable()).as(c.path("id").asText()).isEqualTo(!c.path("expectedLocalBlocked").asBoolean());
            if(p.usable())save(c.path("id").asText()+"-prompt.json",Map.of("prompt",AssistantGroundingGuard.reviewPrompt(c.path("question").asText(),p.answer(),sources(c))));
        }
        save("scope.json",Map.of("fixtureSha256",sha(bytes),"fixture",fixture,"images",images,"embeddingCallsAllowed",0,"maxProviderRequests",32,
                "prepareOnly",Boolean.getBoolean("mealcheck.boundaryPrepareOnly"),"models",List.of("qwen3.8-flash","qwen3.7-text-rerank")));
        if(Boolean.getBoolean("mealcheck.boundaryPrepareOnly"))return;
        System.setProperty("jdk.httpclient.disableRetryConnect","true");
        var env=new HashMap<String,String>();
        for(String line:Files.readAllLines(root.resolve(".env"))) {
            if(line.stripLeading().startsWith("#") || !line.contains("="))continue;
            int p=line.indexOf('=');env.put(line.substring(0,p).strip(),line.substring(p+1).strip().replaceAll("^[\"']|[\"']$",""));
        }
        var props=new AppProperties();
        props.getAi().setApiKey(env.get("MEALCHECK_AI_API_KEY"));
        props.getAi().setBaseUrl(env.get("MEALCHECK_AI_BASE_URL"));
        props.getAi().setModel(env.get("MEALCHECK_AI_MODEL"));
        props.getAi().setVisionModel(env.get("MEALCHECK_AI_VISION_MODEL"));
        props.getAi().setTimeoutSeconds(90);
        props.getKnowledge().setRerankEnabled(true);
        props.getKnowledge().setRerankApiKey(env.get("MEALCHECK_KNOWLEDGE_RERANK_API_KEY"));
        props.getKnowledge().setRerankUrl(env.get("MEALCHECK_KNOWLEDGE_RERANK_URL"));
        props.getKnowledge().setRerankModel(env.get("MEALCHECK_KNOWLEDGE_RERANK_MODEL"));
        props.getKnowledge().setRerankTimeoutSeconds(30);
        props.getRemoteResilience().setMaxAttempts(1);
        props.getRemoteResilience().setTotalTimeoutSeconds(110);
        assertThat(props.getAi().getModel()).isEqualTo("qwen3.8-flash");
        assertThat(props.getAi().getVisionModel()).isEqualTo("qwen3.8-flash");
        assertThat(props.getKnowledge().getRerankModel()).isEqualTo("qwen3.7-text-rerank");
        URI chat=URI.create("https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions");
        URI rerank=URI.create("https://dashscope.aliyuncs.com/api/v1/services/rerank/text-rerank/text-rerank");
        assertThat(AiEndpointResolver.chatCompletions(props.getAi().getBaseUrl())).isEqualTo(chat);
        assertThat(props.getKnowledge().getRerankUrl()).isEqualTo(rerank.toString());
        HttpClient real=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).followRedirects(HttpClient.Redirect.NEVER).build();
        HttpClient client=mock(HttpClient.class);
        when(client.send(any(HttpRequest.class),any(HttpResponse.BodyHandler.class))).thenAnswer(call->{
            if(stopped || ledger.size()>=32)throw new IllegalStateException("LIVE_REQUEST_LIMIT_OR_STOP");
            HttpRequest request=call.getArgument(0);
            if(!request.method().equals("POST") || !Set.of(chat,rerank).contains(request.uri()))throw new IllegalStateException("FORBIDDEN_ENDPOINT");
            String body=NoEmbeddingProductionAcceptanceTest.body(request);
            var parsed=json.readTree(body);
            String expected=request.uri().equals(chat)?"qwen3.8-flash":"qwen3.7-text-rerank";
            if(!expected.equals(parsed.path("model").asText()))throw new IllegalStateException("FORBIDDEN_MODEL");
            var entry=new LinkedHashMap<String,Object>();
            entry.put("phase",phase);entry.put("endpoint",request.uri().toString());entry.put("model",expected);
            entry.put("requestSha256",sha(body.getBytes(StandardCharsets.UTF_8)));entry.put("state","DISPATCHING");ledger.add(entry);
            redact(parsed);save(String.format("%02d-request.json",ledger.size()),parsed);save(String.format("%02d-dispatch.json",ledger.size()),entry);
            long start=System.nanoTime();
            try {
                var response=real.send(request,HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                entry.put("httpStatus",response.statusCode());entry.put("elapsedMs",TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start));
                var payload=json.readTree(response.body());entry.put("usage",payload.path("usage"));save(String.format("%02d-response.json",ledger.size()),payload);
                if(response.statusCode()!=200) {stopped=true;throw new IllegalStateException("PROVIDER_HTTP_"+response.statusCode());}
                entry.put("state","COMPLETE");return response;
            } catch(Exception error) {stopped=true;entry.put("state","ERROR");throw new IllegalStateException("LIVE_PROVIDER_STOPPED_"+error.getClass().getSimpleName());}
        });
        var obs=mock(ApplicationObservability.class);
        var transport=new AiHttpTransport(client,new RemoteCallGuard(props),mock(AiBudgetService.class),props,json,obs,mock(AiCallConcurrency.class));
        var factory=new AssistantChatModelFactory(props,transport);
        try {
            for(var c:fixture.path("reviews")) {
                phase=c.path("id").asText();int before=ledger.size();
                var item=new LinkedHashMap<String,Object>();item.put("id",phase);item.put("group",c.path("group").asText());item.put("expected",c);
                try {
                    var p=prepared(c);item.put("prepared",p.answer());
                    if(!p.usable()) {
                        item.put("localIssues",p.issues());item.put("modelEvaluated",false);
                        item.put("passed",c.path("expectedLocalBlocked").asBoolean());
                    } else {
                        String prompt=AssistantGroundingGuard.reviewPrompt(c.path("question").asText(),p.answer(),sources(c));
                        var model=factory.create(Duration.ofSeconds(90),0,"diet-assistant-evidence-review");
                        var response=model.chat(ChatRequest.builder().messages(List.of(UserMessage.from(prompt))).build());
                        var verdict=AssistantGroundingGuard.parse(response.aiMessage().text());
                        item.put("modelEvaluated",true);item.put("verdict",verdict);
                        var checked=AssistantGroundingGuard.reviewedCurrent(p.answer(),verdict);
                        item.put("finalUsable",checked.usable());item.put("localIssues",checked.issues());
                        boolean validSchema=checked.issues().stream().noneMatch(i->i.code().equals("INCOMPLETE_REVIEW"));
                        boolean rawSupported=verdict.path("answersQuestion").asBoolean();
                        for(var claim:verdict.path("claims"))for(var a:claim.path("assertions")) {
                            rawSupported &= a.path("supported").asBoolean();
                            for(String dim:List.of("fact","degree","causality","conditions"))rawSupported &= a.path("checks").path(dim).asBoolean();
                        }
                        item.put("modelSupported",rawSupported);item.put("validSchema",validSchema);
                        if("information".equals(c.path("group").asText())) {
                            String status=checked.answer().path("informationStatus").asText();
                            boolean needs=Set.of("MISSING_KNOWLEDGE","MISSING_BOTH").contains(status);
                            item.put("effectiveInformationStatus",status);
                            item.put("passed",validSchema && status.equals(c.path("expectedStatus").asText())
                                    && (!verdict.path("knowledgeMissing").isEmpty())==needs);
                        } else {
                            boolean expected=c.path("expectedSupported").asBoolean();
                            item.put("passed",validSchema && rawSupported==expected && checked.usable()==expected);
                        }
                    }
                } catch(Exception error) {item.put("passed",false);item.put("errorType",error.getClass().getSimpleName());}
                item.put("providerRequests",ledger.size()-before);results.add(item);save(phase+"-result.json",item);
                if(stopped)throw new IllegalStateException("PROVIDER_STOPPED");
            }
            var reranker=new KnowledgeRerankerService(props,json,obs,transport);
            assertThat(reranker.isConfigured()).isTrue();
            for(var c:fixture.path("reranks")) {
                phase=c.path("id").asText();var candidates=new ArrayList<KnowledgeSnippet>();
                for(var d:c.path("documents"))candidates.add(new KnowledgeSnippet(d.path("id").asLong(),d.path("title").asText(),d.path("content").asText(),1));
                var ranked=reranker.rerank(c.path("query").asText(),candidates,candidates.size());
                boolean applied=ranked.stream().allMatch(KnowledgeSnippet::isReranked);
                var item=Map.<String,Object>of("id",phase,"group","rerank","ranked",ranked,"providerApplied",applied,
                        "passed",applied && ranked.get(0).getId()==c.path("expectedTopId").asLong());
                results.add(item);save(phase+"-result.json",item);
                if(stopped)throw new IllegalStateException("PROVIDER_STOPPED");
            }
            var ai=new AiChatClient(props,json,new AiStatusService(),obs,transport);
            var vision=new VisionFoodRecognitionService(ai,json);
            for(var c:fixture.path("vision")) {
                phase=c.path("id").asText();var item=new LinkedHashMap<String,Object>();item.put("id",phase);item.put("group","vision");
                try {
                    Path image=root.resolve("tools/datasets/synthetic-meals-100/images/"+phase+".png");
                    var pixels=javax.imageio.ImageIO.read(image.toFile());
                    var result=vision.recognize(new ValidatedImage(Files.readAllBytes(image),"image/png",".png",pixels.getWidth(),pixels.getHeight(),phase+".png"));
                    boolean pass=result.isFoodImage()==c.path("foodImage").asBoolean() && result.getFoods().size()>=c.path("minimumFoods").asInt();
                    if(!result.isFoodImage())pass &= result.getFoods().isEmpty();
                    if(c.path("requiresDrink").asBoolean())pass &= result.getFoods().stream().anyMatch(f->"drink".equalsIgnoreCase(f.getCategory()));
                    item.put("recognition",result);item.put("passed",pass);
                } catch(Exception error) {item.put("passed",false);item.put("errorType",error.getClass().getSimpleName());}
                results.add(item);save(phase+"-result.json",item);
                if(stopped)throw new IllegalStateException("PROVIDER_STOPPED");
            }
        } finally {
            save("ledger.json",ledger);save("results.json",results);
            save("summary.json",Map.of("providerRequests",ledger.size(),"embeddingCalls",0,"completedCases",results.size(),
                    "passedCases",results.stream().filter(r->Boolean.TRUE.equals(r.get("passed"))).count(),
                    "failedCaseIds",results.stream().filter(r->!Boolean.TRUE.equals(r.get("passed"))).map(r->r.get("id")).toList(),"stopped",stopped));
        }
        assertThat(results).hasSize(26).allSatisfy(r->assertThat(r.get("passed")).as(r.get("id").toString()).isEqualTo(true));
    }
}
