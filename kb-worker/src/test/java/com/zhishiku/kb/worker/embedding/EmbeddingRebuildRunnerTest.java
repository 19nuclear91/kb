package com.zhishiku.kb.worker.embedding;

import com.zhishiku.kb.common.embedding.EmbeddingProfile;
import com.zhishiku.kb.worker.embedding.execution.WorkerEmbeddingFailure;
import com.zhishiku.kb.worker.embedding.execution.WorkerEmbeddingSettings;
import com.zhishiku.kb.worker.embedding.execution.WorkerExecution;
import com.zhishiku.kb.worker.embedding.execution.WorkerExecutions;
import com.zhishiku.kb.worker.embedding.model.RebuildBatch;
import com.zhishiku.kb.worker.embedding.service.EmbeddingBatchProcessor;
import com.zhishiku.kb.worker.embedding.service.EmbeddingLeaseService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class EmbeddingRebuildRunnerTest {
    @Test
    void heartbeatRunsWhileModelIsBusyAndLostOwnerDoesNotClaimMoreWork() throws Exception {
        var profile = new EmbeddingProfile("p", "local", "source", "local-hash-v1", "1", 1024,
                "cosine", "identity-v1", "identity-v1", "query", "document", "local-hash-v1");
        var batch = new RebuildBatch(UUID.randomUUID(), 2, 3, UUID.randomUUID(), profile,
                List.of(new RebuildBatch.Item(1, 2, 3, "digest", 4, 1)));
        var leases = mock(EmbeddingLeaseService.class);
        var processor = mock(EmbeddingBatchProcessor.class);
        when(leases.claim(any())).thenReturn(batch);
        when(leases.renew(batch)).thenReturn(true, false);
        var started = new CountDownLatch(1);
        var finish = new CountDownLatch(1);
        doAnswer(invocation -> {
            started.countDown();
            finish.await(5, TimeUnit.SECONDS);
            return null;
        })
                .when(processor).process(eq(batch), any());
        var runner = new EmbeddingRebuildRunner(leases, processor);
        try {
            var ticket = runner.tick();
            assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
            runner.heartbeat();
            runner.heartbeat();
            runner.heartbeat();
            assertThat(runner.tick()).isNull();
            assertThat(ticket.execution().reason()).isEqualTo(WorkerEmbeddingFailure.Reason.LEASE_LOST);
            verify(leases, times(2)).renew(batch);
            verify(leases).claim(any());
            verify(processor).process(eq(batch), any());
        } finally {
            finish.countDown();
            runner.close();
        }
    }

    @Test
    void noClaimMeansNoModelInvocation() throws Exception {
        var leases = mock(EmbeddingLeaseService.class);
        var processor = mock(EmbeddingBatchProcessor.class);
        var runner = new EmbeddingRebuildRunner(leases, processor);
        try {
            runner.tick().exited().get(5, TimeUnit.SECONDS);
            verifyNoInteractions(processor);
        } finally {
            runner.close();
        }
    }

    @Test
    void parseOccupancyPreventsClaimAndEmptyBatchReleases() throws Exception {
        var finish = new CountDownLatch(1);
        var leases = mock(EmbeddingLeaseService.class);
        var processor = mock(EmbeddingBatchProcessor.class);
        try (var lanes = new WorkerExecutions(WorkerEmbeddingSettings.defaults())) {
            var parse = lanes.submit(WorkerExecution.Purpose.PARSE, execution -> await(finish));
            var runner = new EmbeddingRebuildRunner(leases, processor, lanes);
            assertThat(runner.tick()).isNull();
            verifyNoInteractions(leases);
            finish.countDown();
            parse.exited().get(5, TimeUnit.SECONDS);
            var empty = batch(List.of());
            when(leases.claim(any())).thenReturn(empty);
            submit(runner).exited().get(5, TimeUnit.SECONDS);
            verify(leases).release(empty);
            verifyNoInteractions(processor);
            assertThat(lanes.occupied(WorkerExecutions.Channel.BUSINESS)).isZero();
        } finally {
            finish.countDown();
        }
    }

    @Test
    void lateHeartbeatExceptionCannotStopNextExecution() throws Exception {
        var leases = mock(EmbeddingLeaseService.class);
        var processor = mock(EmbeddingBatchProcessor.class);
        var first = batch(List.of(new RebuildBatch.Item(1, 2, 3, "digest", 4, 1)));
        var second = batch(first.items());
        when(leases.claim(any())).thenReturn(first, second);
        var started = new CountDownLatch(1);
        var finish = new CountDownLatch(1);
        var heartbeatStarted = new CountDownLatch(1);
        var heartbeatFinish = new CountDownLatch(1);
        var secondStarted = new CountDownLatch(1);
        var secondFinish = new CountDownLatch(1);
        doAnswer(call -> {
            var execution = call.getArgument(1, WorkerExecution.class);
            execution.begin(Duration.ofMinutes(1));
            boolean initial = call.getArgument(0) == first;
            (initial ? started : secondStarted).countDown();
            await(initial ? finish : secondFinish);
            execution.succeed();
            return null;
        }).when(processor).process(any(), any());
        when(leases.renew(first)).thenAnswer(call -> {
            heartbeatStarted.countDown();
            await(heartbeatFinish);
            throw new IllegalStateException("lease result unknown");
        });
        try (var lanes = new WorkerExecutions(WorkerEmbeddingSettings.defaults());
             var thread = Executors.newSingleThreadExecutor()) {
            var runner = new EmbeddingRebuildRunner(leases, processor, lanes);
            var a = submit(runner);
            assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
            var heartbeat = thread.submit(runner::heartbeat);
            assertThat(heartbeatStarted.await(5, TimeUnit.SECONDS)).isTrue();
            finish.countDown();
            a.exited().get(5, TimeUnit.SECONDS);
            var b = submit(runner);
            assertThat(secondStarted.await(5, TimeUnit.SECONDS)).isTrue();
            heartbeatFinish.countDown();
            heartbeat.get(5, TimeUnit.SECONDS);
            b.execution().check();
            when(leases.renew(second)).thenThrow(new IllegalStateException("unknown"));
            runner.heartbeat();
            assertThat(b.execution().reason()).isEqualTo(WorkerEmbeddingFailure.Reason.LEASE_UNKNOWN);
            assertThat(b.exited()).isNotDone();
            secondFinish.countDown();
            b.exited().get(5, TimeUnit.SECONDS);
        } finally {
            finish.countDown();
            heartbeatFinish.countDown();
            secondFinish.countDown();
        }
    }

    static WorkerExecutions.Ticket submit(EmbeddingRebuildRunner runner) {
        long start = System.nanoTime();
        do {
            var ticket = runner.tick();
            if (ticket != null) {
                return ticket;
            }
            Thread.yield();
        } while (System.nanoTime() - start < TimeUnit.SECONDS.toNanos(5));
        throw new AssertionError("execution handoff timed out");
    }

    private static RebuildBatch batch(List<RebuildBatch.Item> items) {
        var profile = new EmbeddingProfile("p", "local", "source", "local-hash-v1", "1", 1024,
                "cosine", "identity-v1", "identity-v1", "query", "document", "local-hash-v1");
        return new RebuildBatch(UUID.randomUUID(), 2, 3, UUID.randomUUID(), profile, items);
    }

    private static void await(CountDownLatch latch) {
        try {
            assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
        } catch (InterruptedException error) {
            throw new AssertionError(error);
        }
    }
}
