package com.example.mealcheck.service;

import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import java.time.Duration;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class AssistantTaskManagerTest {
    @Test void coordinationOutageRejectsWorkAndDoesNotLeaveALocalUserReservation() {
        var store=mock(AssistantTaskStore.class);
        var manager=new AssistantTaskManager(1,1,Duration.ofSeconds(5),store,"node",15);
        var ran=new AtomicBoolean();
        try {
            when(store.admit(anyString(),anyLong(),anyString(),anyLong(),anyInt()))
                    .thenThrow(new org.springframework.dao.DataAccessResourceFailureException("offline"));
            assertThatThrownBy(()->manager.call(1,d->{ran.set(true);return "bad";})).isInstanceOf(IllegalStateException.class);
            assertThat(ran).isFalse();assertThat(manager.queuedTasks()).isZero();
            doAnswer(invocation->new AssistantTaskStore.Lease(invocation.getArgument(0),1,"node","token"))
                    .when(store).admit(anyString(),anyLong(),anyString(),anyLong(),anyInt());
            when(store.start(any())).thenReturn(true);
            assertThat(manager.<String>call(1,d->"recovered")).isEqualTo("recovered");
        }finally{manager.shutdown();}
    }

    @Test void failedHeartbeatsDoNotExtendTheLocalExecutionLease() throws Exception {
        var store=mock(AssistantTaskStore.class);
        when(store.admit(anyString(),anyLong(),anyString(),anyLong(),anyInt()))
                .thenAnswer(invocation->new AssistantTaskStore.Lease(invocation.getArgument(0),1,"node","token"));
        when(store.start(any())).thenReturn(true);
        when(store.heartbeat(anyString(),anyList(),anyInt()))
                .thenThrow(new org.springframework.dao.DataAccessResourceFailureException("offline"));
        var manager=new AssistantTaskManager(1,1,Duration.ofSeconds(15),store,"node",2);
        var entered=new CountDownLatch(1);var stopped=new CountDownLatch(1);var delivery=new AssistantStreamDelivery(null);
        try {
            var task=manager.submit(1,delivery,()->{
                entered.countDown();try{new CountDownLatch(1).await();}catch(InterruptedException interrupted){Thread.currentThread().interrupt();}
            },state->{},stopped::countDown);
            assertThat(entered.await(2,TimeUnit.SECONDS)).isTrue();
            assertThat(stopped.await(6,TimeUnit.SECONDS)).isTrue();
            assertThat(task.state()).isEqualTo(AssistantTaskManager.State.INTERRUPTED);
            assertThat(delivery.failureStatus).isEqualTo("ERROR");
        }finally{manager.shutdown();}
    }
    @Test void synchronousCallsShareAdmissionAndReleaseItBeforeReturningSuccess() throws Exception {
        var manager=new AssistantTaskManager(1,2,Duration.ofSeconds(5));
        var callers=Executors.newSingleThreadExecutor();var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        try {
            var first=callers.submit(()->manager.call(1,delivery->{
                entered.countDown();try{release.await(3,TimeUnit.SECONDS);}catch(InterruptedException interrupted){throw new CancellationException();}
                return "first";
            }));
            assertThat(entered.await(2,TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(()->manager.submit(1,new AssistantStreamDelivery(null),()->{},s->{},()->{})).isInstanceOf(ResponseStatusException.class);
            release.countDown();assertThat(first.get(3,TimeUnit.SECONDS)).isEqualTo("first");
            assertThat(manager.<String>call(1,delivery->"second")).isEqualTo("second");
            assertThatThrownBy(()->manager.call(1,delivery->{throw new IllegalArgumentException("bad request");})).isInstanceOf(IllegalArgumentException.class);
        }finally{release.countDown();manager.shutdown();callers.shutdownNow();}
    }
    @Test void closedManagerRejectsAdmission() {
        var manager=new AssistantTaskManager(1,1,Duration.ofSeconds(5));manager.shutdown();
        assertThatThrownBy(()->manager.call(1,delivery->"late")).isInstanceOf(ResponseStatusException.class);
    }
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
