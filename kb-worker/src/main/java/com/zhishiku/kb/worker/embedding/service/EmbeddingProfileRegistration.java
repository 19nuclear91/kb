package com.zhishiku.kb.worker.embedding.service;

import com.zhishiku.kb.common.embedding.EmbeddingClientRegistry;
import com.zhishiku.kb.common.embedding.EmbeddingProfile;
import com.zhishiku.kb.worker.embedding.mapper.EmbeddingStateMapper;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.stereotype.Service;

@Service
public class EmbeddingProfileRegistration implements InitializingBean {
    private final EmbeddingStateMapper state;
    private final EmbeddingClientRegistry clients;

    public EmbeddingProfileRegistration(EmbeddingStateMapper state, EmbeddingClientRegistry clients) {
        this.state = state;
        this.clients = clients;
    }

    @Override
    public void afterPropertiesSet() {
        if (!Integer.valueOf(EmbeddingProfile.DIMENSION).equals(state.vectorDimension())) {
            throw new IllegalStateException("embedding storage dimension mismatch");
        }
        for (var client : clients.clients()) {
            var existing = state.profile(client.profile().profileId());
            if (existing == null) throw new IllegalStateException("embedding profile must be registered by kb-app");
            existing.require(client.profile());
        }
        var control = state.control();
        if (control == null || (control.active()
                && clients.find(control.activeProfileId(), control.activeFingerprint()).isEmpty())) {
            throw new IllegalStateException("active embedding profile unavailable");
        }
    }
}
