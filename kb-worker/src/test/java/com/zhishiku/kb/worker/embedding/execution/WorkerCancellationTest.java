package com.zhishiku.kb.worker.embedding.execution;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class WorkerCancellationTest {
    @Test
    void blockedCloseAndIgnoredInterruptKeepSlotWithoutBlockingProbeDeadline() throws Exception {
        var workFinish = new CountDownLatch(1);
        var workReturned = new CountDownLatch(1);
        var closeStarted = new CountDownLatch(1);
        var closeFinish = new CountDownLatch(1);
        var probeFinish = new CountDownLatch(1);
        var probeCancelled = new CountDownLatch(1);
        var closeCount = new AtomicInteger();
        try (var lanes = new WorkerExecutions(WorkerEmbeddingSettings.defaults())) {
            var business = lanes.submit(WorkerExecution.Purpose.PARSE, execution -> {
                execution.begin(Duration.ofMillis(50));
                try (var interrupt = execution.interruptCurrentThread(); var resource = execution.onCancel(() -> {
                    closeCount.incrementAndGet();
                    closeStarted.countDown();
                    WorkerExecutionsTest.await(closeFinish);
                })) {
                    awaitIgnoringInterrupt(workFinish);
                }
                workReturned.countDown();
            });
            assertThat(closeStarted.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(business.execution().reason()).isEqualTo(WorkerEmbeddingFailure.Reason.TIMEOUT);
            business.execution().stop(WorkerEmbeddingFailure.Reason.LEASE_LOST);
            assertThat(lanes.lingering(WorkerExecutions.Channel.BUSINESS)).isOne();
            var probe = lanes.submit(WorkerExecution.Purpose.PROBE, execution -> {
                execution.begin(Duration.ofMillis(50));
                try (var resource = execution.onCancel(probeCancelled::countDown)) {
                    WorkerExecutionsTest.await(probeFinish);
                }
            });
            assertThat(probeCancelled.await(5, TimeUnit.SECONDS)).isTrue();
            workFinish.countDown();
            assertThat(workReturned.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(business.exited()).isNotDone();
            assertThat(lanes.submit(WorkerExecution.Purpose.REBUILD, ignored -> {
                throw new AssertionError("claim while previous cancellation is running");
            })).isNull();
            closeFinish.countDown();
            business.exited().get(5, TimeUnit.SECONDS);
            assertThat(closeCount.get()).isOne();
            assertThat(lanes.occupied(WorkerExecutions.Channel.BUSINESS)).isZero();
            probeFinish.countDown();
            probe.exited().get(5, TimeUnit.SECONDS);
        } finally {
            workFinish.countDown();
            closeFinish.countDown();
            probeFinish.countDown();
        }
    }

    @Test
    void shutdownRefusesNewWorkAndWaitsForActualCancellation() throws Exception {
        var ready = new CountDownLatch(1);
        var stop = new CountDownLatch(1);
        var finish = new CountDownLatch(1);
        try (var lanes = new WorkerExecutions(WorkerEmbeddingSettings.defaults())) {
            var ticket = lanes.submit(WorkerExecution.Purpose.REBUILD, execution -> {
                execution.begin(Duration.ofMinutes(1));
                try (var registration = execution.onCancel(stop::countDown)) {
                    ready.countDown();
                    WorkerExecutionsTest.await(finish);
                }
            });
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            lanes.close();
            assertThat(stop.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(ticket.exited()).isNotDone();
            assertThat(lanes.submit(WorkerExecution.Purpose.PROBE, ignored -> { })).isNull();
            finish.countDown();
            ticket.exited().get(5, TimeUnit.SECONDS);
            assertThat(lanes.occupied(WorkerExecutions.Channel.BUSINESS)).isZero();
        } finally {
            finish.countDown();
        }
    }

    private static void awaitIgnoringInterrupt(CountDownLatch latch) {
        long started = System.nanoTime();
        while (true) {
            try {
                long remaining = TimeUnit.SECONDS.toNanos(5) - (System.nanoTime() - started);
                if (remaining <= 0 || !latch.await(remaining, TimeUnit.NANOSECONDS)) {
                    throw new AssertionError("barrier timed out");
                }
                return;
            } catch (InterruptedException ignored) {
                // 模拟底层拒绝中断；仍有测试自身的固定截止。
            }
        }
    }
}
