package com.zhishiku.kb.app.embedding.service;

import com.zhishiku.kb.app.embedding.mapper.EmbeddingControlMapper;
import com.zhishiku.kb.app.embedding.mapper.EmbeddingStateMapper;
import com.zhishiku.kb.common.embedding.EmbeddingControl;
import com.zhishiku.kb.common.embedding.EmbeddingRun;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.ConcurrentModificationException;
import java.util.UUID;

@Service
public class EmbeddingControlService {
    private final EmbeddingStateMapper state;
    private final EmbeddingControlMapper commands;

    public EmbeddingControlService(EmbeddingStateMapper state, EmbeddingControlMapper commands) {
        this.state = state;
        this.commands = commands;
    }

    public EmbeddingControl current() {
        EmbeddingControl control = state.control();
        if (control == null) throw new IllegalStateException("embedding control is missing");
        return control;
    }

    public EmbeddingRun run(UUID runId) {
        if (runId == null) throw new IllegalArgumentException("runId is required");
        return state.run(runId);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public EmbeddingControl lockShared() {
        var control = state.lockShared();
        if (control == null) throw new IllegalStateException("embedding control is missing");
        return control;
    }

    @Transactional(timeout = 10)
    public void advancePhase(UUID runId, long expectedEpoch, long expectedVersion, String nextState) {
        var control = state.lockExclusive();
        if (control == null) throw new IllegalStateException("embedding control is missing");
        var run = state.run(runId);
        if (runId == null || !runId.equals(control.runId()) || control.epoch() != expectedEpoch
                || run == null || run.version() != expectedVersion || !run.state().equals(control.state())) {
            throw conflict();
        }
        boolean allowed = ("QUIESCING".equals(control.state()) && "REBUILDING".equals(nextState))
                || ("REBUILDING".equals(control.state()) && "VERIFYING".equals(nextState));
        if (!allowed) throw new IllegalArgumentException("embedding phase transition not allowed");
        if (commands.advance(runId, control.state(), expectedEpoch, nextState) != 1
                || commands.advanceRun(runId, control.state(), expectedVersion, nextState) != 1) {
            throw conflict();
        }
    }

    private static ConcurrentModificationException conflict() {
        return new ConcurrentModificationException("embedding control version conflict");
    }
}
