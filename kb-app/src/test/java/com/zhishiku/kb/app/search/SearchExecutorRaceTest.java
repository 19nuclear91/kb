package com.zhishiku.kb.app.search;

import com.zhishiku.kb.app.search.config.SearchExecutionProperties;
import com.zhishiku.kb.app.search.execution.SearchAdmission;
import com.zhishiku.kb.app.search.execution.SearchBudget;
import com.zhishiku.kb.app.search.execution.SearchContext;
import com.zhishiku.kb.app.search.execution.SearchExecutor;
import com.zhishiku.kb.app.search.execution.SearchStageFailure;
import com.zhishiku.kb.app.search.model.SearchFailure;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SearchExecutorRaceTest {
    @ParameterizedTest
    @EnumSource(value = SearchFailure.Kind.class, names = {"TIMEOUT", "CANCELLED"})
    void firstFailureWinsWhileCompletionAndAnotherSignalRace(SearchFailure.Kind first) throws Exception {
        try (var fixture = new Fixture()) {
            var entered = new CountDownLatch(1);
            Future<Object> response = fixture.start(state -> fixture.executor.required(state, SearchBudget.Stage.RETRIEVAL, () -> {
                try (var registration = SearchContext.current().onCancel(fixture::cancelResource)) {
                    entered.countDown();
                    await(fixture.releaseWork);
                    return "late rows";
                }
            }));
            ready(entered);
            Runnable deadline = fixture.deadline();
            if (first == SearchFailure.Kind.TIMEOUT) {
                fixture.clock.set(3_000_000_000L);
                deadline.run();
            } else {
                fixture.state.get().cancel(new SearchFailure(first));
            }
            ready(fixture.cancellationEntered);

            fixture.race(fixture.releaseWork::countDown, () -> {
                fixture.clock.set(3_000_000_000L);
                if (first == SearchFailure.Kind.TIMEOUT) {
                    fixture.state.get().cancel(new SearchFailure(SearchFailure.Kind.CANCELLED));
                } else {
                    deadline.run();
                }
            });
            Object terminal = response.get(3, TimeUnit.SECONDS);
            assertThat(terminal).isInstanceOfSatisfying(SearchFailure.class, error -> assertThat(error.kind()).isEqualTo(first));
            fixture.workers.submit(() -> { }).get(3, TimeUnit.SECONDS);
            fixture.assertStillOccupied();
            fixture.state.get().cancel(new SearchFailure(SearchFailure.Kind.CANCELLED));
            deadline.run();
            assertThat(response.get()).isSameAs(terminal);
            fixture.releaseAndAssertSingleExit();
        }
    }

    @Test
    void completedStageIgnoresItsAlreadyScheduledWatchdogDuringResponsePreparation() throws Exception {
        try (var fixture = new Fixture()) {
            var preparing = new CountDownLatch(1);
            var response = fixture.start(state -> {
                String rows = fixture.executor.required(state, SearchBudget.Stage.RETRIEVAL, () -> "rows");
                return fixture.executor.required(state, SearchBudget.Stage.RESPONSE, () -> {
                    preparing.countDown();
                    await(fixture.releaseWork);
                    return rows;
                });
            });
            ready(preparing);
            Runnable oldDeadline = fixture.deadline();
            fixture.clock.set(3_000_000_000L);
            // 模拟定时回调已取出，cancel(false) 不能撤回，回调延后到下一阶段才执行。
            oldDeadline.run();
            fixture.releaseWork.countDown();
            assertThat(response.get(3, TimeUnit.SECONDS)).isEqualTo("rows");
            fixture.state.get().exited().get(3, TimeUnit.SECONDS);
            verify(fixture.ticket.get()).close();
            assertThat(fixture.admission.inflight()).isZero();
        }
    }

    @Test
    void successfulFallbackStaysCommittedWhileLateWorkAndEndSignalsRace() throws Exception {
        try (var fixture = new Fixture()) {
            var entered = new CountDownLatch(1);
            var response = fixture.start(state -> {
                try {
                    return fixture.executor.optional(state, SearchBudget.Stage.EMBEDDING, () -> {
                        var context = SearchContext.current();
                        try (var registration = context.onCancel(fixture::cancelResource);
                             var timeout = context.limitTo(context.window().remainingNanos())) {
                            entered.countDown();
                            await(fixture.releaseWork);
                            return "late vector";
                        }
                    });
                } catch (SearchStageFailure failure) {
                    assertThat(failure.reason()).isEqualTo(SearchStageFailure.Reason.TIMEOUT);
                    return "BM25";
                }
            });
            ready(entered);
            Runnable deadline = fixture.deadline();
            fixture.clock.set(2_000_000_000L);
            deadline.run();
            ready(fixture.cancellationEntered);
            assertThat(response.get(3, TimeUnit.SECONDS)).isEqualTo("BM25");
            fixture.race(fixture.releaseWork::countDown, () -> {
                fixture.clock.set(8_000_000_000L);
                fixture.state.get().cancel(new SearchFailure(SearchFailure.Kind.CANCELLED));
                deadline.run();
            });
            fixture.external.submit(() -> { }).get(3, TimeUnit.SECONDS);
            fixture.assertStillOccupied();
            fixture.releaseAndAssertSingleExit();
            assertThat(response.get()).isEqualTo("BM25");
        }
    }

    @Test
    void completionAtTotalDeadlineIsRejectedBeforeWatchdogRuns() throws Exception {
        try (var fixture = new Fixture()) {
            var entered = new CountDownLatch(1);
            var response = fixture.start(state -> fixture.executor.required(state, SearchBudget.Stage.RESPONSE, () -> {
                entered.countDown();
                await(fixture.releaseWork);
                return "late response";
            }));
            ready(entered);
            fixture.clock.set(8_000_000_000L);
            fixture.releaseWork.countDown();
            assertThat(response.get(3, TimeUnit.SECONDS)).isInstanceOfSatisfying(SearchFailure.class,
                    error -> assertThat(error.kind()).isEqualTo(SearchFailure.Kind.TIMEOUT));
            fixture.deadline().run();
            fixture.state.get().exited().get(3, TimeUnit.SECONDS);
            verify(fixture.ticket.get()).close();
            assertThat(fixture.admission.inflight()).isZero();
        }
    }

    private static final class Fixture implements AutoCloseable {
        final AtomicLong clock = new AtomicLong();
        final SearchExecutionProperties properties = new SearchExecutionProperties(8000, 2000, 3000, 1500, 500, 1, 1, 1);
        final SearchAdmission admission = spy(new SearchAdmission(properties));
        final AtomicReference<SearchAdmission.Ticket> ticket = new AtomicReference<>();
        final AtomicReference<SearchExecutor.Execution> state = new AtomicReference<>();
        final ExecutorService workers = Executors.newSingleThreadExecutor();
        final ExecutorService external = Executors.newSingleThreadExecutor();
        final ExecutorService callers = Executors.newSingleThreadExecutor();
        final ExecutorService signals = Executors.newFixedThreadPool(2);
        final BlockingQueue<Runnable> deadlines = new LinkedBlockingQueue<>();
        final CountDownLatch releaseWork = new CountDownLatch(1);
        final CountDownLatch cancellationEntered = new CountDownLatch(1);
        final CountDownLatch releaseCancellation = new CountDownLatch(1);
        final AtomicInteger cancellations = new AtomicInteger();
        final SearchExecutor executor;

        Fixture() {
            doAnswer(call -> {
                var acquired = spy((SearchAdmission.Ticket) call.callRealMethod());
                ticket.set(acquired);
                return acquired;
            }).when(admission).acquire(anyLong());
            var timer = mock(ScheduledExecutorService.class);
            when(timer.schedule(any(Runnable.class), anyLong(), eq(TimeUnit.NANOSECONDS))).thenAnswer(call -> {
                deadlines.add(call.getArgument(0));
                return mock(ScheduledFuture.class);
            });
            executor = new SearchExecutor(properties, admission, clock::get, workers, external, timer,
                    Executors.newSingleThreadExecutor());
        }

        Future<Object> start(Function<SearchExecutor.Execution, String> action) {
            return callers.submit(() -> {
                try {
                    return executor.execute(1, execution -> {
                        state.set(execution);
                        return action.apply(execution);
                    });
                } catch (SearchFailure failure) {
                    return failure;
                }
            });
        }

        Runnable deadline() throws InterruptedException {
            var task = deadlines.poll(3, TimeUnit.SECONDS);
            assertThat(task).isNotNull();
            return task;
        }

        void cancelResource() {
            cancellations.incrementAndGet();
            cancellationEntered.countDown();
            await(releaseCancellation);
        }

        void race(Runnable first, Runnable second) throws Exception {
            var ready = new CountDownLatch(2);
            var start = new CountDownLatch(1);
            Future<?> a = signals.submit(() -> {
                ready.countDown();
                await(start);
                first.run();
            });
            Future<?> b = signals.submit(() -> {
                ready.countDown();
                await(start);
                second.run();
            });
            try {
                ready(ready);
            } finally {
                start.countDown();
            }
            a.get(3, TimeUnit.SECONDS);
            b.get(3, TimeUnit.SECONDS);
        }

        void assertStillOccupied() {
            assertThat(admission.inflight()).isOne();
            assertThat(executor.lingering()).isOne();
            assertThat(state.get().exited()).isNotDone();
            verify(ticket.get(), never()).close();
            assertThatThrownBy(() -> executor.execute(2, ignored -> "unexpected"))
                    .isInstanceOfSatisfying(SearchFailure.class, error -> assertThat(error.kind()).isEqualTo(SearchFailure.Kind.CAPACITY));
        }

        void releaseAndAssertSingleExit() throws Exception {
            releaseCancellation.countDown();
            state.get().exited().get(3, TimeUnit.SECONDS);
            state.get().cancel(new SearchFailure(SearchFailure.Kind.CANCELLED));
            verify(ticket.get()).close();
            assertThat(cancellations).hasValue(1);
            assertThat(admission.inflight()).isZero();
            assertThat(admission.trackedUsers()).isZero();
            assertThat(executor.lingering()).isZero();
        }

        @Override
        public void close() {
            releaseWork.countDown();
            releaseCancellation.countDown();
            executor.close();
            callers.shutdownNow();
            signals.shutdownNow();
        }
    }

    private static void ready(CountDownLatch latch) throws InterruptedException {
        assertThat(latch.await(3, TimeUnit.SECONDS)).isTrue();
    }

    private static void await(CountDownLatch latch) {
        while (latch.getCount() != 0) {
            try {
                if (!latch.await(5, TimeUnit.SECONDS)) {
                    throw new AssertionError("受控工作未被测试释放");
                }
            } catch (InterruptedException ignored) {
                // 受控依赖忽略中断，由测试明确允许其实际退出。
            }
        }
    }
}
