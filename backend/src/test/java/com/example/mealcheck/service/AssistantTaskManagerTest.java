package com.example.mealcheck.service;

import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import java.time.Duration;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.assertj.core.api.Assertions.*;

class AssistantTaskManagerTest {
    private Runnable waitFor(CountDownLatch gate, CountDownLatch entered) {
        return () -> { entered.countDown(); try { gate.await(); } catch(InterruptedException cancelled) { Thread.currentThread().interrupt(); } };
    }
    @Test void cancellingQueuedWorkReleasesCapacityAndChecksOwnership() throws Exception {
        var manager=new AssistantTaskManager(1,1,Duration.ofSeconds(5));
        var release=new CountDownLatch(1);var entered=new CountDownLatch(1);var ran=new AtomicBoolean();
        var delivery=new AssistantStreamDelivery(null);
        try {
            manager.submit(1,new AssistantStreamDelivery(null),waitFor(release,entered),s->{},()->{});
            assertThat(entered.await(1,TimeUnit.SECONDS)).isTrue();
            var queued=manager.submit(2,delivery,()->ran.set(true),s->{},()->{});
            manager.cancel(99,delivery.requestId);assertThat(manager.queuedTasks()).isEqualTo(1);
            manager.cancel(2,delivery.requestId);assertThat(manager.queuedTasks()).isZero();
            assertThat(queued.state()).isEqualTo(AssistantTaskManager.State.CANCELLED);
            var finished=new CountDownLatch(1);
            manager.submit(3,new AssistantStreamDelivery(null),()->{},s->{},finished::countDown);
            release.countDown();assertThat(finished.await(1,TimeUnit.SECONDS)).isTrue();assertThat(ran).isFalse();
        } finally { release.countDown();manager.shutdown(); }
    }
    @Test void queueTimeCountsTowardDeadlineAndExpiredWorkNeverRuns() throws Exception {
        var manager=new AssistantTaskManager(1,2,Duration.ofSeconds(5));
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var expired=new CountDownLatch(1);var executed=new AtomicBoolean();
        var queued=new AssistantStreamDelivery(null);
        try {
            manager.submit(1,new AssistantStreamDelivery(null),waitFor(release,entered),s->{},()->{});
            assertThat(entered.await(1,TimeUnit.SECONDS)).isTrue();
            AssistantTaskManager.Task task;
            try(var deadline=AiCallContext.open(2L,Duration.ofMillis(150))) {
                task=manager.submit(2,queued,()->executed.set(true),state->{ assertThat(state).isEqualTo(AssistantTaskManager.State.TIMED_OUT);expired.countDown(); },()->{});
            }
            assertThat(expired.await(2,TimeUnit.SECONDS)).isTrue();assertThat(manager.queuedTasks()).isZero();
            assertThat(task.state()).isEqualTo(AssistantTaskManager.State.TIMED_OUT);assertThat(executed).isFalse();
            assertThat(queued.failureStatus).isEqualTo("ERROR");
        } finally { release.countDown();manager.shutdown(); }
    }
    @Test void deadlineContextIsInheritedAndUserAdmissionSurvivesRunningCleanup() throws Exception {
        var manager=new AssistantTaskManager(1,2,Duration.ofSeconds(5));
        var started=new CountDownLatch(1);var releaseCleanup=new CountDownLatch(1);var finished=new CountDownLatch(1);
        var id=new AssistantStreamDelivery(null);var context=new AtomicReference<AiCallContext.Context>();
        try {
            long deadline;
            try(var scope=AiCallContext.open(1L,Duration.ofSeconds(3))) {
                deadline=AiCallContext.capture().deadlineNanos();
                manager.submit(1,id,()->{
                    context.set(AiCallContext.capture());started.countDown();
                    try { new CountDownLatch(1).await(); } catch(InterruptedException cancelled) {
                        try { releaseCleanup.await(1,TimeUnit.SECONDS); }catch(InterruptedException ignored) { }
                    }
                },s->{},finished::countDown);
            }
            assertThat(started.await(1,TimeUnit.SECONDS)).isTrue();manager.cancel(1,id.requestId);
            assertThat(context.get().deadlineNanos()).isEqualTo(deadline);
            assertThatThrownBy(()->manager.submit(1,new AssistantStreamDelivery(null),()->{},s->{},()->{})).isInstanceOf(ResponseStatusException.class);
            releaseCleanup.countDown();assertThat(finished.await(1,TimeUnit.SECONDS)).isTrue();
            var accepted=new CountDownLatch(1);manager.submit(1,new AssistantStreamDelivery(null),()->{},s->{},accepted::countDown);
            assertThat(accepted.await(1,TimeUnit.SECONDS)).isTrue();
        } finally { releaseCleanup.countDown();manager.shutdown(); }
    }
}
