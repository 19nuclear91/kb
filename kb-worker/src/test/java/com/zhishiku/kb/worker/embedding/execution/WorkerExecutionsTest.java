package com.zhishiku.kb.worker.embedding.execution;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class WorkerExecutionsTest {
    @Test
    void sharedBusinessSlotRejectsBeforeClaimWhileProbeCanRun() throws Exception {
        var finish = new CountDownLatch(1);
        var started = new CountDownLatch(1);
        var claims = new AtomicInteger();
        try (var lanes = new WorkerExecutions(WorkerEmbeddingSettings.defaults())) {
            var business = lanes.submit(WorkerExecution.Purpose.PARSE, execution -> {
                claims.incrementAndGet();
                execution.begin(Duration.ofMinutes(1));
                started.countDown();
                await(finish);
                execution.succeed();
            });
            assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(lanes.submit(WorkerExecution.Purpose.REBUILD, execution -> claims.incrementAndGet())).isNull();
            assertThat(claims.get()).isOne();
            var probe = lanes.submit(WorkerExecution.Purpose.PROBE, execution -> {
                execution.begin(Duration.ofMinutes(1));
                execution.succeed();
            });
            assertThat(probe).isNotNull();
            probe.exited().get(5, TimeUnit.SECONDS);
            assertThat(lanes.occupied(WorkerExecutions.Channel.BUSINESS)).isOne();
            finish.countDown();
            business.exited().get(5, TimeUnit.SECONDS);
            assertThat(lanes.occupied(WorkerExecutions.Channel.BUSINESS)).isZero();
        } finally {
            finish.countDown();
        }
    }

    @Test
    void emptyClaimAndClaimFailureReleaseWithoutStartingGeneration() throws Exception {
        try (var lanes = new WorkerExecutions(WorkerEmbeddingSettings.defaults())) {
            var empty = lanes.submit(WorkerExecution.Purpose.PARSE, execution -> { });
            empty.exited().get(5, TimeUnit.SECONDS);
            assertThat(lanes.occupied(WorkerExecutions.Channel.BUSINESS)).isZero();
        }
        try (var lanes = new WorkerExecutions(WorkerEmbeddingSettings.defaults())) {
            var failed = lanes.submit(WorkerExecution.Purpose.REBUILD, execution -> {
                throw new IllegalStateException("database unavailable");
            });
            failed.exited().get(5, TimeUnit.SECONDS);
            assertThat(lanes.occupied(WorkerExecutions.Channel.BUSINESS)).isZero();
            lanes.close();
            assertThat(lanes.submit(WorkerExecution.Purpose.PARSE, execution -> {
                throw new AssertionError("claim after close");
            })).isNull();
        }
    }

    static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new AssertionError("barrier timed out");
            }
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new AssertionError(error);
        }
    }
}
