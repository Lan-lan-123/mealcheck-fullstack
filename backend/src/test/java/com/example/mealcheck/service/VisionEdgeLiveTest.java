package com.example.mealcheck.service;

import com.example.mealcheck.config.AppProperties;
import com.example.mealcheck.dto.*;
import com.example.mealcheck.util.Jsons;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
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

/** Production vision parsing and scoring with opt-in provider calls; no vector/database client. */
@EnabledIfSystemProperty(named="mealcheck.visionEdgeLive",matches="true")
class VisionEdgeLiveTest {
    final ObjectMapper json=new ObjectMapper().findAndRegisterModules();
    final Path root=Path.of("..").toAbsolutePath().normalize();
    final List<Map<String,Object>> ledger=new ArrayList<>(),results=new ArrayList<>();
    Path output;
    String phase="";
    JsonNode rawPayload;
    boolean stopped;
    void save(String name,Object value) throws Exception {
        Files.writeString(output.resolve(name),json.writerWithDefaultPrettyPrinter().writeValueAsString(value),StandardCharsets.UTF_8,StandardOpenOption.CREATE_NEW);
    }
    String sha(byte[] bytes) throws Exception {return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}
    void redact(JsonNode node) {
        if(node instanceof ObjectNode o) {if(o.path("url").asText().startsWith("data:image/"))o.put("url","[fixed synthetic image omitted]");o.elements().forEachRemaining(this::redact);}
        else if(node.isArray())node.forEach(this::redact);
    }
    @Test @SuppressWarnings("unchecked")
    void leftoversObstructionAndPackaging() throws Exception {
        var base=root.resolve("tools/datasets/vision-boundaries-18-v1");
        byte[] datasetBytes=Files.readAllBytes(base.resolve("dataset.json"));var dataset=json.readTree(datasetBytes);
        assertThat(dataset.path("frozen").asBoolean()).isTrue();assertThat(dataset.path("cases").size()).isEqualTo(18);
        int expectedCalls=0;
        var ids=new HashSet<String>();
        for(var c:dataset.path("cases")) {
            assertThat(ids.add(c.path("id").asText())).isTrue();
            Path image=base.resolve(c.path("image").asText()).normalize();assertThat(image.startsWith(base.resolve("images"))).isTrue();
            assertThat(sha(Files.readAllBytes(image))).isEqualTo(c.path("sha256").asText());
            assertThat(c.path("reviewStatus").asText()).isEqualTo("assistant-visual-inspected");
            assertThat(c.path("repeat").asInt()).isBetween(1,3);expectedCalls+=c.path("repeat").asInt();
        }
        assertThat(expectedCalls).isEqualTo(26);
        output=Path.of(System.getProperty("mealcheck.visionEdgeOutput")).toAbsolutePath().normalize();
        assertThat(output.startsWith(root.resolve("logs"))).isTrue();try(var files=Files.list(output)){assertThat(files.findAny()).isEmpty();}
        save("scope.json",Map.of("datasetSha256",sha(datasetBytes),"dataset",dataset,"embeddingCallsAllowed",0,"maxProviderRequests",26,
                "prepareOnly",Boolean.getBoolean("mealcheck.visionEdgePrepareOnly"),"model","qwen3.8-flash"));
        if(Boolean.getBoolean("mealcheck.visionEdgePrepareOnly"))return;
        System.setProperty("jdk.httpclient.disableRetryConnect","true");
        var env=new HashMap<String,String>();
        for(String line:Files.readAllLines(root.resolve(".env"))) {
            if(line.stripLeading().startsWith("#") || !line.contains("="))continue;
            int p=line.indexOf('=');env.put(line.substring(0,p).strip(),line.substring(p+1).strip().replaceAll("^[\"']|[\"']$",""));
        }
        var props=new AppProperties();props.getAi().setApiKey(env.get("MEALCHECK_AI_API_KEY"));
        props.getAi().setBaseUrl(env.get("MEALCHECK_AI_BASE_URL"));props.getAi().setModel(env.get("MEALCHECK_AI_MODEL"));
        props.getAi().setVisionModel(env.get("MEALCHECK_AI_VISION_MODEL"));props.getAi().setTimeoutSeconds(90);
        props.getRemoteResilience().setMaxAttempts(1);props.getRemoteResilience().setTotalTimeoutSeconds(100);
        assertThat(props.getAi().getVisionModel()).isEqualTo("qwen3.8-flash");
        URI endpoint=URI.create("https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions");
        assertThat(AiEndpointResolver.chatCompletions(props.getAi().getBaseUrl())).isEqualTo(endpoint);
        var real=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).followRedirects(HttpClient.Redirect.NEVER).build();
        var client=mock(HttpClient.class);
        when(client.send(any(HttpRequest.class),any(HttpResponse.BodyHandler.class))).thenAnswer(call->{
            if(stopped || ledger.size()>=26)throw new IllegalStateException("LIVE_REQUEST_LIMIT_OR_STOP");
            HttpRequest request=call.getArgument(0);
            if(!request.uri().equals(endpoint) || !request.method().equals("POST"))throw new IllegalStateException("FORBIDDEN_ENDPOINT");
            String body=NoEmbeddingProductionAcceptanceTest.body(request);var parsed=json.readTree(body);
            if(!"qwen3.8-flash".equals(parsed.path("model").asText()))throw new IllegalStateException("FORBIDDEN_MODEL");
            var entry=new LinkedHashMap<String,Object>();entry.put("phase",phase);entry.put("endpoint",endpoint.toString());
            entry.put("requestSha256",sha(body.getBytes(StandardCharsets.UTF_8)));entry.put("state","DISPATCHING");ledger.add(entry);
            redact(parsed);save(String.format("%02d-request.json",ledger.size()),parsed);save(String.format("%02d-dispatch.json",ledger.size()),entry);
            long start=System.nanoTime();
            try {
                var response=real.send(request,HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                entry.put("httpStatus",response.statusCode());entry.put("elapsedMs",TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start));
                var payload=json.readTree(response.body());entry.put("usage",payload.path("usage"));save(String.format("%02d-response.json",ledger.size()),payload);
                if(response.statusCode()!=200)throw new IllegalStateException("PROVIDER_HTTP_"+response.statusCode());
                entry.put("state","COMPLETE");
                try {rawPayload=json.readTree(Jsons.extractJsonObject(payload.path("choices").path(0).path("message").path("content").asText()));}
                catch(Exception ignored) {rawPayload=json.createObjectNode();}
                return response;
            } catch(Exception failure) {stopped=true;entry.put("state","ERROR");throw new IllegalStateException("LIVE_PROVIDER_STOPPED_"+failure.getClass().getSimpleName());}
        });
        var obs=mock(ApplicationObservability.class);
        var transport=new AiHttpTransport(client,new RemoteCallGuard(props),mock(AiBudgetService.class),props,json,obs,mock(AiCallConcurrency.class));
        var ai=new AiChatClient(props,json,new AiStatusService(),obs,transport);
        var vision=new VisionFoodRecognitionService(ai,json);var scorer=new MealScoringService();
        try {
            // Every image gets a first pass before any predefined stability repeats.
            for(int round=1;round<=3;round++)for(var c:dataset.path("cases")) {
                if(c.path("repeat").asInt()<round)continue;
                phase=c.path("id").asText()+"-r"+round;rawPayload=json.createObjectNode();
                var item=new LinkedHashMap<String,Object>();item.put("id",c.path("id").asText());item.put("round",round);item.put("group",c.path("group").asText());
                item.put("imageSha256",c.path("sha256").asText());item.put("expectedOutcomes",c.path("outcomes"));
                RecognitionResult recognition=null;List<FoodItem> accepted=List.of();String decision="EXECUTION_ERROR";
                try {
                    var path=base.resolve(c.path("image").asText());var pixels=javax.imageio.ImageIO.read(path.toFile());
                    recognition=vision.recognize(new ValidatedImage(Files.readAllBytes(path),"image/png",".png",pixels.getWidth(),pixels.getHeight(),"evaluation.png"));
                    item.put("recognition",recognition);accepted=scorer.acceptedFoods(recognition);
                    item.put("acceptedFoods",accepted);item.put("uncertainFoods",MealScoringService.uncertainFoods(recognition.getFoods()));
                    decision=!recognition.isFoodImage()?"NON_FOOD":accepted.isEmpty()?"UNCERTAIN_ONLY":"FOOD";
                    if("FOOD".equals(decision))item.put("evaluation",scorer.evaluate(recognition,"balanced"));
                    item.put("effectivePipelineCode","NON_FOOD".equals(decision)?"NON_FOOD":"UNCERTAIN_ONLY".equals(decision)?"IMAGE_UNCLEAR":"SUCCESS");
                } catch(RecognitionFailureException failure) {decision=failure.code();item.put("effectivePipelineCode",failure.code());}
                catch(Exception failure) {item.put("errorType",failure.getClass().getSimpleName());}
                var issues=VisionEdgeCriteria.issues(c,decision,recognition,accepted,rawPayload);
                item.put("decision",decision);item.put("issues",issues);item.put("passed",issues.isEmpty());item.put("rawPayload",rawPayload);
                results.add(item);save(phase+"-result.json",item);
                if(stopped)throw new IllegalStateException("PROVIDER_STOPPED");
            }
        } finally {
            save("ledger.json",ledger);save("results.json",results);
            save("summary.json",Map.of("completedRuns",results.size(),"passedRuns",results.stream().filter(r->Boolean.TRUE.equals(r.get("passed"))).count(),
                    "providerRequests",ledger.size(),"embeddingCalls",0,"stopped",stopped,"failedRuns",results.stream().filter(r->!Boolean.TRUE.equals(r.get("passed"))).map(r->r.get("id")+"-r"+r.get("round")).toList()));
        }
        assertThat(results).hasSize(26).allSatisfy(r->assertThat(r.get("passed")).as(r.get("id")+"-r"+r.get("round")).isEqualTo(true));
    }
}
