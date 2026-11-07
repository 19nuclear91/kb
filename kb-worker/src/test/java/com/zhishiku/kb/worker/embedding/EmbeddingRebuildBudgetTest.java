package com.zhishiku.kb.worker.embedding;

import com.zhishiku.kb.common.embedding.EmbeddingClientRegistry;
import com.zhishiku.kb.common.embedding.EmbeddingConnection;
import com.zhishiku.kb.common.embedding.EmbeddingDigest;
import com.zhishiku.kb.common.embedding.EmbeddingProfile;
import com.zhishiku.kb.common.embedding.EmbeddingProfileDefinition;
import com.zhishiku.kb.common.embedding.EmbeddingRole;
import com.zhishiku.kb.common.embedding.RemoteProfileEmbeddingClient;
import com.zhishiku.kb.worker.embedding.execution.WorkerEmbeddingFailure;
import com.zhishiku.kb.worker.embedding.execution.WorkerEmbeddingSettings;
import com.zhishiku.kb.worker.embedding.execution.WorkerExecution;
import com.zhishiku.kb.worker.embedding.model.RebuildBatch;
import com.zhishiku.kb.worker.embedding.model.RebuildChunk;
import com.zhishiku.kb.worker.embedding.service.EmbeddingBatchCommitter;
import com.zhishiku.kb.worker.embedding.service.EmbeddingBatchGenerator;
import com.zhishiku.kb.worker.embedding.service.EmbeddingBatchProcessor;
import com.zhishiku.kb.worker.embedding.service.EmbeddingBatchReader;
import com.zhishiku.kb.worker.embedding.service.EmbeddingChunkStore;
import com.zhishiku.kb.worker.embedding.service.EmbeddingFailureService;
import com.zhishiku.kb.worker.embedding.service.EmbeddingLeaseService;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class EmbeddingRebuildBudgetTest {
    private final AtomicLong clock = new AtomicLong();
    private final List<Runnable> timers = new ArrayList<>();
    private final WorkerExecution execution = new WorkerExecution(WorkerExecution.Purpose.REBUILD, 4194304,
            clock::get, (action, nanos) -> {
                timers.add(action);
                return () -> { };
            }, Runnable::run);
    private final EmbeddingLeaseService leases = mock(EmbeddingLeaseService.class);
    private final EmbeddingChunkStore chunks = mock(EmbeddingChunkStore.class);
    private final EmbeddingBatchCommitter committer = mock(EmbeddingBatchCommitter.class);
    private final EmbeddingFailureService failures = mock(EmbeddingFailureService.class);
    private final EmbeddingProfile profile = new EmbeddingProfile("p", "remote", "vendor/model", "model", "1", 1024,
            "cosine", "identity-v1", "identity-v1", "query", "document", "embeddings-v1");
    private final RebuildBatch batch = new RebuildBatch(UUID.randomUUID(), 2, 3, UUID.randomUUID(), profile,
            List.of(new RebuildBatch.Item(1, 2, 3, EmbeddingDigest.text("body"), 4, 1),
                    new RebuildBatch.Item(2, 2, 3, EmbeddingDigest.text("body"), 5, 1)));

    @Test
    void readAndAllSubBatchesShareBudgetAndStaleTimersCannotRevokeCommit() {
        var calls = new AtomicInteger();
        var processor = processor(298, () -> {
            assertThat(execution.remainingNanos()).isEqualTo(TimeUnit.SECONDS.toNanos(calls.get() == 0 ? 2 : 1));
            leases.renew(batch);
            clock.addAndGet(TimeUnit.MILLISECONDS.toNanos(calls.incrementAndGet() == 1 ? 1000 : 500));
        });
        doAnswer(call -> {
            assertThat(execution.succeeded()).isTrue();
            clock.set(TimeUnit.SECONDS.toNanos(1000));
            timers.forEach(Runnable::run);
            assertThat(execution.succeeded()).isTrue();
            return null;
        }).when(committer).commit(any());
        processor.process(batch, execution);
        assertThat(calls.get()).isEqualTo(2);
        verify(committer).commit(any());
        verifyNoInteractions(failures);
    }

    @Test
    void readingAtDeadlinePreventsFirstModelCallAndCommit() {
        var calls = new AtomicInteger();
        processor(300, calls::incrementAndGet).process(batch, execution);
        assertThat(execution.reason()).isEqualTo(WorkerEmbeddingFailure.Reason.TIMEOUT);
        assertThat(calls.get()).isZero();
        verifyNoInteractions(committer);
    }

    @Test
    void lossAfterFirstSubBatchPreventsSecondAndAnyFailureWrite() {
        var calls = new AtomicInteger();
        processor(1, () -> {
            calls.incrementAndGet();
            execution.stop(WorkerEmbeddingFailure.Reason.LEASE_UNKNOWN);
        }).process(batch, execution);
        assertThat(calls.get()).isOne();
        verifyNoInteractions(committer, failures);
    }

    private EmbeddingBatchProcessor processor(int readSeconds, Runnable onCall) {
        when(leases.renew(batch)).thenReturn(true);
        when(chunks.read(1)).thenAnswer(call -> {
            clock.set(TimeUnit.SECONDS.toNanos(readSeconds));
            return new RebuildChunk(1, 2, 3, "body", "published", 3L);
        });
        when(chunks.read(2)).thenReturn(new RebuildChunk(2, 2, 3, "body", "published", 3L));
        var definition = new EmbeddingProfileDefinition(profile,
                new EmbeddingConnection("https://supplier.invalid", "key", 60, 1));
        var client = new RemoteProfileEmbeddingClient(definition, EmbeddingRole.DOCUMENT, body -> {
            onCall.run();
            return Map.of("data", List.of(Map.of("index", 0, "embedding", Collections.nCopies(1024, 1))));
        });
        var clients = new EmbeddingClientRegistry(List.of(definition), EmbeddingRole.DOCUMENT, ignored -> client);
        var generator = new EmbeddingBatchGenerator(new EmbeddingBatchReader(leases, chunks), clients);
        return new EmbeddingBatchProcessor(generator, committer, failures, new EmbeddingRebuildProperties(300, 16, 3),
                WorkerEmbeddingSettings.defaults());
    }
}
