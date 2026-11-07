package com.zhishiku.kb.worker.parse;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.zhishiku.kb.common.embedding.EmbeddingResult;
import com.zhishiku.kb.common.embedding.EmbeddingRole;
import com.zhishiku.kb.common.embedding.EmbeddingClientRegistry;
import com.zhishiku.kb.common.embedding.ProfileEmbeddingClient;
import com.zhishiku.kb.worker.embedding.execution.WorkerEmbeddingSettings;
import com.zhishiku.kb.worker.embedding.execution.WorkerExecutions;
import com.zhishiku.kb.worker.embedding.mapper.EmbeddingStateMapper;
import com.zhishiku.kb.worker.parse.mapper.ParseTaskMapper;
import com.zhishiku.kb.worker.storage.ObjectStorage;
import com.zhishiku.kb.worker.telemetry.InMemoryTracing;
import com.zhishiku.kb.worker.telemetry.TaskContextPropagation;
import com.zhishiku.kb.worker.telemetry.TaskTelemetry;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.tracing.Tracer;
import io.opentelemetry.api.common.AttributeKey;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ParseTaskTracingTest {
    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void eachExecutionHasIndependentRootAndThreeRealStagesWithoutChangingOrder(boolean enabled) throws Exception {
        try (var tracing = new InMemoryTracing()) {
            var order = new CopyOnWriteArrayList<String>();
            var claims = mock(ParseTaskClaimService.class);
            var task = ParseEmbeddingFixture.task();
            when(claims.claim(anyString(), anyInt(), anyInt())).thenAnswer(call -> {
                order.add("claim");
                return List.of(task);
            });
            var telemetry = new TaskTelemetry(tracing.tracer, enabled);
            ObjectStorage storage = key -> {
                order.add("download");
                return new ByteArrayInputStream(new byte[]{1});
            };
            DocumentParser parser = (input, name, mime, limit) -> {
                order.add("parse");
                assertThat(tracing.tracer.currentSpan() != null).isEqualTo(enabled);
                return "secret-document";
            };
            var clients = mock(EmbeddingClientRegistry.class);
            var client = mock(ProfileEmbeddingClient.class);
            when(clients.find(anyString(), anyString())).thenReturn(Optional.of(client));
            when(client.role()).thenReturn(EmbeddingRole.DOCUMENT);
            when(client.profile()).thenReturn(ParseEmbeddingFixture.PROFILE);
            when(client.embed(anyList(), any())).thenAnswer(call -> {
                order.add("embedding");
                assertThat(tracing.tracer.currentSpan() != null).isEqualTo(enabled);
                return new EmbeddingResult(ParseEmbeddingFixture.PROFILE, EmbeddingRole.DOCUMENT, List.of(ParseEmbeddingFixture.vector()));
            });
            var pipeline = new DefaultParsePipeline(storage, parser, new IngestionProperties(1024, 1000, 100, 5), clients);
            pipeline.setTelemetry(telemetry);
            var publisher = mock(ChunkPublisher.class);
            when(publisher.publish(any(), anyString(), any(ParsePipeline.ParsedDocument.class))).thenAnswer(call -> {
                order.add("publish");
                assertThat(tracing.tracer.currentSpan() != null).isEqualTo(enabled);
                return true;
            });
            var executor = new WorkerExecutions(WorkerEmbeddingSettings.defaults());
            var runner = new ParseTaskRunner(mock(ParseTaskMapper.class), pipeline, publisher, new ParseProperties(60, 1, 100, 1000, 10),
                    mock(TaskFailureAlert.class), new FailureClassifier(), mock(ParseTaskRecoveryService.class), new ParseTaskMetrics(),
                    claims, new TaskContextPropagation(ObservationRegistry.NOOP, enabled ? tracing.tracer : Tracer.NOOP), executor);
            runner.setTelemetry(telemetry);
            try {
                ParseRunnerTestSupport.run(runner);
                ParseRunnerTestSupport.run(runner);
                ParseRunnerTestSupport.inspect(executor, ignored -> assertThat(tracing.tracer.currentSpan()).isNull());
                assertThat(order).containsExactly("claim", "download", "parse", "embedding", "publish",
                        "claim", "download", "parse", "embedding", "publish");
                var spans = tracing.exporter.getFinishedSpanItems();
                assertThat(spans).hasSize(enabled ? 8 : 0);
                if (enabled) {
                    var roots = spans.stream().filter(span -> "kb.parse.task".equals(span.getName())).toList();
                    assertThat(roots).hasSize(2);
                    assertThat(roots.get(0).getTraceId()).isNotEqualTo(roots.get(1).getTraceId());
                    for (var root : roots) {
                        assertThat(root.getParentSpanContext().isValid()).isFalse();
                        assertThat(root.getAttributes().get(AttributeKey.stringKey("kb.task.id"))).isEqualTo("1");
                        assertThat(root.getAttributes().get(AttributeKey.stringKey("kb.version.id"))).isEqualTo("2");
                        assertThat(root.getAttributes().get(AttributeKey.stringKey("kb.execution.generation"))).isEqualTo("0");
                        assertThat(spans).filteredOn(span -> span.getParentSpanId().equals(root.getSpanId()))
                                .extracting(span -> span.getName()).containsExactlyInAnyOrder("kb.parse.document", "kb.parse.embedding", "kb.parse.publish");
                    }
                }
            } finally {
                runner.shutdownPipelineExecutor();
                executor.close();
            }
        }
    }

    @Test
    void unavailableParserDoesNotClaimOrCreateTaskSpans() throws Exception {
        try (var tracing = new InMemoryTracing()) {
            var parser = mock(DocumentParser.class);
            var state = mock(EmbeddingStateMapper.class);
            var clients = mock(EmbeddingClientRegistry.class);
            var tasks = mock(ParseTaskMapper.class);
            var claims = new ParseTaskClaimService(state, clients, tasks, parser);
            var pipeline = mock(ParsePipeline.class);
            var publisher = mock(ChunkPublisher.class);
            var runner = new ParseTaskRunner(tasks, pipeline, publisher, new ParseProperties(60, 1, 100, 1000, 10),
                    mock(TaskFailureAlert.class), new FailureClassifier(), mock(ParseTaskRecoveryService.class), claims);
            runner.setTelemetry(new TaskTelemetry(tracing.tracer, true));
            try {
                ParseRunnerTestSupport.run(runner);
                verifyNoInteractions(state, clients, tasks, pipeline, publisher);
                assertThat(tracing.exporter.getFinishedSpanItems()).isEmpty();
            } finally {
                runner.shutdownPipelineExecutor();
            }
        }
    }

    @Test
    void failedExecutionExportsCategoryWithoutParserBodyOrStacktrace() throws Exception {
        var logger = (Logger) LoggerFactory.getLogger(ParseTaskRunner.class);
        var logs = new ListAppender<ILoggingEvent>();
        logs.start();
        logger.addAppender(logs);
        try (var tracing = new InMemoryTracing()) {
            var claims = mock(ParseTaskClaimService.class);
            when(claims.claim(anyString(), anyInt(), anyInt())).thenReturn(List.of(ParseEmbeddingFixture.task()));
            var telemetry = new TaskTelemetry(tracing.tracer, true);
            ParsePipeline pipeline = (task, lease) -> telemetry.inStage(TaskTelemetry.Stage.PARSE, () -> {
                throw new IOException("secret-parser-body secret-object-key");
            });
            var executor = new WorkerExecutions(WorkerEmbeddingSettings.defaults());
            var runner = new ParseTaskRunner(mock(ParseTaskMapper.class), pipeline, mock(ChunkPublisher.class), new ParseProperties(60, 1, 100, 1000, 10),
                    mock(TaskFailureAlert.class), new FailureClassifier(), mock(ParseTaskRecoveryService.class), new ParseTaskMetrics(), claims,
                    new TaskContextPropagation(ObservationRegistry.NOOP, tracing.tracer), executor);
            runner.setTelemetry(telemetry);
            try {
                ParseRunnerTestSupport.run(runner);
                assertThat(tracing.exporter.getFinishedSpanItems()).hasSize(2).allSatisfy(span -> {
                    assertThat(span.getAttributes().get(AttributeKey.stringKey("kb.failure.class"))).isNotBlank();
                    assertThat(span.getAttributes().toString()).doesNotContain("secret-");
                    assertThat(span.getEvents()).isEmpty();
                });
                assertThat(logs.list).allSatisfy(event -> {
                    assertThat(event.getFormattedMessage()).doesNotContain("secret-");
                    assertThat(event.getThrowableProxy()).isNull();
                });
            } finally {
                runner.shutdownPipelineExecutor();
                executor.close();
            }
        } finally {
            logger.detachAppender(logs);
            logs.stop();
        }
    }
}
