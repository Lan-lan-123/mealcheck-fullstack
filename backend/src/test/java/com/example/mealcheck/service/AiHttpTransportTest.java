package com.example.mealcheck.service;

import com.example.mealcheck.config.AppProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import java.net.*;
import java.net.http.*;
import java.time.*;
import java.math.BigDecimal;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class AiHttpTransportTest {
    @Test void guardedModel429CoolsDownOnceAndReleasesBothGates() {
        var gate=mock(AiModelAdmission.class);
        var lease=new AiModelAdmission.Lease(java.util.UUID.randomUUID(),"test-scope","test-model");
        org.springframework.test.util.ReflectionTestUtils.setField(transport,"modelAdmission",gate);
        when(gate.acquire(any(),anyString(),any())).thenReturn(lease);
        respond(429,"60",false);
        assertThatThrownBy(()->transport.send("meal-advice",request(),body)).isInstanceOf(AiFailure.ProviderException.class).hasMessage("MODEL_RATE_LIMITED");
        assertThat(calls).hasValue(1);verify(gate).rateLimited(lease,Duration.ofSeconds(60));verify(gate).release(eq(lease),anyString());verify(admission).release(any());
    }
    @Test void modelLeaseIsReleasedIfGlobalAdmissionFailsBeforeBudgetReservation() {
        var gate=mock(AiModelAdmission.class);
        var lease=new AiModelAdmission.Lease(java.util.UUID.randomUUID(),"test-scope","test-model");
        org.springframework.test.util.ReflectionTestUtils.setField(transport,"modelAdmission",gate);
        when(gate.acquire(any(),anyString(),any())).thenReturn(lease);
        when(admission.acquire(any())).thenThrow(new RemoteCallGuard.LocalAdmissionDeadlineException("closed"));
        assertThatThrownBy(()->transport.send("meal-advice",request(),body)).isInstanceOf(RemoteCallGuard.LocalAdmissionDeadlineException.class);
        verify(gate).release(lease,null);verifyNoInteractions(budgets);assertThat(calls).hasValue(0);
    }
    @Test void explicitQuota403DoesNotRetryOrExposeProviderPayload() throws Exception {
        server.createContext("/v1/chat/completions",exchange->{
            calls.incrementAndGet();exchange.getRequestBody().readAllBytes();
            byte[] bytes="{\"error\":{\"code\":\"Arrearage\",\"message\":\"sk-private hidden\"}}".getBytes();
            exchange.sendResponseHeaders(403,bytes.length);exchange.getResponseBody().write(bytes);exchange.close();
        });server.start();
        assertThatThrownBy(()->transport.send("diet-assistant-evidence-review",request(),body))
                .isInstanceOf(AiFailure.ProviderException.class).hasMessage("MODEL_QUOTA_EXHAUSTED");
        assertThat(calls).hasValue(1);verify(budgets).settle(isNull(),isNull());
    }
    @Test void runtimeConnectionChangesReachBothDirectAndSdkRequestsWithoutRebuildingServices() throws Exception {
        var client=new AiChatClient(properties,mapper,new AiStatusService(),mock(ApplicationObservability.class),transport);
        var factory=new AssistantChatModelFactory(properties,transport);
        respond(200,null,false);
        assertThat(client.complete("vision-recognition",java.util.List.of(),.1)).isEqualTo("ok");
        assertThat(client.lastModel()).isEqualTo("test-model");
        var headers=new java.util.ArrayList<String>();
        server.createContext("/v2/chat/completions",exchange->{
            calls.incrementAndGet(); headers.add(exchange.getRequestHeaders().getFirst("Authorization"));
            sentBody=new String(exchange.getRequestBody().readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);
            boolean stream=mapper.readTree(sentBody).path("stream").asBoolean();
            byte[] bytes=(stream ? "data: {\"choices\":[{\"delta\":{\"content\":\"updated\"}}]}\n\ndata: [DONE]\n\n"
                    : "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"updated\"}}]}").getBytes();
            exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);exchange.close();
        });
        properties.getAi().overrideConnection(new AppProperties.Ai.Connection("replacement-key",endpoint.resolve("/v2").toString(),"replacement-model"));
        assertThat(client.complete("vision-recognition",java.util.List.of(),.1)).isEqualTo("updated");
        assertThat(client.lastModel()).isEqualTo("replacement-model");
        assertThat(mapper.readTree(sentBody).path("model").asText()).isEqualTo("replacement-model");
        assertThat(factory.create(Duration.ofSeconds(5)).chat("test")).isEqualTo("updated");
        assertThat(mapper.readTree(sentBody).path("model").asText()).isEqualTo("replacement-model");
        assertThat(factory.stream("test",new AssistantStreamDelivery((event,data)->{}))).isEqualTo("updated");
        assertThat(mapper.readTree(sentBody).path("model").asText()).isEqualTo("replacement-model");
        assertThat(headers).containsExactly("Bearer replacement-key","Bearer replacement-key","Bearer replacement-key");
    }
    @Test void qwenDirectAndSdkCallsReceiveTheTaskThinkingPolicy() throws Exception {
        properties.getAi().setModel("qwen3.8-max");
        respond(200,null,false);
        transport.send("vision-recognition",request(),body.replace("test-model","qwen3.8-max"));
        assertThat(mapper.readTree(sentBody).path("enable_thinking").asBoolean(true)).isFalse();
        assertThat(mapper.readTree(sentBody).path("max_completion_tokens").asInt()).isEqualTo(2048);
        properties.getAi().setWeeklyThinkingMode("low");
        var factory=new AssistantChatModelFactory(properties,transport);
        assertThat(factory.create(Duration.ofSeconds(5),.2,"weekly-report-agent").chat("Return a complete report")).isEqualTo("ok");
        assertThat(mapper.readTree(sentBody).path("enable_thinking").asBoolean()).isTrue();
        assertThat(mapper.readTree(sentBody).path("thinking_budget").asInt()).isEqualTo(512);
        assertThat(mapper.readTree(sentBody).path("response_format").path("type").asText()).isEqualTo("json_object");
        assertThat(mapper.readTree(sentBody).has("max_tokens")).isFalse();
        factory.create(Duration.ofSeconds(5)).chat("hello");
        assertThat(mapper.readTree(sentBody).path("enable_thinking").asBoolean(true)).isFalse();
        assertThat(calls).hasValue(3);
    }
    @Test void qwenFactoryStreamUsesTheSamePolicy() throws Exception {
        properties.getAi().setModel("qwen3.8-max");
        server.createContext("/v1/chat/completions",exchange->{
            calls.incrementAndGet();sentBody=new String(exchange.getRequestBody().readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);
            byte[] bytes=("data: {\"choices\":[{\"delta\":{\"content\":\"完整回答\"}}]}\n\n"
                    +"data: {\"choices\":[{\"finish_reason\":\"stop\"}],\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":5}}\n\ndata: [DONE]\n\n").getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type","text/event-stream");exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);exchange.close();
        });server.start();
        var factory=new AssistantChatModelFactory(properties,transport);
        assertThat(factory.stream("hello",new AssistantStreamDelivery((event,data)->{}))).isEqualTo("完整回答");
        var sent=mapper.readTree(sentBody);
        assertThat(sent.path("enable_thinking").asBoolean(true)).isFalse();
        assertThat(sent.path("max_completion_tokens").asInt()).isEqualTo(2048);
        assertThat(sent.has("max_tokens")).isFalse();
        assertThat(sent.path("stream_options").path("include_usage").asBoolean()).isTrue();
        assertThat(sent.has("response_format")).isFalse();
    }
    @Test void truncatedJsonIsNotAcceptedAsACompletedResponseAndUsageIsSettled() throws Exception {
        server.createContext("/v1/chat/completions",exchange->{
            calls.incrementAndGet();exchange.getRequestBody().readAllBytes();
            byte[] bytes="{\"choices\":[{\"message\":{\"content\":\"{\\\"foods\\\":\"},\"finish_reason\":\"length\"}],\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":5}}".getBytes();
            exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);exchange.close();
        });server.start();
        assertThatThrownBy(()->transport.send("vision-recognition",request(),body))
                .isInstanceOf(RemoteCallGuard.NonRetryableRemoteCallException.class).hasMessageContaining("token limit");
        assertThat(calls).hasValue(1);verify(budgets).settle(isNull(),eq(new BigDecimal("0.000020")));
    }
    @Test void truncatedStreamRemainsAFailureAfterDoneAndKeepsKnownUsage() throws Exception {
        server.createContext("/v1/chat/completions",exchange->{
            calls.incrementAndGet();exchange.getRequestBody().readAllBytes();
            byte[] bytes=("data: {\"choices\":[{\"delta\":{\"content\":\"partial\"},\"finish_reason\":\"length\"}]}\n\n"
                    +"data: {\"choices\":[],\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":5}}\n\ndata: [DONE]\n\n").getBytes();
            exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);exchange.close();
        });server.start();
        assertThatThrownBy(()->transport.stream("diet-assistant",request(),body,new AssistantStreamDelivery((event,data)->{})))
                .isInstanceOf(RemoteCallGuard.NonRetryableRemoteCallException.class).hasMessageContaining("token limit");
        assertThat(calls).hasValue(1);verify(budgets).settle(isNull(),eq(new BigDecimal("0.000020")));
    }
    @Test void endpointSpecificEmbeddingPriceSettlesInputOnlyUsage() throws Exception {
        URI embedding=endpoint.resolve("/v1/embeddings");
        String catalog=mapper.writeValueAsString(java.util.List.of(new ModelPriceCatalog.Price(embedding.toString(),"vector-model",new BigDecimal("2"),BigDecimal.ZERO,"CNY")));
        org.springframework.test.util.ReflectionTestUtils.setField(transport,"priceCatalog",new ModelPriceCatalog(properties,mapper,"CNY",catalog));
        server.createContext("/v1/embeddings",exchange->{
            exchange.getRequestBody().readAllBytes();byte[] data="{\"usage\":{\"prompt_tokens\":100}}".getBytes();
            exchange.sendResponseHeaders(200,data.length);exchange.getResponseBody().write(data);exchange.close();
        });server.start();
        var request=HttpRequest.newBuilder(embedding).POST(HttpRequest.BodyPublishers.ofString("{}")).build();
        assertThat(transport.send("embedding",request,"{\"model\":\"vector-model\",\"input\":\"query\"}").statusCode()).isEqualTo(200);
        verify(budgets).settle(isNull(),eq(new BigDecimal("0.000200")));
    }
    @Test void lostExecutionLeasePreventsPhysicalCallsAndBudgetReservation() {
        try (var scope=AiCallContext.systemOperation(Duration.ofSeconds(5), () -> {
            throw new IllegalStateException("lost lease");
        })) {
            assertThatThrownBy(() -> transport.send("lease",request(),body)).isInstanceOf(IllegalStateException.class);
        }
        assertThat(calls).hasValue(0);
        verify(budgets,never()).reserve(any());
    }
    HttpServer server;
    final AtomicInteger calls=new AtomicInteger();
    final ObjectMapper mapper=new ObjectMapper();
    final AiBudgetService budgets=mock(AiBudgetService.class);
    AppProperties properties;
    AiHttpTransport transport;
    AiCallConcurrency admission;
    URI endpoint;
    String sentBody;
    java.util.concurrent.ExecutorService serverExecutor;
    @BeforeEach void setup() throws Exception {
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        serverExecutor=java.util.concurrent.Executors.newFixedThreadPool(2);
        server.setExecutor(serverExecutor);
        endpoint=URI.create("http://127.0.0.1:"+server.getAddress().getPort()+"/v1/chat/completions");
        properties=new AppProperties();
        properties.getAiBudget().setMinimumHttpExecutionMillis(20);
        properties.getAi().setBaseUrl(endpoint.resolve("/v1").toString());properties.getAi().setModel("test-model");properties.getAi().setApiKey("test-key");
        properties.getAi().setInputCostPerMillion(BigDecimal.ONE);properties.getAi().setOutputCostPerMillion(new BigDecimal("2"));
        properties.getRemoteResilience().setInitialBackoffMillis(10);
        admission=mock(AiCallConcurrency.class);
        when(admission.acquire(any())).thenReturn(java.util.UUID.randomUUID());
        transport=new AiHttpTransport(HttpClient.newHttpClient(),new RemoteCallGuard(properties),budgets,properties,mapper,mock(ApplicationObservability.class),admission);
    }
    @Test void admissionWaitDoesNotConsumeThePerHttpTimeout() throws Exception {
        properties.getRemoteResilience().setMaxAttempts(1);
        var localClient=mock(HttpClient.class);
        var reply=mock(HttpResponse.class);
        when(reply.statusCode()).thenReturn(200);when(reply.body()).thenReturn("{}");
        doReturn(reply).when(localClient).send(any(),any());
        transport.stopStreamDeadline();
        transport=new AiHttpTransport(localClient,new RemoteCallGuard(properties),budgets,properties,mapper,mock(ApplicationObservability.class),admission);
        when(admission.acquire(any())).thenAnswer(call->{Thread.sleep(300);return java.util.UUID.randomUUID();});
        var small=HttpRequest.newBuilder(endpoint).timeout(Duration.ofMillis(150)).POST(HttpRequest.BodyPublishers.ofString(body)).build();
        assertThat(transport.send("rerank",small,body).statusCode()).isEqualTo(200);
        var sent=org.mockito.ArgumentCaptor.forClass(HttpRequest.class);
        verify(localClient).send(sent.capture(),any());
        assertThat(sent.getValue().timeout()).contains(Duration.ofMillis(150));
        verify(admission).release(any());
    }
    @Test void shortRemainingDeadlineDoesNotDispatchOrConsumeProviderUsage() {
        properties.getAiBudget().setMinimumHttpExecutionMillis(1000);
        try(var context=AiCallContext.open(1L,Duration.ofMillis(200))) {
            assertThatThrownBy(()->transport.send("no-time",request(),body))
                    .isInstanceOf(RemoteCallGuard.LocalAdmissionDeadlineException.class);
        }
        verifyNoInteractions(admission,budgets);
        assertThat(calls).hasValue(0);
    }
    @Test void slowReservationAndExpiredAdmissionReleaseWithoutHttp() {
        properties.getAiBudget().setMinimumHttpExecutionMillis(150);
        when(budgets.reserve(any())).thenAnswer(call->{Thread.sleep(150);return null;});
        try(var context=AiCallContext.open(1L,Duration.ofMillis(270))) {
            assertThatThrownBy(()->transport.send("reserve-delay",request(),body))
                    .isInstanceOf(RemoteCallGuard.LocalAdmissionDeadlineException.class);
        }
        verify(budgets,never()).dispatched(any());
        verify(admission).release(any());
        assertThat(calls).hasValue(0);
    }
    @AfterEach void stop() { server.stop(0);serverExecutor.shutdownNow();transport.stopStreamDeadline(); }
    @Test void largeInlineImageCanPassASmallBudgetWithoutChangingThePayload() throws Exception {
        properties.getAi().setModel("qwen3.8-max");properties.getAi().setInputCostPerMillion(new BigDecimal("12"));properties.getAi().setOutputCostPerMillion(new BigDecimal("36"));
        when(budgets.reserve(any())).thenAnswer(call->{BigDecimal estimate=call.getArgument(0);if(estimate.compareTo(new BigDecimal("2"))>0)throw new AiBudgetService.BudgetExceededException(100,1);return null;});
        String image="data:image/png;base64,"+"A".repeat(4_000_000);
        String requestBody=mapper.writeValueAsString(java.util.Map.of("model","qwen3.8-max","messages",java.util.List.of(java.util.Map.of("role","user","content",java.util.List.of(java.util.Map.of("type","image_url","image_url",java.util.Map.of("url",image)))))));
        respond(200,null,false);assertThat(transport.send("vision-recognition",request(),requestBody).statusCode()).isEqualTo(200);
        assertThat(mapper.readTree(sentBody).path("messages").get(0).path("content").get(0).path("image_url").path("url").asText()).isEqualTo(image);
        var estimate=org.mockito.ArgumentCaptor.forClass(BigDecimal.class);verify(budgets).reserve(estimate.capture());assertThat(estimate.getValue()).isLessThan(new BigDecimal("0.2"));
        verify(budgets).settle(isNull(),eq(new BigDecimal("0.000300")));
    }
    void respond(int status,String retryAfter,boolean transientFirst) {
        server.createContext("/v1/chat/completions",exchange->{
            int count=calls.incrementAndGet();
            sentBody=new String(exchange.getRequestBody().readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);
            int code=transientFirst && count>1 ? 200:status;
            if (retryAfter!=null) exchange.getResponseHeaders().set("Retry-After",retryAfter);
            byte[] data=(code==200?"{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"ok\"},\"finish_reason\":\"stop\"}],\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":5}}":"{}").getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(code,data.length);exchange.getResponseBody().write(data);exchange.close();
        });server.start();
    }
    HttpRequest request() { return HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(10)).POST(HttpRequest.BodyPublishers.ofString("{}")).build(); }
    final String body="{\"model\":\"test-model\",\"messages\":[{\"role\":\"user\",\"content\":\"hello\"}],\"max_tokens\":999999}";
    @Test void retriesReserveEachAttemptAndSettleKnownUsage() throws Exception {
        var reservation=new AiBudgetStore.Reservation(java.util.UUID.randomUUID(),"user:1",LocalDate.now(),new BigDecimal("0.02"));
        when(budgets.reserve(any())).thenReturn(reservation);
        respond(429,"0",true);
        assertThat(transport.send("test",request(),body).statusCode()).isEqualTo(200);
        assertThat(calls).hasValue(2);
        assertThat(mapper.readTree(sentBody).path("max_tokens").asInt()).isEqualTo(2048);
        verify(budgets,times(2)).reserve(any());verify(budgets,times(2)).dispatched(reservation);
        verify(budgets).settle(reservation,null);verify(budgets).settle(eq(reservation),eq(new BigDecimal("0.000020")));
    }
    @Test void providerRetryDelayBeyondDeadlineStopsWithoutAnotherAttempt() {
        respond(429,"120",false);
        try (var scope=AiCallContext.open(1L,Duration.ofSeconds(1))) {
            assertThatThrownBy(()->transport.send("test",request(),body)).isInstanceOf(RemoteCallGuard.RemoteCallDeadlineException.class);
        }
        assertThat(calls).hasValue(1);
    }
    @Test void exhaustedBudgetNeverContactsProvider() {
        respond(200,null,false);
        when(budgets.reserve(any())).thenThrow(new AiBudgetService.BudgetExceededException(100,10));
        assertThatThrownBy(()->transport.send("test",request(),body)).isInstanceOf(AiBudgetService.BudgetExceededException.class);
        assertThat(calls).hasValue(0);verify(budgets,times(1)).reserve(any());verify(budgets,never()).dispatched(any());
    }
    @Test void langchainUsesSameTransportAndDisablesNestedRetries() {
        respond(400,null,false);
        var model=new AssistantChatModelFactory(properties,transport).create(Duration.ofSeconds(5));
        assertThatThrownBy(()->model.chat("hello")).isInstanceOf(RuntimeException.class);
        assertThat(calls).hasValue(1);verify(budgets,times(1)).reserve(any());
    }
    @Test void langchainSuccessAlsoCapsOutputAndSettlesUsage() throws Exception {
        respond(200,null,false);
        var model=new AssistantChatModelFactory(properties,transport).create(Duration.ofSeconds(5));
        assertThat(model.chat("hello")).isEqualTo("ok");
        assertThat(mapper.readTree(sentBody).path("max_tokens").asInt()).isEqualTo(2048);
        verify(budgets).settle(isNull(),eq(new BigDecimal("0.000020")));
    }
    @Test void socketTimeoutUsesRemainingOperationDeadline() throws Exception {
        server.createContext("/v1/chat/completions",exchange->{
            calls.incrementAndGet();
            try { new java.util.concurrent.CountDownLatch(1).await(2,java.util.concurrent.TimeUnit.SECONDS); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            exchange.close();
        });server.start();
        long start=System.nanoTime();
        try (var scope=AiCallContext.open(1L,Duration.ofMillis(150))) {
            assertThatThrownBy(()->transport.send("timeout",request(),body)).isInstanceOf(RuntimeException.class);
        }
        assertThat(Duration.ofNanos(System.nanoTime()-start)).isLessThan(Duration.ofSeconds(1));
        assertThat(calls).hasValue(1);
        var metrics=org.springframework.test.util.ReflectionTestUtils.getField(transport,"observability");
        verify((ApplicationObservability)metrics).recordAiUsageAvailability("timeout",false,true);
        verify((ApplicationObservability)metrics).recordAiHttpAttempt(eq("timeout"),anyLong(),eq(false),eq(false));
        verify((ApplicationObservability)metrics).recordAiHttpFailure("timeout","timeout");
    }
    @Test void streamDeliversUtf8BeforeProviderCompletesAndSettlesFinalUsage() throws Exception {
        var first=new java.util.concurrent.CountDownLatch(1);var release=new java.util.concurrent.CountDownLatch(1);
        server.createContext("/v1/chat/completions",exchange->{
            calls.incrementAndGet();sentBody=new String(exchange.getRequestBody().readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type","text/event-stream");exchange.sendResponseHeaders(200,0);
            try {
                byte[] bytes="data: {\"choices\":[{\"delta\":{\"content\":\"蔬菜\"}}]}\r\n\r\n".getBytes(java.nio.charset.StandardCharsets.UTF_8);
                // Split a multibyte UTF-8 character across physical writes.
                for(byte b:bytes) { exchange.getResponseBody().write(b);exchange.getResponseBody().flush(); }
                release.await(3,java.util.concurrent.TimeUnit.SECONDS);
                exchange.getResponseBody().write("data: {\"choices\":[{\"delta\":{\"content\":\"与蛋白\"}}]}\n\ndata: {\"choices\":[],\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":5}}\n\ndata: [DONE]\n\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            } catch(InterruptedException e) { Thread.currentThread().interrupt(); } finally { exchange.close(); }
        });server.start();
        var delivery=new AssistantStreamDelivery((event,data)->{ if(event.equals("delta"))first.countDown(); });
        var worker=java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            var result=worker.submit(()->transport.stream("stream",request(),body,delivery));
            assertThat(first.await(2,java.util.concurrent.TimeUnit.SECONDS)).isTrue();assertThat(result.isDone()).isFalse();
            release.countDown();assertThat(result.get(3,java.util.concurrent.TimeUnit.SECONDS)).isEqualTo("蔬菜与蛋白");
            assertThat(mapper.readTree(sentBody).path("max_tokens").asInt()).isEqualTo(2048);
            verify(budgets).settle(isNull(),eq(new BigDecimal("0.000020")));
        } finally { release.countDown();worker.shutdownNow(); }
    }
    @Test void cancellingAStreamClosesProviderAndDoesNotRetryOrInventUsage() throws Exception {
        var sent=new java.util.concurrent.CountDownLatch(1);var release=new java.util.concurrent.CountDownLatch(1);
        server.createContext("/v1/chat/completions",exchange->{
            calls.incrementAndGet();exchange.getRequestBody().readAllBytes();exchange.sendResponseHeaders(200,0);
            try { exchange.getResponseBody().write("data: {\"choices\":[{\"delta\":{\"content\":\"部分内容\"}}]}\n\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));exchange.getResponseBody().flush();release.await(3,java.util.concurrent.TimeUnit.SECONDS); }
            catch(InterruptedException e) { Thread.currentThread().interrupt(); }finally { exchange.close(); }
        });server.start();
        var delivery=new AssistantStreamDelivery((event,data)->sent.countDown());
        var worker=java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            var result=worker.submit(()->transport.stream("cancel",request(),body,delivery));
            assertThat(sent.await(2,java.util.concurrent.TimeUnit.SECONDS)).isTrue();delivery.cancel();
            assertThatThrownBy(()->result.get(2,java.util.concurrent.TimeUnit.SECONDS)).hasCauseInstanceOf(java.util.concurrent.CancellationException.class);
            assertThat(calls).hasValue(1);verify(budgets).settle(null,null);
        }finally{ release.countDown();worker.shutdownNow(); }
    }
    @Test void streamTimeoutAndIncompleteEndDoNotReplayDeliveredTokens() throws Exception {
        server.createContext("/v1/chat/completions",exchange->{
            calls.incrementAndGet();exchange.getRequestBody().readAllBytes();exchange.sendResponseHeaders(200,0);
            exchange.getResponseBody().write("data: {\"choices\":[{\"delta\":{\"content\":\"不完整\"}}]}\n\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));exchange.getResponseBody().flush();
            try { new java.util.concurrent.CountDownLatch(1).await(1,java.util.concurrent.TimeUnit.SECONDS); }catch(InterruptedException e) { Thread.currentThread().interrupt(); } finally { exchange.close(); }
        });server.start();
        var delivery=new AssistantStreamDelivery((event,data)->{});long started=System.nanoTime();
        try(var context=AiCallContext.open(1L,Duration.ofMillis(200))) {
            assertThatThrownBy(()->transport.stream("timeout-stream",request(),body,delivery)).isInstanceOf(RemoteCallGuard.NonRetryableRemoteCallException.class);
        }
        assertThat(Duration.ofNanos(System.nanoTime()-started)).isLessThan(Duration.ofSeconds(1));assertThat(calls).hasValue(1);
    }
}
