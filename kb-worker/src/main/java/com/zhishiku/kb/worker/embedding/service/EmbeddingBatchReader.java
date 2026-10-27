package com.zhishiku.kb.worker.embedding.service;

import com.zhishiku.kb.worker.embedding.model.RebuildBatch;
import com.zhishiku.kb.worker.embedding.model.RebuildChunk;
import com.zhishiku.kb.worker.embedding.model.RebuildLeaseLostException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@Service
public class EmbeddingBatchReader {
    private final EmbeddingLeaseService leases;
    private final EmbeddingChunkStore chunks;

    public EmbeddingBatchReader(EmbeddingLeaseService leases, EmbeddingChunkStore chunks) {
        this.leases = leases;
        this.chunks = chunks;
    }

    @Transactional(timeout = 10)
    public List<RebuildChunk> read(RebuildBatch batch) {
        if (!leases.renew(batch)) throw new RebuildLeaseLostException();
        var result = new ArrayList<RebuildChunk>();
        for (var item : batch.items()) result.add(chunks.read(item.chunkId()));
        return Collections.unmodifiableList(result);
    }
}
