package com.zhishiku.kb.app.document.metadata;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhishiku.kb.app.auth.service.IdentityAuditService;
import com.zhishiku.kb.app.document.metadata.mapper.CategoryMapper;
import com.zhishiku.kb.app.document.metadata.mapper.MetadataMapper;
import com.zhishiku.kb.app.document.metadata.model.CategoryRow;
import com.zhishiku.kb.app.document.metadata.model.MetadataCommandRow;
import com.zhishiku.kb.app.document.metadata.model.MetadataInputs;
import com.zhishiku.kb.app.document.metadata.service.CategoryWriteService;
import com.zhishiku.kb.app.document.metadata.service.MetadataAccess;
import com.zhishiku.kb.app.document.metadata.service.MetadataCommands;
import com.zhishiku.kb.app.document.metadata.service.MetadataException;
import com.zhishiku.kb.app.space.model.SpaceRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.LongStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CategoryWriteTest {
    private final CategoryMapper mapper = mock(CategoryMapper.class);
    private final MetadataMapper metadata = mock(MetadataMapper.class);
    private final MetadataAccess access = mock(MetadataAccess.class);
    private final IdentityAuditService audit = mock(IdentityAuditService.class);
    private final CategoryWriteService service = new CategoryWriteService(mapper, metadata, access,
            new MetadataCommands(metadata, new ObjectMapper()), audit);
    private final MetadataInputs.CategoryWrite input = new MetadataInputs.CategoryWrite("分类", "分类", null, 0, 3);

    @BeforeEach
    void setup() {
        var space = new SpaceRecord();
        space.setArchived(false);
        when(access.manager(2)).thenReturn(new MetadataAccess.Managed(7, space));
        when(mapper.lockTree(2)).thenReturn(3L);
        when(mapper.insert(any())).thenReturn(1);
        when(mapper.advance(2, 3)).thenReturn(1);
        when(mapper.nextId()).thenReturn(10L);
        when(metadata.insertCommand(any())).thenReturn(1);
    }

    @Test
    void createAndVersionConflictDoNotOverwriteTree() {
        assertThat(service.write(2, null, input, "create").treeVersion()).isEqualTo(4);
        when(mapper.lockTree(2)).thenReturn(4L);
        assertThatThrownBy(() -> service.write(2, null, input, "stale"))
                .isInstanceOfSatisfying(MetadataException.class, e -> assertThat(e.reason()).isEqualTo(MetadataException.Reason.TREE_VERSION_CONFLICT));
    }

    @Test
    void rejectsDuplicateRootBeforeAllocatingIdentity() {
        when(mapper.duplicate(2, null, "分类", 0)).thenReturn(true);
        assertThatThrownBy(() -> service.write(2, null, input, "duplicate"))
                .isInstanceOfSatisfying(MetadataException.class, e -> assertThat(e.reason()).isEqualTo(MetadataException.Reason.NAME_CONFLICT));
        verify(mapper, never()).nextId();
    }

    @Test
    void moveRejectsSelfDescendantAndExcessiveSubtreeDepth() {
        var root = new CategoryRow(10, 2, null, "分类", "分类", 0, null, null);
        when(metadata.category(2, 10)).thenReturn(root);
        when(mapper.height(2, 10)).thenReturn(2);
        when(mapper.path(2, 11)).thenReturn(List.of(root, new CategoryRow(11, 2, 10L, "子", "子", 0, null, null)));
        var move = new MetadataInputs.CategoryWrite("分类", "分类", 11L, 0, 3);
        assertThatThrownBy(() -> service.write(2, 10L, move, "cycle")).isInstanceOf(MetadataException.class);
        when(mapper.path(2, 11)).thenReturn(LongStream.range(20, 27).mapToObj(id ->
                new CategoryRow(id, 2, id == 20 ? null : id - 1, "层", "层", 0, null, null)).toList());
        assertThatThrownBy(() -> service.write(2, 10L, move, "deep")).isInstanceOf(MetadataException.class);
        verify(mapper, never()).update(any());
    }

    @Test
    void nameUniquenessIsScopedToSpaceAndMoveCanPreserveNodeIdentity() {
        var space = new SpaceRecord();
        space.setArchived(false);
        when(access.manager(4)).thenReturn(new MetadataAccess.Managed(7, space));
        when(mapper.lockTree(4)).thenReturn(3L);
        when(mapper.advance(4, 3)).thenReturn(1);
        when(mapper.duplicate(2, null, "分类", 0)).thenReturn(true);
        assertThat(service.write(4, null, input, "other-space").categoryId()).isEqualTo(10);
        var root = new CategoryRow(10, 2, null, "旧名", "旧名", 0, null, null);
        when(metadata.category(2, 10)).thenReturn(root);
        when(mapper.height(2, 10)).thenReturn(1);
        when(mapper.update(any())).thenReturn(1);
        assertThat(service.write(2, 10L, input, "rename").categoryId()).isEqualTo(10);
    }

    @Test
    void deletionAuditFailurePropagates() {
        when(metadata.category(2, 10)).thenReturn(new CategoryRow(10, 2, null, "分类", "分类", 0, null, null));
        when(mapper.delete(2, 10)).thenReturn(1);
        doThrow(new IllegalStateException("审计不可用")).when(audit)
                .appendSuccess(any(), any(), anyLong(), anyLong(), any(), any(), any());
        assertThatThrownBy(() -> service.delete(2, 10, 3, "failure")).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void referencedDeleteIsRejectedAndSuccessfulDeleteCanReplayAfterDisappearance() {
        when(metadata.category(2, 10)).thenReturn(new CategoryRow(10, 2, null, "分类", "分类", 0, null, null));
        when(mapper.referenced(2, 10)).thenReturn(true);
        assertThatThrownBy(() -> service.delete(2, 10, 3, "referenced"))
                .isInstanceOfSatisfying(MetadataException.class, e -> assertThat(e.reason()).isEqualTo(MetadataException.Reason.REFERENCE_CONFLICT));
        verify(mapper, never()).delete(anyLong(), anyLong());
        var stored = new AtomicReference<MetadataCommandRow>();
        when(metadata.insertCommand(any())).thenAnswer(call -> {
            stored.set(call.getArgument(0));
            return 1;
        });
        when(metadata.command("DOCUMENT_CATEGORY_DELETE", "delete")).thenAnswer(call -> stored.get());
        when(mapper.referenced(2, 10)).thenReturn(false);
        when(mapper.delete(2, 10)).thenReturn(1);
        assertThat(service.delete(2, 10, 3, "delete").replayed()).isFalse();
        when(metadata.category(2, 10)).thenReturn(null);
        when(mapper.lockTree(2)).thenReturn(4L);
        assertThat(service.delete(2, 10, 3, "delete").replayed()).isTrue();
        verify(mapper).delete(2, 10);
    }

    @Test
    void archivedSpaceOrRevokedManagerCannotChangeDirectory() {
        var archived = new SpaceRecord();
        archived.setArchived(true);
        when(access.manager(2)).thenReturn(new MetadataAccess.Managed(7, archived));
        assertThatThrownBy(() -> service.write(2, null, input, "archived"))
                .isInstanceOfSatisfying(MetadataException.class, e -> assertThat(e.reason()).isEqualTo(MetadataException.Reason.SPACE_ARCHIVED));
        archived.setArchived(false);
        when(access.manager(2)).thenReturn(new MetadataAccess.Managed(7, archived))
                .thenThrow(new MetadataException(MetadataException.Reason.NOT_FOUND));
        assertThatThrownBy(() -> service.write(2, null, input, "revoked")).isInstanceOf(MetadataException.class);
        verify(mapper, never()).insert(any());
    }
}
