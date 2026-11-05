package com.zhishiku.notifier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class AdmissionTest extends TestSupport {
    @Test
    void slidingWindowCountsFailuresAndExpiresAtExactlySixtySeconds() throws Exception {
        behavior = (uri, body, deadline) -> new Transport.Response(500, new byte[0]);
        for (int index = 0; index < 10; index++) {
            mvc.perform(post(webhook(1))).andExpect(status().isServiceUnavailable());
        }
        assertThat(calls).hasValue(10);
        mvc.perform(post(webhook(1))).andExpect(status().isServiceUnavailable());
        assertThat(calls).hasValue(10);
        nanos.set(Duration.ofSeconds(60).toNanos() - 1);
        mvc.perform(post(webhook(1))).andExpect(status().isServiceUnavailable());
        assertThat(calls).hasValue(10);
        nanos.incrementAndGet();
        mvc.perform(post(webhook(1))).andExpect(status().isServiceUnavailable());
        assertThat(calls).hasValue(11);
        assertThat(registry.get("kb.alert.notifier.rejections").tag("reason", "rate").counter().count()).isEqualTo(2);
        assertThat(registry.get("kb.alert.notifier.deliveries").tag("outcome", "remote_http").counter().count()).isEqualTo(11);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @SuppressWarnings("unchecked")
    void timeoutOrCancellationRetainsBothSlotsUntilActualJdkTermination(boolean cancellation) throws Exception {
        CountDownLatch cleaning = new CountDownLatch(2);
        CountDownLatch exit = new CountDownLatch(1);
        HttpClient client = mock(HttpClient.class);
        CompletableFuture<HttpResponse<byte[]>> pending = mock(CompletableFuture.class);
        when(client.sendAsync(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(pending);
        when(pending.get(anyLong(), eq(TimeUnit.NANOSECONDS))).thenAnswer(invocation -> {
            if (cancellation) {
                throw new InterruptedException("caller-cancelled-canary");
            }
            throw new TimeoutException("body-stalled-canary");
        });
        when(client.awaitTermination(any(Duration.class))).thenAnswer(invocation -> {
            cleaning.countDown();
            assertThat(exit.await(5, TimeUnit.SECONDS)).isTrue();
            return true;
        });
        behavior = new JdkTransport(() -> client);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> one = executor.submit(() -> assertThatThrownBy(() -> service.deliver(BoundedJson.encode(webhook(1))))
                    .hasMessage(cancellation ? "cancelled" : "timeout"));
            Future<?> two = executor.submit(() -> assertThatThrownBy(() -> service.deliver(BoundedJson.encode(webhook(1))))
                    .hasMessage(cancellation ? "cancelled" : "timeout"));
            assertThat(cleaning.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(admission.inflight()).isEqualTo(2);
            assertThat(registry.get("kb.alert.notifier.inflight").gauge().value()).isEqualTo(2);
            assertThat(one.isDone()).isFalse();
            assertThat(two.isDone()).isFalse();
            mvc.perform(post(webhook(1))).andExpect(status().isServiceUnavailable());
            assertThat(calls).hasValue(2);
            assertThat(registry.get("kb.alert.notifier.rejections").tag("reason", "concurrency").counter().count()).isEqualTo(1);
            exit.countDown();
            one.get(5, TimeUnit.SECONDS);
            two.get(5, TimeUnit.SECONDS);
            assertThat(admission.inflight()).isZero();
            behavior = (uri, body, deadline) -> success();
            mvc.perform(post(webhook(1))).andExpect(status().isNoContent());
            assertThat(calls).hasValue(3);
        } finally {
            exit.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void windowIsSlidingRatherThanFixedAndNewInstanceResetsIt() {
        for (int index = 0; index < 5; index++) {
            admission.enter();
            admission.exit();
        }
        nanos.set(Duration.ofSeconds(30).toNanos());
        for (int index = 0; index < 5; index++) {
            admission.enter();
            admission.exit();
        }
        nanos.set(Duration.ofSeconds(60).toNanos());
        for (int index = 0; index < 5; index++) {
            admission.enter();
            admission.exit();
        }
        assertThatThrownBy(admission::enter).hasMessage("rate");
        Admission restarted = new Admission(nanos::get);
        restarted.enter();
        assertThat(restarted.inflight()).isEqualTo(1);
        restarted.exit();
    }
}
