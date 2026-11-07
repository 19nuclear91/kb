package com.zhishiku.kb.worker.embedding.service;

import com.zhishiku.kb.common.embedding.CallBudget;
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
        return generate(batch, null);
    }

    @Transactional(propagation = Propagation.NEVER)
    public GeneratedRebuildBatch generate(RebuildBatch batch, CallBudget budget) {
        check(budget);
        var snapshots = reader.read(batch);
        check(budget);
        var inputs = new ArrayList<GeneratedRebuildBatch.Input>();
        var texts = new ArrayList<String>();
        for (int index = 0; index < batch.items().size(); index++) {
            check(budget);
            var item = batch.items().get(index);
            var chunk = snapshots.get(index);
            String digest = null;
            if (chunk != null && chunk.inScope(item)) {
                digest = EmbeddingDigest.text(chunk.content());
                if (!digest.equals(item.contentDigest())) {
                    throw new RebuildContentChangedException();
                }
                texts.add(chunk.content());
            }
            inputs.add(new GeneratedRebuildBatch.Input(item, digest));
        }
        EmbeddingResult result;
        if (texts.isEmpty()) {
            result = new EmbeddingResult(batch.profile(), EmbeddingRole.DOCUMENT, List.of());
        } else {
            var client = clients.find(batch.profile().profileId(), batch.profile().fingerprint())
                    .filter(candidate -> candidate.role() == EmbeddingRole.DOCUMENT)
                    .orElseThrow(() -> new IllegalStateException("embedding document client unavailable"));
            result = budget == null ? client.embed(List.copyOf(texts)) : client.embed(List.copyOf(texts), budget);
        }
        var generated = new GeneratedRebuildBatch(batch, inputs, result);
        check(budget);
        return generated;
    }

    private static void check(CallBudget budget) {
        if (budget != null) {
            budget.check();
        }
    }
}
