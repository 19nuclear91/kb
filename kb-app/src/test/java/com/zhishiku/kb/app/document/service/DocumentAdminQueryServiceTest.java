package com.zhishiku.kb.app.document.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhishiku.kb.app.auth.model.Permission;
import com.zhishiku.kb.app.auth.service.AdminQueryAccess;
import com.zhishiku.kb.app.auth.service.PermissionService;
import com.zhishiku.kb.app.auth.service.PermissionService.PrincipalHandle;
import com.zhishiku.kb.app.document.mapper.DocumentAdminQueryMapper;
import com.zhishiku.kb.app.document.model.DocumentAdminView;
import com.zhishiku.kb.app.query.QueryException;
import com.zhishiku.kb.common.security.IdentityException;
import com.zhishiku.kb.common.security.PermissionScope;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class DocumentAdminQueryServiceTest {
    @Test
    void manageOnlyUsesFreshScopeAndReturnsOnlyThreeFields() throws Exception {
        var mapper = mock(DocumentAdminQueryMapper.class);
        var access = mock(AdminQueryAccess.class);
        var permissions = mock(PermissionService.class);
        var handle = mock(PrincipalHandle.class);
        when(access.current()).thenReturn(handle);
        when(handle.functionalPermissions()).thenReturn(Set.of());
        when(permissions.resolve(handle, PermissionService.PERM_MANAGE)).thenReturn(
                PermissionScope.of(7, (short) 0, List.of(3L), List.of()),
                PermissionScope.of(7, (short) 0, List.of(), List.of()));
        when(mapper.list(false, List.of(3L), null, null, null, null, 2)).thenReturn(
                List.of(new DocumentAdminView(9, 3, true), new DocumentAdminView(8, 3, false)));
        var service = new DocumentAdminQueryService(mapper, access, permissions);
        var page = service.list(Map.of("limit", List.of("1")));
        assertThat(page.items()).containsExactly(new DocumentAdminView(9, 3, true));
        assertThat(page.hasMore()).isTrue();
        var json = new ObjectMapper().valueToTree(page.items().getFirst());
        assertThat(json.size()).isEqualTo(3);
        assertThat(json.has("documentId") && json.has("spaceId") && json.has("spaceArchived")).isTrue();
        assertThat(service.list(Map.of("cursor", List.of(page.nextCursor()))).items()).isEmpty();
        verify(mapper, never()).list(anyBoolean(), anyList(), any(), any(), any(), eq(9L), anyInt());
        assertThatThrownBy(() -> service.list(Map.of("title", List.of("secret")))).isInstanceOf(QueryException.class);
    }

    @Test
    void unrelatedReadEditOwnerAndDocumentExceptionsDoNotBecomeManagement() {
        for (Set<String> codes : List.of(Set.<String>of(), Set.of("view"), Set.of("edit"), Set.of("owner"), Set.of(
                "document:view"))) {
            var mapper = mock(DocumentAdminQueryMapper.class);
            var access = mock(AdminQueryAccess.class);
            var permissions = mock(PermissionService.class);
            var handle = mock(PrincipalHandle.class);
            when(access.current()).thenReturn(handle);
            when(handle.functionalPermissions()).thenReturn(codes);
            when(permissions.resolve(handle, PermissionService.PERM_MANAGE))
                    .thenReturn(PermissionScope.of(7, (short) 0, List.of(), List.of("1:7")));
            assertThat(new DocumentAdminQueryService(mapper, access, permissions).list(Map.of()).items()).isEmpty();
            verifyNoInteractions(mapper);
        }
    }

    @Test
    void globalAndMovedDocumentUseCurrentMapperFactAndFailuresPropagate() {
        var mapper = mock(DocumentAdminQueryMapper.class);
        var access = mock(AdminQueryAccess.class);
        var permissions = mock(PermissionService.class);
        var handle = mock(PrincipalHandle.class);
        when(access.current()).thenReturn(handle);
        when(handle.functionalPermissions()).thenReturn(Set.of(Permission.SPACE_MANAGE_ALL.code()));
        when(mapper.list(true, List.of(), null, 9L, null, null, 1))
                .thenReturn(List.of(new DocumentAdminView(9, 3, true)), List.of(new DocumentAdminView(9, 4, false)),
                        List.of());
        var service = new DocumentAdminQueryService(mapper, access, permissions);
        assertThat(service.managedSpaceOf(handle, 9)).isEqualTo(3);
        assertThat(service.managedSpaceOf(handle, 9)).isEqualTo(4);
        assertThatThrownBy(() -> service.managedSpaceOf(handle, 9)).isInstanceOf(QueryException.class);
        verifyNoInteractions(permissions);
        when(mapper.list(true, List.of(), null, null, null, null, 21)).thenThrow(new DataAccessResourceFailureException("secret"));
        assertThatThrownBy(() -> service.list(Map.of())).isInstanceOf(DataAccessResourceFailureException.class);
        doThrow(IdentityException.disabled("disabled")).when(handle).requireEnabled();
        assertThatThrownBy(() -> service.list(Map.of())).isInstanceOf(IdentityException.class);
    }
}
