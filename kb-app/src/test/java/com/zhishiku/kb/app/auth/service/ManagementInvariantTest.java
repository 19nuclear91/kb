package com.zhishiku.kb.app.auth.service;

import com.zhishiku.kb.app.auth.mapper.IdentityDepartmentMapper;
import com.zhishiku.kb.app.auth.model.ManagementChange;
import com.zhishiku.kb.app.auth.model.UserRolePermission;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ManagementInvariantTest {
    @Test
    void qualificationComesFromUserUnionAcrossRolesAndUsesAfterProjection() {
        var mapper = mock(IdentityDepartmentMapper.class);
        var guard = new ManagementInvariant(mapper);
        when(mapper.findEnabledManagementPermissions()).thenReturn(List.of(
                new UserRolePermission(7L, 2L, "identity:role:assign"),
                new UserRolePermission(7L, 3L, "identity:role:write"),
                new UserRolePermission(8L, 2L, "identity:role:assign")));
        assertThat(guard.remainsAfter(ManagementChange.unchanged())).isTrue();
        assertThat(guard.remainsAfter(ManagementChange.revokePermission(2L, "identity:role:assign"))).isFalse();
        assertThat(guard.remainsAfter(ManagementChange.unbindRole(7L, 3L))).isFalse();
        assertThat(guard.remainsAfter(ManagementChange.disableUser(7L))).isFalse();
        assertThat(guard.remainsAfter(ManagementChange.disableUser(8L))).isTrue();
    }

    @Test
    void anotherRoleWithSamePermissionPreservesQualification() {
        var mapper = mock(IdentityDepartmentMapper.class);
        when(mapper.findEnabledManagementPermissions()).thenReturn(List.of(
                new UserRolePermission(7L, 2L, "identity:role:assign"),
                new UserRolePermission(7L, 3L, "identity:role:assign"),
                new UserRolePermission(7L, 3L, "identity:role:write")));
        assertThat(new ManagementInvariant(mapper).remainsAfter(
                ManagementChange.revokePermission(2L, "identity:role:assign"))).isTrue();
    }

    @Test
    void permissionsSplitAcrossUsersDoNotQualifyAnyone() {
        var mapper = mock(IdentityDepartmentMapper.class);
        when(mapper.findEnabledManagementPermissions()).thenReturn(List.of(
                new UserRolePermission(7L, 2L, "identity:role:assign"),
                new UserRolePermission(8L, 3L, "identity:role:write")));
        assertThat(new ManagementInvariant(mapper).remainsAfter(ManagementChange.unchanged())).isFalse();
    }
}
