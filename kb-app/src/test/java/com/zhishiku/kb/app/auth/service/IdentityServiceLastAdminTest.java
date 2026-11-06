package com.zhishiku.kb.app.auth.service;

import com.zhishiku.kb.app.auth.mapper.IdentityDepartmentMapper;
import com.zhishiku.kb.app.auth.model.IdentityRecord;
import com.zhishiku.kb.app.auth.model.UserRolePermission;
import com.zhishiku.kb.common.security.IdentityConflictReason;
import com.zhishiku.kb.common.security.IdentityErrorCode;
import com.zhishiku.kb.common.security.IdentityException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyBoolean;
import static org.mockito.Mockito.anyLong;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 三条资格收缩路径共用的先锁后判约束；角色 code 不参与判定。
 */
class IdentityServiceLastAdminTest {
    private final IdentityDepartmentMapper mapper = mock(IdentityDepartmentMapper.class);
    private final IdentityAuditService audit = mock(IdentityAuditService.class);
    private final IdentityService service = new IdentityService(mapper, null, audit);

    private void lastHolder() {
        when(mapper.findEnabledManagementPermissions()).thenReturn(List.of(
                new UserRolePermission(7L, 2L, "identity:role:assign"),
                new UserRolePermission(7L, 3L, "identity:role:write")));
        when(mapper.findIdentity(7L)).thenReturn(user(true));
        when(mapper.findIdentityForUpdate(7L)).thenReturn(user(true));
    }

    private void anotherHolder() {
        lastHolder();
        when(mapper.findEnabledManagementPermissions()).thenReturn(List.of(
                new UserRolePermission(7L, 2L, "identity:role:assign"),
                new UserRolePermission(7L, 3L, "identity:role:write"),
                new UserRolePermission(8L, 4L, "identity:role:assign"),
                new UserRolePermission(8L, 5L, "identity:role:write")));
    }

    private static void lastManager(IdentityException e) {
        assertThat(e.httpStatus()).isEqualTo(409);
        assertThat(e.errorCode()).isEqualTo(IdentityErrorCode.CONFLICT);
        assertThat(e.reason()).contains(IdentityConflictReason.LAST_MANAGER_REQUIRED);
    }

    @Test
    void revokingTheLastAdminRoleIsRejected() {
        lastHolder();
        assertThatThrownBy(() -> service.revokeRole(7L, 2L, 1L, "revoke"))
                .isInstanceOfSatisfying(IdentityException.class, IdentityServiceLastAdminTest::lastManager);
        verify(mapper, never()).deleteUserRole(anyLong(), anyLong());
        verify(audit, never()).success(any(), any(), any(), any(), any(), any(), any());
        var order = inOrder(mapper);
        order.verify(mapper).lockManagement();
        order.verify(mapper).findEnabledManagementPermissions();
    }

    @Test
    void disablingTheLastAdminIsRejected() {
        lastHolder();
        assertThatThrownBy(() -> service.setEnabled(7L, false, 0L, 1L, "disable"))
                .isInstanceOfSatisfying(IdentityException.class, IdentityServiceLastAdminTest::lastManager);
        verify(mapper, never()).setUserAccessState(anyLong(), anyBoolean(), anyBoolean(), anyLong(), anyLong());
        verify(audit, never()).success(any(), any(), any(), any(), any(), any(), any());
        var order = inOrder(mapper);
        order.verify(mapper).lockManagement();
        order.verify(mapper).findIdentityForUpdate(7L);
        order.verify(mapper).findEnabledManagementPermissions();
    }

    @Test
    void revokingAnAdminWithAnotherHolderProceeds() {
        anotherHolder();
        when(mapper.deleteUserRole(7L, 2L)).thenReturn(1);
        service.revokeRole(7L, 2L, 1L, "revoke");
        var order = inOrder(mapper);
        order.verify(mapper).lockManagement();
        order.verify(mapper).findEnabledManagementPermissions();
        order.verify(mapper).deleteUserRole(7L, 2L);
        verify(audit).success(eq(AuditScope.USER_ROLE_REVOKE), eq("revoke"), eq(1L), eq(7L), anyString(), anyString()
                , anyString());
    }

    @Test
    void disablingAnAdminWithAnotherHolderProceeds() {
        anotherHolder();
        when(mapper.setUserAccessState(7L, false, false, 2L, 0L)).thenReturn(1);
        when(mapper.findIdentity(7L)).thenReturn(user(false));
        assertThat(service.setEnabled(7L, false, 0L, 1L, "disable").enabled()).isFalse();
        var order = inOrder(mapper);
        order.verify(mapper).lockManagement();
        order.verify(mapper).findEnabledManagementPermissions();
        order.verify(mapper).setUserAccessState(7L, false, false, 2L, 0L);
    }

    @Test
    void revokingAnUnrelatedRoleStillChecksGlobalStateAndProceeds() {
        lastHolder();
        when(mapper.deleteUserRole(7L, 9L)).thenReturn(1);
        service.revokeRole(7L, 9L, 1L, "revoke-unrelated");
        var order = inOrder(mapper);
        order.verify(mapper).lockManagement();
        order.verify(mapper).findEnabledManagementPermissions();
        order.verify(mapper).deleteUserRole(7L, 9L);
    }

    private static IdentityRecord user(boolean enabled) {
        return new IdentityRecord(7L, "bob", "Bob", 2L, (short) 1, enabled, 0L, null, null);
    }
}
