package com.zhishiku.kb.app.search;

import com.zhishiku.kb.app.search.config.SearchExecutionProperties;
import com.zhishiku.kb.app.search.execution.SearchAdmission;
import com.zhishiku.kb.app.search.execution.SearchBudget;
import com.zhishiku.kb.app.search.execution.SearchContext;
import com.zhishiku.kb.app.search.execution.SearchExecutor;
import com.zhishiku.kb.app.search.execution.SearchStageFailure;
import com.zhishiku.kb.app.search.model.SearchFailure;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

class SearchExecutorTest {
    private static SearchExecutionProperties properties() {
        return new SearchExecutionProperties(500, 50, 100, 50, 20, 1, 1, 1);
    }

    @Test
    void timedOutWorkRetainsCapacityUntilItsActualExit() throws Exception {
        var admission = new SearchAdmission(properties());
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var execution = new AtomicReference<SearchExecutor.Execution>();
        var callers = Executors.newSingleThreadExecutor();
        try (var executor = new SearchExecutor(properties(), admission)) {
            var result = callers.submit(() -> executor.execute(1, state -> {
                execution.set(state);
                entered.countDown();
                awaitIgnoringInterrupt(release);
                return "late";
            }));
            assertThat(entered.await(3, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> result.get(3, TimeUnit.SECONDS)).hasCauseInstanceOf(SearchFailure.class);
            assertThat(admission.inflight()).isOne();
            assertThat(executor.lingering()).isOne();
            for (int attempt = 0; attempt < 10; attempt++) {
                assertThatThrownBy(() -> executor.execute(2, state -> "unexpected"))
                        .isInstanceOfSatisfying(SearchFailure.class, e -> assertThat(e.status()).isEqualTo(503));
            }
            release.countDown();
            execution.get().exited().get(3, TimeUnit.SECONDS);
            assertThat(admission.inflight()).isZero();
            assertThat(executor.lingering()).isZero();
            boolean hasContext = executor.execute(2, state -> SearchContext.current() != null);
            assertThat(hasContext).isTrue();
            assertThat(SearchContext.current()).isNull();
        } finally {
            release.countDown();
            callers.shutdownNow();
        }
    }

    @Test
    void successfulFallbackStillOwnsItsLingeringOptionalWork() throws Exception {
        var admission = new SearchAdmission(properties());
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var execution = new AtomicReference<SearchExecutor.Execution>();
        try (var executor = new SearchExecutor(properties(), admission)) {
            String result = executor.execute(1, state -> {
                execution.set(state);
                try {
                    return executor.optional(state, SearchBudget.Stage.EMBEDDING, () -> {
                        entered.countDown();
                        awaitIgnoringInterrupt(release);
                        return "late vector";
                    });
                } catch (SearchStageFailure failure) {
                    assertThat(failure.reason()).isEqualTo(SearchStageFailure.Reason.TIMEOUT);
                    return "BM25";
                }
            });
            assertThat(result).isEqualTo("BM25");
            assertThat(entered.getCount()).isZero();
            assertThat(admission.inflight()).isOne();
            assertThat(executor.lingering()).isOne();
            release.countDown();
            execution.get().exited().get(3, TimeUnit.SECONDS);
            assertThat(admission.trackedUsers()).isZero();
        } finally {
            release.countDown();
        }
    }

    @Test
    void cancellationBeforeStartAndRejectedSubmissionReleaseOnce() throws Exception {
        var admission = new SearchAdmission(properties());
        var workers = mock(ExecutorService.class);
        var pending = new AtomicReference<Runnable>();
        doAnswer(call -> {
            pending.set(call.getArgument(0));
            return null;
        }).when(workers).execute(any(Runnable.class));
        var invoked = new AtomicInteger();
        try (var executor = new SearchExecutor(properties(), admission, System::nanoTime, workers,
                mock(ExecutorService.class), mock(ScheduledExecutorService.class), mock(ExecutorService.class))) {
            assertThatThrownBy(() -> executor.execute(1, state -> invoked.incrementAndGet()))
                    .isInstanceOf(SearchFailure.class);
            assertThat(admission.inflight()).isZero();
            pending.get().run();
            assertThat(invoked.get()).isZero();
            assertThat(admission.inflight()).isZero();
            doThrow(new RejectedExecutionException()).when(workers).execute(any(Runnable.class));
            assertThatThrownBy(() -> executor.execute(1, state -> invoked.incrementAndGet()))
                    .isInstanceOf(SearchFailure.class);
            assertThat(admission.trackedUsers()).isZero();
        }
    }

    @Test
    void fatalPhaseTimeoutCancelsTheRequestAndShutdownIsIdempotent() throws Exception {
        var admission = new SearchAdmission(properties());
        var release = new CountDownLatch(1);
        var execution = new AtomicReference<SearchExecutor.Execution>();
        try (var executor = new SearchExecutor(properties(), admission)) {
            assertThatThrownBy(() -> executor.execute(1, state -> {
                execution.set(state);
                return executor.required(state, SearchBudget.Stage.RETRIEVAL, () -> {
                    awaitIgnoringInterrupt(release);
                    return "late rows";
                });
            })).isInstanceOf(SearchFailure.class);
            assertThat(admission.inflight()).isOne();
            executor.close();
            executor.close();
            assertThat(admission.inflight()).isOne();
            release.countDown();
            execution.get().exited().get(3, TimeUnit.SECONDS);
            assertThat(admission.inflight()).isZero();
            assertThatThrownBy(() -> executor.execute(2, state -> "closed")).isInstanceOf(SearchFailure.class);
        } finally {
            release.countDown();
        }
    }

    private static void awaitIgnoringInterrupt(CountDownLatch latch) {
        while (latch.getCount() != 0) {
            try {
                latch.await();
            } catch (InterruptedException ignored) {
                // 模拟不响应取消的本地依赖，测试显式释放后才真正退出。
            }
        }
    }
}
