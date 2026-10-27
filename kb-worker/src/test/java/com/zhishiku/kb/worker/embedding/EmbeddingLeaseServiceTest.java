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
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyInt;
import static org.mockito.Mockito.anyLong;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class EmbeddingLeaseServiceTest {
    private final UUID id = UUID.randomUUID();
    private final UUID owner = UUID.randomUUID();
    private final EmbeddingProfile p = new EmbeddingProfile("p", "local", "source", "local-hash-v1", "1", 1024,
            "cosine", "identity-v1", "identity-v1", "query", "document", "local-hash-v1");
    private final EmbeddingStateMapper state = mock(EmbeddingStateMapper.class);
    private final EmbeddingLeaseMapper mapper = mock(EmbeddingLeaseMapper.class);
    private final EmbeddingClientRegistry clients =
            new EmbeddingClientRegistry(List.of(new EmbeddingProfileDefinition(p,
            new EmbeddingConnection(null, null, 30, 16))), EmbeddingRole.DOCUMENT,
            d -> new LocalProfileEmbeddingClient(d, EmbeddingRole.DOCUMENT));
    private final EmbeddingLeaseService service = new EmbeddingLeaseService(state, mapper, clients,
            new EmbeddingRebuildProperties(300, 16, 3));

    private void ready() {
        when(state.lockShared()).thenReturn(new EmbeddingControl("REBUILDING", null, null, "p", p.fingerprint(), 4,
                id));
    }

    @Test
    void claimBindsEpochRunTokenAndIndependentItemTokens() {
        ready();
        when(mapper.claimRun(id, owner, 300)).thenReturn(9L);
        var item = new RebuildBatch.Item(1, 2, 3, "digest", 7, 1);
        when(mapper.claimItems(id, 9, owner, 300, 16)).thenReturn(List.of(item));
        var batch = service.claim(owner);
        assertThat(batch.epoch()).isEqualTo(4);
        assertThat(batch.runToken()).isEqualTo(9);
        assertThat(batch.items()).containsExactly(item);
        var order = inOrder(state, mapper);
        order.verify(state).lockShared();
        order.verify(mapper).claimRun(id, owner, 300);
        order.verify(mapper).claimItems(id, 9, owner, 300, 16);
    }

    @Test
    void missingClientOrBusyRunDoesNotClaimItems() {
        ready();
        when(mapper.claimRun(id, owner, 300)).thenReturn(null);
        assertThat(service.claim(owner)).isNull();
        when(state.lockShared()).thenReturn(new EmbeddingControl("REBUILDING", null, null, "unknown", "fp", 4, id));
        assertThat(service.claim(owner)).isNull();
        verify(mapper, never()).claimItems(any(), anyLong(), any(), anyInt(), anyInt());
    }

    @Test
    void invalidRunTokenIsRejectedBeforeClaimingItems() {
        ready();
        when(mapper.claimRun(id, owner, 300)).thenReturn(0L, -1L);
        assertThatThrownBy(() -> service.claim(owner)).hasMessage("invalid embedding run token");
        assertThatThrownBy(() -> service.claim(owner)).hasMessage("invalid embedding run token");
        verify(mapper, never()).claimItems(any(), anyLong(), any(), anyInt(), anyInt());
    }

    @Test
    void batchSortsReturningRowsAndRejectsDuplicateOrInvalidIdentity() {
        var first = new RebuildBatch.Item(1, 2, 3, "digest", 7, 1);
        var second = new RebuildBatch.Item(2, 2, 3, "digest", 8, 1);
        assertThat(new RebuildBatch(id, 4, 9, owner, p, List.of(second, first)).items()).containsExactly(first, second);
        assertThatThrownBy(() -> new RebuildBatch(id, 4, 0, owner, p, List.of())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RebuildBatch(id, 4, 9, owner, p, List.of(first, first)))
                .hasMessage("duplicate embedding item");
        assertThatThrownBy(() -> new RebuildBatch.Item(1, 2, 3, "digest", 0, 1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void expiryOrChangedEpochCannotRenewOrReleaseNewOwnerState() {
        ready();
        var batch = new RebuildBatch(id, 4, 9, owner, p, List.of());
        assertThat(service.renew(batch)).isFalse();
        assertThat(service.release(batch)).isFalse();
        when(state.lockShared()).thenReturn(new EmbeddingControl("REBUILDING", null, null, "p", p.fingerprint(), 5,
                id));
        clearInvocations(mapper);
        assertThat(service.renew(batch)).isFalse();
        assertThat(service.release(batch)).isFalse();
        verifyNoInteractions(mapper);
    }

    @Test
    void itemRenewalFailurePropagatesAndSqlUsesActualExpiryAndBackoff() throws Exception {
        ready();
        var item = new RebuildBatch.Item(1, 2, 3, "digest", 7, 1);
        var batch = new RebuildBatch(id, 4, 9, owner, p, List.of(item));
        when(mapper.renewRun(batch, 300)).thenReturn(1);
        assertThatThrownBy(() -> service.renew(batch)).hasMessage("embedding item lease lost");
        String claim = String.join(" ", EmbeddingLeaseMapper.class.getMethod("claimRun", UUID.class, UUID.class,
                        int.class)
                .getAnnotation(Select.class).value());
        assertThat(claim).contains("targets_ready", "next_attempt_at <= clock_timestamp()", "lease_until <= " +
                        "clock_timestamp()",
                "claim_token = claim_token + 1");
        String renewal = String.join(" ", EmbeddingLeaseMapper.class.getMethod("renewItem", RebuildBatch.class,
                RebuildBatch.Item.class,
                int.class).getAnnotation(Update.class).value());
        assertThat(renewal).contains("lease_until > clock_timestamp()", "claim_token = #{i.claimToken}",
                "run_claim_token = #{b.runToken}");
    }
}
