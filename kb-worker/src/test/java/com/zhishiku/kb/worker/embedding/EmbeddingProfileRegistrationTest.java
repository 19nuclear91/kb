package com.zhishiku.kb.worker.embedding;

import com.zhishiku.kb.common.embedding.EmbeddingClientRegistry;
import com.zhishiku.kb.common.embedding.EmbeddingConnection;
import com.zhishiku.kb.common.embedding.EmbeddingControl;
import com.zhishiku.kb.common.embedding.EmbeddingProfile;
import com.zhishiku.kb.common.embedding.EmbeddingProfileDefinition;
import com.zhishiku.kb.common.embedding.EmbeddingRole;
import com.zhishiku.kb.common.embedding.LocalProfileEmbeddingClient;
import com.zhishiku.kb.common.embedding.StoredEmbeddingProfile;
import com.zhishiku.kb.worker.embedding.mapper.EmbeddingStateMapper;
import com.zhishiku.kb.worker.embedding.service.EmbeddingProfileRegistration;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class EmbeddingProfileRegistrationTest {
    private final EmbeddingProfile profile = new EmbeddingProfile("local-v1", "local", "kb/local", "local-hash-v1",
            "1", 1024, "cosine", "identity-v1", "identity-v1", "query", "document", "local-hash-v1");
    private final EmbeddingStateMapper state = mock(EmbeddingStateMapper.class);
    private final EmbeddingClientRegistry clients = new EmbeddingClientRegistry(List.of(new EmbeddingProfileDefinition(
            profile, new EmbeddingConnection(null, null, 30, 16))), EmbeddingRole.DOCUMENT,
            definition -> new LocalProfileEmbeddingClient(definition, EmbeddingRole.DOCUMENT));

    @Test
    void workerOnlyReadsRegisteredProfilesAndNeverClaimsThem() {
        when(state.vectorDimension()).thenReturn(1024);
        when(state.profile(profile.profileId())).thenReturn(new StoredEmbeddingProfile(profile.profileId(),
                profile.fingerprint(), 1024));
        when(state.control()).thenReturn(new EmbeddingControl("UNINITIALIZED", null, null, null, null, 0, null));
        new EmbeddingProfileRegistration(state, clients).afterPropertiesSet();
        verify(state).vectorDimension();
        verify(state).profile(profile.profileId());
        verify(state).control();
        verifyNoMoreInteractions(state);
    }

    @Test
    void missingConflictingOrWrongDimensionProfilesAreRejected() {
        when(state.vectorDimension()).thenReturn(1024);
        assertThatThrownBy(() -> new EmbeddingProfileRegistration(state, clients).afterPropertiesSet())
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("registered by kb-app");
        when(state.profile(profile.profileId())).thenReturn(new StoredEmbeddingProfile(profile.profileId(), "other",
                1024));
        assertThatThrownBy(() -> new EmbeddingProfileRegistration(state, clients).afterPropertiesSet())
                .isInstanceOf(IllegalStateException.class);
        when(state.vectorDimension()).thenReturn(768);
        assertThatThrownBy(() -> new EmbeddingProfileRegistration(state, clients).afterPropertiesSet())
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("dimension");
    }
}
