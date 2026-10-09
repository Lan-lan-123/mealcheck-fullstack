package com.example.mealcheck.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

/** Keeps blocking maintenance work off scheduling and assistant timeout threads. */
@Service
public class BackgroundTaskDispatcher {
    private static final Logger log = LoggerFactory.getLogger(BackgroundTaskDispatcher.class);
    private final Executor executor;
    private final Set<String> active = ConcurrentHashMap.newKeySet();

    public BackgroundTaskDispatcher(@Qualifier("maintenanceExecutor") Executor executor) {
        this.executor = executor;
    }

    public boolean submit(String name, Runnable task) {
        if (!active.add(name)) return false;
        try {
            executor.execute(() -> {
                try {
                    task.run();
                } catch (RuntimeException error) {
                    log.warn("Background maintenance failed task={} type={}", name, error.getClass().getSimpleName());
                } finally {
                    active.remove(name);
                }
            });
            return true;
        } catch (RejectedExecutionException error) {
            active.remove(name);
            log.warn("Background maintenance deferred task={}", name);
            return false;
        }
    }
}
