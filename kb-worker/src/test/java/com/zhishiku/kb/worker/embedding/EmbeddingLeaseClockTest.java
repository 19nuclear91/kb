package com.zhishiku.kb.worker.embedding;

import com.zhishiku.kb.common.embedding.EmbeddingClientRegistry;
import com.zhishiku.kb.common.embedding.EmbeddingConnection;
import com.zhishiku.kb.common.embedding.EmbeddingControl;
import com.zhishiku.kb.common.embedding.EmbeddingProfile;
import com.zhishiku.kb.common.embedding.EmbeddingProfileDefinition;
import com.zhishiku.kb.common.embedding.EmbeddingRole;
import com.zhishiku.kb.common.embedding.LocalProfileEmbeddingClient;
import com.zhishiku.kb.worker.embedding.mapper.EmbeddingLeaseMapper;
import com.zhishiku.kb.worker.embedding.mapper.EmbeddingStateMapper;
import com.zhishiku.kb.worker.embedding.model.RebuildBatch;
import com.zhishiku.kb.worker.embedding.service.EmbeddingLeaseService;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class EmbeddingLeaseClockTest {
    @Test
    void expiryTakeoverAndBackoffPreserveIndependentMonotonicTokens() {
        var id = UUID.randomUUID();
        var profile = new EmbeddingProfile("p", "local", "source", "local-hash-v1", "1", 1024,
                "cosine", "identity-v1", "identity-v1", "query", "document", "local-hash-v1");
        var clients = new EmbeddingClientRegistry(List.of(new EmbeddingProfileDefinition(profile,
                new EmbeddingConnection(null, null, 30, 16))), EmbeddingRole.DOCUMENT,
                d -> new LocalProfileEmbeddingClient(d, EmbeddingRole.DOCUMENT));
        var state = mock(EmbeddingStateMapper.class);
        when(state.lockShared()).thenReturn(new EmbeddingControl("REBUILDING", null, null, "p", profile.fingerprint()
                , 4, id));
        var store = new LeaseStore();
        var limits = new EmbeddingRebuildProperties(30, 16, 3);
        var service = new EmbeddingLeaseService(state, store, clients, limits);
        var owner = UUID.randomUUID();
        assertThat(service.claim(owner)).isNull();
        store.now = store.retryAt;
        var first = service.claim(owner);
        assertThat(first.runToken()).isEqualTo(11);
        assertThat(first.items().getFirst().claimToken()).isEqualTo(41);
        assertThat(service.claim(UUID.randomUUID())).isNull();
        store.now = store.now.plusSeconds(20);
        assertThat(service.renew(first)).isTrue();
        store.now = store.now.plusSeconds(30);
        assertThat(service.renew(first)).isFalse();
        assertThat(service.release(first)).isFalse();
        var restarted = new EmbeddingLeaseService(state, store, clients, limits);
        var next = restarted.claim(UUID.randomUUID());
        assertThat(next.runToken()).isEqualTo(12);
        assertThat(next.items().getFirst().claimToken()).isEqualTo(42);
        assertThat(next.items().getFirst().attempts()).isEqualTo(2);
        assertThat(service.renew(first)).isFalse();
        assertThat(service.release(first)).isFalse();
        assertThat(restarted.renew(next)).isTrue();
        assertThat(restarted.release(next)).isTrue();
    }

    @Test
    void executorLimitsRejectUnsafeValues() {
        assertThatThrownBy(() -> new EmbeddingRebuildProperties(0, 16, 3)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new EmbeddingRebuildProperties(300, 101, 3)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new EmbeddingRebuildProperties(300, 16, 0)).isInstanceOf(IllegalArgumentException.class);
    }

    // 仓储时间可控，仅模拟资格变化，不代表真实数据库租约验证。
    private static class LeaseStore implements EmbeddingLeaseMapper {
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        Instant retryAt = now.plusSeconds(10);
        Instant expires;
        UUID owner;
        long token = 10;
        long itemToken = 40;
        int attempts;

        public Long claimRun(UUID id, UUID nextOwner, int seconds) {
            if (now.isBefore(retryAt) || owner != null && now.isBefore(expires)) return null;
            owner = nextOwner;
            expires = now.plusSeconds(seconds);
            return ++token;
        }

        public List<RebuildBatch.Item> claimItems(UUID id, long runToken, UUID owner, int seconds, int limit) {
            return List.of(new RebuildBatch.Item(1, 2, 3, "digest", ++itemToken, ++attempts));
        }

        public int renewRun(RebuildBatch batch, int seconds) {
            if (validRun(batch) != 1) return 0;
            expires = now.plusSeconds(seconds);
            return 1;
        }

        public int renewItem(RebuildBatch batch, RebuildBatch.Item item, int seconds) {
            return validRun(batch) == 1 && item.claimToken() == itemToken ? 1 : 0;
        }

        public int validRun(RebuildBatch batch) {
            return batch.owner().equals(owner) && batch.runToken() == token && now.isBefore(expires) ? 1 : 0;
        }

        public int release(RebuildBatch batch) {
            if (validRun(batch) != 1) return 0;
            owner = null;
            return 1;
        }
    }
}
