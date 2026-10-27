package com.zhishiku.kb.worker.embedding.service;

import com.zhishiku.kb.worker.embedding.mapper.EmbeddingStateMapper;
import com.zhishiku.kb.worker.embedding.model.RebuildBatch;
import com.zhishiku.kb.worker.embedding.model.RebuildLeaseLostException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Set;

@Service
public class EmbeddingFailureService {
    private final EmbeddingStateMapper state;
    private final EmbeddingLeaseService leases;
    private final EmbeddingFailureStore failures;

    public EmbeddingFailureService(EmbeddingStateMapper state, EmbeddingLeaseService leases,
                                   EmbeddingFailureStore failures) {
        this.state = state;
        this.leases = leases;
        this.failures = failures;
    }

    @Transactional(timeout = 10)
    public boolean block(RebuildBatch batch, String error) {
        return handle(batch, error, false, 1);
    }

    @Transactional(timeout = 10)
    public boolean handle(RebuildBatch batch, String error, boolean retryable, int maxAttempts) {
        if (!Set.of("CONTENT_CHANGED", "DATABASE_ERROR", "INVALID_RESPONSE", "MODEL_UNAVAILABLE", "RETRY_EXHAUSTED")
                .contains(error)) throw new IllegalArgumentException("invalid embedding failure category");
        if (!EmbeddingLeaseService.matches(state.lockExclusive(), batch) || !leases.renew(batch)) return false;
        var run = state.run(batch.runId());
        if (run == null || !"REBUILDING".equals(run.state())) throw new RebuildLeaseLostException();
        int attempt = batch.items().stream().mapToInt(RebuildBatch.Item::attempts).max().orElse(1);
        if (retryable && attempt < maxAttempts) {
            int delay = EmbeddingRetryPolicy.delaySeconds(attempt);
            if (failures.retryItems(batch, error, delay) != batch.items().size()) throw new RebuildLeaseLostException();
            requireOne(failures.retryRun(batch, error, delay));
            return true;
        }
        if (retryable) error = "RETRY_EXHAUSTED";
        if (failures.failItems(batch, error) != batch.items().size()) throw new RebuildLeaseLostException();
        requireOne(failures.blockRun(batch, run.version(), error));
        requireOne(failures.blockControl(batch));
        requireOne(failures.blockEvent(batch, run.version(), error));
        return true;
    }

    private static void requireOne(int count) {
        if (count != 1) throw new RebuildLeaseLostException();
    }
}
