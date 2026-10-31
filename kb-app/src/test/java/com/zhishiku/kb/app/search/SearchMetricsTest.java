package com.zhishiku.kb.app.search;

import com.zhishiku.kb.app.search.config.SearchExecutionProperties;
import com.zhishiku.kb.app.search.execution.SearchAdmission;
import com.zhishiku.kb.app.search.execution.SearchBudget;
import com.zhishiku.kb.app.search.execution.SearchContext;
import com.zhishiku.kb.app.search.execution.SearchExecutor;
import com.zhishiku.kb.app.search.execution.SearchMetrics;
import com.zhishiku.kb.app.search.execution.SearchStageFailure;
import com.zhishiku.kb.app.search.model.SearchFailure;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SearchMetricsTest {
    @Test
    void fallbackIsSuccessfulButCapacityRemainsOccupiedUntilCancellationWorkAlsoExits() throws Exception {
        var properties = new SearchExecutionProperties(2000, 100, 500, 100, 100, 1, 1, 1);
        var admission = new SearchAdmission(properties);
        var metrics = new SimpleMeterRegistry();
        var releaseJob = new CountDownLatch(1);
        var cancellationEntered = new CountDownLatch(1);
        var releaseCancellation = new CountDownLatch(1);
        var state = new AtomicReference<SearchExecutor.Execution>();
        try (var executor = new SearchExecutor(properties, admission, new SearchMetrics(metrics))) {
            String result = executor.execute(987654321L, execution -> {
                state.set(execution);
                try {
                    return executor.optional(execution, SearchBudget.Stage.EMBEDDING, () -> {
                        try (var registration = SearchContext.current().onCancel(() -> {
                            cancellationEntered.countDown();
                            waitFor(releaseCancellation);
                        })) {
                            waitFor(releaseJob);
                            return "late";
                        }
                    });
                } catch (SearchStageFailure fallback) {
                    executor.degraded(SearchBudget.Stage.EMBEDDING, fallback.reason());
                    execution.resultCount(1);
                    return "BM25";
                }
            });
            assertThat(result).isEqualTo("BM25");
            assertThat(cancellationEntered.await(3, TimeUnit.SECONDS)).isTrue();
            releaseJob.countDown();
            assertThat(metrics.get("kb.search.inflight").gauge().value()).isEqualTo(1);
            assertThat(metrics.get("kb.search.lingering").gauge().value()).isEqualTo(1);
            state.get().cancel(new SearchFailure(SearchFailure.Kind.CANCELLED));
            state.get().cancel(new SearchFailure(SearchFailure.Kind.CANCELLED));
            assertThatThrownBy(() -> executor.execute(987654321L, execution -> "unreachable"))
                    .isInstanceOf(SearchFailure.class);
            assertThatThrownBy(() -> executor.execute(123456789L, execution -> "unreachable"))
                    .isInstanceOf(SearchFailure.class);
            assertThat(metrics.get("kb.search.requests").tag("outcome", "SUCCESS").counter().count()).isEqualTo(1);
            assertThat(metrics.get("kb.search.requests").tag("outcome", "REJECTED").counter().count()).isEqualTo(2);
            assertThat(metrics.get("kb.search.rejections").tag("reason", "USER_LIMIT").counter().count()).isEqualTo(1);
            assertThat(metrics.get("kb.search.rejections").tag("reason", "CAPACITY").counter().count()).isEqualTo(1);
            assertThat(metrics.get("kb.search.degradation").tag("reason", "TIMEOUT").counter().count()).isEqualTo(1);
            assertThat(metrics.get("kb.search.stage.duration").timer().count()).isEqualTo(1);
            releaseCancellation.countDown();
            state.get().exited().get(3, TimeUnit.SECONDS);
            assertThat(metrics.get("kb.search.inflight").gauge().value()).isZero();
            assertThat(metrics.get("kb.search.lingering").gauge().value()).isZero();
            assertThat(metrics.getMeters()).allSatisfy(meter -> {
                assertThat(meter.getId().getTags()).allSatisfy(tag -> {
                    assertThat(tag.getKey()).isIn("outcome", "stage", "reason");
                    assertThat(tag.getValue()).doesNotContain("987654321", "123456789");
                });
            });
        } finally {
            releaseJob.countDown();
            releaseCancellation.countDown();
        }
    }

    @Test
    void coreFailureCountsOnceAndDoesNotClaimSuccessOrDegradation() {
        var properties = SearchExecutionProperties.defaults();
        var registry = new SimpleMeterRegistry();
        try (var executor = new SearchExecutor(properties, new SearchAdmission(properties), new SearchMetrics(registry))) {
            assertThatThrownBy(() -> executor.execute(1, state -> executor.required(state, SearchBudget.Stage.CONTROL, () -> {
                throw new SearchFailure(SearchFailure.Kind.DEPENDENCY);
            }))).isInstanceOf(SearchFailure.class);
            executor.close();
            executor.close();
            assertThat(registry.get("kb.search.requests").tag("outcome", "FAILED").counter().count()).isEqualTo(1);
            assertThat(registry.find("kb.search.requests").tag("outcome", "SUCCESS").counter()).isNull();
            assertThat(registry.find("kb.search.degradation").counter()).isNull();
        }
    }

    private static void waitFor(CountDownLatch latch) {
        while (latch.getCount() != 0) {
            try {
                latch.await();
            } catch (InterruptedException ignored) {
                // 模拟取消动作及外部工作实际退出前继续占用。
            }
        }
    }
}
