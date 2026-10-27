package com.zhishiku.kb.worker.embedding.service;

import com.zhishiku.kb.common.embedding.EmbeddingDigest;
import com.zhishiku.kb.worker.embedding.mapper.EmbeddingStateMapper;
import com.zhishiku.kb.worker.embedding.mapper.EmbeddingTargetMapper;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class EmbeddingTargetService {
    private final EmbeddingStateMapper state;
    private final EmbeddingTargetMapper targets;

    public EmbeddingTargetService(EmbeddingStateMapper state, EmbeddingTargetMapper targets) {
        this.state = state;
        this.targets = targets;
    }

    @Scheduled(fixedDelayString = "${kb.embedding.target-interval-ms:2000}")
    @Transactional(timeout = 10)
    public void preparePage() {
        var control = state.lockExclusive();
        if (control == null) throw new IllegalStateException("embedding control unavailable");
        if (!"REBUILDING".equals(control.state())) return;
        var run = state.run(control.runId());
        if (run == null || !run.state().equals(control.state()))
            throw new IllegalStateException("embedding run mismatch");
        Boolean ready = targets.lockReady(run.runId());
        if (ready == null) throw new IllegalStateException("embedding run unavailable");
        if (ready) return;
        if (targets.missingChunks()) {
            requireOne(targets.blockRun(run.runId(), run.version()));
            requireOne(targets.blockControl(run.runId(), control.epoch()));
            String requestId = run.runId() + ":" + Math.addExact(run.version(), 1);
            requireOne(targets.blockEvent(UUID.randomUUID(), requestId, EmbeddingDigest.text(requestId),
                    run.runId(), Math.addExact(control.epoch(), 1)));
            return;
        }
        var page = targets.page(run.runId(), 100);
        for (var target : page) {
            int inserted = targets.insert(run.runId(), target, EmbeddingDigest.text(target.content()));
            if (inserted < 0 || inserted > 1) throw new IllegalStateException("invalid embedding item insert count");
        }
        // 再读到空页才封存清单；不从最后一页长度推断覆盖，重试仅跳过已登记项。
        requireOne(targets.progress(run.runId(), page.isEmpty()));
    }

    private void requireOne(int count) {
        if (count != 1) throw new IllegalStateException("embedding target state conflict");
    }
}
