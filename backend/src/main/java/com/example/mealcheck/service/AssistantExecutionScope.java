package com.example.mealcheck.service;

/** Binds a shared lease to its worker without coupling chat generation to HTTP delivery. */
final class AssistantExecutionScope implements AutoCloseable {
    private record Execution(AssistantTaskStore store, AssistantTaskStore.Lease lease) { }
    private static final ThreadLocal<Execution> CURRENT=new ThreadLocal<>();
    private final Execution previous;
    AssistantExecutionScope(AssistantTaskStore store,AssistantTaskStore.Lease lease) {
        previous=CURRENT.get();
        if(store!=null)CURRENT.set(new Execution(store,lease));
    }
    static void guardPublication(boolean complete) {
        var execution=CURRENT.get();
        if(execution!=null)execution.store().guardPublication(execution.lease(),complete);
    }
    static void preparePublication() {
        var execution=CURRENT.get();
        if(execution!=null)execution.store().preparePublication();
    }
    @Override public void close() { if(previous==null)CURRENT.remove();else CURRENT.set(previous); }
}
