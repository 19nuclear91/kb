package com.zhishiku.kb.app.document.metadata;

import com.zhishiku.kb.app.document.metadata.mapper.CategoryMapper;
import com.zhishiku.kb.app.document.metadata.model.CategoryRow;
import com.zhishiku.kb.app.document.metadata.model.MetadataInputs;
import com.zhishiku.kb.app.document.metadata.service.CategoryService;
import com.zhishiku.kb.app.document.metadata.service.MetadataAccess;
import com.zhishiku.kb.app.document.metadata.service.MetadataException;
import com.zhishiku.kb.app.document.metadata.service.MetadataCursor;
import com.zhishiku.kb.app.document.model.DocumentSummary;
import com.zhishiku.kb.common.security.PermissionScope;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CategoryReadTest {
    @Test
    void editCursorExpiresAfterRelocationAndEmptyFilterDoesNotExposeTreeVersion() {
        var mapper = mock(CategoryMapper.class);
        var access = mock(MetadataAccess.class);
        var service = new CategoryService(mapper, access);
        var document = new DocumentSummary();
        document.setSpaceId(3L);
        when(access.document(9, true)).thenReturn(new MetadataAccess.Editable(7, document));
        when(mapper.version(3)).thenReturn(4L);
        String old = new MetadataCursor(MetadataCursor.context("category-edit", 9L, null, ""), 2L, 4, 0, 1).encode();
        assertThatThrownBy(() -> service.editable(9, new MetadataInputs.CategoryQuery(null, null, 20, old)))
                .isInstanceOf(MetadataException.class);
        when(access.view()).thenReturn(PermissionScope.of(7, (short) 1, List.of(3L), List.of("1:7")));
        when(mapper.visibleChildren(3, null, null, null, 21, List.of(3L), List.of("1:7"), (short) 1)).thenReturn(List.of());
        assertThat(service.filter(new MetadataInputs.CategoryQuery(3L, null, 20, null)).treeVersion()).isZero();
    }
    @Test
    void pageBindsParentSpaceAndTreeVersion() {
        var mapper = mock(CategoryMapper.class);
        var service = new CategoryService(mapper, mock(MetadataAccess.class));
        var first = new CategoryRow(1, 2, null, "甲", "甲", 0, null, null);
        var second = new CategoryRow(3, 2, null, "乙", "乙", 0, null, null);
        when(mapper.version(2)).thenReturn(4L);
        when(mapper.children(2, null, null, null, 2)).thenReturn(List.of(first, second));
        when(mapper.path(2, 1)).thenReturn(List.of(first));
        var page = service.managed(2, new MetadataInputs.CategoryQuery(null, null, 1, null));
        assertThat(page.items()).hasSize(1);
        assertThat(page.hasMore()).isTrue();
        assertThat(page.items().getFirst().path().getFirst().name()).isEqualTo("甲");
        assertThatThrownBy(() -> MetadataCursor.decode(page.nextCursor(),
                MetadataCursor.context("category-edit", 2L, null, ""), 2L, 4)).isInstanceOf(MetadataException.class);
        assertThatThrownBy(() -> MetadataCursor.decode(page.nextCursor(),
                MetadataCursor.context("category-manager", 2L, null, ""), 3L, 4)).isInstanceOf(MetadataException.class);
        assertThatThrownBy(() -> service.managed(2, new MetadataInputs.CategoryQuery(null, 3L, 1, page.nextCursor())))
                .isInstanceOfSatisfying(MetadataException.class, e -> assertThat(e.reason()).isEqualTo(MetadataException.Reason.INVALID_INPUT));
        when(mapper.version(2)).thenReturn(5L);
        assertThatThrownBy(() -> service.managed(2, new MetadataInputs.CategoryQuery(null, null, 1, page.nextCursor())))
                .isInstanceOfSatisfying(MetadataException.class, e -> assertThat(e.reason()).isEqualTo(MetadataException.Reason.TREE_VERSION_CONFLICT));
    }
}
