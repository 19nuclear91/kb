package com.zhishiku.kb.worker.parse;

import com.zhishiku.kb.worker.parse.mapper.ParseTaskMapper;
import com.zhishiku.kb.worker.telemetry.TaskContextPropagation;
import com.zhishiku.kb.worker.telemetry.TaskTelemetry;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.tracing.Tracer;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.boot.actuate.autoconfigure.tracing.OpenTelemetryAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TaskContextPropagationTest {
    @Test
    void pipelineRestoresAndClearsTraceWhileMaintenanceThreadsStayIndependent() {
        new ApplicationContextRunner().withConfiguration(AutoConfigurations.of(
                        org.springframework.boot.actuate.autoconfigure.opentelemetry.OpenTelemetryAutoConfiguration.class,
                        OpenTelemetryAutoConfiguration.class))
                .withPropertyValues("management.tracing.enabled=true", "management.tracing.baggage.enabled=false")
                .run(context -> {
                    var tracer = context.getBean(Tracer.class);
                    var pipelineExecutor = Executors.newSingleThreadExecutor();
                    var pollExecutor = Executors.newSingleThreadExecutor();
                    var maintenance = Executors.newSingleThreadExecutor();
                    var started = new CountDownLatch(1);
                    var release = new CountDownLatch(1);
                    var pipeline = mock(ParsePipeline.class);
                    var recovery = mock(ParseTaskRecoveryService.class);
                    var claims = mock(ParseTaskClaimService.class);
                    var task = ParseEmbeddingFixture.task();
                    var root = tracer.nextSpan().name("test.task").start();
                    when(claims.claim(anyString(), anyInt(), anyInt())).thenReturn(List.of(task));
                    when(pipeline.process(any(), any())).thenAnswer(call -> {
                        assertThat(MDC.get("traceId")).isEqualTo(tracer.currentSpan().context().traceId())
                                .isNotEqualTo(root.context().traceId());
                        assertThat(MDC.get("spanId")).isEqualTo(tracer.currentSpan().context().spanId());
                        started.countDown();
                        assertThat(release.await(5, TimeUnit.SECONDS)).isTrue();
                        return new ParsePipeline.ParsedDocument(List.of());
                    });
                    when(recovery.renewLease(any(), anyString(), anyInt())).thenAnswer(call -> {
                        assertThat(MDC.get("traceId")).isNull();
                        assertThat(tracer.currentSpan()).isNull();
                        return true;
                    });
                    when(recovery.sweepExhausted()).thenAnswer(call -> {
                        assertThat(MDC.get("traceId")).isNull();
                        assertThat(tracer.currentSpan()).isNull();
                        return 0;
                    });
                    var runner = new ParseTaskRunner(mock(ParseTaskMapper.class), pipeline, mock(ChunkPublisher.class),
                            new ParseProperties(60, 1, 100, 1000, 10), mock(TaskFailureAlert.class), mock(FailureClassifier.class),
                            recovery, new ParseTaskMetrics(), claims, new TaskContextPropagation(ObservationRegistry.NOOP, tracer), pipelineExecutor);
                    runner.setTelemetry(new TaskTelemetry(tracer, true));
                    try {
                        var poll = pollExecutor.submit(() -> {
                            try (var scope = tracer.withSpan(root)) {
                                runner.poll();
                            }
                            assertThat(MDC.get("traceId")).isNull();
                        });
                        assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
                        maintenance.submit(() -> {
                            runner.heartbeat();
                            runner.sweepExhausted();
                        }).get(5, TimeUnit.SECONDS);
                        release.countDown();
                        poll.get(5, TimeUnit.SECONDS);
                        pipelineExecutor.submit(() -> {
                            assertThat(MDC.get("traceId")).isNull();
                            assertThat(MDC.get("spanId")).isNull();
                            assertThat(tracer.currentSpan()).isNull();
                        }).get(5, TimeUnit.SECONDS);
                    } finally {
                        release.countDown();
                        root.end();
                        pipelineExecutor.shutdownNow();
                        pollExecutor.shutdownNow();
                        maintenance.shutdownNow();
                    }
                });
    }
}
