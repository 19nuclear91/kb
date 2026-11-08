package com.zhishiku.kb.app.document.imports;

import com.zhishiku.kb.app.auth.service.EpochSessionService;
import com.zhishiku.kb.app.auth.service.PermissionService;
import com.zhishiku.kb.app.document.imports.service.ImportAccess;
import com.zhishiku.kb.common.security.IdentityException;
import com.zhishiku.kb.common.security.PermissionScope;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ImportAccessTest {
    private final EpochSessionService sessions = mock(EpochSessionService.class);
    private final PermissionService permissions = mock(PermissionService.class);
    private final SqlSessionTemplate sql = mock(SqlSessionTemplate.class);
    private final PermissionService.PrincipalHandle principal = mock(PermissionService.PrincipalHandle.class);
    private final ImportAccess access = new ImportAccess(sessions, permissions, sql);

    @BeforeEach
    void setup() {
        when(sessions.check("token")).thenReturn(new EpochSessionService.Session(7, 2, EpochSessionService.Method.LOCAL, false));
        when(permissions.principalOf(7)).thenReturn(principal);
        when(permissions.resolve(principal, PermissionService.PERM_EDIT))
                .thenReturn(PermissionScope.of(7, (short) 0, List.of(9L), List.of("1:7")));
    }

    @Test
    void editAloneAllowsUploadAndEveryCheckRefreshesFacts() {
        assertThat(access.requireUpload("token", 9).userId()).isEqualTo(7);
        assertThat(access.requireUpload("token", 9).authEpoch()).isEqualTo(2);
        verify(sessions, times(2)).check("token");
        verify(sql, times(4)).clearCache();
        verify(permissions, never()).resolve(principal, PermissionService.PERM_VIEW);
        verify(permissions, never()).resolve(principal, PermissionService.PERM_MANAGE);
    }

    @Test
    void revokedOrArchivedSpaceIsNotWritable() {
        access.requireUpload("token", 9);
        when(permissions.resolve(principal, PermissionService.PERM_EDIT)).thenReturn(PermissionScope.denyAll(7));
        assertThat(access.canUpload(7, 9)).isFalse();
        assertThatThrownBy(() -> access.requireUpload("token", 9)).hasMessage("IMPORT_FORBIDDEN");
    }

    @Test
    void expiredEpochOrDisabledDepartmentCannotReachPermissionQuery() {
        for (IdentityException failure : List.of(IdentityException.unauthorized("会话已撤销"), IdentityException.disabled("主部门停用"))) {
            doThrow(failure).when(sessions).check("token");
            assertThatThrownBy(() -> access.requireUpload("token", 9)).isSameAs(failure);
        }
        verifyNoInteractions(permissions);
    }

    @Test
    void mustChangePasswordBlocksUpload() {
        when(sessions.check("token")).thenReturn(new EpochSessionService.Session(7, 2, EpochSessionService.Method.LOCAL, true));
        assertThatThrownBy(() -> access.requireUpload("token", 9)).hasMessage("IMPORT_FORBIDDEN");
        verifyNoInteractions(permissions);
    }

    @Test
    void unknownPermissionFactsAreNotFalseOrAllowed() {
        when(permissions.principalOf(7)).thenThrow(new IllegalStateException("private dependency failure"));
        assertThatThrownBy(() -> access.canUpload(7, 9)).hasMessage("IMPORT_UNAVAILABLE").hasNoCause();
    }

    @Test
    void failedPrincipalCannotBecomeEmptySuccessfulScope() {
        doThrow(IdentityException.disabled("停用")).when(principal).requireEnabled();
        assertThatThrownBy(() -> access.canUpload(7, 9)).isInstanceOf(IdentityException.class);
        verify(permissions, never()).resolve(any(PermissionService.PrincipalHandle.class), anyString());
    }
}
