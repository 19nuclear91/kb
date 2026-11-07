package com.zhishiku.kb.worker.embedding.execution;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WorkerExecutionTest {
    private final AtomicLong clock = new AtomicLong();
    private final ArrayList<Runnable> timers = new ArrayList<>();
    private final WorkerExecution execution = new WorkerExecution(WorkerExecution.Purpose.PARSE, 1024,
            clock::get, (action, nanos) -> {
                timers.add(action);
                return () -> { };
            }, Runnable::run);

    @Test
    void childBudgetSharesParentAndDoesNotRenewOnNextBatch() {
        execution.begin(Duration.ofNanos(100));
        clock.set(70);
        try (var ignored = execution.limitTo(50)) {
            assertThat(execution.remainingNanos()).isEqualTo(30);
        }
        clock.set(95);
        try (var ignored = execution.limitTo(50)) {
            assertThat(execution.remainingNanos()).isEqualTo(5);
            clock.set(100);
            assertThatThrownBy(execution::check).isInstanceOf(WorkerEmbeddingFailure.class);
        }
        assertThat(execution.reason()).isEqualTo(WorkerEmbeddingFailure.Reason.TIMEOUT);
    }

    @Test
    void staleChildTimerCannotCancelNextBatchOrCompletedGeneration() {
        execution.begin(Duration.ofNanos(100));
        execution.limitTo(10).close();
        Runnable oldChild = timers.get(1);
        clock.set(20);
        try (var ignored = execution.limitTo(50)) {
            oldChild.run();
            execution.check();
        }
        execution.succeed();
        clock.set(200);
        timers.forEach(Runnable::run);
        assertThat(execution.succeeded()).isTrue();
        assertThat(execution.stopped()).isFalse();
    }

    @Test
    void cancelResourcesRunOnceAndLateRegistrationReceivesStop() {
        execution.begin(Duration.ofNanos(100));
        var count = new AtomicInteger();
        var removed = execution.onCancel(() -> count.addAndGet(100));
        removed.close();
        execution.onCancel(count::incrementAndGet);
        assertThat(execution.stop(WorkerEmbeddingFailure.Reason.LEASE_UNKNOWN)).isTrue();
        assertThat(execution.stop(WorkerEmbeddingFailure.Reason.TIMEOUT)).isFalse();
        execution.onCancel(count::incrementAndGet).close();
        assertThat(count.get()).isEqualTo(2);
        assertThat(execution.reason()).isEqualTo(WorkerEmbeddingFailure.Reason.LEASE_UNKNOWN);
        assertThatThrownBy(execution::succeed).isInstanceOf(WorkerEmbeddingFailure.class);
    }

    @Test
    void successAtDeadlineIsRejectedWithoutTimerCallback() {
        execution.begin(Duration.ofNanos(100));
        clock.set(100);
        assertThatThrownBy(execution::succeed).isInstanceOf(WorkerEmbeddingFailure.class);
        assertThat(execution.succeeded()).isFalse();
    }

    @Test
    void parentTimerStopsSilentWorkAndTimeoutIsNotLeaseLoss() {
        execution.begin(Duration.ofNanos(100));
        clock.set(100);
        timers.getFirst().run();
        assertThat(execution.reason()).isEqualTo(WorkerEmbeddingFailure.Reason.TIMEOUT);
        assertThat(new WorkerEmbeddingFailure(execution.reason()).controlStop()).isFalse();
    }
}
