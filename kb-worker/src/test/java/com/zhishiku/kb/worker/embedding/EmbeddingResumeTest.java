package com.zhishiku.kb.worker.embedding;

import com.zhishiku.kb.common.embedding.EmbeddingClientRegistry;
import com.zhishiku.kb.common.embedding.EmbeddingConnection;
import com.zhishiku.kb.common.embedding.EmbeddingControl;
import com.zhishiku.kb.common.embedding.EmbeddingDigest;
import com.zhishiku.kb.common.embedding.EmbeddingProfile;
import com.zhishiku.kb.common.embedding.EmbeddingProfileDefinition;
import com.zhishiku.kb.common.embedding.EmbeddingResult;
import com.zhishiku.kb.common.embedding.EmbeddingRole;
import com.zhishiku.kb.common.embedding.ProfileEmbeddingClient;
import com.zhishiku.kb.worker.embedding.mapper.EmbeddingLeaseMapper;
import com.zhishiku.kb.worker.embedding.mapper.EmbeddingStateMapper;
import com.zhishiku.kb.worker.embedding.model.RebuildBatch;
import com.zhishiku.kb.worker.embedding.model.RebuildChunk;
import com.zhishiku.kb.worker.embedding.service.EmbeddingBatchCommitter;
import com.zhishiku.kb.worker.embedding.service.EmbeddingBatchGenerator;
import com.zhishiku.kb.worker.embedding.service.EmbeddingBatchReader;
import com.zhishiku.kb.worker.embedding.service.EmbeddingChunkStore;
import com.zhishiku.kb.worker.embedding.service.EmbeddingLeaseService;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EmbeddingResumeTest {
    @Test
    void reconstructedServicesKeepCommittedItemsAndRegenerateOnlyUncommittedCalls() {
        var p = new EmbeddingProfile("p", "local", "source", "local-hash-v1", "1", 1024,
                "cosine", "identity-v1", "identity-v1", "query", "document", "local-hash-v1");
        var model = mock(ProfileEmbeddingClient.class);
        when(model.profile()).thenReturn(p);
        when(model.role()).thenReturn(EmbeddingRole.DOCUMENT);
        float[] vector = new float[1024];
        vector[0] = 1;
        when(model.embed(any())).thenReturn(new EmbeddingResult(p, EmbeddingRole.DOCUMENT, List.of(vector)));
        var clients = new EmbeddingClientRegistry(List.of(new EmbeddingProfileDefinition(p,
                new EmbeddingConnection(null, null, 30, 1))), EmbeddingRole.DOCUMENT, d -> model);
        var state = mock(EmbeddingStateMapper.class);
        var id = UUID.randomUUID();
        when(state.lockShared()).thenReturn(new EmbeddingControl("REBUILDING", null, null, "p", p.fingerprint(), 4,
                id));
        var store = new ResumeStore();
        var props = new EmbeddingRebuildProperties(300, 1, 3);
        var leases = new EmbeddingLeaseService(state, store, clients, props);
        var generator = new EmbeddingBatchGenerator(new EmbeddingBatchReader(leases, store), clients);
        var first = leases.claim(UUID.randomUUID());
        new EmbeddingBatchCommitter(leases, store).commit(generator.generate(first));
        assertThat(store.successful).containsExactly(1L);
        var unfinished = leases.claim(UUID.randomUUID());
        generator.generate(unfinished);
        assertThat(store.successful).doesNotContain(2L);

        var restartedLeases = new EmbeddingLeaseService(state, store, clients, props);
        var restartedGenerator = new EmbeddingBatchGenerator(new EmbeddingBatchReader(restartedLeases, store), clients);
        var takeover = restartedLeases.claim(UUID.randomUUID());
        assertThat(takeover.items().getFirst().chunkId()).isEqualTo(2);
        assertThat(takeover.items().getFirst().attempts()).isEqualTo(2);
        assertThat(restartedLeases.renew(unfinished)).isFalse();
        new EmbeddingBatchCommitter(restartedLeases, store).commit(restartedGenerator.generate(takeover));
        assertThat(store.successful).containsExactly(1L, 2L);
        assertThat(restartedLeases.claim(UUID.randomUUID()).items()).isEmpty();
        verify(model).embed(List.of("body-1"));
        verify(model, times(2)).embed(List.of("body-2"));
    }

    private static class ResumeStore extends EmbeddingChunkStore implements EmbeddingLeaseMapper {
        final Set<Long> successful = new LinkedHashSet<>();
        final Map<Long, Integer> attempts = new HashMap<>();
        long token;
        UUID owner;

        ResumeStore() {
            super(null);
        }

        @Override
        public Long claimRun(UUID id, UUID owner, int seconds) {
            this.owner = owner;
            return ++token;
        }

        @Override
        public List<RebuildBatch.Item> claimItems(UUID id, long token, UUID owner, int seconds, int limit) {
            for (long chunk = 1; chunk <= 2; chunk++) {
                if (!successful.contains(chunk)) {
                    int attempt = attempts.merge(chunk, 1, Integer::sum);
                    return List.of(new RebuildBatch.Item(chunk, 10, 20, EmbeddingDigest.text("body-" + chunk),
                            attempt, attempt));
                }
            }
            return List.of();
        }

        @Override
        public int validRun(RebuildBatch b) {
            return b.runToken() == token && b.owner().equals(owner) ? 1 : 0;
        }

        @Override
        public int renewRun(RebuildBatch b, int seconds) {
            return validRun(b);
        }

        @Override
        public int renewItem(RebuildBatch b, RebuildBatch.Item i, int seconds) {
            return validRun(b) == 1 && !successful.contains(i.chunkId()) ? 1 : 0;
        }

        @Override
        public int release(RebuildBatch b) {
            return validRun(b);
        }

        @Override
        public RebuildChunk read(long chunk) {
            return new RebuildChunk(chunk, 10, 20, "body-" + chunk, "published", 20L);
        }

        @Override
        public RebuildChunk readLocked(long chunk) {
            return read(chunk);
        }

        @Override
        public void lockDocument(long id) {
        }

        @Override
        public int updateVector(RebuildBatch b, RebuildBatch.Item i, float[] vector) {
            return validRun(b);
        }

        @Override
        public int finishItem(RebuildBatch b, RebuildBatch.Item i, boolean skipped) {
            successful.add(i.chunkId());
            return 1;
        }

        @Override
        public int completeBatch(RebuildBatch b, int succeeded, int skipped) {
            return 1;
        }
    }
}
