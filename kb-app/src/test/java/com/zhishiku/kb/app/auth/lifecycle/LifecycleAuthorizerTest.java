package com.zhishiku.kb.app.auth.lifecycle;

import com.zhishiku.kb.app.auth.mapper.RolePermissionMapper;
import com.zhishiku.kb.app.auth.model.Permission;
import com.zhishiku.kb.app.auth.service.EpochSessionService;
import com.zhishiku.kb.common.security.IdentityException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LifecycleAuthorizerTest {
    @Test
    void fourQualificationsAreIndependentAndRoleNamesOrUserWriteDoNotSubstitute() {
        var sessions = mock(EpochSessionService.class);
        var roles = mock(RolePermissionMapper.class);
        when(sessions.check("token")).thenReturn(new EpochSessionService.Session(7, 1, EpochSessionService.Method.LOCAL, false));
        var auth = new LifecycleAuthorizer(sessions, roles);
        var permissions = List.of(Permission.IDENTITY_LIFECYCLE_READ, Permission.IDENTITY_LIFECYCLE_MANAGE,
                Permission.IDENTITY_LIFECYCLE_RESTORE, Permission.IDENTITY_EMERGENCY_MANAGE);
        for (Permission held : permissions) {
            when(roles.findUserPermissions(7)).thenReturn(List.of(held.code()));
            for (Permission required : permissions) {
                if (held == required) {
                    assertThat(auth.require("token", required).userId()).isEqualTo(7);
                } else {
                    assertThatThrownBy(() -> auth.require("token", required)).isInstanceOf(IdentityException.class);
                }
            }
        }
        when(roles.findUserPermissions(7)).thenReturn(List.of("ROLE_ADMIN", "identity:user:write"));
        for (Permission required : permissions) {
            assertThatThrownBy(() -> auth.require("token", required)).isInstanceOf(IdentityException.class);
        }
    }
}
