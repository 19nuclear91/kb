package com.zhishiku.kb.worker.embedding;

import com.zhishiku.kb.common.embedding.EmbeddingControl;
import com.zhishiku.kb.common.embedding.EmbeddingDigest;
import com.zhishiku.kb.common.embedding.EmbeddingRun;
import com.zhishiku.kb.worker.embedding.mapper.EmbeddingStateMapper;
import com.zhishiku.kb.worker.embedding.mapper.EmbeddingTargetMapper;
import com.zhishiku.kb.worker.embedding.service.EmbeddingTargetService;
import org.apache.ibatis.annotations.Select;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyBoolean;
import static org.mockito.Mockito.anyInt;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class EmbeddingTargetServiceTest {
    private final UUID id = UUID.randomUUID();
    private final EmbeddingStateMapper state = mock(EmbeddingStateMapper.class);
    private final EmbeddingTargetMapper targets = mock(EmbeddingTargetMapper.class);
    private final EmbeddingTargetService service = new EmbeddingTargetService(state, targets);

    private void rebuilding() {
        when(state.lockExclusive()).thenReturn(new EmbeddingControl("REBUILDING", null, null, "p", "fp", 1, id));
        when(state.run(id)).thenReturn(new EmbeddingRun(id, "p", 7, "REBUILDING", 2, null, 0, null, null,
                0, 3, null, 0, 0, 0, null));
        when(targets.lockReady(id)).thenReturn(false);
        when(targets.progress(eq(id), anyBoolean())).thenReturn(1);
    }

    @Test
    void pagesAreIdempotentAndOnlyEmptyPageSealsTargets() {
        rebuilding();
        var first = new EmbeddingTargetMapper.Target(1, 10, 20, "first");
        var second = new EmbeddingTargetMapper.Target(2, 11, 21, "second");
        when(targets.page(id, 100)).thenReturn(List.of(first), List.of(second), List.of());
        when(targets.insert(id, first, EmbeddingDigest.text("first"))).thenReturn(0);
        when(targets.insert(id, second, EmbeddingDigest.text("second"))).thenReturn(1);
        service.preparePage();
        service.preparePage();
        new EmbeddingTargetService(state, targets).preparePage();
        verify(targets, times(2)).progress(id, false);
        verify(targets).progress(id, true);
        var order = inOrder(state, targets);
        order.verify(state).lockExclusive();
        order.verify(targets).lockReady(id);
        order.verify(targets).missingChunks();
        order.verify(targets).page(id, 100);
        order.verify(targets).insert(id, first, EmbeddingDigest.text("first"));
    }

    @Test
    void missingCurrentChunksBlocksWithAuditInsteadOfCompletingEmptyTarget() {
        rebuilding();
        when(targets.missingChunks()).thenReturn(true);
        when(targets.blockRun(id, 2)).thenReturn(1);
        when(targets.blockControl(id, 1)).thenReturn(1);
        when(targets.blockEvent(any(), anyString(), anyString(), eq(id), eq(2L))).thenReturn(1);
        service.preparePage();
        verify(targets).blockEvent(any(), eq(id + ":3"), anyString(), eq(id), eq(2L));
        verify(targets, never()).page(any(), anyInt());
        verify(targets, never()).progress(any(), anyBoolean());
    }

    @Test
    void quiescingOrAlreadyPreparedDoesNotMaterializeTargets() {
        when(state.lockExclusive()).thenReturn(new EmbeddingControl("QUIESCING", null, null, "p", "fp", 1, id));
        service.preparePage();
        verifyNoInteractions(targets);
        rebuilding();
        when(targets.lockReady(id)).thenReturn(true);
        service.preparePage();
        verify(targets, never()).page(any(), anyInt());
    }

    @Test
    void targetSqlIncludesArchivedCurrentVersionsButNotDeletedOrHistoricalContent() throws Exception {
        String sql = String.join(" ", EmbeddingTargetMapper.class.getMethod("page", UUID.class, int.class)
                .getAnnotation(Select.class).value());
        assertThat(sql).contains("d.status <> 'deleted'", "c.version_id = d.current_version_id",
                        "ORDER BY c.id LIMIT #{limit}", "NOT EXISTS")
                .doesNotContain("archived", "published", "security_level", "principal", "space_id");
        String missing = String.join(" ",
                EmbeddingTargetMapper.class.getMethod("missingChunks").getAnnotation(Select.class).value());
        assertThat(missing).contains("d.current_version_id IS NOT NULL", "c.doc_id = d.id");
    }
}
