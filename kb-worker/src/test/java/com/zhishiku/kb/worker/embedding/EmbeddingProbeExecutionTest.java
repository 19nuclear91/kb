package com.zhishiku.kb.worker.embedding;

import com.zhishiku.kb.common.embedding.EmbeddingClientRegistry;
import com.zhishiku.kb.common.embedding.EmbeddingConnection;
import com.zhishiku.kb.common.embedding.EmbeddingProbe;
import com.zhishiku.kb.common.embedding.EmbeddingProfile;
import com.zhishiku.kb.common.embedding.EmbeddingProfileDefinition;
import com.zhishiku.kb.common.embedding.EmbeddingReadiness;
import com.zhishiku.kb.common.embedding.EmbeddingResult;
import com.zhishiku.kb.common.embedding.EmbeddingRole;
import com.zhishiku.kb.common.embedding.ProfileEmbeddingClient;
import com.zhishiku.kb.worker.embedding.execution.WorkerEmbeddingFailure;
import com.zhishiku.kb.worker.embedding.execution.WorkerEmbeddingSettings;
import com.zhishiku.kb.worker.embedding.execution.WorkerExecution;
import com.zhishiku.kb.worker.embedding.execution.WorkerExecutions;
import com.zhishiku.kb.worker.embedding.mapper.EmbeddingReadinessMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EmbeddingProbeExecutionTest {
    private final WorkerEmbeddingSettings settings = WorkerEmbeddingSettings.defaults();
    private final EmbeddingReadinessMapper mapper = mock(EmbeddingReadinessMapper.class);

    @Test
    void successAtDeadlineStoresOnlyFailureEvenWithoutTimerCallback() {
        var clock = new AtomicLong();
        var execution = new WorkerExecution(WorkerExecution.Purpose.PROBE, 4194304, clock::get,
                (action, nanos) -> () -> { }, Runnable::run);
        var client = client("a");
        when(client.embed(anyList(), any())).thenAnswer(call -> {
            clock.set(Duration.ofSeconds(10).toNanos());
            return result(client);
        });
        when(mapper.save(any())).thenReturn(1);
        try (var lanes = new WorkerExecutions(settings)) {
            scheduler(lanes, client).probe(client, execution);
            var row = ArgumentCaptor.forClass(EmbeddingReadiness.class);
            verify(mapper).save(row.capture());
            assertThat(row.getValue().ready()).isFalse();
            assertThat(row.getValue().fingerprint()).isEqualTo(client.profile().fingerprint());
            assertThat(execution.reason()).isEqualTo(WorkerEmbeddingFailure.Reason.TIMEOUT);
        }
    }

    @Test
    void persistenceFailureMakesOneAttemptAndLeavesOldRecordToExpire() throws Exception {
        var client = client("a");
        when(client.embed(anyList(), any())).thenAnswer(call -> result(client));
        when(mapper.save(any())).thenThrow(new IllegalStateException("database unavailable"));
        try (var lanes = new WorkerExecutions(settings)) {
            var scheduler = scheduler(lanes, client);
            var ticket = scheduler.poll();
            ticket.exited().get(5, TimeUnit.SECONDS);
            verify(mapper).save(any());
            verify(mapper, never()).deleteExpired();
            assertThat(scheduler.poll()).isNull();
            assertThat(lanes.occupied(WorkerExecutions.Channel.PROBE)).isZero();
        }
    }

    @Test
    void nextProfileWaitsForActualExitAndReceivesFreshBudgetWhileBusinessStaysBusy() throws Exception {
        var businessFinish = new CountDownLatch(1);
        var workFinish = new CountDownLatch(1);
        var closeFinish = new CountDownLatch(1);
        var started = new CountDownLatch(1);
        var closeStarted = new CountDownLatch(1);
        var saved = new CountDownLatch(1);
        var first = client("a");
        var second = client("b");
        when(first.embed(anyList(), any())).thenAnswer(call -> {
            var execution = call.getArgument(1, WorkerExecution.class);
            try (var resource = execution.onCancel(() -> {
                closeStarted.countDown();
                await(closeFinish);
            })) {
                started.countDown();
                await(workFinish);
                return result(first);
            }
        });
        when(second.embed(anyList(), any())).thenAnswer(call -> {
            var execution = call.getArgument(1, WorkerExecution.class);
            assertThat(execution.purpose()).isEqualTo(WorkerExecution.Purpose.PROBE);
            assertThat(execution.remainingNanos()).isBetween(TimeUnit.SECONDS.toNanos(9), TimeUnit.SECONDS.toNanos(10));
            assertThat(call.getArgument(0, List.class)).containsExactly(EmbeddingProbe.SYNTHETIC_INPUT);
            return result(second);
        });
        when(mapper.save(any())).thenAnswer(call -> {
            saved.countDown();
            return 1;
        });
        try (var lanes = new WorkerExecutions(settings)) {
            var business = lanes.submit(WorkerExecution.Purpose.PARSE, execution -> await(businessFinish));
            var scheduler = scheduler(lanes, first, second);
            var a = scheduler.poll();
            assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
            a.execution().stop(WorkerEmbeddingFailure.Reason.TIMEOUT);
            assertThat(closeStarted.await(5, TimeUnit.SECONDS)).isTrue();
            workFinish.countDown();
            assertThat(saved.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(a.exited()).isNotDone();
            assertThat(scheduler.poll()).isNull();
            verify(second, never()).embed(anyList(), any());
            assertThat(lanes.occupied(WorkerExecutions.Channel.BUSINESS)).isOne();
            assertThat(lanes.occupied(WorkerExecutions.Channel.PROBE)).isOne();
            closeFinish.countDown();
            a.exited().get(5, TimeUnit.SECONDS);
            submit(scheduler).exited().get(5, TimeUnit.SECONDS);
            var rows = ArgumentCaptor.forClass(EmbeddingReadiness.class);
            verify(mapper, times(2)).save(rows.capture());
            assertThat(rows.getAllValues()).extracting(EmbeddingReadiness::ready).containsExactly(false, true);
            assertThat(rows.getAllValues()).extracting(EmbeddingReadiness::instanceId).containsOnly(rows.getValue().instanceId());
            assertThat(scheduler.poll()).isNull();
            businessFinish.countDown();
            business.exited().get(5, TimeUnit.SECONDS);
        } finally {
            businessFinish.countDown();
            workFinish.countDown();
            closeFinish.countDown();
        }
    }

    @Test
    void closedSchedulerDoesNotSendNextProfile() throws Exception {
        var first = client("a");
        var second = client("b");
        when(first.embed(anyList(), any())).thenAnswer(call -> result(first));
        when(mapper.save(any())).thenReturn(1);
        try (var lanes = new WorkerExecutions(settings)) {
            var scheduler = scheduler(lanes, first, second);
            scheduler.poll().exited().get(5, TimeUnit.SECONDS);
            scheduler.close();
            assertThat(scheduler.poll()).isNull();
            verify(second, never()).embed(anyList(), any());
        }
    }

    static WorkerExecutions.Ticket submit(EmbeddingReadinessScheduler scheduler) {
        long start = System.nanoTime();
        do {
            var ticket = scheduler.poll();
            if (ticket != null) {
                return ticket;
            }
            Thread.yield();
        } while (System.nanoTime() - start < TimeUnit.SECONDS.toNanos(5));
        throw new AssertionError("probe handoff timed out");
    }

    private EmbeddingReadinessScheduler scheduler(WorkerExecutions lanes, ProfileEmbeddingClient... clients) {
        var definitions = List.of(clients).stream().map(client -> new EmbeddingProfileDefinition(client.profile(),
                new EmbeddingConnection(null, null, 30, 16))).toList();
        var registry = new EmbeddingClientRegistry(definitions, EmbeddingRole.DOCUMENT,
                definition -> List.of(clients).stream().filter(client -> client.profile().equals(definition.profile()))
                        .findFirst().orElseThrow());
        return new EmbeddingReadinessScheduler(registry, mapper, 30000, 90000, lanes, settings, Clock.systemUTC());
    }

    private static ProfileEmbeddingClient client(String id) {
        var client = mock(ProfileEmbeddingClient.class);
        when(client.profile()).thenReturn(new EmbeddingProfile(id, "local", "kb/local", "local-hash-v1", "1", 1024,
                "cosine", "identity-v1", "identity-v1", "query", "document", "local-hash-v1"));
        when(client.role()).thenReturn(EmbeddingRole.DOCUMENT);
        return client;
    }

    private static EmbeddingResult result(ProfileEmbeddingClient client) {
        float[] vector = new float[1024];
        vector[0] = 1;
        return new EmbeddingResult(client.profile(), client.role(), List.of(vector));
    }

    private static void await(CountDownLatch latch) {
        long start = System.nanoTime();
        while (System.nanoTime() - start < TimeUnit.SECONDS.toNanos(5)) {
            try {
                if (latch.await(5, TimeUnit.SECONDS)) {
                    return;
                }
            } catch (InterruptedException ignored) {
                // 受控替身保持实际占用直到屏障释放。
            }
        }
        throw new AssertionError("probe barrier timed out");
    }
}
