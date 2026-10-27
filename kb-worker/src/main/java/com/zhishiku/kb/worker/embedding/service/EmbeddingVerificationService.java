package com.zhishiku.kb.worker.embedding.service;

import com.zhishiku.kb.common.embedding.EmbeddingControl;
import com.zhishiku.kb.worker.embedding.mapper.EmbeddingStateMapper;
import com.zhishiku.kb.worker.embedding.model.RebuildLeaseLostException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class EmbeddingVerificationService {
    private final EmbeddingStateMapper state;
    private final EmbeddingVerificationStore store;

    public EmbeddingVerificationService(EmbeddingStateMapper state, EmbeddingVerificationStore store) {
        this.state = state;
        this.store = store;
    }

    @Transactional(timeout = 10)
    public EmbeddingControl begin() {
        store.timeouts();
        var control = state.lockExclusive();
        if (control == null) throw new IllegalStateException("embedding control unavailable");
        if ("VERIFYING".equals(control.state())) return control;
        if (!"REBUILDING".equals(control.state())) return null;
        var run = state.run(control.runId());
        if (run == null || !run.state().equals(control.state())) throw new RebuildLeaseLostException();
        if (!store.targetsReady(run.runId()) || store.unfinished(run.runId()) || store.executionOutstanding(run.runId()))
            return null;
        requireOne(store.beginRun(run));
        requireOne(store.beginControl(control));
        return new EmbeddingControl("VERIFYING", control.activeProfileId(), control.activeFingerprint(),
                control.targetProfileId(), control.targetFingerprint(), Math.addExact(control.epoch(), 1), run.runId());
    }

    @Transactional(timeout = 10)
    public List<EmbeddingVerificationStore.Chunk> page(EmbeddingControl expected, long after) {
        store.timeouts();
        if (!matches(state.lockShared(), expected)) throw new RebuildLeaseLostException();
        return store.page(expected.runId(), expected.targetProfileId(), after);
    }

    @Transactional(timeout = 10)
    public String check(EmbeddingControl expected) {
        store.timeouts();
        if (!matches(state.lockShared(), expected)) throw new RebuildLeaseLostException();
        return coverageError(expected);
    }

    public String coverageError(EmbeddingControl control) {
        if (store.missingChunks()) return "MISSING_CHUNKS";
        if (!store.targetsReady(control.runId()) || store.unfinished(control.runId())
                || store.executionOutstanding(control.runId())
                || store.invalidCoverage(control.runId(), control.targetProfileId())) return "COVERAGE_INVALID";
        return null;
    }

    public static boolean matches(EmbeddingControl current, EmbeddingControl expected) {
        if (current == null) throw new IllegalStateException("embedding control unavailable");
        return "VERIFYING".equals(current.state()) && current.equals(expected);
    }

    private static void requireOne(int count) {
        if (count != 1) throw new RebuildLeaseLostException();
    }
}
