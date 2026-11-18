package com.zhishiku.kb.app.document.suggestion;

import com.zhishiku.kb.app.document.diff.mapper.DocumentDiffMapper;
import com.zhishiku.kb.app.document.diff.model.SnapshotBlock;
import com.zhishiku.kb.app.document.diff.model.SnapshotHeader;
import com.zhishiku.kb.app.document.metadata.mapper.CategoryMapper;
import com.zhishiku.kb.app.document.metadata.model.CategoryRow;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SuggestionMaterialsTest {
    private final SuggestionAccess access = mock(SuggestionAccess.class);
    private final DocumentDiffMapper snapshots = mock(DocumentDiffMapper.class);
    private final CategoryMapper categories = mock(CategoryMapper.class);
    private final SuggestionStore store = mock(SuggestionStore.class);
    private final SuggestionMaterials service = new SuggestionMaterials(access, snapshots, categories, store);
    private final SuggestionJob job = new SuggestionJob(UUID.randomUUID(), UUID.randomUUID(), 1, 2, 3, 4, 5, 6, 7, 8,
            java.time.OffsetDateTime.now().plusMinutes(2), "RUNNING", null, null, null, null, null, null, null, null, "NOT_REQUIRED");

    private void context(Long published) {
        when(access.current("session", 4)).thenReturn(new SuggestionAccess.Context(1, 2, 3, 4, 5, published, 7, 8));
    }

    private SnapshotHeader header(boolean truncated, int blocks, boolean available) {
        return new SnapshotHeader(6, 1, "解析", "1", truncated, blocks, blocks * 2L, available);
    }

    @Test
    void publishedVersionOnlyAndNoPartialMaterial() {
        context(null);
        assertEquals("SOURCE_CHANGED", assertThrows(SuggestionMaterials.Rejection.class,
                () -> service.load("session", job)).getMessage());
        context(6L);
        when(snapshots.headers(4, 6, 6)).thenReturn(List.of(header(false, 1, false)));
        assertEquals("SNAPSHOT_MISSING", assertThrows(SuggestionMaterials.Rejection.class,
                () -> service.load("session", job)).getMessage());
        when(snapshots.headers(4, 6, 6)).thenReturn(List.of(header(true, 1, true)));
        assertEquals("SNAPSHOT_TRUNCATED", assertThrows(SuggestionMaterials.Rejection.class,
                () -> service.load("session", job)).getMessage());
        when(snapshots.headers(4, 6, 6)).thenReturn(List.of(header(false, 0, true)));
        assertEquals("SNAPSHOT_EMPTY", assertThrows(SuggestionMaterials.Rejection.class,
                () -> service.load("session", job)).getMessage());
        verify(snapshots, never()).blocks(6);
        when(snapshots.headers(4, 6, 6)).thenThrow(new IllegalStateException("存储暂不可用"));
        assertThrows(IllegalStateException.class, () -> service.load("session", job));
    }

    @Test
    void staleExecutionAndReplacedSnapshotCannotProvideMaterialOrPermission() {
        var expired = new SuggestionJob(job.requestId(), job.executionId(), 1, 2, 3, 4, 5, 6, 7, 8,
                java.time.OffsetDateTime.now().minusSeconds(1), "RUNNING", null, null, null, null, null, null, null, null, "NOT_REQUIRED");
        assertEquals("TIMEOUT", assertThrows(SuggestionMaterials.Rejection.class, () -> service.load("session", expired)).getMessage());
        context(6L);
        when(snapshots.headers(4, 6, 6)).thenReturn(List.of(header(false, 1, true)));
        assertEquals("SNAPSHOT_MISSING", assertThrows(SuggestionMaterials.Rejection.class,
                () -> service.authorizePermit("session", job)).getMessage());
        when(access.current("session", 4)).thenThrow(new IllegalStateException("会话已撤销"));
        assertThrows(IllegalStateException.class, () -> service.load("session", job));
        verify(snapshots, never()).blocks(6);
    }

    @Test
    void completeTableAndBoundedDirectoryPages() {
        context(6L);
        when(store.bindSnapshot(job.requestId())).thenReturn(true);
        when(snapshots.headers(4, 6, 6)).thenReturn(List.of(header(false, 1, true)));
        when(snapshots.blocks(6)).thenReturn(List.of(new SnapshotBlock(0, "TABLE", "表格", "资料", 3,
                null, null, null, null, null, "[[\"甲\",\"乙\"]]")));
        when(categories.version(5)).thenReturn(8L);
        var empty = service.load("session", job);
        assertEquals("[[\"甲\",\"乙\"]]", empty.blocks().getFirst().tableCells());
        assertEquals(List.of(), empty.categories());
        var page = java.util.stream.LongStream.rangeClosed(1, 64).mapToObj(id ->
                new CategoryRow(id, 5, null, "分类" + id, "", 0, null, null)).toList();
        when(categories.children(5, null, null, null, 64)).thenReturn(page);
        assertEquals(64, service.load("session", job).categories().size());
        verify(categories).children(5, null, 0, 64L, 64);
        when(categories.version(5)).thenReturn(8L, 9L);
        assertEquals("TREE_VERSION_CONFLICT", assertThrows(SuggestionMaterials.Rejection.class,
                () -> service.load("session", job)).getMessage());
        when(categories.version(5)).thenReturn(8L);
        when(categories.children(5, null, null, null, 64)).thenReturn(List.of(
                new CategoryRow(2, 42, null, "分类1", "", 0, null, null)));
        assertEquals("UNAVAILABLE", assertThrows(SuggestionMaterials.Rejection.class,
                () -> service.load("session", job)).getMessage());
    }
}
