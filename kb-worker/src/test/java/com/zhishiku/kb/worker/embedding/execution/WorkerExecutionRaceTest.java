package com.zhishiku.kb.worker.embedding.execution;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.time.Duration;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

class WorkerExecutionRaceTest {
    private static final long DEADLINE = TimeUnit.SECONDS.toNanos(1);

    @Test
    void simultaneousSuccessDeadlineAndLeaseLossHaveOneOutcomeWithoutAssumingAWinner() throws Exception {
        try (var contenders = Executors.newFixedThreadPool(2)) {
            // 重复无偏序竞争，只断言合法不变量，不要求调度器随机产生某个胜者。
            for (int iteration = 0; iteration < 32; iteration++) {
                race(null, contenders);
            }
        }
    }

    @ParameterizedTest
    @EnumSource(Winner.class)
    void eachWinnerKeepsOneSettlementAndOneRelease(Winner winner) throws Exception {
        try (var contenders = Executors.newFixedThreadPool(2)) {
            race(winner, contenders);
        }
    }

    private void race(Winner preferred, ExecutorService contenders) throws Exception {
        var clock = new AtomicLong();
        var timers = new CopyOnWriteArrayList<Runnable>();
        var start = new CyclicBarrier(3);
        var prepared = new CountDownLatch(1);
        var chosen = new CountDownLatch(1);
        var settled = new CountDownLatch(1);
        var settleFinish = new CountDownLatch(1);
        var workReturned = new CountDownLatch(1);
        var closeStarted = new CountDownLatch(1);
        var closeFinish = new CountDownLatch(1);
        var claims = new AtomicInteger();
        var commits = new AtomicInteger();
        var retries = new AtomicInteger();
        var abandoned = new AtomicInteger();
        var closes = new AtomicInteger();
        var releases = new AtomicInteger();
        var registry = new SimpleMeterRegistry();
        try (var lanes = new WorkerExecutions(WorkerEmbeddingSettings.defaults(), clock::get, (action, nanos) -> {
            timers.add(action);
            // 保留已取消的回调，以模拟从调度器取出后才迟到执行。
            return () -> { };
        })) {
            new WorkerEmbeddingMetrics(lanes, registry);
            var ticket = lanes.submit(WorkerExecution.Purpose.PARSE, execution -> {
                claims.incrementAndGet();
                execution.begin(Duration.ofNanos(DEADLINE));
                try (var resource = execution.onCancel(() -> {
                    closes.incrementAndGet();
                    closeStarted.countDown();
                    await(closeFinish);
                })) {
                    prepared.countDown();
                    await(start);
                    waitTurn(preferred, Winner.SUCCESS, chosen);
                    try {
                        execution.succeed();
                        commits.incrementAndGet();
                    } catch (WorkerEmbeddingFailure failure) {
                        if (failure.reason() == WorkerEmbeddingFailure.Reason.TIMEOUT) {
                            retries.incrementAndGet();
                        } else {
                            assertThat(failure.reason()).isEqualTo(WorkerEmbeddingFailure.Reason.LEASE_LOST);
                            abandoned.incrementAndGet();
                        }
                    } finally {
                        chosen.countDown();
                        settled.countDown();
                    }
                    // 内存提交／恢复替身的收尾仍属于本次业务工作。
                    await(settleFinish);
                } finally {
                    workReturned.countDown();
                }
            });
            await(prepared);
            var releaseObserved = ticket.exited().thenRun(releases::incrementAndGet);
            Runnable oldTimer = timers.getFirst();
            var timeout = contenders.submit(() -> {
                await(start);
                waitTurn(preferred, Winner.TIMEOUT, chosen);
                clock.set(DEADLINE);
                oldTimer.run();
                if (preferred == Winner.TIMEOUT) {
                    chosen.countDown();
                }
            });
            var leaseLoss = contenders.submit(() -> {
                await(start);
                waitTurn(preferred, Winner.LEASE_LOST, chosen);
                ticket.execution().stop(WorkerEmbeddingFailure.Reason.LEASE_LOST);
                if (preferred == Winner.LEASE_LOST) {
                    chosen.countDown();
                }
            });
            timeout.get(5, TimeUnit.SECONDS);
            leaseLoss.get(5, TimeUnit.SECONDS);
            await(settled);

            Winner actual = ticket.execution().succeeded() ? Winner.SUCCESS
                    : Winner.valueOf(ticket.execution().reason().name());
            if (preferred != null) {
                assertThat(actual).isEqualTo(preferred);
            }
            assertThat(commits.get()).isEqualTo(actual == Winner.SUCCESS ? 1 : 0);
            assertThat(retries.get()).isEqualTo(actual == Winner.TIMEOUT ? 1 : 0);
            assertThat(abandoned.get()).isEqualTo(actual == Winner.LEASE_LOST ? 1 : 0);
            assertThat(commits.get() + retries.get() + abandoned.get()).isOne();
            assertThat(stops(registry)).isEqualTo(actual == Winner.SUCCESS ? 0 : 1);
            assertThat(lanes.occupied(WorkerExecutions.Channel.BUSINESS)).isOne();
            assertThat(ticket.exited()).isNotDone();
            assertThat(lanes.submit(WorkerExecution.Purpose.REBUILD, ignored -> claims.incrementAndGet())).isNull();
            assertThat(claims.get()).isOne();

            // 提交／恢复尚未返回时再次触发旧截止和其他停止原因，选定结果保持不变。
            clock.incrementAndGet();
            oldTimer.run();
            assertThat(ticket.execution().stop(WorkerEmbeddingFailure.Reason.SHUTDOWN)).isFalse();
            if (actual != Winner.SUCCESS) {
                await(closeStarted);
            }
            settleFinish.countDown();
            await(workReturned);
            if (actual != Winner.SUCCESS) {
                assertThat(ticket.exited()).isNotDone();
                assertThat(lanes.occupied(WorkerExecutions.Channel.BUSINESS)).isOne();
            }
            closeFinish.countDown();
            releaseObserved.get(5, TimeUnit.SECONDS);
            assertThat(releases.get()).isOne();
            assertThat(closes.get()).isEqualTo(actual == Winner.SUCCESS ? 0 : 1);
            assertThat(stops(registry)).isEqualTo(actual == Winner.SUCCESS ? 0 : 1);
            assertThat(lanes.occupied(WorkerExecutions.Channel.BUSINESS)).isZero();

            var next = submit(lanes, execution -> {
                claims.incrementAndGet();
                execution.begin(Duration.ofNanos(DEADLINE));
                oldTimer.run();
                execution.check();
                execution.succeed();
            });
            next.exited().get(5, TimeUnit.SECONDS);
            assertThat(next.execution().succeeded()).isTrue();
            assertThat(claims.get()).isEqualTo(2);
            assertThat(releases.get()).isOne();
            assertThat(stops(registry)).isEqualTo(actual == Winner.SUCCESS ? 0 : 1);
        } finally {
            chosen.countDown();
            settleFinish.countDown();
            closeFinish.countDown();
            registry.close();
        }
    }

    private static double stops(SimpleMeterRegistry registry) {
        return registry.get("kb.worker.embedding.execution.stops").tag("purpose", "PARSE")
                .counters().stream().mapToDouble(Counter::count).sum();
    }

    private static void waitTurn(Winner preferred, Winner contender, CountDownLatch chosen) {
        if (preferred != null && preferred != contender) {
            await(chosen);
        }
    }

    private static WorkerExecutions.Ticket submit(WorkerExecutions lanes, Consumer<WorkerExecution> work) {
        long started = System.nanoTime();
        do {
            var ticket = lanes.submit(WorkerExecution.Purpose.PARSE, work);
            if (ticket != null) {
                return ticket;
            }
            Thread.yield();
        } while (System.nanoTime() - started < TimeUnit.SECONDS.toNanos(5));
        throw new AssertionError("execution handoff timed out");
    }

    private static void await(CountDownLatch latch) {
        WorkerExecutionsTest.await(latch);
    }

    private static void await(CyclicBarrier barrier) {
        try {
            barrier.await(5, TimeUnit.SECONDS);
        } catch (Exception failure) {
            throw new AssertionError("race barrier failed", failure);
        }
    }

    private enum Winner { SUCCESS, TIMEOUT, LEASE_LOST }
}
