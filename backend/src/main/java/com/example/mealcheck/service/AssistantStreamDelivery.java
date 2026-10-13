package com.example.mealcheck.service;

import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.function.BiConsumer;
import com.example.mealcheck.entity.AssistantConversation;

/** Delivery state belongs to one worker and one authenticated request. */
public final class AssistantStreamDelivery implements AutoCloseable {
    private static final ThreadLocal<AssistantStreamDelivery> CURRENT=new ThreadLocal<>();
    public final String requestId=UUID.randomUUID().toString();
    public final StringBuilder partial=new StringBuilder();
    public AssistantConversation conversation;
    public boolean persisted;
    private final BiConsumer<String,Object> listener;
    private volatile boolean cancelled;
    public volatile String failureStatus = "CANCELLED";
    private Runnable closeTransport;
    public AssistantStreamDelivery(BiConsumer<String,Object> listener) { this.listener=listener; }
    public static AssistantStreamDelivery current() { return CURRENT.get(); }
    public void enter() { CURRENT.set(this); }
    public boolean streaming() { return listener!=null; }
    public void event(String name,Object value) { check(); if(listener!=null)listener.accept(name,value); }
    public void delta(String value) { check(); if(partial.length()+value.length()>120_000)throw new IllegalStateException("回答超过长度限制"); partial.append(value); event("delta",java.util.Map.of("text",value)); }
    public void check() { if(cancelled || Thread.currentThread().isInterrupted())throw new CancellationException("回复已取消"); }
    public synchronized void transport(Runnable close) { closeTransport=close; if(cancelled && close!=null)close.run(); }
    public void cancel() { cancel(false); }
    public synchronized void cancel(boolean timeout) { failureStatus=timeout?"ERROR":"CANCELLED";cancelled=true; if(closeTransport!=null)closeTransport.run(); }
    @Override public void close() { CURRENT.remove(); transport(null); }
}
