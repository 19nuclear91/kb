package com.zhishiku.kb.app.document.metadata;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhishiku.kb.app.auth.service.IdentityAuditService;
import com.zhishiku.kb.app.document.metadata.mapper.MetadataMapper;
import com.zhishiku.kb.app.document.metadata.mapper.TagMapper;
import com.zhishiku.kb.app.document.metadata.model.MetadataCommandRow;
import com.zhishiku.kb.app.document.metadata.model.MetadataInputs;
import com.zhishiku.kb.app.document.metadata.model.TagRow;
import com.zhishiku.kb.app.document.metadata.service.MetadataAccess;
import com.zhishiku.kb.app.document.metadata.service.MetadataCommands;
import com.zhishiku.kb.app.document.metadata.service.MetadataException;
import com.zhishiku.kb.app.document.metadata.service.TagService;
import com.zhishiku.kb.app.document.model.DocumentSummary;
import com.zhishiku.kb.common.security.PermissionScope;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TagServiceTest {
    private final TagMapper tags = mock(TagMapper.class);
    private final MetadataMapper metadata = mock(MetadataMapper.class);
    private final MetadataAccess access = mock(MetadataAccess.class);
    private final TagService service = new TagService(tags, access, new MetadataCommands(metadata, new ObjectMapper()), mock(IdentityAuditService.class));

    @Test
    void disableUsesCurrentVersionAndReplaysWithoutTouchingRelations() {
        when(access.tagManager(true)).thenReturn(7L);
        when(tags.lock(9)).thenReturn(new TagRow(9, "主题", "主题", true, 2, null, null));
        when(tags.update(any())).thenReturn(1);
        var stored = new AtomicReference<MetadataCommandRow>();
        when(metadata.insertCommand(any())).thenAnswer(call -> {
            stored.set(call.getArgument(0));
            return 1;
        });
        when(metadata.command("DOCUMENT_TAG_UPDATE", "disable")).thenAnswer(call -> stored.get());
        var input = new MetadataInputs.TagWrite("主题", "主题", false, 2L);
        assertThat(service.write(9L, input, "disable").version()).isEqualTo(3);
        when(tags.lock(9)).thenReturn(new TagRow(9, "主题", "主题", false, 3, null, null));
        assertThat(service.write(9L, input, "disable").replayed()).isTrue();
        verify(tags).update(any());
        assertThatThrownBy(() -> service.write(9L, input, "stale"))
                .isInstanceOfSatisfying(MetadataException.class, e -> assertThat(e.reason()).isEqualTo(MetadataException.Reason.TAG_VERSION_CONFLICT));
    }

    @Test
    void occupiedNameDoesNotCreateSuccessReceipt() {
        when(access.tagManager(true)).thenReturn(7L);
        when(tags.nextId()).thenReturn(9L);
        when(tags.insert(any())).thenReturn(0);
        assertThatThrownBy(() -> service.write(null, new MetadataInputs.TagWrite("停用词", "停用词", true, null), "create"))
                .isInstanceOfSatisfying(MetadataException.class, e -> assertThat(e.reason()).isEqualTo(MetadataException.Reason.NAME_CONFLICT));
        verify(metadata, never()).insertCommand(any());
    }

    @Test
    void candidatesBindPurposeAndUseExactAuthorizedScope() {
        var row = new TagRow(9, "停用词", "停用词", false, 2, null, null);
        when(tags.list(null, null, null, 2)).thenReturn(List.of(row, row));
        var page = service.managed(new MetadataInputs.TagQuery(null, null, 1, null));
        var doc = new DocumentSummary();
        doc.setSpaceId(2L);
        when(access.document(10, true)).thenReturn(new MetadataAccess.Editable(7, doc));
        assertThatThrownBy(() -> service.editable(10, new MetadataInputs.TagQuery(null, null, 1, page.nextCursor())))
                .isInstanceOf(MetadataException.class);
        when(access.view()).thenReturn(PermissionScope.of(7, (short) 1, List.of(), List.of("1:7")));
        when(tags.visible("%词%", null, 21, List.of(), List.of("1:7"), (short) 1)).thenReturn(List.of(row));
        assertThat(service.filter(new MetadataInputs.TagQuery("词", null, 20, null)).items().getFirst().enabled()).isFalse();
        when(tags.list(null, true, null, 21)).thenReturn(List.of());
        assertThat(service.editable(10, new MetadataInputs.TagQuery(null, null, 20, null)).items()).isEmpty();
        verify(tags).list(null, true, null, 21);
        when(tags.visible(null, null, 21, List.of(), List.of("1:7"), (short) 1)).thenThrow(new IllegalStateException("不可用"));
        assertThatThrownBy(() -> service.filter(new MetadataInputs.TagQuery(null, null, 20, null))).isInstanceOf(IllegalStateException.class);
    }
}
