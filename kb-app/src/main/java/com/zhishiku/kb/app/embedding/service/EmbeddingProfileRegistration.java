package com.zhishiku.kb.app.embedding.service;

import com.zhishiku.kb.app.embedding.mapper.EmbeddingProfileMapper;
import com.zhishiku.kb.app.embedding.mapper.EmbeddingStateMapper;
import com.zhishiku.kb.common.embedding.EmbeddingClientRegistry;
import com.zhishiku.kb.common.embedding.EmbeddingProfile;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import org.springframework.stereotype.Service;

@Service
@DependsOnDatabaseInitialization
public class EmbeddingProfileRegistration implements InitializingBean {
    private final EmbeddingProfileMapper profiles;
    private final EmbeddingStateMapper state;
    private final EmbeddingClientRegistry clients;

    public EmbeddingProfileRegistration(EmbeddingProfileMapper profiles, EmbeddingStateMapper state,
                                        EmbeddingClientRegistry clients) {
        this.profiles = profiles;
        this.state = state;
        this.clients = clients;
    }

    @Override
    public void afterPropertiesSet() {
        if (!Integer.valueOf(EmbeddingProfile.DIMENSION).equals(state.vectorDimension())) {
            throw new IllegalStateException("embedding storage dimension mismatch");
        }
        for (var client : clients.clients()) {
            var profile = client.profile();
            var existing = state.profile(profile.profileId());
            if (existing == null) {
                // INSERT 的唯一键冲突不覆盖既有定义，语句结束后重读赢家。
                profiles.register(profile, profile.fingerprint());
                existing = state.profile(profile.profileId());
            }
            if (existing == null) throw new IllegalStateException("embedding profile registration missing");
            existing.require(profile);
        }
        var control = state.control();
        if (control == null || (control.active()
                && clients.find(control.activeProfileId(), control.activeFingerprint()).isEmpty())) {
            throw new IllegalStateException("active embedding profile unavailable");
        }
    }
}
