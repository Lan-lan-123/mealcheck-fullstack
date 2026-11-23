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
    public boolean knowledgeAnswer;
    public boolean semanticReviewCompleted;
    public com.example.mealcheck.dto.assistant.AssistantReviewMode reviewMode=com.example.mealcheck.dto.assistant.AssistantReviewMode.REVIEWED;
    private final BiConsumer<String,Object> listener;
    private volatile boolean cancelled;
    public volatile String failureStatus = "CANCELLED";
    private Runnable closeTransport;
    private String hiddenModelSuffix;
    private final StringBuilder modelPending = new StringBuilder();
    private boolean suffixStarted;
    private int modelCharacters;
    public AssistantStreamDelivery(BiConsumer<String,Object> listener) { this.listener=listener; }
    public static AssistantStreamDelivery current() { return CURRENT.get(); }
    public void enter() { CURRENT.set(this); }
    public boolean streaming() { return listener!=null; }
    public void event(String name,Object value) { check(); if(listener!=null)listener.accept(name,value); }
    public void delta(String value) { check(); if(partial.length()+value.length()>120_000)throw new IllegalStateException("回答超过长度限制"); partial.append(value); event("delta",java.util.Map.of("text",value)); }
    /** Only provider tokens pass this filter; completed/local answers still use delta directly. */
    public void hideModelSuffix(String marker) {
        if(marker == null || marker.isEmpty()) throw new IllegalArgumentException("Stream metadata marker is required");
        hiddenModelSuffix=marker;
        modelPending.setLength(0);
        suffixStarted=false;
        modelCharacters=0;
    }
    public void modelDelta(String value) {
        check();
        modelCharacters+=value.length();
        if(modelCharacters>120_000) throw new IllegalStateException("回答超过长度限制");
        if(hiddenModelSuffix==null) { delta(value);return; }
        if(suffixStarted) return;
        modelPending.append(value);
        int markerAt=modelPending.indexOf(hiddenModelSuffix);
        if(markerAt>=0) {
            if(markerAt>0) delta(modelPending.substring(0,markerAt));
            modelPending.setLength(0);
            suffixStarted=true;
            return;
        }
        // Hold a possible marker prefix so a delimiter split across SSE chunks never leaks.
        int held=Math.min(modelPending.length(),hiddenModelSuffix.length()-1);
        while(held>0 && !modelPending.substring(modelPending.length()-held).equals(hiddenModelSuffix.substring(0,held))) held--;
        int visible=modelPending.length()-held;
        if(visible>0) {
            delta(modelPending.substring(0,visible));
            modelPending.delete(0,visible);
        }
    }
    public void finishModelText() {
        check();
        if(!suffixStarted && modelPending.length()==1) delta(modelPending.toString());
        modelPending.setLength(0);
    }
    public void check() { if(cancelled || Thread.currentThread().isInterrupted())throw new CancellationException("回复已取消"); }
    public synchronized void transport(Runnable close) { closeTransport=close; if(cancelled && close!=null)close.run(); }
    public void cancel() { cancel(false); }
    public synchronized void cancel(boolean timeout) { failureStatus=timeout?"ERROR":"CANCELLED";cancelled=true; if(closeTransport!=null)closeTransport.run(); }
    @Override public void close() { CURRENT.remove(); transport(null); }
}
