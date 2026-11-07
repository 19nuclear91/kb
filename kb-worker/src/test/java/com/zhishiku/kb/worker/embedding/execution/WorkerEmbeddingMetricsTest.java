package com.zhishiku.kb.worker.embedding.execution;

import com.zhishiku.kb.common.embedding.EmbeddingRole;
import com.zhishiku.kb.common.embedding.RemoteProfileEmbeddingClient;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.time.Duration;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WorkerEmbeddingMetricsTest {
    @ParameterizedTest
    @EnumSource(WorkerExecution.Purpose.class)
    void timeoutIsObservedBeforeBlockedCallAndCancellationExitWithoutDoubleCounting(WorkerExecution.Purpose purpose)
            throws Exception {
        var started = new CountDownLatch(1);
        var callFinish = new CountDownLatch(1);
        var returned = new CountDownLatch(1);
        var closeStarted = new CountDownLatch(1);
        var closeFinish = new CountDownLatch(1);
        var registry = new SimpleMeterRegistry();
        try (var lanes = new WorkerExecutions(WorkerEmbeddingSettings.defaults())) {
            var metrics = new WorkerEmbeddingMetrics(lanes, registry);
            var stopped = registry.get("kb.worker.embedding.execution.stops")
                    .tag("purpose", purpose.name()).tag("reason", "TIMEOUT").counter();
            var ticket = lanes.submit(purpose, execution -> {
                execution.begin(Duration.ofMillis(200));
                try (var resource = execution.onCancel(() -> {
                    closeStarted.countDown();
                    WorkerExecutionsTest.await(closeFinish);
                })) {
                    metrics.observe(execution, () -> {
                        started.countDown();
                        WorkerExecutionsTest.await(callFinish);
                        execution.check();
                        return null;
                    });
                } finally {
                    returned.countDown();
                }
            });
            assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(closeStarted.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(stopped.count()).isEqualTo(1);
            assertThat(registry.find("kb.worker.embedding.calls").tag("purpose", purpose.name()).counter()).isNull();
            assertThat(ticket.execution().stop(WorkerEmbeddingFailure.Reason.LEASE_LOST)).isFalse();
            assertThat(ticket.execution().stop(WorkerEmbeddingFailure.Reason.TIMEOUT)).isFalse();
            assertThat(lanes.lingering(WorkerExecutions.channel(purpose))).isOne();
            assertThat(ticket.exited()).isNotDone();

            var otherPurpose = purpose == WorkerExecution.Purpose.PROBE
                    ? WorkerExecution.Purpose.PARSE : WorkerExecution.Purpose.PROBE;
            var other = lanes.submit(otherPurpose, execution -> {
                execution.begin(Duration.ofMinutes(1));
                execution.stop(WorkerEmbeddingFailure.Reason.LEASE_LOST);
            });
            other.exited().get(5, TimeUnit.SECONDS);
            assertThat(registry.get("kb.worker.embedding.execution.stops").tag("purpose", otherPurpose.name())
                    .tag("reason", "LEASE_LOST").counter().count()).isEqualTo(1);
            assertThat(stopped.count()).isEqualTo(1);

            callFinish.countDown();
            assertThat(returned.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(ticket.exited()).isNotDone();
            assertThat(stopped.count()).isEqualTo(1);
            assertThat(registry.get("kb.worker.embedding.calls").tag("purpose", purpose.name())
                    .tag("outcome", "TIMEOUT").counter().count()).isEqualTo(1);
            closeFinish.countDown();
            ticket.exited().get(5, TimeUnit.SECONDS);
            assertThat(stopped.count()).isEqualTo(1);
            assertThat(lanes.occupied(WorkerExecutions.channel(purpose))).isZero();
        } finally {
            callFinish.countDown();
            closeFinish.countDown();
            registry.close();
        }
    }

    @Test
    void emptyClaimsAndSuccessfulHandoffsAreNotCountedAsStops() throws Exception {
        for (boolean generate : new boolean[]{false, true}) {
            var registry = new SimpleMeterRegistry();
            try (var lanes = new WorkerExecutions(WorkerEmbeddingSettings.defaults())) {
                new WorkerEmbeddingMetrics(lanes, registry);
                lanes.submit(WorkerExecution.Purpose.REBUILD, execution -> {
                    if (generate) {
                        execution.begin(Duration.ofMinutes(1));
                        execution.succeed();
                    }
                }).exited().get(5, TimeUnit.SECONDS);
                assertThat(registry.get("kb.worker.embedding.execution.stops").counters())
                        .allSatisfy(counter -> assertThat(counter.count()).isZero());
            } finally {
                registry.close();
            }
        }
    }

    @Test
    void malformedVectorsHaveOneFixedFailureOutcomeWithoutSensitiveLabels() {
        var registry = new SimpleMeterRegistry();
        var settings = WorkerEmbeddingSettings.defaults();
        try (var lanes = new WorkerExecutions(settings)) {
            var metrics = new WorkerEmbeddingMetrics(lanes, registry);
            var json = new WorkerEmbeddingJson(settings);
            var transport = new WorkerEmbeddingTransport(settings, json,
                    () -> new MemoryEmbeddingSession(200, "{\"data\":[],\"extra\":\"private response\"}"
                            .getBytes(StandardCharsets.UTF_8), Map.of()), metrics);
            var definition = EmbeddingTestSupport.definition(16);
            var client = transport.observe(new RemoteProfileEmbeddingClient(definition, EmbeddingRole.DOCUMENT,
                    transport.forProfile(definition), new WorkerEmbeddingBatchPlanner(settings, json)));
            assertThatThrownBy(() -> client.embed(List.of("private document"), EmbeddingTestSupport.budget()))
                    .hasMessage("Worker embedding: INVALID_RESPONSE").hasNoCause();
            assertThat(registry.get("kb.worker.embedding.calls").tag("purpose", "PARSE")
                    .tag("outcome", "INVALID_RESPONSE").counter().count()).isEqualTo(1);
            assertThat(registry.get("kb.worker.embedding.call.duration").tag("purpose", "PARSE")
                    .tag("outcome", "INVALID_RESPONSE").timer().count()).isEqualTo(1);
            registry.getMeters().forEach(meter -> assertThat(meter.getId().toString())
                    .doesNotContain("private", "supplier", "test-key", "remote-v1"));
        } finally {
            registry.close();
        }
    }

    @Test
    void gaugesRemainOccupiedUntilActualExitAndUseOnlyFixedChannels() throws Exception {
        var ready = new CountDownLatch(1);
        var finish = new CountDownLatch(1);
        var registry = new SimpleMeterRegistry();
        try (var lanes = new WorkerExecutions(WorkerEmbeddingSettings.defaults())) {
            new WorkerEmbeddingMetrics(lanes, registry);
            var occupied = registry.get("kb.worker.embedding.execution.occupied").tag("channel", "BUSINESS").gauge();
            var lingering = registry.get("kb.worker.embedding.execution.lingering").tag("channel", "BUSINESS").gauge();
            assertThat(occupied.value()).isZero();
            var ticket = lanes.submit(WorkerExecution.Purpose.REBUILD, execution -> {
                execution.begin(Duration.ofMinutes(1));
                ready.countDown();
                WorkerExecutionsTest.await(finish);
            });
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(occupied.value()).isEqualTo(1);
            assertThat(lingering.value()).isZero();
            ticket.execution().stop(WorkerEmbeddingFailure.Reason.TIMEOUT);
            assertThat(occupied.value()).isEqualTo(1);
            assertThat(lingering.value()).isEqualTo(1);
            assertThat(registry.get("kb.worker.embedding.execution.occupied").tag("channel", "PROBE").gauge().value())
                    .isZero();
            finish.countDown();
            ticket.exited().get(5, TimeUnit.SECONDS);
            assertThat(occupied.value()).isZero();
            assertThat(lingering.value()).isZero();
            registry.getMeters().forEach(meter -> {
                if (meter.getId().getName().equals("kb.worker.embedding.execution.stops")) {
                    assertThat(meter.getId().getTags()).hasSize(2);
                    assertThat(WorkerExecution.Purpose.valueOf(meter.getId().getTag("purpose"))).isNotNull();
                    assertThat(WorkerEmbeddingFailure.Reason.valueOf(meter.getId().getTag("reason"))).isNotNull();
                } else {
                    assertThat(meter.getId().getTags()).hasSize(1);
                    assertThat(meter.getId().getTag("channel")).isIn("BUSINESS", "PROBE");
                }
            });
        } finally {
            finish.countDown();
            registry.close();
        }
    }
}
