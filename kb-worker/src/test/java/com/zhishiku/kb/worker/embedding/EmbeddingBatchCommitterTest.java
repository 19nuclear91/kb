package com.zhishiku.kb.worker.embedding;

import com.zhishiku.kb.common.embedding.EmbeddingDigest;
import com.zhishiku.kb.common.embedding.EmbeddingMath;
import com.zhishiku.kb.common.embedding.EmbeddingProfile;
import com.zhishiku.kb.common.embedding.EmbeddingResult;
import com.zhishiku.kb.common.embedding.EmbeddingRole;
import com.zhishiku.kb.worker.embedding.model.GeneratedRebuildBatch;
import com.zhishiku.kb.worker.embedding.model.RebuildBatch;
import com.zhishiku.kb.worker.embedding.model.RebuildChunk;
import com.zhishiku.kb.worker.embedding.model.RebuildContentChangedException;
import com.zhishiku.kb.worker.embedding.model.RebuildLeaseLostException;
import com.zhishiku.kb.worker.embedding.service.EmbeddingBatchCommitter;
import com.zhishiku.kb.worker.embedding.service.EmbeddingChunkStore;
import com.zhishiku.kb.worker.embedding.service.EmbeddingLeaseService;
import org.junit.jupiter.api.Test;
import org.mockito.AdditionalMatchers;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyBoolean;
import static org.mockito.Mockito.anyInt;
import static org.mockito.Mockito.contains;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class EmbeddingBatchCommitterTest {
    private final EmbeddingProfile p = new EmbeddingProfile("p", "local", "source", "local-hash-v1", "1", 1024,
            "cosine", "identity-v1", "identity-v1", "query", "document", "local-hash-v1");
    private final RebuildBatch.Item first = new RebuildBatch.Item(1, 20, 30, EmbeddingDigest.text("first"), 3, 1);
    private final RebuildBatch.Item second = new RebuildBatch.Item(2, 10, 31, EmbeddingDigest.text("second"), 4, 1);
    private final RebuildBatch batch = new RebuildBatch(UUID.randomUUID(), 2, 5, UUID.randomUUID(), p, List.of(first,
            second));
    private final EmbeddingLeaseService leases = mock(EmbeddingLeaseService.class);
    private final EmbeddingChunkStore chunks = mock(EmbeddingChunkStore.class);
    private final EmbeddingBatchCommitter service = new EmbeddingBatchCommitter(leases, chunks);

    private GeneratedRebuildBatch generated() {
        float[] one = new float[1024];
        one[0] = 1;
        float[] two = new float[1024];
        two[1] = 1;
        return new GeneratedRebuildBatch(batch, List.of(new GeneratedRebuildBatch.Input(first, first.contentDigest()),
                new GeneratedRebuildBatch.Input(second, second.contentDigest())),
                new EmbeddingResult(p, EmbeddingRole.DOCUMENT, List.of(one, two)));
    }

    private void ready() {
        when(leases.renew(batch)).thenReturn(true);
        when(chunks.readLocked(1)).thenReturn(new RebuildChunk(1, 20, 30, "first", "published", 30L));
        when(chunks.readLocked(2)).thenReturn(new RebuildChunk(2, 10, 31, "second", "archived", 31L));
        when(chunks.updateVector(eq(batch), any(), any())).thenReturn(1);
        when(chunks.finishItem(eq(batch), any(), anyBoolean())).thenReturn(1);
        when(chunks.completeBatch(eq(batch), anyInt(), anyInt())).thenReturn(1);
    }

    @Test
    void leaseThenSortedDocumentsThenVectorAndProgressInSingleShortTransaction() throws Exception {
        ready();
        var generated = generated();
        service.commit(generated);
        var order = inOrder(leases, chunks);
        order.verify(leases).renew(batch);
        order.verify(chunks).lockDocument(10);
        order.verify(chunks).lockDocument(20);
        order.verify(chunks).readLocked(1);
        order.verify(chunks).updateVector(eq(batch), eq(first), aryEq(generated.embeddings().vector(0)));
        order.verify(chunks).finishItem(batch, first, false);
        order.verify(chunks).readLocked(2);
        order.verify(chunks).updateVector(eq(batch), eq(second), aryEq(generated.embeddings().vector(1)));
        order.verify(chunks).finishItem(batch, second, false);
        order.verify(chunks).completeBatch(batch, 2, 0);
        assertThat(EmbeddingBatchCommitter.class.getMethod("commit", GeneratedRebuildBatch.class)
                .getAnnotation(Transactional.class).timeout()).isEqualTo(10);
    }

    @Test
    void staleLeaseFailsBeforeAnyDocumentOrVectorAccess() {
        assertThatThrownBy(() -> service.commit(generated())).isInstanceOf(RebuildLeaseLostException.class);
        verifyNoInteractions(chunks);
    }

    @Test
    void progressFailurePropagatesInsteadOfReturningSuccessfulCommit() {
        ready();
        when(chunks.finishItem(batch, first, false)).thenReturn(0);
        assertThatThrownBy(() -> service.commit(generated())).isInstanceOf(RebuildLeaseLostException.class);
        verify(chunks).updateVector(eq(batch), eq(first), any());
        verify(chunks, never()).completeBatch(any(), anyInt(), anyInt());
        verify(chunks, never()).readLocked(2);
    }

    @Test
    void deletedFirstItemDoesNotShiftSecondVectorAndDoesNotResurrectContent() {
        ready();
        when(chunks.readLocked(1)).thenReturn(null);
        var generated = generated();
        service.commit(generated);
        verify(chunks, never()).updateVector(eq(batch), eq(first), any());
        verify(chunks).finishItem(batch, first, true);
        verify(chunks).updateVector(eq(batch), eq(second), aryEq(generated.embeddings().vector(1)));
        verify(chunks).completeBatch(batch, 1, 1);
    }

    @Test
    void changedContentRejectsOldVectorWithoutExposingText() {
        ready();
        when(chunks.readLocked(1)).thenReturn(new RebuildChunk(1, 20, 30, "private change", "published", 30L));
        assertThatThrownBy(() -> service.commit(generated())).isInstanceOf(RebuildContentChangedException.class)
                .hasMessage("embedding rebuild content changed");
        verify(chunks, never()).updateVector(any(), any(), any());
        verify(chunks, never()).completeBatch(any(), anyInt(), anyInt());
    }

    @Test
    void jdbcWritesOnlyVectorFieldsAndFencesItemProgress() throws Exception {
        var jdbc = mock(JdbcTemplate.class);
        var store = new EmbeddingChunkStore(jdbc);
        var vector = generated().embeddings().vector(0);
        store.lockDocument(20);
        store.readLocked(1);
        store.updateVector(batch, first, vector);
        store.finishItem(batch, first, false);
        store.completeBatch(batch, 1, 0);
        verify(jdbc).query(contains("FOR UPDATE"), any(RowMapper.class), eq(20L));
        verify(jdbc).query(contains("FOR UPDATE OF c"), any(RowMapper.class), eq(1L));
        verify(jdbc).update(contains("SET embedding = CAST(? AS vector), embedding_profile_id = ?, embed_model = ?"),
                eq(EmbeddingMath.toPgVector(vector)), eq("p"), eq("local-hash-v1"), eq(1L), eq(20L), eq(30L));
        verify(jdbc).update(contains("AND lease_until > clock_timestamp()"), eq("SUCCEEDED"), eq(batch.runId()),
                eq(1L), eq(20L), eq(30L), eq(first.contentDigest()), eq(3L), eq(5L), eq(batch.owner()));
    }

    private static float[] aryEq(float[] value) {
        return AdditionalMatchers.aryEq(value);
    }
}
