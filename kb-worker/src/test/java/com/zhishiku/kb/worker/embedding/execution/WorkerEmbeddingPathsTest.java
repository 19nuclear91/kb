package com.zhishiku.kb.worker.embedding.execution;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhishiku.kb.common.embedding.EmbeddingClientRegistry;
import com.zhishiku.kb.common.embedding.EmbeddingDigest;
import com.zhishiku.kb.common.embedding.EmbeddingProfileDefinition;
import com.zhishiku.kb.worker.embedding.EmbeddingProfilesConfiguration;
import com.zhishiku.kb.worker.embedding.EmbeddingProfilesProperties;
import com.zhishiku.kb.worker.embedding.EmbeddingReadinessScheduler;
import com.zhishiku.kb.worker.embedding.EmbeddingRebuildProperties;
import com.zhishiku.kb.worker.embedding.EmbeddingRebuildRunner;
import com.zhishiku.kb.worker.embedding.mapper.EmbeddingReadinessMapper;
import com.zhishiku.kb.worker.embedding.model.RebuildBatch;
import com.zhishiku.kb.worker.embedding.model.RebuildChunk;
import com.zhishiku.kb.worker.embedding.service.EmbeddingBatchCommitter;
import com.zhishiku.kb.worker.embedding.service.EmbeddingBatchGenerator;
import com.zhishiku.kb.worker.embedding.service.EmbeddingBatchProcessor;
import com.zhishiku.kb.worker.embedding.service.EmbeddingBatchReader;
import com.zhishiku.kb.worker.embedding.service.EmbeddingFailureService;
import com.zhishiku.kb.worker.embedding.service.EmbeddingLeaseService;
import com.zhishiku.kb.worker.embedding.service.EmbeddingProfileRegistration;
import com.zhishiku.kb.worker.parse.ChunkPublisher;
import com.zhishiku.kb.worker.parse.DefaultParsePipeline;
import com.zhishiku.kb.worker.parse.DocumentParser;
import com.zhishiku.kb.worker.parse.FailureClassifier;
import com.zhishiku.kb.worker.parse.IngestionProperties;
import com.zhishiku.kb.worker.parse.ParseProperties;
import com.zhishiku.kb.worker.parse.ParsePipeline;
import com.zhishiku.kb.worker.parse.ParseTaskClaimService;
import com.zhishiku.kb.worker.parse.ParseTaskMetrics;
import com.zhishiku.kb.worker.parse.ParseTaskRecoveryService;
import com.zhishiku.kb.worker.parse.ParseTaskRunner;
import com.zhishiku.kb.worker.parse.TaskFailureAlert;
import com.zhishiku.kb.worker.parse.mapper.ParseTaskMapper;
import com.zhishiku.kb.worker.parse.model.ParseTask;
import com.zhishiku.kb.worker.telemetry.TaskContextPropagation;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.tracing.Tracer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** 真实 Runner／流水线／分批／预算／传输组合，仅存储和 HTTP 会话使用内存替身。 */
class WorkerEmbeddingPathsTest {
    @ParameterizedTest
    @EnumSource(WorkerExecution.Purpose.class)
    void productionPathsDeliverOneWholeResultOrOnePermanentFailure(WorkerExecution.Purpose purpose) throws Exception {
        for (boolean fail : new boolean[]{false, true}) {
            var calls = new AtomicInteger();
            byte[] valid = response();
            try (var chain = new Chain(() -> {
                int call = calls.incrementAndGet();
                boolean rejected = fail && (purpose == WorkerExecution.Purpose.PROBE || call == 2);
                return new MemoryEmbeddingSession(rejected ? 401 : 200,
                        rejected ? "private upstream body".getBytes(StandardCharsets.UTF_8) : valid, Map.of());
            })) {
                var ticket = switch (purpose) {
                    case PARSE -> chain.parse.poll();
                    case REBUILD -> chain.rebuild.tick();
                    case PROBE -> chain.probe.poll();
                };
                ticket.exited().get(5, TimeUnit.SECONDS);
                assertThat(calls.get()).isEqualTo(purpose == WorkerExecution.Purpose.PROBE ? 1 : fail ? 2 : 3);
                if (purpose == WorkerExecution.Purpose.PARSE) {
                    if (fail) {
                        verify(chain.recovery).deadLetter(any(), anyString(), argThat(value ->
                                value.code().equals("EMBEDDING_AUTH_REJECTED") && value.permanent()));
                        verifyNoInteractions(chain.publisher);
                    } else {
                        verify(chain.publisher).publish(any(), anyString(), argThat((ParsePipeline.ParsedDocument value) -> value.chunks().size() == 3));
                        verifyNoInteractions(chain.recovery);
                    }
                } else if (purpose == WorkerExecution.Purpose.REBUILD) {
                    if (fail) {
                        verify(chain.failures).handle(any(), eq("MODEL_UNAVAILABLE"), eq(false), eq(3));
                        verifyNoInteractions(chain.committer);
                    } else {
                        verify(chain.committer).commit(argThat(value -> value.embeddings().size() == 3));
                        verifyNoInteractions(chain.failures);
                    }
                } else {
                    verify(chain.readiness).save(argThat(value -> value.ready() != fail));
                }
                assertThat(chain.lanes.occupied(WorkerExecutions.channel(purpose))).isZero();
            }
        }
    }

    @Test
    void timeoutRetainsParseSlotWhileProbeFinishesThenRebuildCanClaim() throws Exception {
        var terminating = new CountDownLatch(1);
        var finish = new CountDownLatch(1);
        var calls = new AtomicInteger();
        byte[] valid = response();
        try (var chain = new Chain(() -> {
            if (calls.incrementAndGet() == 1) {
                return new MemoryEmbeddingSession(200, valid, Map.of()) {
                    @Override
                    public boolean awaitTermination(Duration timeout) throws InterruptedException {
                        terminating.countDown();
                        return finish.await(timeout.toNanos(), TimeUnit.NANOSECONDS);
                    }
                };
            }
            return new MemoryEmbeddingSession(200, valid, Map.of());
        })) {
            var parse = chain.parse.poll();
            assertThat(terminating.await(5, TimeUnit.SECONDS)).isTrue();
            parse.execution().stop(WorkerEmbeddingFailure.Reason.TIMEOUT);
            assertThat(chain.rebuild.tick()).isNull();
            verifyNoInteractions(chain.leases);
            chain.probe.poll().exited().get(5, TimeUnit.SECONDS);
            verify(chain.readiness).save(argThat(value -> value.ready()));
            assertThat(chain.lanes.occupied(WorkerExecutions.Channel.BUSINESS)).isOne();
            assertThat(chain.lanes.occupied(WorkerExecutions.Channel.PROBE)).isZero();
            assertThat(parse.exited()).isNotDone();
            assertThat(calls.get()).isEqualTo(2);
            finish.countDown();
            parse.exited().get(5, TimeUnit.SECONDS);
            verify(chain.recovery).scheduleRetry(any(), anyString(), argThat(value -> value.code().equals("EMBEDDING_TIMEOUT")), any());
            verifyNoInteractions(chain.publisher);
            submit(chain.rebuild::tick).exited().get(5, TimeUnit.SECONDS);
            verify(chain.committer).commit(argThat(value -> value.embeddings().size() == 3));
            assertThat(calls.get()).isEqualTo(5);
        } finally {
            finish.countDown();
        }
    }

    private static WorkerExecutions.Ticket submit(Supplier<WorkerExecutions.Ticket> action) {
        long start = System.nanoTime();
        do {
            var ticket = action.get();
            if (ticket != null) {
                return ticket;
            }
            Thread.yield();
        } while (System.nanoTime() - start < TimeUnit.SECONDS.toNanos(5));
        throw new AssertionError("execution handoff timed out");
    }

    private static byte[] response() throws Exception {
        return new ObjectMapper().writeValueAsBytes(Map.of("data", List.of(EmbeddingTestSupport.item(0, 1))));
    }

    private static final class Chain implements AutoCloseable {
        private final WorkerEmbeddingSettings settings = WorkerEmbeddingSettings.defaults();
        private final WorkerExecutions lanes = new WorkerExecutions(settings);
        private final ChunkPublisher publisher = mock(ChunkPublisher.class);
        private final ParseTaskRecoveryService recovery = mock(ParseTaskRecoveryService.class);
        private final EmbeddingBatchCommitter committer = mock(EmbeddingBatchCommitter.class);
        private final EmbeddingFailureService failures = mock(EmbeddingFailureService.class);
        private final EmbeddingReadinessMapper readiness = mock(EmbeddingReadinessMapper.class);
        private final EmbeddingLeaseService leases = mock(EmbeddingLeaseService.class);
        private final ParseTaskRunner parse;
        private final EmbeddingRebuildRunner rebuild;
        private final EmbeddingReadinessScheduler probe;

        private Chain(Supplier<WorkerEmbeddingTransport.Session> sessions) {
            EmbeddingProfileDefinition definition = EmbeddingTestSupport.definition(1);
            var json = new WorkerEmbeddingJson(settings);
            var transport = new WorkerEmbeddingTransport(settings, json, sessions);
            EmbeddingClientRegistry clients = new EmbeddingProfilesConfiguration().embeddingClientRegistry(
                    new EmbeddingProfilesProperties(List.of(definition)), settings, json, transport);
            var profile = definition.profile();
            var task = new ParseTask(1L, 2L, 1, 3, OffsetDateTime.now().plusMinutes(5), "owner",
                    null, null, null, null, null, 0L, "key", "a.txt", 250L, "text/plain",
                    profile.profileId(), profile.fingerprint(), 4L, 9L);
            var claims = mock(ParseTaskClaimService.class);
            when(claims.claim(anyString(), anyInt(), eq(1))).thenReturn(List.of(task));
            when(publisher.publish(any(), anyString(), any(ParsePipeline.ParsedDocument.class))).thenReturn(true);
            DocumentParser parser = (input, name, mime, max) -> "a".repeat(250);
            var pipeline = new DefaultParsePipeline(key -> new ByteArrayInputStream(new byte[0]), parser,
                    new IngestionProperties(1024, 1000, 100, 0), clients);
            parse = new ParseTaskRunner(mock(ParseTaskMapper.class), pipeline, publisher,
                    new ParseProperties(60, 10, 100, 1000, 10), mock(TaskFailureAlert.class), new FailureClassifier(),
                    recovery, new ParseTaskMetrics(), claims, new TaskContextPropagation(ObservationRegistry.NOOP, Tracer.NOOP), lanes);
            var items = List.of(1L, 2L, 3L).stream().map(id ->
                    new RebuildBatch.Item(id, 2, 3, EmbeddingDigest.text("body"), id, 1)).toList();
            var batch = new RebuildBatch(UUID.randomUUID(), 4, 3, UUID.randomUUID(), profile, items);
            when(leases.claim(any())).thenReturn(batch);
            var reader = mock(EmbeddingBatchReader.class);
            when(reader.read(batch)).thenReturn(items.stream().map(item ->
                    new RebuildChunk(item.chunkId(), 2, 3, "body", "published", 3L)).toList());
            var processor = new EmbeddingBatchProcessor(new EmbeddingBatchGenerator(reader, clients), committer,
                    failures, new EmbeddingRebuildProperties(300, 16, 3), settings);
            rebuild = new EmbeddingRebuildRunner(leases, processor, lanes);
            when(readiness.save(any())).thenReturn(1);
            probe = new EmbeddingReadinessScheduler(clients, readiness, mock(EmbeddingProfileRegistration.class),
                    30000, 90000, lanes, settings);
        }

        @Override
        public void close() {
            probe.close();
            rebuild.close();
            lanes.close();
        }
    }
}
