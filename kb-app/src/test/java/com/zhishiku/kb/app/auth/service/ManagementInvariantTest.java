package com.zhishiku.kb.app.auth.service;

import com.zhishiku.kb.app.auth.mapper.IdentityDepartmentMapper;
import com.zhishiku.kb.app.auth.model.ManagementChange;
import com.zhishiku.kb.app.auth.model.EmergencyIdentityFact;
import com.zhishiku.kb.common.security.IdentityException;
import com.zhishiku.kb.app.auth.model.UserRolePermission;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ManagementInvariantTest {
    @Test
    void secondExitRechecksCommittedFirstExitInsteadOfUsingOldQualification() {
        var mapper = mock(IdentityDepartmentMapper.class);
        var identities = new java.util.ArrayList<EmergencyIdentityFact>();
        identities.add(emergency("EMERGENCY", true, true, false));
        identities.add(new EmergencyIdentityFact(8, 2L, "EMERGENCY", true, true, true, true, false, "CLEAR"));
        when(mapper.findEmergencyIdentities()).thenAnswer(call -> List.copyOf(identities));
        when(mapper.findEmergencyPermissions()).thenReturn(java.util.stream.Stream.of(7L, 8L)
                .flatMap(id -> ManagementInvariant.EMERGENCY_PERMISSIONS.stream()
                        .map(code -> new UserRolePermission(id, 1L, code))).toList());
        when(mapper.lifecycleProtectionRequired()).thenReturn(true);
        var guard = new ManagementInvariant(mapper);
        guard.requireEmergencyAfter(ManagementChange.disableUser(7));
        identities.removeIf(identity -> identity.userId() == 7);
        assertThatThrownBy(() -> guard.requireEmergencyAfter(ManagementChange.disableUser(8)))
                .isInstanceOf(IdentityException.class);
        assertThat(identities).hasSize(1);
    }

    @Test
    void emergencyUsesExplicitUnionOfPermissionsAndRejectsDepartmentCredentialOrManagedFailures() {
        var mapper = mock(IdentityDepartmentMapper.class);
        var guard = new ManagementInvariant(mapper);
        when(mapper.findEmergencyPermissions()).thenReturn(ManagementInvariant.EMERGENCY_PERMISSIONS.stream()
                .map(code -> new UserRolePermission(7L, (long) code.length(), code)).toList());
        when(mapper.findEmergencyIdentities()).thenReturn(List.of(emergency("EMERGENCY", true, true, false)));
        assertThat(guard.emergencyRemainsAfter(ManagementChange.unchanged())).isTrue();
        assertThat(guard.emergencyRemainsAfter(ManagementChange.disableUser(7))).isFalse();
        for (var invalid : List.of(emergency("EMERGENCY", false, true, false), emergency("EMERGENCY", true, false, false),
                emergency("EMERGENCY", true, true, true), emergency("MANAGED", true, true, false))) {
            when(mapper.findEmergencyIdentities()).thenReturn(List.of(invalid));
            assertThat(guard.emergencyRemainsAfter(ManagementChange.unchanged())).isFalse();
        }
    }

    @Test
    void roleNameNeverQualifiesAndSubscriptionToggleCannotDisablePersistentProtection() {
        var mapper = mock(IdentityDepartmentMapper.class);
        when(mapper.findEmergencyIdentities()).thenReturn(List.of(emergency("EMERGENCY", true, true, false)));
        when(mapper.findEmergencyPermissions()).thenReturn(List.of(new UserRolePermission(7L, 1L, "ROLE_ADMIN")));
        when(mapper.lifecycleProtectionRequired()).thenReturn(true);
        var guard = new ManagementInvariant(mapper);
        assertThatThrownBy(() -> guard.requireEmergencyAfter(ManagementChange.unchanged()))
                .isInstanceOfSatisfying(IdentityException.class, error -> assertThat(error.httpStatus()).isEqualTo(409));
    }

    private static EmergencyIdentityFact emergency(String kind, boolean department, boolean password, boolean mustChange) {
        return new EmergencyIdentityFact(7, 2L, kind, true, true, department, password, mustChange, "CLEAR");
    }
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
