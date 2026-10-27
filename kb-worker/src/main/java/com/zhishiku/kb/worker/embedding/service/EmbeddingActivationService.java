package com.zhishiku.kb.worker.embedding.service;

import com.zhishiku.kb.common.embedding.EmbeddingControl;
import com.zhishiku.kb.common.embedding.EmbeddingRole;
import com.zhishiku.kb.common.embedding.EmbeddingRun;
import com.zhishiku.kb.worker.embedding.mapper.EmbeddingReadinessMapper;
import com.zhishiku.kb.worker.embedding.mapper.EmbeddingStateMapper;
import com.zhishiku.kb.worker.embedding.model.RebuildLeaseLostException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.Set;

@Service
public class EmbeddingActivationService {
    private final EmbeddingStateMapper state;
    private final EmbeddingVerificationStore store;
    private final EmbeddingVerificationService verification;
    private final EmbeddingReadinessMapper readiness;
    private final Clock clock;

    @Autowired
    public EmbeddingActivationService(EmbeddingStateMapper state, EmbeddingVerificationStore store,
                                      EmbeddingVerificationService verification, EmbeddingReadinessMapper readiness) {
        this(state, store, verification, readiness, Clock.systemUTC());
    }

    public EmbeddingActivationService(EmbeddingStateMapper state, EmbeddingVerificationStore store,
                                      EmbeddingVerificationService verification, EmbeddingReadinessMapper readiness,
                                      Clock clock) {
        this.state = state;
        this.store = store;
        this.verification = verification;
        this.readiness = readiness;
        this.clock = clock;
    }

    @Transactional(timeout = 10)
    public boolean activate(EmbeddingControl expected) {
        store.timeouts();
        var current = state.lockExclusive();
        if (!EmbeddingVerificationService.matches(current, expected)) return false;
        var run = state.run(current.runId());
        if (run == null || !"VERIFYING".equals(run.state())) throw new RebuildLeaseLostException();
        String error = verification.coverageError(current);
        if (error != null) return blockLocked(current, run, error);
        // 激活依据持久目标和集群探测，不以协调实例是否配置模型判定全局失败。
        var rows = readiness.findFresh(current.targetProfileId());
        var now = clock.instant();
        if (rows.stream().noneMatch(row -> row.usable(current.targetProfileId(), current.targetFingerprint(),
                EmbeddingRole.QUERY, now))
                || rows.stream().noneMatch(row -> row.usable(current.targetProfileId(), current.targetFingerprint(),
                EmbeddingRole.DOCUMENT, now))) {
            return blockLocked(current, run, "READINESS_EXPIRED");
        }
        requireOne(store.activateRun(run));
        requireOne(store.activateControl(current));
        requireOne(store.event(current, run, "SUCCEEDED", null));
        return true;
    }

    @Transactional(timeout = 10)
    public boolean block(EmbeddingControl expected, String error) {
        if (!Set.of("COVERAGE_INVALID", "MISSING_CHUNKS", "READINESS_EXPIRED", "DATABASE_ERROR").contains(error)) {
            throw new IllegalArgumentException("invalid embedding verification failure");
        }
        store.timeouts();
        var current = state.lockExclusive();
        if (!EmbeddingVerificationService.matches(current, expected)) return false;
        var run = state.run(current.runId());
        if (run == null || !"VERIFYING".equals(run.state())) throw new RebuildLeaseLostException();
        return blockLocked(current, run, error);
    }

    private boolean blockLocked(EmbeddingControl current, EmbeddingRun run, String error) {
        requireOne(store.blockRun(run, error));
        requireOne(store.blockControl(current));
        requireOne(store.event(current, run, "BLOCKED", error));
        return false;
    }

    private static void requireOne(int count) {
        if (count != 1) throw new RebuildLeaseLostException();
    }
}
