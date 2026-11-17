package com.zhishiku.kb.app.document.diff;

import com.zhishiku.kb.app.document.diff.service.DiffAdmission;
import com.zhishiku.kb.app.document.diff.service.DiffDeadline;
import com.zhishiku.kb.app.document.diff.service.DiffException;
import com.zhishiku.kb.app.document.diff.service.DiffExecutor;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

class DiffExecutionTest {
    @Test
    void capacityRejectsImmediatelyAndReadFailureCanReleaseTicket() {
        var admission = new DiffAdmission(new DocumentDiffProperties(100, 10, 50, 1, 100, 100, 0.3));
        var ticket = admission.acquire();
        assertThatThrownBy(admission::acquire).isInstanceOfSatisfying(DiffException.class, failure -> {
            assertThat(failure.status()).isEqualTo(429);
            assertThat(failure.reasonCode()).isEqualTo("DIFF_CAPACITY");
        });
        ticket.close();
        ticket.close();
        assertThat(admission.inflight()).isZero();
        admission.acquire().close();
    }

    @Test
    void timeoutKeepsTicketUntilBlockedComputationActuallyExits() throws Exception {
        var props = new DocumentDiffProperties(100, 10, 100, 1, 100, 100, 0.3);
        var admission = new DiffAdmission(props);
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var executor = new DiffExecutor(props)) {
            assertThatThrownBy(() -> executor.execute(admission.acquire(), deadline -> {
                entered.countDown();
                try {
                    release.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException failure) {
                    Thread.currentThread().interrupt();
                }
                return "done";
            })).isInstanceOf(DiffDeadline.Expired.class);
            assertThat(entered.await(1, TimeUnit.SECONDS)).isTrue();
            assertThat(admission.inflight()).isEqualTo(1);
            assertThatThrownBy(admission::acquire).isInstanceOf(DiffException.class);
            release.countDown();
            await().atMost(2, TimeUnit.SECONDS).untilAsserted(() -> assertThat(admission.inflight()).isZero());
        } finally {
            release.countDown();
        }
    }

    @Test
    void controlledClockExpiresAndWorkerFailureReleasesTicket() {
        var props = new DocumentDiffProperties(100, 10, 100, 1, 100, 100, 0.3);
        var admission = new DiffAdmission(props);
        var clock = new AtomicLong();
        try (var executor = new DiffExecutor(props, clock::get)) {
            assertThatThrownBy(() -> executor.execute(admission.acquire(), deadline -> {
                clock.set(TimeUnit.MILLISECONDS.toNanos(101));
                deadline.check();
                return "late";
            })).isInstanceOf(DiffDeadline.Expired.class);
            await().atMost(2, TimeUnit.SECONDS).untilAsserted(() -> assertThat(admission.inflight()).isZero());
        }
    }
}
