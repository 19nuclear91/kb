package com.zhishiku.kb.worker.embedding;

import com.zhishiku.kb.common.embedding.EmbeddingClientRegistry;
import com.zhishiku.kb.common.embedding.EmbeddingConnection;
import com.zhishiku.kb.common.embedding.EmbeddingProbe;
import com.zhishiku.kb.common.embedding.EmbeddingProfile;
import com.zhishiku.kb.common.embedding.EmbeddingProfileDefinition;
import com.zhishiku.kb.common.embedding.EmbeddingRole;
import com.zhishiku.kb.common.embedding.LocalProfileEmbeddingClient;
import com.zhishiku.kb.common.embedding.ProfileEmbeddingClient;
import com.zhishiku.kb.worker.embedding.mapper.EmbeddingReadinessMapper;
import com.zhishiku.kb.worker.embedding.service.EmbeddingProfileRegistration;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.argThat;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class EmbeddingReadinessSchedulerTest {
    @Test
    void modelsRunOutsideTransactionAndStorageFollowsModelValidation() throws Exception {
        var definition = new EmbeddingProfileDefinition(new EmbeddingProfile("p", "local", "kb/local", "local-hash-v1",
                "1", 1024, "cosine", "identity-v1", "identity-v1", "query", "document", "local-hash-v1"),
                new EmbeddingConnection(null, null, 30, 16));
        var client = mock(ProfileEmbeddingClient.class);
        when(client.profile()).thenReturn(definition.profile());
        when(client.role()).thenReturn(EmbeddingRole.DOCUMENT);
        when(client.embed(List.of(EmbeddingProbe.SYNTHETIC_INPUT))).thenAnswer(ignored -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return new LocalProfileEmbeddingClient(definition, EmbeddingRole.DOCUMENT).embed(List.of(EmbeddingProbe.SYNTHETIC_INPUT));
        });
        var registry = new EmbeddingClientRegistry(List.of(definition), EmbeddingRole.DOCUMENT, ignored -> client);
        var mapper = mock(EmbeddingReadinessMapper.class);
        when(mapper.save(any())).thenReturn(1);
        var scheduler = new EmbeddingReadinessScheduler(registry, mapper, mock(EmbeddingProfileRegistration.class),
                30000, 90000);
        clearInvocations(client);
        scheduler.poll();
        var order = inOrder(client, mapper);
        order.verify(client).embed(List.of(EmbeddingProbe.SYNTHETIC_INPUT));
        order.verify(mapper).save(argThat(row -> row.ready() && row.role() == EmbeddingRole.DOCUMENT));
        order.verify(mapper).deleteExpired();
        assertThat(EmbeddingReadinessScheduler.class.getAnnotation(Transactional.class)).isNull();
        assertThat(EmbeddingReadinessScheduler.class.getMethod("poll").getAnnotation(Transactional.class)).isNull();
    }
}
