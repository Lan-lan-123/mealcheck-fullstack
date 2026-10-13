package com.example.mealcheck.controller;

import com.example.mealcheck.dto.JournalDtos.Feedback;
import com.example.mealcheck.dto.TrackingDtos.AnswerFeedback;
import com.example.mealcheck.dto.assistant.AssistantRequest;
import com.example.mealcheck.security.UserPrincipal;
import com.example.mealcheck.service.*;
import jakarta.validation.Valid;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import java.util.Map;
import java.util.concurrent.CancellationException;

@RestController
@RequestMapping("/api/assistant")
public class AssistantDeliveryController {
    private static final org.slf4j.Logger log=org.slf4j.LoggerFactory.getLogger(AssistantDeliveryController.class);
    private final LangChainDietAssistantService assistant;
    private final RateLimitService rateLimit;
    private final AssistantTaskManager tasks;
    private final AnswerFeedbackService feedback;
    public AssistantDeliveryController(LangChainDietAssistantService assistant,RateLimitService rateLimit,AssistantTaskManager tasks,AnswerFeedbackService feedback) {
        this.assistant=assistant;this.rateLimit=rateLimit;this.tasks=tasks;this.feedback=feedback;
    }
    private void send(SseEmitter emitter,String event,Object data) {
        try { emitter.send(SseEmitter.event().name(event).data(data)); }
        catch(Exception disconnected) { throw new CancellationException("连接已关闭"); }
    }
    @PostMapping(value="/stream",produces=MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<SseEmitter> stream(@AuthenticationPrincipal UserPrincipal user,@Valid @RequestBody AssistantRequest request) {
        if(request.questionText().isBlank())throw new IllegalArgumentException("问题须为 1 到 4000 字。");
        feedback.requireConversation(user.getId(),request.getConversationId());
        var limit=rateLimit.consumeAssistant(user.getUsername());
        if(limit.unavailable())throw new IllegalStateException("限流服务暂时不可用");
        if(!limit.allowed())throw new RateLimitExceededException("提问太频繁了，请稍后再试。",limit.limit(),limit.remaining(),limit.retryAfter());
        // The task manager owns the operation deadline, including time spent in its queue.
        var emitter=new SseEmitter(180_000L);
        var delivery=new AssistantStreamDelivery((event,data)->send(emitter,event,data));
        emitter.onTimeout(()->tasks.cancel(user.getId(),delivery.requestId));
        emitter.onError(error->tasks.cancel(user.getId(),delivery.requestId));
        emitter.onCompletion(()->tasks.cancel(user.getId(),delivery.requestId));
        // Buffer an admission event before starting work, so queued callers receive their
        // cancellation ID immediately. A rejected submission never returns this emitter.
        send(emitter,"queued",Map.of("requestId",delivery.requestId,"status","QUEUED"));
        tasks.submit(user.getId(),delivery,()->{
            org.slf4j.MDC.put("requestId",delivery.requestId);
            try {
                log.info("Assistant delivery started userId={}",user.getId());
                var response=assistant.deliver(user,request.questionText(),request.getConversationId(),request.getHistory(),delivery);
                delivery.event("complete",response);
            } catch(Exception error) {
                log.warn("Assistant delivery incomplete userId={} type={}",user.getId(),error.getClass().getSimpleName());
                try { send(emitter,"error",Map.of("requestId",delivery.requestId,"message",error instanceof CancellationException?"回复已取消":"回复未完成，请稍后重试。可凭请求 ID 定位问题。")); }catch(CancellationException ignored) { }
                throw error instanceof RuntimeException runtime?runtime:new IllegalStateException(error);
            } finally {
                log.info("Assistant delivery finished userId={} complete={}",user.getId(),delivery.persisted);
                org.slf4j.MDC.remove("requestId");
            }
        },state->{
            try { send(emitter,"error",Map.of("requestId",delivery.requestId,"code",state.name(),"message",state==AssistantTaskManager.State.TIMED_OUT?"回复已超时（包含排队时间）。":"回复已取消")); }catch(CancellationException ignored) { }
            emitter.complete();
        },emitter::complete);
        return ResponseEntity.ok().headers(RateLimitHttpHeaders.from(limit)).header("X-Assistant-Request-ID",delivery.requestId)
            .header("Cache-Control","no-cache").header("X-Accel-Buffering","no").body(emitter);
    }
    @DeleteMapping("/requests/{requestId}") public void cancel(@AuthenticationPrincipal UserPrincipal user,@PathVariable String requestId) { tasks.cancel(user.getId(),requestId); }
    @PutMapping("/messages/{id}/feedback") public void feedback(@AuthenticationPrincipal UserPrincipal user,@PathVariable long id,@Valid @RequestBody Feedback body) { feedback.save(user,id,body); }
    @GetMapping("/messages/{id}/feedback") public AnswerFeedback feedback(@AuthenticationPrincipal UserPrincipal user,@PathVariable long id) { return feedback.get(user.getId(),id); }
}
