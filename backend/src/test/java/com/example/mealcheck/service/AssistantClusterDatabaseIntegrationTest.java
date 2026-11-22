package com.example.mealcheck.service;

import com.example.mealcheck.entity.UserAccount;
import com.example.mealcheck.repository.UserAccountRepository;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.*;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.utility.DockerImageName;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.assertj.core.api.Assertions.*;

@DataJpaTest(properties={"spring.jpa.hibernate.ddl-auto=validate","spring.flyway.baseline-on-migrate=false"})
@AutoConfigureTestDatabase(replace=AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation=Propagation.NOT_SUPPORTED)
@Import({AssistantTaskStore.class,AssistantConversationService.class})
@Testcontainers
class AssistantClusterDatabaseIntegrationTest {
    @Container static final PostgreSQLContainer<?> POSTGRES=new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username",POSTGRES::getUsername);
        registry.add("spring.datasource.password",POSTGRES::getPassword);
    }
    @Autowired AssistantTaskStore store;
    @Autowired AssistantConversationService conversations;
    @Autowired UserAccountRepository users;
    @Autowired com.example.mealcheck.repository.AssistantConversationRepository conversationRepository;
    @Autowired com.example.mealcheck.repository.AssistantConversationMessageRepository messageRepository;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactions;
    UserAccount user,other;

    @BeforeEach void setup() {
        jdbc.execute("TRUNCATE users CASCADE");
        user=create("cluster-user");other=create("other-user");
    }
    UserAccount create(String name) {var user=new UserAccount();user.setUsername(name);user.setPasswordHash("test-hash");return users.saveAndFlush(user);}
    AssistantTaskStore.Lease admit(String owner) {return store.admit(UUID.randomUUID().toString(),user.getId(),owner,30000,15);}
    void expire(AssistantTaskStore.Lease lease) {jdbc.update("UPDATE assistant_tasks SET lease_until=clock_timestamp()-INTERVAL '1 second' WHERE request_id=?",lease.requestId());}
    void append(AssistantTaskStore.Lease lease,boolean rollback) {
        var conversation=conversations.resolve(user,null,"问题");
        new TransactionTemplate(transactions).execute(status->{
            try(var execution=new AssistantExecutionScope(store,lease)) {
                conversations.appendTurn(conversation,"问题","回答",lease.requestId(),"COMPLETE");
                if(rollback)throw new IllegalStateException("rollback");
            }
            return null;
        });
    }
    long messages() {return jdbc.queryForObject("SELECT count(*) FROM assistant_conversation_messages",Long.class);}

    @Test void simultaneousInstancesAdmitOnlyOneTaskPerUser() throws Exception {
        var executor=Executors.newFixedThreadPool(2);var gate=new CountDownLatch(1);
        try {
            var futures=new ArrayList<Future<Boolean>>();
            for(int i=0;i<2;i++){String owner="node-"+i;futures.add(executor.submit(()->{
                gate.await();try{admit(owner);return true;}catch(ResponseStatusException conflict){assertThat(conflict.getStatusCode().value()).isEqualTo(409);return false;}
            }));}
            gate.countDown();int accepted=0;for(var future:futures)if(future.get(10,TimeUnit.SECONDS))accepted++;
            assertThat(accepted).isOne();
            assertThat(store.admit(UUID.randomUUID().toString(),other.getId(),"node-b",30000,15)).isNotNull();
        }finally{executor.shutdownNow();}
    }

    @Test void cancellationChecksOwnershipAndPreventsQueuedStartAndPublication() {
        var lease=admit("node-a");
        assertThat(store.cancel(other.getId(),lease.requestId())).isEqualTo(AssistantTaskStore.Cancellation.ABSENT);
        assertThatThrownBy(()->store.status(other.getId(),lease.requestId())).isInstanceOf(ResponseStatusException.class);
        assertThat(store.start(lease)).isTrue();
        assertThat(store.cancel(user.getId(),lease.requestId())).isEqualTo(AssistantTaskStore.Cancellation.REQUESTED);
        assertThatThrownBy(()->append(lease,false)).isInstanceOf(CancellationException.class);
        assertThat(messages()).isZero();
        store.finish(lease,"CANCELLED");
        var queued=admit("node-b");store.cancel(user.getId(),queued.requestId());assertThat(store.start(queued)).isFalse();
    }

    @Test void expiredOwnerCannotRenewPublishOrFinishTheReplacement() {
        var old=admit("node-a");assertThat(store.start(old)).isTrue();expire(old);
        assertThat(store.status(user.getId(),old.requestId()).state()).isEqualTo("INTERRUPTED");
        var replacement=admit("node-b");assertThat(store.start(replacement)).isTrue();
        assertThat(store.heartbeat("node-a",List.of(old),15)).isEmpty();
        assertThatThrownBy(()->append(old,false)).isInstanceOf(CancellationException.class);
        store.finish(old,"SUCCEEDED");
        assertThat(store.status(user.getId(),replacement.requestId()).state()).isEqualTo("RUNNING");
        append(replacement,false);store.finish(replacement,"SUCCEEDED");assertThat(messages()).isEqualTo(2);
    }

    @Test void heartbeatOnlyRenewsTasksStillRegisteredOnTheOwner() {
        var tracked=admit("node-a");
        var orphan=store.admit(UUID.randomUUID().toString(),other.getId(),"node-a",30000,15);
        jdbc.update("UPDATE assistant_tasks SET lease_until=clock_timestamp()+INTERVAL '2 seconds'");
        store.heartbeat("node-a",List.of(tracked),15);
        assertThat(jdbc.queryForObject("SELECT lease_until>clock_timestamp()+INTERVAL '10 seconds' FROM assistant_tasks WHERE request_id=?",Boolean.class,tracked.requestId())).isTrue();
        assertThat(jdbc.queryForObject("SELECT lease_until<clock_timestamp()+INTERVAL '5 seconds' FROM assistant_tasks WHERE request_id=?",Boolean.class,orphan.requestId())).isTrue();
        jdbc.update("UPDATE assistant_tasks SET execution_token='replaced-token',lease_until=clock_timestamp()+INTERVAL '2 seconds' WHERE request_id=?",tracked.requestId());
        assertThat(store.heartbeat("node-a",List.of(tracked),15)).isEmpty();
        assertThat(store.start(tracked)).isFalse();
        assertThat(jdbc.queryForObject("SELECT lease_until<clock_timestamp()+INTERVAL '5 seconds' FROM assistant_tasks WHERE request_id=?",Boolean.class,tracked.requestId())).isTrue();
    }

    @Test void rollbackDoesNotLeaveAPublishedFlagOrCompletedHistory() {
        var lease=admit("node-a");store.start(lease);
        assertThatThrownBy(()->append(lease,true)).isInstanceOf(IllegalStateException.class);
        assertThat(messages()).isZero();
        assertThat(store.cancel(user.getId(),lease.requestId())).isEqualTo(AssistantTaskStore.Cancellation.REQUESTED);
    }

    @Test void frozenPublisherReleasesItsFirstUserLockWithoutClientCleanup() throws Exception {
        var lease=admit("node-a");store.start(lease);
        var conversation=conversations.resolve(user,null,"问题");
        var locked=new CountDownLatch(1);var resume=new CountDownLatch(1);
        var guardedUsers=org.mockito.Mockito.mock(UserAccountRepository.class);
        org.mockito.Mockito.when(guardedUsers.findByIdForUpdate(user.getId())).thenAnswer(invocation->{
            assertThat(jdbc.queryForObject("SHOW idle_in_transaction_session_timeout",String.class)).isEqualTo("3s");
            var owner=users.findByIdForUpdate(user.getId());locked.countDown();
            if(!resume.await(8,TimeUnit.SECONDS))throw new IllegalStateException("test timeout");
            return owner;
        });
        var publisher=new AssistantConversationService(conversationRepository,messageRepository,guardedUsers);
        var executor=Executors.newSingleThreadExecutor();
        try {
            var publication=executor.submit(()->new TransactionTemplate(transactions).execute(status->{
                try(var execution=new AssistantExecutionScope(store,lease)) {
                    return publisher.appendTurn(conversation,"问题","回答",lease.requestId(),"COMPLETE");
                }
            }));
            assertThat(locked.await(5,TimeUnit.SECONDS)).isTrue();
            var contender=new TransactionTemplate(transactions);contender.setTimeout(6);
            // The publisher remains frozen; PostgreSQL alone must release its lock.
            Long acquired=contender.execute(status->jdbc.queryForObject("SELECT id FROM users WHERE id=? FOR UPDATE",Long.class,user.getId()));
            assertThat(acquired).isEqualTo(user.getId());
            resume.countDown();
            assertThatThrownBy(()->publication.get(5,TimeUnit.SECONDS)).isInstanceOf(ExecutionException.class);
            assertThat(messages()).isZero();
        } finally {resume.countDown();executor.shutdownNow();}
    }

    @Test void cancellationWaitsForAnInProgressPublicationAndObservesItsCommittedResult() throws Exception {
        var lease=admit("node-a");store.start(lease);
        var conversation=conversations.resolve(user,null,"问题");
        var written=new CountDownLatch(1);var release=new CountDownLatch(1);
        var executor=Executors.newFixedThreadPool(2);
        try {
            var publishing=executor.submit(()->new TransactionTemplate(transactions).execute(status->{
                try(var execution=new AssistantExecutionScope(store,lease)) {
                    conversations.appendTurn(conversation,"问题","回答",lease.requestId(),"COMPLETE");written.countDown();
                    try{if(!release.await(10,TimeUnit.SECONDS))throw new IllegalStateException("test timeout");}
                    catch(InterruptedException interrupted){throw new IllegalStateException(interrupted);}
                }
                return null;
            }));
            assertThat(written.await(5,TimeUnit.SECONDS)).isTrue();
            var cancelling=executor.submit(()->store.cancel(user.getId(),lease.requestId()));
            assertThatThrownBy(()->cancelling.get(100,TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
            release.countDown();publishing.get(5,TimeUnit.SECONDS);
            assertThat(cancelling.get(5,TimeUnit.SECONDS)).isEqualTo(AssistantTaskStore.Cancellation.COMPLETED);
            assertThat(messages()).isEqualTo(2);
        }finally{release.countDown();executor.shutdownNow();}
    }

    @Test void executionGuardFailureAtCommitRollsBackHistoryAndPublicationTogether() {
        var lease=admit("node-a");store.start(lease);var conversation=conversations.resolve(user,null,"问题");
        var expired=new AtomicBoolean();
        assertThatThrownBy(()->{
            try(var deadline=AiCallContext.restore(new AiCallContext.Context(user.getId(),System.nanoTime()+TimeUnit.SECONDS.toNanos(30),()->{
                if(expired.get())throw new RemoteCallGuard.RemoteCallDeadlineException("Execution expired before commit");
            }));var execution=new AssistantExecutionScope(store,lease)) {
                new TransactionTemplate(transactions).execute(status->{
                    conversations.appendTurn(conversation,"问题","回答",lease.requestId(),"COMPLETE");
                    expired.set(true);
                    return null;
                });
            }
        }).isInstanceOf(RemoteCallGuard.RemoteCallDeadlineException.class);
        assertThat(expired).isTrue();
        assertThat(messages()).isZero();
        assertThat(jdbc.queryForObject("SELECT published_at IS NULL FROM assistant_tasks WHERE request_id=?",Boolean.class,lease.requestId())).isTrue();
    }

    @Test void aCommittedAnswerWinsALaterCancellationButRetainsAdmissionUntilCleanup() {
        var lease=admit("node-a");store.start(lease);append(lease,false);
        assertThat(store.cancel(user.getId(),lease.requestId())).isEqualTo(AssistantTaskStore.Cancellation.COMPLETED);
        assertThatThrownBy(()->admit("node-b")).isInstanceOf(ResponseStatusException.class);
        expire(lease);store.reap();
        assertThat(store.status(user.getId(),lease.requestId()).state()).isEqualTo("SUCCEEDED");
        assertThat(admit("node-b")).isNotNull();assertThat(messages()).isEqualTo(2);
    }

    @Test void deadlineExpiredBeforePublicationCannotCommitACompleteTurn() {
        var lease=admit("node-a");store.start(lease);
        jdbc.update("UPDATE assistant_tasks SET deadline_at=clock_timestamp()-INTERVAL '1 second'");
        assertThatThrownBy(()->append(lease,false)).isInstanceOf(CancellationException.class);assertThat(messages()).isZero();
        expire(lease);store.reap();assertThat(store.status(user.getId(),lease.requestId()).state()).isEqualTo("TIMED_OUT");
    }

    @Test void remoteCancellationRetainsAdmissionUntilTheOldWorkerFinishesCleanup() throws Exception {
        try(var a=new ManagedNode("node-a");var b=new ManagedNode("node-b")) {
            var entered=new CountDownLatch(1);var cleanup=new CountDownLatch(1);var finished=new CountDownLatch(1);
            var delivery=new AssistantStreamDelivery(null);
            try {
                a.manager.submit(user.getId(),delivery,()->{
                    entered.countDown();try{new CountDownLatch(1).await();}catch(InterruptedException interrupted){
                        try{cleanup.await(10,TimeUnit.SECONDS);}catch(InterruptedException ignored){}
                    }
                },state->{},finished::countDown);
                assertThat(entered.await(5,TimeUnit.SECONDS)).isTrue();b.manager.cancel(user.getId(),delivery.requestId);
                assertThatThrownBy(()->b.manager.call(user.getId(),d->"new")).isInstanceOf(ResponseStatusException.class);
                cleanup.countDown();assertThat(finished.await(10,TimeUnit.SECONDS)).isTrue();
                assertThat(store.status(user.getId(),delivery.requestId).state()).isEqualTo("CANCELLED");
                assertThat(b.manager.<String>call(user.getId(),d->"new")).isEqualTo("new");
            }finally{cleanup.countDown();}
        }
    }

    @Test void remoteQueuedCancellationFreesCapacityAndNeverExecutesTheCancelledWork() throws Exception {
        try(var a=new ManagedNode("node-a");var b=new ManagedNode("node-b")) {
            var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var done=new CountDownLatch(1);var ran=new AtomicBoolean();
            var queued=new AssistantStreamDelivery(null);
            try {
                a.manager.submit(user.getId(),new AssistantStreamDelivery(null),()->{
                    entered.countDown();try{release.await(10,TimeUnit.SECONDS);}catch(InterruptedException interrupted){Thread.currentThread().interrupt();}
                },state->{},()->{});
                assertThat(entered.await(5,TimeUnit.SECONDS)).isTrue();
                a.manager.submit(other.getId(),queued,()->ran.set(true),state->{},done::countDown);
                b.manager.cancel(other.getId(),queued.requestId);
                assertThat(done.await(10,TimeUnit.SECONDS)).isTrue();assertThat(ran).isFalse();assertThat(a.manager.queuedTasks()).isZero();
            }finally{release.countDown();}
        }
    }

    @Test void terminalRetentionIsBoundedAndNeverDeletesActiveTasks() {
        var active=admit("node-a");
        jdbc.update("UPDATE assistant_tasks SET updated_at=clock_timestamp()-INTERVAL '40 days'");
        var old=store.admit(UUID.randomUUID().toString(),other.getId(),"node-b",30000,15);store.finish(old,"FAILED");
        jdbc.update("UPDATE assistant_tasks SET updated_at=clock_timestamp()-INTERVAL '40 days' WHERE request_id=?",old.requestId());
        store.reap();assertThat(store.status(user.getId(),active.requestId()).state()).isEqualTo("QUEUED");
        assertThatThrownBy(()->store.status(other.getId(),old.requestId())).isInstanceOf(ResponseStatusException.class);
    }

    class ManagedNode implements AutoCloseable {
        final AssistantTaskManager manager;
        ManagedNode(String id){manager=new AssistantTaskManager(1,2,Duration.ofSeconds(20),store,id,6);}
        @Override public void close(){manager.shutdown();}
    }
}
