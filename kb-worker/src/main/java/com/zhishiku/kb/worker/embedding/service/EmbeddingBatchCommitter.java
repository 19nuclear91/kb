package com.zhishiku.kb.worker.embedding.service;

import com.zhishiku.kb.common.embedding.EmbeddingDigest;
import com.zhishiku.kb.worker.embedding.model.GeneratedRebuildBatch;
import com.zhishiku.kb.worker.embedding.model.RebuildBatch;
import com.zhishiku.kb.worker.embedding.model.RebuildContentChangedException;
import com.zhishiku.kb.worker.embedding.model.RebuildLeaseLostException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class EmbeddingBatchCommitter {
    private final EmbeddingLeaseService leases;
    private final EmbeddingChunkStore chunks;

    public EmbeddingBatchCommitter(EmbeddingLeaseService leases, EmbeddingChunkStore chunks) {
        this.leases = leases;
        this.chunks = chunks;
    }

    @Transactional(timeout = 10)
    public void commit(GeneratedRebuildBatch generated) {
        var batch = generated.batch();
        if (!leases.renew(batch)) throw new RebuildLeaseLostException();
        batch.items().stream().map(RebuildBatch.Item::docId).distinct().sorted().forEach(chunks::lockDocument);
        int vectorIndex = 0;
        int succeeded = 0;
        int skipped = 0;
        for (var input : generated.inputs()) {
            var item = input.item();
            var current = chunks.readLocked(item.chunkId());
            float[] vector = input.generated() ? generated.embeddings().vector(vectorIndex++) : null;
            boolean outside = current == null || !current.inScope(item);
            if (!outside) {
                if (!input.generated() || !input.digest().equals(EmbeddingDigest.text(current.content()))) {
                    throw new RebuildContentChangedException();
                }
                requireOne(chunks.updateVector(batch, item, vector));
                succeeded++;
            } else {
                skipped++;
            }
            requireOne(chunks.finishItem(batch, item, outside));
        }
        requireOne(chunks.completeBatch(batch, succeeded, skipped));
    }

    private static void requireOne(int changed) {
        if (changed != 1) throw new RebuildLeaseLostException();
    }
}
