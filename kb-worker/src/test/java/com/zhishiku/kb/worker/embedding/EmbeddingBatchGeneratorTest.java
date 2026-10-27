package com.zhishiku.kb.worker.embedding;

import com.zhishiku.kb.common.embedding.EmbeddingClientRegistry;
import com.zhishiku.kb.common.embedding.EmbeddingConnection;
import com.zhishiku.kb.common.embedding.EmbeddingDigest;
import com.zhishiku.kb.common.embedding.EmbeddingProfile;
import com.zhishiku.kb.common.embedding.EmbeddingProfileDefinition;
import com.zhishiku.kb.common.embedding.EmbeddingResult;
import com.zhishiku.kb.common.embedding.EmbeddingRole;
import com.zhishiku.kb.common.embedding.ProfileEmbeddingClient;
import com.zhishiku.kb.worker.embedding.model.GeneratedRebuildBatch;
import com.zhishiku.kb.worker.embedding.model.RebuildBatch;
import com.zhishiku.kb.worker.embedding.model.RebuildChunk;
import com.zhishiku.kb.worker.embedding.model.RebuildContentChangedException;
import com.zhishiku.kb.worker.embedding.model.RebuildLeaseLostException;
import com.zhishiku.kb.worker.embedding.service.EmbeddingBatchGenerator;
import com.zhishiku.kb.worker.embedding.service.EmbeddingBatchReader;
import com.zhishiku.kb.worker.embedding.service.EmbeddingChunkStore;
import com.zhishiku.kb.worker.embedding.service.EmbeddingLeaseService;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class EmbeddingBatchGeneratorTest {
    private final EmbeddingProfile profile = new EmbeddingProfile("p", "local", "source", "local-hash-v1", "1", 1024,
            "cosine", "identity-v1", "identity-v1", "query", "document", "local-hash-v1");
    private final ProfileEmbeddingClient client = mock(ProfileEmbeddingClient.class);
    private final EmbeddingBatchReader reader = mock(EmbeddingBatchReader.class);
    private final RebuildBatch.Item first = new RebuildBatch.Item(1, 10, 20, EmbeddingDigest.text("first"), 3, 1);
    private final RebuildBatch.Item second = new RebuildBatch.Item(2, 10, 20, EmbeddingDigest.text("second"), 4, 1);
    private final RebuildBatch batch = new RebuildBatch(UUID.randomUUID(), 2, 5, UUID.randomUUID(), profile,
            List.of(first, second));

    private EmbeddingBatchGenerator generator() {
        when(client.profile()).thenReturn(profile);
        when(client.role()).thenReturn(EmbeddingRole.DOCUMENT);
        var registry = new EmbeddingClientRegistry(List.of(new EmbeddingProfileDefinition(profile,
                new EmbeddingConnection(null, null, 30, 16))), EmbeddingRole.DOCUMENT, d -> client);
        return new EmbeddingBatchGenerator(reader, registry);
    }

    private float[] vector() {
        var v = new float[1024];
        v[0] = 1;
        return v;
    }

    @Test
    void fixedTargetAlwaysGeneratesFromStoredTextAndRetainsDigest() {
        when(reader.read(batch)).thenReturn(List.of(new RebuildChunk(1, 10, 20, "first", "published", 20L),
                new RebuildChunk(2, 10, 20, "second", "archived", 20L)));
        when(client.embed(List.of("first", "second"))).thenReturn(new EmbeddingResult(profile, EmbeddingRole.DOCUMENT,
                List.of(vector(), vector())));
        var generator = generator();
        var result = generator.generate(batch);
        assertThat(result.inputs()).extracting(GeneratedRebuildBatch.Input::digest)
                .containsExactly(first.contentDigest(), second.contentDigest());
        assertThat(result.embeddings().profile()).isEqualTo(profile);
        verify(client).embed(List.of("first", "second"));
        var order = inOrder(reader, client);
        order.verify(reader).read(batch);
        order.verify(client).embed(List.of("first", "second"));
    }

    @Test
    void deletedOrMissingChunksDoNotSendText() {
        when(reader.read(batch)).thenReturn(Arrays.asList(null, new RebuildChunk(2, 10, 20, "second", "deleted", 20L)));
        var result = generator().generate(batch);
        assertThat(result.embeddings().size()).isZero();
        assertThat(result.inputs()).noneMatch(GeneratedRebuildBatch.Input::generated);
        verify(client, never()).embed(any());
    }

    @Test
    void changedInputFailsBeforeExternalCallWithoutLeakingText() {
        when(reader.read(batch)).thenReturn(List.of(new RebuildChunk(1, 10, 20, "private changed text", "published",
                20L)));
        assertThatThrownBy(() -> generator().generate(batch)).isInstanceOf(RebuildContentChangedException.class)
                .hasMessage("embedding rebuild content changed");
        verify(client, never()).embed(any());
    }

    @Test
    void wrongRoleOrCountCannotBecomeGeneratedBatch() {
        when(reader.read(batch)).thenReturn(List.of(new RebuildChunk(1, 10, 20, "first", "published", 20L),
                new RebuildChunk(2, 10, 20, "second", "published", 20L)));
        when(client.embed(any())).thenReturn(new EmbeddingResult(profile, EmbeddingRole.QUERY, List.of(vector(),
                        vector())),
                new EmbeddingResult(profile, EmbeddingRole.DOCUMENT, List.of(vector())));
        var generator = generator();
        assertThatThrownBy(() -> generator.generate(batch)).hasMessage("embedding identity, role or count mismatch");
        assertThatThrownBy(() -> generator.generate(batch)).hasMessage("embedding identity, role or count mismatch");
    }

    @Test
    void missingTargetClientDoesNotFallbackToAnotherProfile() {
        var generator = generator();
        when(reader.read(batch)).thenReturn(List.of(new RebuildChunk(1, 10, 20, "first", "published", 20L),
                new RebuildChunk(2, 10, 20, "second", "published", 20L)));
        when(client.profile()).thenReturn(new EmbeddingProfile("other", "local", "source", "local-hash-v1", "2", 1024,
                "cosine", "identity-v1", "identity-v1", "query", "document", "local-hash-v1"));
        assertThatThrownBy(() -> generator.generate(batch)).hasMessage("embedding document client unavailable");
        verify(client, never()).embed(any());
    }

    @Test
    void readerChecksLeaseAndGeneratorRejectsAmbientTransactions() throws Exception {
        var leases = mock(EmbeddingLeaseService.class);
        var chunks = mock(EmbeddingChunkStore.class);
        var actual = new EmbeddingBatchReader(leases, chunks);
        assertThatThrownBy(() -> actual.read(batch)).isInstanceOf(RebuildLeaseLostException.class);
        verifyNoInteractions(chunks);
        when(leases.renew(batch)).thenReturn(true);
        assertThat(actual.read(batch)).hasSize(2);
        assertThat(EmbeddingBatchGenerator.class.getMethod("generate", RebuildBatch.class)
                .getAnnotation(Transactional.class).propagation()).isEqualTo(Propagation.NEVER);
    }
}
