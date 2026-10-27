package com.zhishiku.kb.worker.embedding.service;

import com.zhishiku.kb.common.embedding.EmbeddingClientRegistry;
import com.zhishiku.kb.common.embedding.EmbeddingDigest;
import com.zhishiku.kb.common.embedding.EmbeddingResult;
import com.zhishiku.kb.common.embedding.EmbeddingRole;
import com.zhishiku.kb.worker.embedding.model.GeneratedRebuildBatch;
import com.zhishiku.kb.worker.embedding.model.RebuildBatch;
import com.zhishiku.kb.worker.embedding.model.RebuildContentChangedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

@Service
public class EmbeddingBatchGenerator {
    private final EmbeddingBatchReader reader;
    private final EmbeddingClientRegistry clients;

    public EmbeddingBatchGenerator(EmbeddingBatchReader reader, EmbeddingClientRegistry clients) {
        this.reader = reader;
        this.clients = clients;
    }

    @Transactional(propagation = Propagation.NEVER)
    public GeneratedRebuildBatch generate(RebuildBatch batch) {
        var snapshots = reader.read(batch);
        var inputs = new ArrayList<GeneratedRebuildBatch.Input>();
        var texts = new ArrayList<String>();
        for (int index = 0; index < batch.items().size(); index++) {
            var item = batch.items().get(index);
            var chunk = snapshots.get(index);
            String digest = null;
            if (chunk != null && chunk.inScope(item)) {
                digest = EmbeddingDigest.text(chunk.content());
                if (!digest.equals(item.contentDigest())) throw new RebuildContentChangedException();
                texts.add(chunk.content());
            }
            inputs.add(new GeneratedRebuildBatch.Input(item, digest));
        }
        var result = texts.isEmpty() ? new EmbeddingResult(batch.profile(), EmbeddingRole.DOCUMENT, List.of())
                : clients.find(batch.profile().profileId(), batch.profile().fingerprint())
                .filter(client -> client.role() == EmbeddingRole.DOCUMENT)
                .orElseThrow(() -> new IllegalStateException("embedding document client unavailable"))
                .embed(List.copyOf(texts));
        return new GeneratedRebuildBatch(batch, inputs, result);
    }
}
