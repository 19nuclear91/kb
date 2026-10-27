package com.zhishiku.kb.app.embedding;

import com.zhishiku.kb.app.embedding.mapper.EmbeddingProfileMapper;
import com.zhishiku.kb.app.embedding.mapper.EmbeddingStateMapper;
import com.zhishiku.kb.app.embedding.service.EmbeddingProfileRegistration;
import com.zhishiku.kb.common.embedding.EmbeddingClientRegistry;
import com.zhishiku.kb.common.embedding.EmbeddingConnection;
import com.zhishiku.kb.common.embedding.EmbeddingControl;
import com.zhishiku.kb.common.embedding.EmbeddingProfile;
import com.zhishiku.kb.common.embedding.EmbeddingProfileDefinition;
import com.zhishiku.kb.common.embedding.EmbeddingRole;
import com.zhishiku.kb.common.embedding.LocalProfileEmbeddingClient;
import com.zhishiku.kb.common.embedding.StoredEmbeddingProfile;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class EmbeddingProfileRegistrationTest {
    private final EmbeddingProfile profile = new EmbeddingProfile("local-v1", "local", "kb/local", "local-hash-v1",
            "1", 1024, "cosine", "identity-v1", "identity-v1", "query", "document", "local-hash-v1");
    private final EmbeddingStateMapper state = mock(EmbeddingStateMapper.class);
    private final EmbeddingProfileMapper profiles = mock(EmbeddingProfileMapper.class);
    private final EmbeddingClientRegistry clients = new EmbeddingClientRegistry(List.of(new EmbeddingProfileDefinition(
            profile, new EmbeddingConnection(null, null, 30, 16))), EmbeddingRole.QUERY,
            definition -> new LocalProfileEmbeddingClient(definition, EmbeddingRole.QUERY));

    @Test
    void registersMissingIdentityAndRereadsAfterConcurrentInsert() {
        when(state.vectorDimension()).thenReturn(1024);
        when(state.control()).thenReturn(new EmbeddingControl("UNINITIALIZED", null, null, null, null, 0, null));
        when(state.profile(profile.profileId())).thenReturn(null,
                new StoredEmbeddingProfile(profile.profileId(), profile.fingerprint(), 1024));
        when(profiles.register(profile, profile.fingerprint())).thenReturn(0);
        new EmbeddingProfileRegistration(profiles, state, clients).afterPropertiesSet();
        var order = inOrder(state, profiles);
        order.verify(state).vectorDimension();
        order.verify(state).profile(profile.profileId());
        order.verify(profiles).register(profile, profile.fingerprint());
        order.verify(state).profile(profile.profileId());
        order.verify(state).control();
        verifyNoMoreInteractions(profiles, state);
    }

    @Test
    void existingIdentityIsNotUpdatedAndConflictsAreRejected() {
        when(state.vectorDimension()).thenReturn(1024);
        when(state.control()).thenReturn(new EmbeddingControl("UNINITIALIZED", null, null, null, null, 0, null));
        when(state.profile(profile.profileId())).thenReturn(new StoredEmbeddingProfile(profile.profileId(),
                profile.fingerprint(), 1024));
        new EmbeddingProfileRegistration(profiles, state, clients).afterPropertiesSet();
        verifyNoInteractions(profiles);
        when(state.profile(profile.profileId())).thenReturn(new StoredEmbeddingProfile(profile.profileId(), "other",
                1024));
        assertThatThrownBy(() -> new EmbeddingProfileRegistration(profiles, state, clients).afterPropertiesSet())
                .isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(profiles);
    }

    @Test
    void dimensionMismatchAndConfigurationBehindActiveProfilePreventStartup() {
        when(state.vectorDimension()).thenReturn(768);
        assertThatThrownBy(() -> new EmbeddingProfileRegistration(profiles, state, clients).afterPropertiesSet())
                .isInstanceOf(IllegalStateException.class);
        when(state.vectorDimension()).thenReturn(1024);
        when(state.profile(profile.profileId())).thenReturn(new StoredEmbeddingProfile(profile.profileId(),
                profile.fingerprint(), 1024));
        when(state.control()).thenReturn(new EmbeddingControl("ACTIVE", "missing", "fingerprint", null, null, 7, null));
        assertThatThrownBy(() -> new EmbeddingProfileRegistration(profiles, state, clients).afterPropertiesSet())
                .isInstanceOf(IllegalStateException.class).hasMessage("active embedding profile unavailable");
    }

    @Test
    void repeatedStartupReadsUninitializedFactsWithoutClaimingLegacyNullIdentity() {
        var stored = new AtomicReference<>(
                new EmbeddingControl("UNINITIALIZED", null, null, null, null, 0, null));
        when(state.vectorDimension()).thenReturn(1024);
        when(state.profile(profile.profileId())).thenReturn(new StoredEmbeddingProfile(profile.profileId(),
                profile.fingerprint(), 1024));
        when(state.control()).thenAnswer(invocation -> stored.get());
        new EmbeddingProfileRegistration(profiles, state, clients).afterPropertiesSet();
        new EmbeddingProfileRegistration(profiles, state, clients).afterPropertiesSet();
        assertThat(stored.get().activeProfileId()).isNull();
        assertThat(stored.get().state()).isEqualTo("UNINITIALIZED");
        verifyNoInteractions(profiles);
    }
}
