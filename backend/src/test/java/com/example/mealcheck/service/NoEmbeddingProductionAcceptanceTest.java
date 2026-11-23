package com.example.mealcheck.service;

import com.example.mealcheck.config.AppProperties;
import com.example.mealcheck.dto.*;
import com.example.mealcheck.service.skill.AssistantSkillPlan;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.langchain4j.agent.tool.ToolSpecification;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import java.net.*;
import java.net.http.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.Flow;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** Opt-in live acceptance using production prompts, orchestration, transport and guards.
 * Knowledge access is a fixed approved fixture; no database or embedding client exists here. */
@EnabledIfSystemProperty(named="mealcheck.noEmbeddingLive", matches="true")
class NoEmbeddingProductionAcceptanceTest {
    final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    final List<Map<String,Object>> ledger = new ArrayList<>();
    final List<Map<String,Object>> results = new ArrayList<>();
    Path root, output;
    String phase = "";
    boolean stopped;
    boolean textOnly;
    int requestCap;

    void save(String name, Object value) throws Exception {
        Files.writeString(output.resolve(name), json.writerWithDefaultPrettyPrinter().writeValueAsString(value),
                StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
    }
    static String body(HttpRequest request) throws Exception {
        var collected = new CompletableFuture<String>();
        var bytes = new java.io.ByteArrayOutputStream();
        request.bodyPublisher().orElseThrow().subscribe(new Flow.Subscriber<ByteBuffer>() {
            public void onSubscribe(Flow.Subscription subscription) { subscription.request(Long.MAX_VALUE); }
            public void onNext(ByteBuffer buffer) { byte[] b=new byte[buffer.remaining()];buffer.get(b);bytes.writeBytes(b); }
            public void onError(Throwable failure) { collected.completeExceptionally(failure); }
            public void onComplete() { collected.complete(bytes.toString(StandardCharsets.UTF_8)); }
        });
        return collected.get(5, TimeUnit.SECONDS);
    }
    void redact(JsonNode node) {
        if (node instanceof ObjectNode object) {
            if (object.path("url").asText().startsWith("data:image/")) object.put("url", "[approved image omitted]");
            object.elements().forEachRemaining(this::redact);
        } else if (node.isArray()) node.forEach(this::redact);
    }
    @Test @SuppressWarnings("unchecked")
    void productionPromptsAndFinalResultsWithoutEmbedding() throws Exception {
        System.setProperty("jdk.httpclient.disableRetryConnect", "true");
        root=Path.of("..").toAbsolutePath().normalize();
        output=Path.of(System.getProperty("mealcheck.noEmbeddingOutput")).toAbsolutePath().normalize();
        requestCap=Integer.getInteger("mealcheck.noEmbeddingMaxRequests",10);
        textOnly=Boolean.getBoolean("mealcheck.noEmbeddingTextOnly");
        assertThat(requestCap).isBetween(1,10);
        assertThat(output.startsWith(root.resolve("logs"))).isTrue();
        try (var files=Files.list(output)) { assertThat(files.findAny()).isEmpty(); }
        var env=new HashMap<String,String>();
        for(String line:Files.readAllLines(root.resolve(".env"))) {
            if(line.stripLeading().startsWith("#") || !line.contains("="))continue;
            int p=line.indexOf('=');env.put(line.substring(0,p).strip(),line.substring(p+1).strip().replaceAll("^[\"']|[\"']$", ""));
        }
        var props=new AppProperties();
        props.getAi().setApiKey(env.get("MEALCHECK_AI_API_KEY"));
        props.getAi().setBaseUrl(env.get("MEALCHECK_AI_BASE_URL"));
        props.getAi().setModel(env.get("MEALCHECK_AI_MODEL"));
        props.getAi().setVisionModel(env.get("MEALCHECK_AI_VISION_MODEL"));
        props.getRemoteResilience().setMaxAttempts(1);
        assertThat(props.getAi().getModel()).isEqualTo("qwen3.8-flash");
        assertThat(props.getAi().getVisionModel()).isEqualTo("qwen3.8-flash");
        URI endpoint=URI.create("https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions");
        assertThat(AiEndpointResolver.chatCompletions(props.getAi().getBaseUrl())).isEqualTo(endpoint);
        HttpClient real=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).followRedirects(HttpClient.Redirect.NEVER).build();
        HttpClient client=mock(HttpClient.class);
        when(client.send(any(HttpRequest.class),any(HttpResponse.BodyHandler.class))).thenAnswer(call -> {
            if(stopped || ledger.size()>=requestCap)throw new IllegalStateException("LIVE_REQUEST_LIMIT_OR_STOP");
            HttpRequest request=call.getArgument(0);
            if(!request.uri().equals(endpoint) || !request.method().equals("POST"))throw new IllegalStateException("NON_CHAT_ENDPOINT_FORBIDDEN");
            String body=body(request);JsonNode parsed=json.readTree(body);
            if(!"qwen3.8-flash".equals(parsed.path("model").asText()))throw new IllegalStateException("UNAPPROVED_MODEL");
            var entry=new LinkedHashMap<String,Object>();
            entry.put("phase",phase);entry.put("endpoint",endpoint.toString());entry.put("requestSha256",HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body.getBytes(StandardCharsets.UTF_8))));
            entry.put("state","DISPATCHING");ledger.add(entry);
            redact(parsed);save(String.format("%02d-request.json",ledger.size()),parsed);
            save(String.format("%02d-dispatch.json",ledger.size()),entry);
            long start=System.nanoTime();
            try {
                var response=real.send(request,HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                entry.put("httpStatus",response.statusCode());entry.put("elapsedMs",TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start));
                var payload=json.readTree(response.body());entry.put("usage",payload.path("usage"));
                save(String.format("%02d-response.json",ledger.size()),payload);
                if(response.statusCode()!=200){stopped=true;throw new IllegalStateException("PROVIDER_HTTP_"+response.statusCode());}
                entry.put("state","COMPLETE");return response;
            } catch(Exception error) {stopped=true;entry.put("state","ERROR");throw new IllegalStateException("LIVE_PROVIDER_STOPPED_"+error.getClass().getSimpleName());}
        });
        var obs=mock(ApplicationObservability.class);
        var transport=new AiHttpTransport(client,new RemoteCallGuard(props),mock(AiBudgetService.class),props,json,obs,mock(AiCallConcurrency.class));
        var factory=new AssistantChatModelFactory(props,transport);
        var executor=Executors.newSingleThreadExecutor();
        var productionTools=new AssistantFunctionToolRegistry(null,null,null,null,null,json,executor,executor,props,obs).specifications();
        String fixtureName="evaluation/no_embedding_captured_regressions.json";
        byte[] fixtureBytes;
        try(var stream=new org.springframework.core.io.ClassPathResource(fixtureName).getInputStream()) { fixtureBytes=stream.readAllBytes(); }
        var fixed=json.readTree(fixtureBytes);
        save("scope.json",Map.of("embeddingCallsAllowed",0,"maxProviderRequests",requestCap,"textOnly",textOnly,"fixedInputFixture",fixtureName,
                "fixtureSha256",HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(fixtureBytes)),
                "productionComponents",List.of("AssistantFunctionPromptFactory","AssistantFunctionCallingService","AssistantChatModelFactory","AiHttpTransport","AssistantGroundingGuard","VisionFoodRecognitionService")));
        try {
            for(String id:List.of("rag-187","rag-172")) {
                phase=id;JsonNode c=null;for(var item:fixed.path("cases"))if(id.equals(item.path("caseId").asText()))c=item;
                if(c==null)throw new IllegalStateException("MISSING_FIXTURE");
                var sources=new ArrayList<KnowledgeSnippet>();
                for(var s:c.path("sources"))sources.add(new KnowledgeSnippet(s.path("sourceId").asLong(),s.path("title").asText(),s.path("content").asText(),1));
                var toolCalls=new ArrayList<String>();
                AssistantFunctionToolRuntime runtime=new AssistantFunctionToolRuntime() {
                    public List<ToolSpecification> specifications(){return productionTools.stream().filter(s->s.name().equals("search_diet_knowledge")).toList();}
                    public ToolResult execute(com.example.mealcheck.entity.UserAccount user,dev.langchain4j.agent.tool.ToolExecutionRequest request,long deadline) {
                        if(!"search_diet_knowledge".equals(request.name()))throw new IllegalStateException("UNAPPROVED_TOOL");
                        toolCalls.add(request.arguments());return new ToolResult("固定已批准的片段",sources);
                    }
                };
                var service=new AssistantFunctionCallingService(props,null,factory,executor);
                String prompt=new AssistantFunctionPromptFactory().build("balanced",new AssistantSkillPlan("balanced","一般选餐","balanced","","依照知识证据回答"),"","",Map.of());
                var response=service.askWithRuntime(prompt,List.of(),c.path("question").asText(),runtime);
                var answer=json.readTree(response.answer());boolean quotesValid=true;
                for(var claim:answer.path("claims"))for(var evidence:claim.path("evidence")) {
                    String source=sources.stream().filter(s->s.getId()==evidence.path("sourceId").asLong()).findFirst().orElseThrow().getContent();
                    for(var quote:evidence.path("quotes"))quotesValid &= source.contains(quote.asText());
                }
                var item=new LinkedHashMap<String,Object>();item.put("id",id);item.put("answer",answer);item.put("toolCalls",toolCalls);item.put("quotesValid",quotesValid);
                boolean pass=!answer.path("refused").asBoolean()&&!answer.path("summary").asText().isBlank()&&!answer.path("claims").isEmpty()&&quotesValid;
                if(id.equals("rag-172"))pass &= "MISSING_INPUT".equals(answer.path("informationStatus").asText())&&toolCalls.size()==1;
                else pass &= "SUFFICIENT".equals(answer.path("informationStatus").asText())&&answer.path("missingInformation").isArray()&&answer.path("missingInformation").isEmpty();
                item.put("passed",pass);results.add(item);save(id+"-result.json",item);
                if(stopped)throw new IllegalStateException("STOPPED");
            }
            if(!textOnly) {
            var ai=new AiChatClient(props,json,new AiStatusService(),obs,transport);
            var vision=new VisionFoodRecognitionService(ai,json);
            for(String id:List.of("synth-001","synth-081")) {
                phase=id;Path image=root.resolve("tools/datasets/synthetic-meals-100/images/"+id+".png");
                var pixels=javax.imageio.ImageIO.read(image.toFile());
                var result=vision.recognize(new ValidatedImage(Files.readAllBytes(image),"image/png",".png",pixels.getWidth(),pixels.getHeight(),id+".png"));
                boolean pass=id.equals("synth-001")?result.isFoodImage()&&!result.getFoods().isEmpty():!result.isFoodImage()&&result.getFoods().isEmpty();
                var item=Map.<String,Object>of("id",id,"recognition",result,"passed",pass);
                results.add(item);save(id+"-result.json",item);
            }
            phase="dark-local-control";int before=ledger.size();
            var dark=new java.awt.image.BufferedImage(32,24,java.awt.image.BufferedImage.TYPE_INT_RGB);
            var bytes=new java.io.ByteArrayOutputStream();javax.imageio.ImageIO.write(dark,"png",bytes);
            assertThatThrownBy(()->vision.recognize(new ValidatedImage(bytes.toByteArray(),"image/png",".png",32,24,"dark.png")))
                .isInstanceOfSatisfying(RecognitionFailureException.class,e->assertThat(e.code()).isEqualTo("IMAGE_UNCLEAR"));
            results.add(Map.of("id",phase,"passed",before==ledger.size(),"providerCalls",0));
            }
        } finally {
            executor.shutdownNow();save("ledger.json",ledger);save("results.json",results);
            save("summary.json",Map.of("providerRequests",ledger.size(),"embeddingCalls",0,"completedCases",results.size(),
                    "passedCases",results.stream().filter(r->Boolean.TRUE.equals(r.get("passed"))).count(),
                    "failedCaseIds",results.stream().filter(r->!Boolean.TRUE.equals(r.get("passed"))).map(r->r.get("id")).toList(),"stopped",stopped));
        }
        assertThat(results).hasSize(textOnly?2:5).allSatisfy(r->assertThat(r.get("passed")).as(r.get("id").toString()).isEqualTo(true));
    }
}
