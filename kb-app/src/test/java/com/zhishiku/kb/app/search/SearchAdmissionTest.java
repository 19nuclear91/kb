package com.zhishiku.kb.app.search;

import com.zhishiku.kb.app.search.config.SearchExecutionProperties;
import com.zhishiku.kb.app.search.execution.SearchAdmission;
import com.zhishiku.kb.app.search.model.SearchFailure;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SearchAdmissionTest {
    @Test
    void simultaneousCallsShareAtomicLimitsAndReleaseExactlyOnce() throws Exception {
        var admission = new SearchAdmission(SearchExecutionProperties.defaults());
        var start = new CountDownLatch(1);
        List<Future<SearchAdmission.Ticket>> pending = new ArrayList<>();
        try (var threads = Executors.newFixedThreadPool(8)) {
            for (int index = 0; index < 8; index++) {
                long user = index / 2 + 1;
                pending.add(threads.submit(() -> {
                    start.await();
                    return admission.acquire(user);
                }));
            }
            start.countDown();
            for (var future : pending) {
                future.get(3, TimeUnit.SECONDS);
            }
            assertThat(admission.inflight()).isEqualTo(8);
            assertThat(admission.trackedUsers()).isEqualTo(4);
            assertThatThrownBy(() -> admission.acquire(1)).isInstanceOfSatisfying(SearchFailure.class,
                    e -> assertThat(e.status()).isEqualTo(429));
            assertThatThrownBy(() -> admission.acquire(99)).isInstanceOfSatisfying(SearchFailure.class,
                    e -> assertThat(e.status()).isEqualTo(503));
            assertThat(admission.trackedUsers()).isEqualTo(4);
            for (var future : pending) {
                future.get().close();
                future.get().close();
            }
            assertThat(admission.inflight()).isZero();
            assertThat(admission.trackedUsers()).isZero();
            try (var next = admission.acquire(99)) {
                assertThat(admission.inflight()).isOne();
            }
        }
    }
}
