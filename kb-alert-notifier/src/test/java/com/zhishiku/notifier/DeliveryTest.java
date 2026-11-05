package com.zhishiku.notifier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.net.ConnectException;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class DeliveryTest extends TestSupport {
    @ParameterizedTest
    @ValueSource(strings = {"{\"errcode\":1}", "{\"errcode\":-1}", "{\"errcode\":4294967296}", "{\"errcode\":0.0}",
            "{\"errcode\":0e0}", "{\"errcode\":\"0\"}", "{\"errcode\":false}", "{\"errcode\":null}", "{}", "null", "[]", "broken",
            "{\"errcode\":0,\"errcode\":0}", "{\"errcode\":0}{}"})
    void httpSuccessWithoutStrictIntegerZeroNeverAcknowledges(String body) throws Exception {
        behavior = (uri, bytes, deadline) -> new Transport.Response(200, body.getBytes(StandardCharsets.UTF_8));
        mvc.perform(post(webhook(1))).andExpect(status().isServiceUnavailable()).andExpect(result -> {
            assertThat(result.getResponse().getContentAsByteArray()).isEmpty();
            assertThat(result.getResponse().getHeader("Cache-Control")).isEqualTo("no-store");
        });
        assertThat(calls).hasValue(1);
        assertThat(admission.inflight()).isZero();
        assertThat(registry.get("kb.alert.notifier.deliveries").tag("outcome", "success").counter().count()).isZero();
    }

    @ParameterizedTest
    @ValueSource(ints = {201, 202, 299})
    void any2xxWithIntegerZeroIsAcknowledged(int code) throws Exception {
        behavior = (uri, bytes, deadline) -> new Transport.Response(code, "{\"errcode\":0}".getBytes(StandardCharsets.UTF_8));
        mvc.perform(post(webhook(1))).andExpect(status().isNoContent());
        assertThat(calls).hasValue(1);
    }

    @ParameterizedTest
    @ValueSource(ints = {301, 302, 307, 308, 400, 429, 500})
    void redirectsAndHttpFailuresAreOneAttemptOnly(int code) throws Exception {
        behavior = (uri, bytes, deadline) -> new Transport.Response(code, success().body());
        mvc.perform(post(webhook(1))).andExpect(status().isServiceUnavailable());
        assertThat(calls).hasValue(1);
        assertThat(registry.get("kb.alert.notifier.deliveries").tag("outcome", "remote_http").counter().count()).isEqualTo(1);
    }

    @Test
    void responseByteBoundaryIsStrictAndInvalidUtf8Fails() throws Exception {
        byte[] exact = Arrays.copyOf("{\"errcode\":0}".getBytes(StandardCharsets.UTF_8), BoundedJson.RESPONSE_BYTES);
        Arrays.fill(exact, 13, exact.length, (byte) ' ');
        behavior = (uri, body, deadline) -> new Transport.Response(200, exact);
        mvc.perform(post(webhook(1))).andExpect(status().isNoContent());
        behavior = (uri, body, deadline) -> new Transport.Response(200, Arrays.copyOf(exact, exact.length + 1));
        mvc.perform(post(webhook(1))).andExpect(status().isServiceUnavailable());
        behavior = (uri, body, deadline) -> new Transport.Response(200, new byte[]{(byte) 0xc3, 0x28});
        mvc.perform(post(webhook(1))).andExpect(status().isServiceUnavailable());
        assertThat(calls).hasValue(3);
    }

    @ParameterizedTest
    @ValueSource(strings = {"connect", "lost", "timeout", "cancelled"})
    void failureAndRetryAreIndependentWithoutBackgroundResend(String failure) throws Exception {
        behavior = (uri, body, deadline) -> {
            if (failure.equals("connect")) {
                throw new ConnectException("remote-error-canary " + uri);
            }
            if (failure.equals("timeout")) {
                throw new HttpTimeoutException("remote-error-canary");
            }
            if (failure.equals("cancelled")) {
                throw new InterruptedException("cancelled-canary");
            }
            throw new IOException("response-lost-canary " + uri);
        };
        try {
            mvc.perform(post(webhook(1))).andExpect(status().isServiceUnavailable());
        } finally {
            Thread.interrupted();
        }
        assertThat(calls).hasValue(1);
        behavior = (uri, body, deadline) -> success();
        mvc.perform(post(webhook(1))).andExpect(status().isNoContent());
        mvc.perform(post(webhook(1))).andExpect(status().isNoContent());
        assertThat(calls).hasValue(3);
    }

    @Test
    void monotonicDeadlineIncludesCompleteResponseAndExactEightSecondBoundary() throws Exception {
        behavior = (uri, body, deadline) -> {
            nanos.addAndGet(Duration.ofSeconds(8).toNanos());
            return success();
        };
        mvc.perform(post(webhook(1))).andExpect(status().isServiceUnavailable());
        assertThat(registry.get("kb.alert.notifier.deliveries").tag("outcome", "timeout").counter().count()).isEqualTo(1);
        assertThat(calls).hasValue(1);
        behavior = (uri, body, deadline) -> {
            nanos.addAndGet(Duration.ofSeconds(8).toNanos() - 1);
            return success();
        };
        mvc.perform(post(webhook(1))).andExpect(status().isNoContent());
    }

    @Test
    void alreadyCancelledCallerDoesNotStartAnyAttempt() {
        Thread.currentThread().interrupt();
        try {
            assertThatThrownBy(() -> service.deliver(BoundedJson.encode(webhook(1)))).isInstanceOf(Rejection.class)
                    .hasMessage("cancelled");
            assertThat(calls).hasValue(0);
            assertThat(admission.inflight()).isZero();
        } finally {
            Thread.interrupted();
        }
    }
}
