package com.zhishiku.kb.app.document.metadata;

import com.zhishiku.kb.app.auth.service.AdminQueryAccess;
import com.zhishiku.kb.app.auth.service.PermissionService;
import com.zhishiku.kb.app.document.mapper.DocumentMapper;
import com.zhishiku.kb.app.document.metadata.service.MetadataAccess;
import com.zhishiku.kb.app.document.metadata.service.MetadataException;
import com.zhishiku.kb.app.document.model.DocumentSummary;
import com.zhishiku.kb.app.space.mapper.SpaceMapper;
import com.zhishiku.kb.common.security.PermissionScope;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MetadataAccessTest {
    @Test
    void documentExceptionEditRequiresIndependentViewAndEdit() {
        var current = mock(AdminQueryAccess.class);
        var actor = mock(PermissionService.PrincipalHandle.class);
        var permissions = mock(PermissionService.class);
        var documents = mock(DocumentMapper.class);
        var access = new MetadataAccess(current, permissions, documents, mock(SpaceMapper.class), mock(SqlSessionTemplate.class));
        var scope = PermissionScope.of(7, (short) 1, List.of(), List.of("1:7"));
        when(current.current()).thenReturn(actor);
        when(actor.userId()).thenReturn(7L);
        when(permissions.resolve(actor, "view")).thenReturn(scope);
        when(permissions.resolve(actor, "edit")).thenReturn(scope);
        var document = new DocumentSummary();
        document.setSpaceId(2L);
        document.setStatus("published");
        when(documents.findSummary(9, List.of(), List.of("1:7"), (short) 1, "view")).thenReturn(document);
        assertThatThrownBy(() -> access.document(9, true)).isInstanceOfSatisfying(MetadataException.class,
                e -> assertThat(e.reason()).isEqualTo(MetadataException.Reason.FORBIDDEN));
        when(documents.findSummary(9, List.of(), List.of("1:7"), (short) 1, "edit")).thenReturn(document);
        assertThat(access.document(9, true).document().getSpaceId()).isEqualTo(2);
        when(documents.findSummary(9, List.of(), List.of("1:7"), (short) 1, "view")).thenReturn(null);
        assertThatThrownBy(() -> access.document(9, true)).isInstanceOfSatisfying(MetadataException.class,
                e -> assertThat(e.reason()).isEqualTo(MetadataException.Reason.NOT_FOUND));
    }
}
