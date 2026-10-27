package com.zhishiku.kb.app.space.service;

import com.zhishiku.kb.app.auth.model.Permission;
import com.zhishiku.kb.app.auth.service.AdminQueryAccess;
import com.zhishiku.kb.app.auth.service.IdentityQueryService;
import com.zhishiku.kb.app.auth.service.PermissionService;
import com.zhishiku.kb.app.auth.service.PermissionService.PrincipalHandle;
import com.zhishiku.kb.app.document.service.DocumentAdminQueryService;
import com.zhishiku.kb.app.query.QueryException;
import com.zhishiku.kb.app.space.mapper.SpaceMapper;
import com.zhishiku.kb.app.space.model.SpaceRecord;
import com.zhishiku.kb.common.security.PermissionScope;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PrincipalQueryServiceTest {
    @Test
    void contextualQualificationPrecedesCandidateQueryAndIsRepeated() {
        var access = mock(AdminQueryAccess.class);
        var handle = mock(PrincipalHandle.class);
        var spaces = mock(SpaceQueryService.class);
        var documents = mock(DocumentAdminQueryService.class);
        var identities = mock(IdentityQueryService.class);
        when(access.current()).thenReturn(handle);
        var service = new PrincipalQueryService(access, spaces, documents, identities);
        service.space("3", Map.of("principalType", List.of("USER")));
        verify(spaces).requireManaged(handle, 3);
        verify(identities).principals(eq("space-principals"), eq(3L), any());
        when(documents.managedSpaceOf(handle, 9)).thenReturn(3L).thenThrow(QueryException.missing());
        service.document("9", Map.of("principalType", List.of("ROLE")));
        assertThatThrownBy(() -> service.document("9", Map.of("principalType", List.of("ROLE"))))
                .isInstanceOfSatisfying(QueryException.class, e -> assertThat(e.status()).isEqualTo(404));
        doThrow(QueryException.missing()).when(spaces).requireManaged(handle, 4);
        assertThatThrownBy(() -> service.space("4", Map.of())).isInstanceOf(QueryException.class);
        verify(identities, never()).principals(eq("space-principals"), eq(4L), any());
    }

    @Test
    void archivedSpaceManageIsIndependentOfViewAndGlobalChecksExistence() {
        var mapper = mock(SpaceMapper.class);
        var permissions = mock(PermissionService.class);
        var handle = mock(PrincipalHandle.class);
        var service = new SpaceQueryService(mapper, permissions, mock(SpaceAuthorizer.class));
        var archived = new SpaceRecord();
        archived.setArchived(true);
        when(handle.functionalPermissions()).thenReturn(Set.of());
        when(permissions.resolve(handle, PermissionService.PERM_MANAGE)).thenReturn(PermissionScope.of(7, (short) 0,
                List.of(3L), List.of()));
        when(mapper.findById(3, List.of(), List.of(3L), false)).thenReturn(archived);
        service.requireManaged(handle, 3);
        assertThatThrownBy(() -> service.requireManaged(handle, 4)).isInstanceOf(QueryException.class);
        when(handle.functionalPermissions()).thenReturn(Set.of(Permission.SPACE_MANAGE_ALL.code()));
        assertThatThrownBy(() -> service.requireManaged(handle, 99)).isInstanceOf(QueryException.class);
        verify(permissions, never()).resolve(handle, PermissionService.PERM_VIEW);
    }
}
