package com.example.mealcheck.service;

import java.net.http.*;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.*;

/** Payload-free timings. For non-streaming JSON first byte is NOT first model token. */
final class AiHttpObservation {
    private final long started=System.nanoTime();
    volatile long headers=-1,firstByte=-1;
    volatile int status;
    volatile String providerTrace="unavailable";
    HttpResponse.BodyHandler<String> handler() {
        return info->{
            headers=micros();status=info.statusCode();
            String value=info.headers().firstValue("x-siliconcloud-trace-id").orElse("");
            if(value.matches("[A-Za-z0-9_.:/-]{1,160}"))providerTrace=value;
            var delegate=HttpResponse.BodyHandlers.ofString().apply(info);
            return new HttpResponse.BodySubscriber<>() {
                public CompletionStage<String> getBody(){return delegate.getBody();}
                public void onSubscribe(Flow.Subscription subscription){delegate.onSubscribe(subscription);}
                public void onNext(List<ByteBuffer> bytes){
                    if(firstByte<0 && bytes.stream().anyMatch(ByteBuffer::hasRemaining))firstByte=micros();
                    delegate.onNext(bytes);
                }
                public void onError(Throwable error){delegate.onError(error);}
                public void onComplete(){delegate.onComplete();}
            };
        };
    }
    private long micros(){return (System.nanoTime()-started)/1000;}
}
