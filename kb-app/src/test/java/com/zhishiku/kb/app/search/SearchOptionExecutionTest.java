package com.zhishiku.kb.app.search;

import com.zhishiku.kb.app.search.config.SearchExecutionProperties;
import com.zhishiku.kb.app.search.execution.SearchAdmission;
import com.zhishiku.kb.app.search.execution.SearchBudget;
import com.zhishiku.kb.app.search.execution.SearchExecutor;
import com.zhishiku.kb.app.search.execution.SearchMetrics;
import com.zhishiku.kb.app.search.model.SearchFailure;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SearchOptionExecutionTest {
    @Test
    void timedOutCandidateKeepsSharedCapacityUntilItsWorkActuallyExits() throws Exception {
        var properties = new SearchExecutionProperties(2000, 200, 200, 100, 100, 1, 1, 1);
        var admission = new SearchAdmission(properties);
        var meters = new SimpleMeterRegistry();
        var release = new CountDownLatch(1);
        var state = new AtomicReference<SearchExecutor.Execution>();
        try (var executor = new SearchExecutor(properties, admission, new SearchMetrics(meters))) {
            assertThatThrownBy(() -> executor.execute(7, SearchMetrics.Operation.CATEGORIES, execution -> {
                state.set(execution);
                return executor.required(execution, SearchBudget.Stage.RETRIEVAL, () -> {
                    while (release.getCount() > 0) {
                        try {
                            release.await();
                        } catch (InterruptedException ignored) {
                            // 内存工作故意延迟退出，验证响应结束不会提前归还容量。
                        }
                    }
                    return "late";
                });
            })).isInstanceOfSatisfying(SearchFailure.class, error -> assertThat(error.kind()).isEqualTo(SearchFailure.Kind.TIMEOUT));
            assertThat(admission.inflight()).isEqualTo(1);
            assertThat(executor.lingering()).isEqualTo(1);
            assertThatThrownBy(() -> executor.execute(7, execution -> "search"))
                    .isInstanceOfSatisfying(SearchFailure.class, error -> assertThat(error.kind()).isEqualTo(SearchFailure.Kind.USER_LIMIT));
            assertThatThrownBy(() -> executor.execute(8, SearchMetrics.Operation.TAGS, execution -> "tags"))
                    .isInstanceOfSatisfying(SearchFailure.class, error -> assertThat(error.kind()).isEqualTo(SearchFailure.Kind.CAPACITY));
            assertThat(meters.get("kb.search.options.requests").tags("operation", "CATEGORIES", "outcome", "FAILED").counter().count()).isEqualTo(1);
            assertThat(meters.find("kb.search.requests").tag("outcome", "SUCCESS").counter()).isNull();
            release.countDown();
            state.get().exited().get(3, TimeUnit.SECONDS);
            assertThat(admission.inflight()).isZero();
            assertThat(meters.get("kb.search.options.stage.duration").tag("operation", "CATEGORIES").timer().count()).isEqualTo(1);
        } finally {
            release.countDown();
        }
    }

    @Test
    void successfulCandidateCountsAreSeparateFromSearchAndUseFixedTags() {
        var properties = SearchExecutionProperties.defaults();
        var meters = new SimpleMeterRegistry();
        try (var executor = new SearchExecutor(properties, new SearchAdmission(properties), new SearchMetrics(meters))) {
            String result = executor.execute(7, SearchMetrics.Operation.TAGS, execution -> {
                execution.resultCount(0);
                return executor.required(execution, SearchBudget.Stage.RESPONSE, () -> "empty-page");
            });
            assertThat(result).isEqualTo("empty-page");
            assertThat(meters.get("kb.search.options.requests").tags("operation", "TAGS", "outcome", "EMPTY").counter().count()).isEqualTo(1);
            assertThat(meters.find("kb.search.requests").counters()).isEmpty();
            assertThat(meters.getMeters()).allSatisfy(meter -> assertThat(meter.getId().getTags()).allSatisfy(tag -> {
                assertThat(tag.getKey()).isIn("operation", "outcome", "stage", "reason");
                assertThat(tag.getValue()).doesNotContain("user", "query");
            }));
        }
    }
}
