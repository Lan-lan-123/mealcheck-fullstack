package com.example.mealcheck.service;

import org.junit.jupiter.api.Test;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;

class BackgroundTaskDispatcherTest {
    @Test void overlappingTicksAreCoalescedAndARejectedTaskCanBeResubmitted() {
        var tasks=new java.util.ArrayList<Runnable>();
        var dispatcher=new BackgroundTaskDispatcher(tasks::add);
        var calls=new java.util.concurrent.atomic.AtomicInteger();
        assertThat(dispatcher.submit("outbox",calls::incrementAndGet)).isTrue();
        assertThat(dispatcher.submit("outbox",calls::incrementAndGet)).isFalse();
        tasks.get(0).run();
        assertThat(calls.get()).isEqualTo(1);
        assertThat(dispatcher.submit("outbox",calls::incrementAndGet)).isTrue();
        var rejectOnce=new java.util.concurrent.atomic.AtomicBoolean(true);
        var bounded=new BackgroundTaskDispatcher(task -> { if (rejectOnce.getAndSet(false)) throw new RejectedExecutionException(); task.run(); });
        assertThat(bounded.submit("cleanup",calls::incrementAndGet)).isFalse();
        assertThat(bounded.submit("cleanup",calls::incrementAndGet)).isTrue();
    }
}
