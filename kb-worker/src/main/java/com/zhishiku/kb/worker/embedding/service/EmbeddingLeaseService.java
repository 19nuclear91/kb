package com.zhishiku.kb.worker.embedding.service;

import com.zhishiku.kb.common.embedding.EmbeddingClientRegistry;
import com.zhishiku.kb.common.embedding.EmbeddingControl;
import com.zhishiku.kb.common.embedding.EmbeddingRole;
import com.zhishiku.kb.worker.embedding.EmbeddingRebuildProperties;
import com.zhishiku.kb.worker.embedding.mapper.EmbeddingLeaseMapper;
import com.zhishiku.kb.worker.embedding.mapper.EmbeddingStateMapper;
import com.zhishiku.kb.worker.embedding.model.RebuildBatch;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class EmbeddingLeaseService {
    private final EmbeddingStateMapper state;
    private final EmbeddingLeaseMapper leases;
    private final EmbeddingClientRegistry clients;
    private final EmbeddingRebuildProperties props;

    public EmbeddingLeaseService(EmbeddingStateMapper state, EmbeddingLeaseMapper leases,
                                 EmbeddingClientRegistry clients, EmbeddingRebuildProperties props) {
        this.state = state;
        this.leases = leases;
        this.clients = clients;
        this.props = props;
    }

    @Transactional(timeout = 10)
    public RebuildBatch claim(UUID owner) {
        if (owner == null) throw new IllegalArgumentException("embedding owner required");
        var control = state.lockShared();
        if (control == null) throw new IllegalStateException("embedding control unavailable");
        if (!"REBUILDING".equals(control.state())) return null;
        var client = clients.find(control.targetProfileId(), control.targetFingerprint());
        if (client.isEmpty() || client.get().role() != EmbeddingRole.DOCUMENT) return null;
        var token = leases.claimRun(control.runId(), owner, props.leaseSeconds());
        if (token == null) return null;
        if (token <= 0) throw new IllegalStateException("invalid embedding run token");
        var items = leases.claimItems(control.runId(), token, owner, props.leaseSeconds(), props.batchSize());
        return new RebuildBatch(control.runId(), control.epoch(), token, owner, client.get().profile(), items);
    }

    @Transactional(timeout = 10)
    public boolean renew(RebuildBatch batch) {
        if (!matches(state.lockShared(), batch)) return false;
        if (leases.renewRun(batch, props.leaseSeconds()) != 1) return false;
        for (var item : batch.items()) {
            if (leases.renewItem(batch, item, props.leaseSeconds()) != 1) {
                throw new IllegalStateException("embedding item lease lost");
            }
        }
        return true;
    }

    @Transactional(timeout = 10)
    public boolean release(RebuildBatch batch) {
        return matches(state.lockShared(), batch) && leases.release(batch) == 1;
    }

    public static boolean matches(EmbeddingControl control, RebuildBatch batch) {
        if (control == null) throw new IllegalStateException("embedding control unavailable");
        return "REBUILDING".equals(control.state()) && batch.runId().equals(control.runId())
                && batch.epoch() == control.epoch()
                && batch.profile().hasIdentity(control.targetProfileId(), control.targetFingerprint());
    }
}
