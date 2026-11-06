package com.zhishiku.kb.app.auth.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhishiku.kb.app.auth.mapper.IdentityDepartmentMapper;
import com.zhishiku.kb.app.auth.mapper.RolePermissionMapper;
import com.zhishiku.kb.app.auth.model.DepartmentRecord;
import com.zhishiku.kb.app.auth.model.EmergencyIdentityFact;
import com.zhishiku.kb.app.auth.model.IdentityRecord;
import com.zhishiku.kb.app.auth.model.RoleRecord;
import com.zhishiku.kb.app.auth.model.UserRolePermission;
import com.zhishiku.kb.app.config.AuthPasswordProperties;
import com.zhishiku.kb.common.security.IdentityConflictReason;
import com.zhishiku.kb.common.security.IdentityException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EmergencyMutationGuardTest {
    private final IdentityDepartmentMapper mapper = mock(IdentityDepartmentMapper.class);
    private final IdentityAuditService audit = mock(IdentityAuditService.class);
    private final IdentityService users = new IdentityService(mapper, null, audit, mock(SecurityLevelDictionary.class),
            new BCryptPasswordEncoder(4), new AuthPasswordProperties(8));

    @BeforeEach
    void onlyEmergency() {
        when(mapper.lifecycleProtectionRequired()).thenReturn(true);
        when(mapper.findEmergencyIdentities()).thenReturn(List.of(
                new EmergencyIdentityFact(7, 2L, "EMERGENCY", true, true, true, true, false, "CLEAR")));
        when(mapper.findEmergencyPermissions()).thenReturn(ManagementInvariant.EMERGENCY_PERMISSIONS.stream()
                .map(code -> new UserRolePermission(7L, 1L, code)).toList());
        // 普通管理员仍在，旧最后管理员规则不能代替独立应急保障。
        when(mapper.findEnabledManagementPermissions()).thenReturn(List.of(
                new UserRolePermission(8L, 9L, "identity:role:assign"), new UserRolePermission(8L, 9L, "identity:role:write")));
        var user = new IdentityRecord(7L, "emergency", "应急", 2L, (short) 1, true, 0L, null, null);
        when(mapper.findIdentity(7)).thenReturn(user);
        when(mapper.findIdentityForUpdate(7)).thenReturn(user);
        when(mapper.findDepartmentForUpdate(2)).thenReturn(department(2, true));
        when(mapper.findDepartment(3)).thenReturn(department(3, false));
    }

    @Test
    void manualDisableAndCredentialResetCannotWeakenLastGuarantee() {
        conflict(() -> users.setEnabled(7, false, 0, 8L, "disable"));
        conflict(() -> users.resetPassword(7, "new-password", 8L, "reset"));
        verify(mapper, never()).setUserAccessState(anyLong(), anyBoolean(), anyBoolean(), anyLong(), anyLong());
        verify(mapper, never()).updatePassword(anyLong(), anyString(), anyBoolean());
        verify(audit, never()).success(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void disablingOrMovingIntoInvalidDepartmentCannotWeakenLastGuarantee() {
        conflict(() -> new DepartmentService(mapper, audit).setEnabled(2, false, 0, 8L, "department"));
        conflict(() -> users.moveToDepartment(7, 3, 0, 8L, "move"));
        verify(mapper, never()).setDepartmentEnabled(anyLong(), anyBoolean(), anyLong());
        verify(mapper, never()).moveUserToDepartment(anyLong(), anyLong(), anyLong());
    }

    @Test
    void unbindingRoleAndRemovingLifecyclePermissionAreBothProtected() {
        conflict(() -> users.revokeRole(7, 1, 8L, "unbind"));
        var roles = mock(RolePermissionMapper.class);
        when(roles.lockRole(1)).thenReturn(new RoleRecord(1L, "custom", "自定义"));
        when(roles.findRolePermissions(1)).thenReturn(List.of("identity:lifecycle:restore"));
        var service = new RoleManagementService(mapper, roles, audit, new ObjectMapper());
        conflict(() -> service.setPermission(1, "identity:lifecycle:restore", false, 8, "remove-permission"));
        verify(mapper, never()).deleteUserRole(anyLong(), anyLong());
        verify(roles, never()).revokePermission(anyLong(), anyString());
        verify(roles, never()).grantPermission(anyLong(), anyString());
    }

    @Test
    void validDepartmentMoveKeepsGuardAndAcquiresManagementBeforeUserLock() {
        when(mapper.findDepartment(3)).thenReturn(department(3, true));
        when(mapper.moveUserToDepartment(7, 3, 0)).thenReturn(1);
        users.moveToDepartment(7, 3, 0, 8L, "move");
        var order = inOrder(mapper);
        order.verify(mapper).lockManagement();
        order.verify(mapper).lockLifecycleForUser(7);
        order.verify(mapper).findIdentityForUpdate(7);
        order.verify(mapper).moveUserToDepartment(7, 3, 0);
        order.verify(mapper).findEmergencyPermissions();
    }

    private static DepartmentRecord department(long id, boolean enabled) {
        return new DepartmentRecord(id, null, "department", "部门", "/" + id + "/", enabled, 0L, null, null);
    }

    private static void conflict(Runnable operation) {
        assertThatThrownBy(operation::run).isInstanceOfSatisfying(IdentityException.class, error -> {
            assertThat(error.httpStatus()).isEqualTo(409);
            assertThat(error.reason()).contains(IdentityConflictReason.EMERGENCY_MANAGER_REQUIRED);
        });
    }
}
