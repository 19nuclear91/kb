package com.zhishiku.kb.app.auth.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhishiku.kb.app.auth.mapper.IdentityDepartmentMapper;
import com.zhishiku.kb.app.auth.mapper.RolePermissionMapper;
import com.zhishiku.kb.app.auth.model.IdentityAuditRecord;
import com.zhishiku.kb.app.auth.model.RoleRecord;
import com.zhishiku.kb.app.auth.model.UserRolePermission;
import com.zhishiku.kb.common.security.IdentityException;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyLong;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.contains;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class RolePermissionWriteTest {
    private final IdentityDepartmentMapper identities = mock(IdentityDepartmentMapper.class);
    private final RolePermissionMapper mapper = mock(RolePermissionMapper.class);
    private final IdentityAuditService audit = mock(IdentityAuditService.class);
    private final RoleManagementService service = new RoleManagementService(identities, mapper, audit,
            new ObjectMapper());

    private void role() {
        when(mapper.lockRole(2L)).thenReturn(new RoleRecord(2L, "CUSTOM", "自定义"));
        when(identities.findRoleById(2L)).thenReturn(new RoleRecord(2L, "CUSTOM", "自定义"));
    }

    @Test
    void grantAndReplayWriteOnceAndAuditPermissionCode() {
        role();
        var permissions = new ArrayList<String>();
        when(mapper.findRolePermissions(2L)).thenAnswer(invocation -> List.copyOf(permissions));
        when(mapper.grantPermission(2L, "identity:user:read")).thenAnswer(invocation -> {
            permissions.add("identity:user:read");
            return 1;
        });
        assertThat(service.setPermission(2L, "identity:user:read", true, 1L, "grant").permissions())
                .containsExactly("identity:user:read");
        var event = new IdentityAuditRecord();
        event.setTargetId(2L);
        when(audit.existing(AuditScope.ROLE_PERMISSION_GRANT, "grant")).thenReturn(event);
        service.setPermission(2L, "identity:user:read", true, 1L, "grant");
        verify(mapper, times(1)).grantPermission(2L, "identity:user:read");
        verify(audit, times(1)).success(eq(AuditScope.ROLE_PERMISSION_GRANT), eq("grant"), eq(1L), eq(2L),
                eq("grant_permission"), contains("identity:user:read"), contains("identity:user:read"));
    }

    @Test
    void removingLastQualificationRejectsBeforeAnyWriteAndLocksBeforeQuery() {
        role();
        when(mapper.findRolePermissions(2L)).thenReturn(List.of("identity:role:assign"));
        when(identities.findEnabledManagementPermissions()).thenReturn(List.of(
                new UserRolePermission(7L, 2L, "identity:role:assign"),
                new UserRolePermission(7L, 3L, "identity:role:write")));
        assertThatThrownBy(() -> service.setPermission(2L, "identity:role:assign", false, 1L, "revoke"))
                .isInstanceOfSatisfying(IdentityException.class, e -> assertThat(e.httpStatus()).isEqualTo(409));
        verify(mapper, never()).revokePermission(anyLong(), anyString());
        verify(audit, never()).success(any(), any(), any(), any(), any(), any(), any());
        var order = inOrder(identities, mapper);
        order.verify(identities).lockManagement();
        order.verify(mapper).lockRole(2L);
        order.verify(identities).findEnabledManagementPermissions();
    }

    @Test
    void removingPermissionPreservesQualificationFromOtherRoleAndReplayDoesNotRemoveAgain() {
        role();
        when(mapper.findRolePermissions(2L)).thenReturn(List.of("identity:role:assign"), List.of());
        when(identities.findEnabledManagementPermissions()).thenReturn(List.of(
                new UserRolePermission(7L, 2L, "identity:role:assign"),
                new UserRolePermission(7L, 3L, "identity:role:assign"),
                new UserRolePermission(7L, 3L, "identity:role:write")));
        assertThat(service.setPermission(2L, "identity:role:assign", false, 1L, "revoke").permissions()).isEmpty();
        var order = inOrder(identities, mapper);
        order.verify(identities).lockManagement();
        order.verify(identities).findEnabledManagementPermissions();
        order.verify(mapper).revokePermission(2L, "identity:role:assign");
        var event = new IdentityAuditRecord();
        event.setTargetId(2L);
        when(audit.existing(AuditScope.ROLE_PERMISSION_REVOKE, "revoke")).thenReturn(event);
        service.setPermission(2L, "identity:role:assign", false, 1L, "revoke");
        verify(mapper, times(1)).revokePermission(2L, "identity:role:assign");
        verify(audit, times(1)).success(eq(AuditScope.ROLE_PERMISSION_REVOKE), eq("revoke"), eq(1L), eq(2L),
                eq("revoke_permission"), contains("identity:role:assign"), contains("identity:role:assign"));
    }

    @Test
    void unknownCodeReturns400WithoutChangingBindings() {
        assertThatThrownBy(() -> service.setPermission(2L, "space:*", true, 1L, "unknown"))
                .isInstanceOfSatisfying(IdentityException.class, e -> assertThat(e.httpStatus()).isEqualTo(400));
        verifyNoInteractions(mapper);
        verify(audit, never()).success(any(), any(), any(), any(), any(), any(), any());
    }
}
