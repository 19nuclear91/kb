package com.zhishiku.kb.worker.embedding.execution;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WorkerEmbeddingTransportDeadlineTest {
    @Test
    void timedOutHeadersCannotReleaseSlotUntilSessionReallyTerminates() throws Exception {
        var sent = new CountDownLatch(1);
        var stopped = new CountDownLatch(1);
        var terminate = new CountDownLatch(1);
        var observed = new AtomicReference<WorkerEmbeddingFailure.Reason>();
        var pending = new CompletableFuture<HttpResponse<InputStream>>() {
            @Override
            public boolean cancel(boolean interrupt) {
                return false;
            }
        };
        var session = new WorkerEmbeddingTransport.Session() {
            @Override
            public CompletableFuture<HttpResponse<InputStream>> send(HttpRequest request,
                                                                      HttpResponse.BodyHandler<InputStream> handler) {
                sent.countDown();
                return pending;
            }

            @Override
            public void shutdown() {
                stopped.countDown();
            }

            @Override
            public boolean awaitTermination(Duration timeout) throws InterruptedException {
                return terminate.await(timeout.toNanos(), TimeUnit.NANOSECONDS);
            }
        };
        var settings = WorkerEmbeddingSettings.defaults();
        var transport = new WorkerEmbeddingTransport(settings, new WorkerEmbeddingJson(settings), () -> session);
        try (var lanes = new WorkerExecutions(settings)) {
            var ticket = lanes.submit(WorkerExecution.Purpose.PARSE, execution -> {
                execution.begin(Duration.ofMillis(100));
                try {
                    transport.forProfile(EmbeddingTestSupport.definition(16))
                            .post(EmbeddingTestSupport.body(List.of("text")), execution);
                    throw new AssertionError("late success");
                } catch (WorkerEmbeddingFailure failure) {
                    observed.set(failure.reason());
                }
            });
            assertThat(sent.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(stopped.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(ticket.exited()).isNotDone();
            assertThat(lanes.occupied(WorkerExecutions.Channel.BUSINESS)).isOne();
            assertThat(lanes.submit(WorkerExecution.Purpose.REBUILD, ignored -> { })).isNull();
            var probe = lanes.submit(WorkerExecution.Purpose.PROBE, execution -> {
                execution.begin(Duration.ofSeconds(1));
                execution.succeed();
            });
            probe.exited().get(5, TimeUnit.SECONDS);
            pending.complete(null);
            terminate.countDown();
            ticket.exited().get(5, TimeUnit.SECONDS);
            assertThat(observed.get()).isEqualTo(WorkerEmbeddingFailure.Reason.TIMEOUT);
        } finally {
            terminate.countDown();
        }
    }

    @Test
    void bodyReadStopsAtParentDeadlineWithoutEndOfStream() throws Exception {
        var bodyStarted = new CountDownLatch(1);
        var session = new MemoryEmbeddingSession(200, new byte[0], Map.of()) {
            @Override
            protected void deliver(HttpResponse.BodySubscriber<InputStream> subscriber, int current) {
                if (current == 0) {
                    subscriber.onNext(List.of(ByteBuffer.wrap("{".getBytes(StandardCharsets.UTF_8))));
                    bodyStarted.countDown();
                }
            }
        };
        var settings = WorkerEmbeddingSettings.defaults();
        var transport = new WorkerEmbeddingTransport(settings, new WorkerEmbeddingJson(settings), () -> session);
        var reason = new AtomicReference<WorkerEmbeddingFailure.Reason>();
        try (var lanes = new WorkerExecutions(settings)) {
            var ticket = lanes.submit(WorkerExecution.Purpose.REBUILD, execution -> {
                execution.begin(Duration.ofMillis(200));
                try {
                    transport.forProfile(EmbeddingTestSupport.definition(16))
                            .post(EmbeddingTestSupport.body(List.of("text")), execution);
                } catch (WorkerEmbeddingFailure failure) {
                    reason.set(failure.reason());
                }
            });
            assertThat(bodyStarted.await(5, TimeUnit.SECONDS)).isTrue();
            ticket.exited().get(5, TimeUnit.SECONDS);
            assertThat(reason.get()).isEqualTo(WorkerEmbeddingFailure.Reason.TIMEOUT);
            assertThat(session.cancellations.get()).isPositive();
        }
    }

    @Test
    void terminationTimeStillCountsAgainstHttpChildDeadline() {
        var clock = new AtomicLong();
        var execution = new WorkerExecution(WorkerExecution.Purpose.PARSE, 1024, clock::get,
                (action, nanos) -> () -> { }, Runnable::run);
        execution.begin(Duration.ofSeconds(100));
        var session = new MemoryEmbeddingSession(200, "{}".getBytes(StandardCharsets.UTF_8), Map.of()) {
            @Override
            public boolean awaitTermination(Duration timeout) {
                clock.set(TimeUnit.SECONDS.toNanos(61));
                return true;
            }
        };
        var settings = WorkerEmbeddingSettings.defaults();
        var transport = new WorkerEmbeddingTransport(settings, new WorkerEmbeddingJson(settings), () -> session);
        assertThatThrownBy(() -> transport.forProfile(EmbeddingTestSupport.definition(16))
                .post(EmbeddingTestSupport.body(List.of("text")), execution))
                .hasMessage("Worker embedding: TIMEOUT");
    }
}
