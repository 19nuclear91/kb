package com.zhishiku.kb.app.document.metadata;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhishiku.kb.app.auth.service.IdentityAuditService;
import com.zhishiku.kb.app.document.metadata.mapper.CategoryMapper;
import com.zhishiku.kb.app.document.metadata.mapper.MetadataMapper;
import com.zhishiku.kb.app.document.metadata.mapper.TagMapper;
import com.zhishiku.kb.app.document.metadata.model.MetadataInputs;
import com.zhishiku.kb.app.document.metadata.model.MetadataCommandRow;
import com.zhishiku.kb.app.document.metadata.model.TagRow;
import com.zhishiku.kb.app.document.metadata.service.CategoryService;
import com.zhishiku.kb.app.document.metadata.service.MetadataAccess;
import com.zhishiku.kb.app.document.metadata.service.MetadataCommands;
import com.zhishiku.kb.app.document.metadata.service.MetadataException;
import com.zhishiku.kb.app.document.metadata.service.MetadataReadService;
import com.zhishiku.kb.app.document.metadata.service.MetadataWriteService;
import com.zhishiku.kb.app.document.model.DocumentEntity;
import com.zhishiku.kb.app.document.model.DocumentSummary;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doThrow;
import static org.mockito.ArgumentMatchers.anyLong;

class MetadataServiceTest {
    private final MetadataMapper mapper = mock(MetadataMapper.class);
    private final CategoryMapper categories = mock(CategoryMapper.class);
    private final TagMapper tags = mock(TagMapper.class);
    private final MetadataAccess access = mock(MetadataAccess.class);
    private final IdentityAuditService audit = mock(IdentityAuditService.class);
    private final MetadataWriteService write = new MetadataWriteService(mapper, categories, tags, access,
            new MetadataCommands(mapper, new ObjectMapper().findAndRegisterModules()), audit);
    private final DocumentEntity before = DocumentEntity.builder().id(9L).spaceId(2L).title("原名").metadataVersion(0L)
            .updatedAt(OffsetDateTime.parse("2026-11-01T00:00:00Z")).build();
    private final TagRow disabled = new TagRow(3, "停用", "停用", false, 1, null, null);

    @BeforeEach
    void setup() {
        when(access.document(9, true)).thenReturn(new MetadataAccess.Editable(7, new DocumentSummary()));
        when(mapper.document(9)).thenReturn(before);
        when(mapper.lockDocument(9)).thenReturn(before);
        when(categories.shareTree(2)).thenReturn(0L);
        when(tags.share(List.of(3L))).thenReturn(List.of(disabled));
        when(tags.forDocument(9)).thenReturn(List.of(disabled));
        when(mapper.updateMetadata(any())).thenReturn(1);
        when(mapper.insertCommand(any())).thenReturn(1);
    }

    @Test
    void readShowsCurrentDocumentAndDisabledTagWithoutVersionSelection() {
        var read = new MetadataReadService(mapper, tags, mock(CategoryService.class), access);
        assertThat(read.get(9).tags().getFirst().enabled()).isFalse();
        assertThat(read.get(9).title()).isEqualTo("原名");
        when(access.document(9, false)).thenThrow(new MetadataException(MetadataException.Reason.NOT_FOUND));
        assertThatThrownBy(() -> read.get(9)).isInstanceOf(MetadataException.class);
    }

    @Test
    void unchangedAndStaleInputsAreRejected() {
        assertThatThrownBy(() -> write.update(9, new MetadataInputs.Update("原名", null, null, List.of(3L), 0), "same"))
                .isInstanceOfSatisfying(MetadataException.class, e -> assertThat(e.reason()).isEqualTo(MetadataException.Reason.NO_CHANGE));
        assertThatThrownBy(() -> write.update(9, new MetadataInputs.Update("新名", null, null, List.of(3L), 1), "stale"))
                .isInstanceOfSatisfying(MetadataException.class, e -> assertThat(e.reason()).isEqualTo(MetadataException.Reason.METADATA_VERSION_CONFLICT));
        verify(mapper, never()).updateMetadata(any());
    }

    @Test
    void disabledExistingTagCanRemainButCannotBeAdded() {
        var after = DocumentEntity.builder().id(9L).spaceId(2L).metadataVersion(1L).updatedAt(before.getUpdatedAt()).build();
        when(mapper.document(9)).thenReturn(before, after);
        assertThat(write.update(9, new MetadataInputs.Update("新名", null, null, List.of(3L), 0), "keep").changedFields())
                .containsExactly("title");
        verify(mapper, never()).clearTags(9);
        var order = inOrder(categories, tags, mapper);
        order.verify(mapper).lockRequest("document-metadata:DOCUMENT_METADATA_UPDATE:keep");
        order.verify(categories).shareTree(2);
        order.verify(tags).share(List.of(3L));
        order.verify(mapper).lockDocument(9);
        when(mapper.document(9)).thenReturn(before);
        when(tags.forDocument(9)).thenReturn(List.of());
        assertThatThrownBy(() -> write.update(9, new MetadataInputs.Update("新名", null, null, List.of(3L), 0), "add"))
                .isInstanceOfSatisfying(MetadataException.class, e -> assertThat(e.reason()).isEqualTo(MetadataException.Reason.TAG_UNAVAILABLE));
    }

    @Test
    void disabledTagRemovalAndUnavailableCategoryAreExplicit() {
        when(tags.share(List.of())).thenReturn(List.of());
        assertThat(write.update(9, new MetadataInputs.Update("原名", null, null, List.of(), 0), "remove").changedFields())
                .containsExactly("tagIds");
        verify(mapper).clearTags(9);
        assertThatThrownBy(() -> write.update(9, new MetadataInputs.Update("新名", null, 8L, List.of(3L), 0), "category"))
                .isInstanceOfSatisfying(MetadataException.class, e -> assertThat(e.reason()).isEqualTo(MetadataException.Reason.CATEGORY_UNAVAILABLE));
    }

    @Test
    void responseLossReplaysOriginalVersionAfterReauthorization() {
        var stored = new AtomicReference<MetadataCommandRow>();
        var current = new AtomicReference<>(before);
        when(mapper.command("DOCUMENT_METADATA_UPDATE", "retry")).thenAnswer(call -> stored.get());
        when(mapper.insertCommand(any())).thenAnswer(call -> {
            stored.set(call.getArgument(0));
            return 1;
        });
        when(mapper.document(9)).thenAnswer(call -> current.get());
        when(mapper.lockDocument(9)).thenAnswer(call -> current.get());
        when(mapper.updateMetadata(any())).thenAnswer(call -> {
            current.set(DocumentEntity.builder().id(9L).spaceId(2L).title("新名").metadataVersion(1L)
                    .updatedAt(before.getUpdatedAt().plusSeconds(1)).build());
            return 1;
        });
        var input = new MetadataInputs.Update("新名", null, null, List.of(3L), 0);
        var first = write.update(9, input, "retry");
        var replay = write.update(9, input, "retry");
        assertThat(replay.replayed()).isTrue();
        assertThat(replay.metadataVersion()).isEqualTo(first.metadataVersion()).isEqualTo(1);
        assertThat(replay.updatedAt()).isEqualTo(first.updatedAt());
        verify(mapper).updateMetadata(any());
        doThrow(new MetadataException(MetadataException.Reason.FORBIDDEN)).when(access).document(9, true);
        assertThatThrownBy(() -> write.update(9, input, "retry")).isInstanceOf(MetadataException.class);
    }

    @Test
    void authorizationRevocationAndRelocationWhileWaitingPreventWrites() {
        var input = new MetadataInputs.Update("新名", null, null, List.of(3L), 0);
        when(access.document(9, true)).thenReturn(new MetadataAccess.Editable(7, new DocumentSummary()))
                .thenThrow(new MetadataException(MetadataException.Reason.FORBIDDEN));
        assertThatThrownBy(() -> write.update(9, input, "revoked")).isInstanceOf(MetadataException.class);
        org.mockito.Mockito.doReturn(new MetadataAccess.Editable(7, new DocumentSummary())).when(access).document(9, true);
        when(mapper.lockDocument(9)).thenReturn(DocumentEntity.builder().id(9L).spaceId(4L).metadataVersion(1L).build());
        assertThatThrownBy(() -> write.update(9, input, "moved"))
                .isInstanceOfSatisfying(MetadataException.class, e -> assertThat(e.reason()).isEqualTo(MetadataException.Reason.METADATA_VERSION_CONFLICT));
        verify(mapper, never()).updateMetadata(any());
    }

    @Test
    void auditFailurePropagatesWithoutAcknowledgingSave() {
        doThrow(new IllegalStateException("审计不可用")).when(audit).appendSuccess(any(), any(), anyLong(), anyLong(), any(), any(), any());
        assertThatThrownBy(() -> write.update(9, new MetadataInputs.Update("新名", null, null, List.of(3L), 0), "audit"))
                .isInstanceOf(IllegalStateException.class);
    }
}
