package com.zhishiku.kb.app.embedding;

import com.zhishiku.kb.app.embedding.mapper.EmbeddingReadinessMapper;
import com.zhishiku.kb.app.embedding.service.EmbeddingReadinessService;
import com.zhishiku.kb.common.embedding.EmbeddingProfile;
import com.zhishiku.kb.common.embedding.EmbeddingReadiness;
import com.zhishiku.kb.common.embedding.EmbeddingRole;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class EmbeddingReadinessServiceTest {
    @Test
    void requiresBothFreshCompatibleRolesAndPropagatesStoreFailure() {
        var profile = new EmbeddingProfile("p", "remote", "s", "m", "r", 1024, "cosine",
                "identity-v1", "identity-v1", "query", "document", "embeddings-v1");
        var mapper = mock(EmbeddingReadinessMapper.class);
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        var query = new EmbeddingReadiness(UUID.randomUUID(), EmbeddingRole.QUERY, "p", profile.fingerprint(),
                1, true, now.minusSeconds(30), now.plusSeconds(30));
        var document = new EmbeddingReadiness(UUID.randomUUID(), EmbeddingRole.DOCUMENT, "p", profile.fingerprint(),
                1, true, now.minusSeconds(30), now.plusSeconds(30));
        var service = new EmbeddingReadinessService(mapper, Clock.fixed(now, ZoneOffset.UTC));
        when(mapper.findFresh("p")).thenReturn(List.of(query));
        assertThat(service.ready(profile)).isFalse();
        when(mapper.findFresh("p")).thenReturn(List.of(query, document));
        assertThat(service.ready(profile)).isTrue();
        assertThat(new EmbeddingReadinessService(mapper, Clock.fixed(now.plusSeconds(30), ZoneOffset.UTC)).ready(profile)).isFalse();
        when(mapper.findFresh("p")).thenReturn(List.of(query, new EmbeddingReadiness(document.instanceId(),
                document.role(),
                "p", "other", 1, true, now, now.plusSeconds(30))));
        assertThat(service.ready(profile)).isFalse();
        when(mapper.findFresh("p")).thenThrow(new IllegalStateException("store unavailable"));
        assertThatThrownBy(() -> service.ready(profile)).hasMessage("store unavailable");
    }
}
