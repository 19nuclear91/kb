package com.zhishiku.kb.app.auth.service.diagnostics;

import com.zhishiku.kb.app.config.DiagnosticLimits;

import com.zhishiku.kb.app.auth.mapper.IdentityDepartmentMapper;
import com.zhishiku.kb.app.auth.mapper.PermissionMapper;
import com.zhishiku.kb.app.auth.mapper.RolePermissionMapper;
import com.zhishiku.kb.app.auth.model.DepartmentRecord;
import com.zhishiku.kb.app.auth.model.IdentityRecord;
import com.zhishiku.kb.app.auth.service.IdentityService;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class DiagnosticTargetLoaderTest {
    private final IdentityDepartmentMapper identities = mock(IdentityDepartmentMapper.class);
    private final PermissionMapper permissions = mock(PermissionMapper.class);
    private final RolePermissionMapper roles = mock(RolePermissionMapper.class);
    private final DiagnosticTargetLoader loader = new DiagnosticTargetLoader(identities,
            new IdentityService(identities), permissions, roles, DiagnosticLimits.defaults());

    @Test
    void blockedIdentityDoesNotReadMembershipOrClearanceAndMissingIsDifferent() {
        var user = new IdentityRecord(8L, "private-login", "目标", 5L, (short) 9, false, 0L, null, null);
        when(identities.findIdentity(8)).thenReturn(user);
        assertBlocked();
        user.setManualEnabled(true);
        user.setLifecycleRestriction("TERMINATED");
        assertBlocked();
        user.setEnabled(true);
        user.setLifecycleRestriction("CLEAR");
        assertBlocked();
        when(identities.findDepartment(5)).thenReturn(new DepartmentRecord(5L, null, "D", "部门", "/5/", false, 0L, null, null));
        assertBlocked();
        when(identities.findDepartment(5)).thenReturn(new DepartmentRecord(5L, null, "D", "部门", "/5/", true, 0L, null, null));
        when(identities.findMustChangePasswordById(8)).thenReturn(true);
        assertBlocked();
        when(identities.findIdentity(8)).thenReturn(null);
        assertReason(DiagnosticException.Reason.NOT_FOUND);
        when(identities.findIdentity(8)).thenThrow(new DataAccessResourceFailureException("private database detail"));
        assertReason(DiagnosticException.Reason.DIAGNOSTIC_UNAVAILABLE);
        verifyNoInteractions(permissions, roles);
    }

    @Test
    void enabledTargetUsesBoundedServerMembershipAndOnlyGlobalManage() {
        when(identities.findIdentity(8)).thenReturn(new IdentityRecord(8L, "private", "目标", 5L, (short) 3, true, 0L, null, null));
        when(identities.findDepartment(5)).thenReturn(new DepartmentRecord(5L, null, "D", "部门", "/1/5/", true, 0L, null, null));
        when(identities.findMustChangePasswordById(8)).thenReturn(false);
        when(permissions.findRoleIdsBounded(8, 4096)).thenReturn(List.of(2L, 3L));
        when(permissions.findDeptChainIdsBounded(8, 4094)).thenReturn(List.of(1L, 5L));
        when(roles.findUserPermissions(8)).thenReturn(List.of("space:manage-all", "authorization:diagnose"));
        var target = loader.load(8);
        assertThat(target.passed()).isTrue();
        assertThat(target.keys()).containsExactlyInAnyOrder("1:8", "2:2", "2:3", "3:1", "3:5");
        assertThat(target.globalManage()).isTrue();
        clearInvocations(roles);
        var tiny = new DiagnosticTargetLoader(identities, new IdentityService(identities), permissions, roles,
                new DiagnosticLimits(65536, 2, 1000, 524288, 5000));
        when(permissions.findRoleIdsBounded(8, 2)).thenReturn(List.of(2L, 3L));
        assertThatThrownBy(() -> tiny.load(8)).isInstanceOfSatisfying(DiagnosticException.class,
                error -> assertThat(error.reason()).isEqualTo(DiagnosticException.Reason.DIAGNOSTIC_LIMIT_EXCEEDED));
        verifyNoInteractions(roles);
    }

    private void assertBlocked() {
        var target = loader.load(8);
        assertThat(target.passed()).isFalse();
        assertThat(target.keys()).isEmpty();
        assertThat(target.clearance()).isZero();
        assertThat(target.globalManage()).isFalse();
    }

    private void assertReason(DiagnosticException.Reason reason) {
        assertThatThrownBy(() -> loader.load(8)).isInstanceOfSatisfying(DiagnosticException.class,
                error -> assertThat(error.reason()).isEqualTo(reason));
    }
}
