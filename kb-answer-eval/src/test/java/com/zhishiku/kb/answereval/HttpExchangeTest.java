package com.zhishiku.kb.answereval;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpHeaders;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HttpExchangeTest {
    @Test
    void invalidMediaTypeRedirectAndDeadlineNeverRetryAndAlwaysClose() {
        for (String mode : List.of("type", "redirect", "deadline")) {
            AtomicInteger calls = new AtomicInteger();
            AtomicInteger closed = new AtomicInteger();
            AtomicLong clock = new AtomicLong();
            Transport stub = (request, deadline) -> {
                calls.incrementAndGet();
                if ("deadline".equals(mode)) {
                    clock.set(20_000_000_000L);
                }
                return new Transport.Response("redirect".equals(mode) ? 302 : 200,
                        HttpHeaders.of(Map.of("Content-Type", List.of("type".equals(mode) ? "text/html" : "application/json")),
                                (key, value) -> true), new ByteArrayInputStream(new byte[0]) {
                            @Override
                            public void close() {
                                closed.incrementAndGet();
                            }
                        });
            };
            assertThatThrownBy(() -> new HttpExchange(stub).execute(request(), new Deadline(clock::get, 10_000),
                    (response, remaining) -> HttpExchange.contentType(response, Set.of(200), Set.of("application/json"))))
                    .isInstanceOf(EvaluationFailure.class);
            assertThat(calls).hasValue(1);
            assertThat(closed.get()).isPositive();
        }
    }

    @Test
    void cancelledCallerDoesNotSend() {
        AtomicInteger calls = new AtomicInteger();
        Transport stub = (request, deadline) -> {
            calls.incrementAndGet();
            return BusinessClientTest.response(200, Map.of());
        };
        Thread.currentThread().interrupt();
        try {
            assertThatThrownBy(() -> new HttpExchange(stub).execute(request(), new Deadline(System::nanoTime, 1000),
                    (response, deadline) -> true)).isInstanceOf(EvaluationFailure.class)
                    .hasMessage("ANSWER_EVAL_ERROR CANCELLED");
        } finally {
            Thread.interrupted();
        }
        assertThat(calls).hasValue(0);
    }

    @Test
    void postTransportFailureAndExpiredResponseRemainUnknownButExplicitRejectionDoesNot() {
        for (String mode : List.of("io", "deadline", "rejected")) {
            AtomicInteger calls = new AtomicInteger();
            AtomicLong clock = new AtomicLong();
            Transport stub = (request, deadline) -> {
                calls.incrementAndGet();
                if ("io".equals(mode)) {
                    throw new IOException("SECRET");
                }
                if ("deadline".equals(mode)) {
                    clock.set(20_000_000_000L);
                }
                return BusinessClientTest.response("rejected".equals(mode) ? 403 : 200, Map.of());
            };
            var post = new Transport.Request(URI.create("https://test.invalid/api"), "POST", Map.of(), new byte[0]);
            assertThatThrownBy(() -> new HttpExchange(stub).execute(post, new Deadline(clock::get, 10_000),
                    (response, deadline) -> HttpExchange.contentType(response, Set.of(200), Set.of("application/json"))))
                    .isInstanceOf(EvaluationFailure.class).hasMessage("ANSWER_EVAL_ERROR "
                            + ("rejected".equals(mode) ? "PROTOCOL" : "SUBMISSION_UNKNOWN"));
            assertThat(calls).hasValue(1);
        }
    }

    @Test
    void deadlineBeforeSubmissionDoesNotClaimUnknownSubmission() {
        AtomicLong clock = new AtomicLong();
        var deadline = new Deadline(clock::get, 1);
        clock.set(2_000_000);
        var post = new Transport.Request(URI.create("https://test.invalid/api"), "POST", Map.of(), new byte[0]);
        assertThatThrownBy(() -> new HttpExchange((request, remaining) -> {
            throw new AssertionError("已过期的请求不得发送");
        }).execute(post, deadline, (response, remaining) -> true))
                .isInstanceOf(EvaluationFailure.class).hasMessage("ANSWER_EVAL_ERROR DEADLINE");
    }

    @Test
    void callerObservingExpiredDeadlineAfterDispatchStillRecordsUnknownSubmission() {
        var sent = new CountDownLatch(1);
        var callerReads = new AtomicInteger();
        var calls = new AtomicInteger();
        var time = new AtomicLong();
        Thread caller = Thread.currentThread();
        LongSupplier clock = () -> {
            if (Thread.currentThread() == caller && callerReads.incrementAndGet() >= 3) {
                try {
                    assertThat(sent.await(5, TimeUnit.SECONDS)).isTrue();
                } catch (InterruptedException failure) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(failure);
                }
            }
            return time.get();
        };
        Transport transport = (request, remaining) -> {
            calls.incrementAndGet();
            time.set(20_000_000_000L);
            sent.countDown();
            return BusinessClientTest.response(200, Map.of());
        };
        var deadline = new Deadline(clock, 10_000);
        var post = new Transport.Request(URI.create("https://test.invalid/api"), "POST", Map.of(), new byte[0]);
        assertThatThrownBy(() -> new HttpExchange(transport).execute(post, deadline, (response, remaining) -> true))
                .isInstanceOf(EvaluationFailure.class).hasMessage("ANSWER_EVAL_ERROR SUBMISSION_UNKNOWN");
        assertThat(calls).hasValue(1);
    }

    private Transport.Request request() {
        return new Transport.Request(URI.create("https://test.invalid/api"), "GET", Map.of(), new byte[0]);
    }
}
