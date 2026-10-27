package com.zhishiku.kb.worker.embedding;

import com.zhishiku.kb.common.embedding.EmbeddingControl;
import com.zhishiku.kb.common.embedding.EmbeddingDigest;
import com.zhishiku.kb.common.embedding.EmbeddingProfile;
import com.zhishiku.kb.common.embedding.EmbeddingResult;
import com.zhishiku.kb.common.embedding.EmbeddingRole;
import com.zhishiku.kb.common.embedding.EmbeddingRun;
import com.zhishiku.kb.worker.embedding.mapper.EmbeddingStateMapper;
import com.zhishiku.kb.worker.embedding.model.GeneratedRebuildBatch;
import com.zhishiku.kb.worker.embedding.model.RebuildBatch;
import com.zhishiku.kb.worker.embedding.model.RebuildChunk;
import com.zhishiku.kb.worker.embedding.model.RebuildContentChangedException;
import com.zhishiku.kb.worker.embedding.service.EmbeddingBatchCommitter;
import com.zhishiku.kb.worker.embedding.service.EmbeddingBatchGenerator;
import com.zhishiku.kb.worker.embedding.service.EmbeddingBatchProcessor;
import com.zhishiku.kb.worker.embedding.service.EmbeddingChunkStore;
import com.zhishiku.kb.worker.embedding.service.EmbeddingFailureService;
import com.zhishiku.kb.worker.embedding.service.EmbeddingFailureStore;
import com.zhishiku.kb.worker.embedding.service.EmbeddingLeaseService;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EmbeddingRebuildScopeTest {
    private final EmbeddingProfile profile = new EmbeddingProfile("p", "local", "source", "local-hash-v1", "1", 1024,
            "cosine", "identity-v1", "identity-v1", "query", "document", "local-hash-v1");
    private final RebuildBatch.Item item = new RebuildBatch.Item(1, 2, 3, EmbeddingDigest.text("body"), 1, 1);
    private final RebuildBatch batch = new RebuildBatch(UUID.randomUUID(), 4, 7, UUID.randomUUID(), profile,
            List.of(item));

    @Test
    void contentMismatchAfterGenerationBlocksInSeparateTransaction() throws Exception {
        var generator = mock(EmbeddingBatchGenerator.class);
        var committer = mock(EmbeddingBatchCommitter.class);
        var failure = mock(EmbeddingFailureService.class);
        var result = mock(GeneratedRebuildBatch.class);
        when(generator.generate(batch)).thenReturn(result);
        doThrow(new RebuildContentChangedException()).when(committer).commit(result);
        var processor = new EmbeddingBatchProcessor(generator, committer, failure, new EmbeddingRebuildProperties(300
                , 16, 3));
        processor.process(batch);
        var order = inOrder(generator, committer, failure);
        order.verify(generator).generate(batch);
        order.verify(committer).commit(result);
        order.verify(failure).block(batch, "CONTENT_CHANGED");
        assertThat(EmbeddingBatchProcessor.class.getMethod("process", RebuildBatch.class)
                .getAnnotation(Transactional.class).propagation()).isEqualTo(Propagation.NEVER);
    }

    @Test
    void blockFencesExecutionAndRecordsAuditButStaleExecutorCannotBlockAgain() {
        var state = mock(EmbeddingStateMapper.class);
        var leases = mock(EmbeddingLeaseService.class);
        var store = mock(EmbeddingFailureStore.class);
        var control = new EmbeddingControl("REBUILDING", null, null, "p", profile.fingerprint(), 4, batch.runId());
        when(state.lockExclusive()).thenReturn(control);
        when(state.run(batch.runId())).thenReturn(new EmbeddingRun(batch.runId(), "p", 1, "REBUILDING", 3, null,
                7, batch.owner(), null, 0, 3, null, 1, 0, 0, null));
        when(leases.renew(batch)).thenReturn(true, false);
        when(store.failItems(batch, "CONTENT_CHANGED")).thenReturn(1);
        when(store.blockRun(batch, 3, "CONTENT_CHANGED")).thenReturn(1);
        when(store.blockControl(batch)).thenReturn(1);
        when(store.blockEvent(batch, 3, "CONTENT_CHANGED")).thenReturn(1);
        var service = new EmbeddingFailureService(state, leases, store);
        assertThat(service.block(batch, "CONTENT_CHANGED")).isTrue();
        assertThat(service.block(batch, "CONTENT_CHANGED")).isFalse();
        verify(store).blockEvent(batch, 3, "CONTENT_CHANGED");
        var order = inOrder(state, leases, store);
        order.verify(state).lockExclusive();
        order.verify(leases).renew(batch);
        order.verify(store).failItems(batch, "CONTENT_CHANGED");
        order.verify(store).blockRun(batch, 3, "CONTENT_CHANGED");
        order.verify(store).blockControl(batch);
        order.verify(store).blockEvent(batch, 3, "CONTENT_CHANGED");
        when(leases.renew(batch)).thenReturn(true);
        when(store.blockEvent(batch, 3, "CONTENT_CHANGED")).thenThrow(new IllegalStateException("audit unavailable"));
        assertThatThrownBy(() -> service.block(batch, "CONTENT_CHANGED")).hasMessage("audit unavailable");
    }

    @Test
    void movedAndUpclassifiedSnapshotKeepsLatestFactsAndOnlyReceivesVectorUpdate() {
        var leases = mock(EmbeddingLeaseService.class);
        when(leases.renew(batch)).thenReturn(true);
        var store = new ScopeStore();
        var committer = new EmbeddingBatchCommitter(leases, store);
        float[] vector = new float[1024];
        vector[0] = 1;
        var result = new GeneratedRebuildBatch(batch, List.of(new GeneratedRebuildBatch.Input(item,
                item.contentDigest())),
                new EmbeddingResult(profile, EmbeddingRole.DOCUMENT, List.of(vector)));
        store.spaceId = 90;
        store.securityLevel = 4;
        committer.commit(result);
        assertThat(store.spaceId).isEqualTo(90);
        assertThat(store.securityLevel).isEqualTo(4);
        assertThat(store.writtenProfile).isEqualTo("p");
        assertThat(store.succeeded).isEqualTo(1);
        store.deleted = true;
        store.writtenProfile = null;
        committer.commit(result);
        assertThat(store.writtenProfile).isNull();
        assertThat(store.skipped).isEqualTo(1);
        assertThat(store.deleted).isTrue();
    }

    private static class ScopeStore extends EmbeddingChunkStore {
        long spaceId;
        int securityLevel;
        boolean deleted;
        String writtenProfile;
        int succeeded;
        int skipped;

        ScopeStore() {
            super(null);
        }

        @Override
        public void lockDocument(long id) {
        }

        @Override
        public RebuildChunk readLocked(long id) {
            return new RebuildChunk(1, 2, 3, "body", deleted ? "deleted" : "published", 3L);
        }

        @Override
        public int updateVector(RebuildBatch b, RebuildBatch.Item i, float[] vector) {
            writtenProfile = b.profile().profileId();
            return 1;
        }

        @Override
        public int finishItem(RebuildBatch b, RebuildBatch.Item i, boolean skipped) {
            return 1;
        }

        @Override
        public int completeBatch(RebuildBatch b, int succeeded, int skipped) {
            this.succeeded = succeeded;
            this.skipped = skipped;
            return 1;
        }
    }
}
