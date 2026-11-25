package com.zhishiku.kb.app.auth.service.diagnostics;

import cn.dev33.satoken.stp.StpUtil;
import com.zhishiku.kb.app.auth.mapper.IdentityDepartmentMapper;
import com.zhishiku.kb.app.auth.mapper.PermissionMapper;
import com.zhishiku.kb.app.auth.mapper.RolePermissionMapper;
import com.zhishiku.kb.app.auth.model.Permission;
import com.zhishiku.kb.app.auth.service.AdminQueryAccess;
import com.zhishiku.kb.app.auth.service.IdentityService;
import com.zhishiku.kb.app.auth.service.PermissionService;
import com.zhishiku.kb.app.auth.service.RolePermissionValidator;
import com.zhishiku.kb.app.query.QueryException;
import com.zhishiku.kb.common.security.ResolvedPrincipal;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class DiagnosticPermissionTest {
    @Test
    void exactPermissionDoesNotFollowOtherManagementOrRoleName() {
        var permissions = mock(PermissionService.class);
        var identities = mock(IdentityDepartmentMapper.class);
        var handle = mock(PermissionService.PrincipalHandle.class);
        when(permissions.principalOf(7)).thenReturn(handle);
        when(identities.findMustChangePasswordById(7)).thenReturn(false);
        var access = new AdminQueryAccess(permissions, identities);
        try (var session = mockStatic(StpUtil.class)) {
            session.when(StpUtil::getLoginIdAsLong).thenReturn(7L);
            for (String unrelated : List.of("ROLE_ADMIN", "space:manage-all", "manage", "space:grant-manage",
                    "identity:user:read", "audit:read", "authorization:*", "AUTHORIZATION:DIAGNOSE")) {
                when(handle.functionalPermissions()).thenReturn(Set.of(unrelated));
                assertThatThrownBy(() -> access.require(Permission.AUTHORIZATION_DIAGNOSE))
                        .isInstanceOf(QueryException.class);
            }
            when(handle.functionalPermissions()).thenReturn(Set.of("authorization:diagnose"));
            assertThat(access.require(Permission.AUTHORIZATION_DIAGNOSE)).isSameAs(handle);
            assertThatThrownBy(() -> access.require(Permission.SPACE_MANAGE_ALL)).isInstanceOf(QueryException.class);
        }
    }

    @Test
    void roleUnionAndNewHandleObserveRevocationWithoutWritingAcl() {
        var identities = mock(IdentityService.class);
        var roles = mock(RolePermissionMapper.class);
        var acl = mock(PermissionMapper.class);
        when(identities.resolvePrincipal(7)).thenReturn(new ResolvedPrincipal(7, 5, (short) 1,
                List.of(2L, 3L), List.of(5L)));
        var first = new HashSet<>(Set.of("authorization:diagnose"));
        var second = new HashSet<>(Set.of("authorization:diagnose", "space:manage-all"));
        when(roles.findUserPermissions(7)).thenAnswer(invocation ->
                java.util.stream.Stream.concat(first.stream(), second.stream()).distinct().toList());
        var service = new PermissionService(acl, identities, roles);
        assertThat(service.principalOf(7).functionalPermissions()).contains("authorization:diagnose");
        first.clear();
        assertThat(service.principalOf(7).functionalPermissions()).contains("authorization:diagnose");
        second.remove("authorization:diagnose");
        var reconstructed = new PermissionService(acl, identities, roles);
        assertThat(reconstructed.principalOf(7).functionalPermissions()).containsExactly("space:manage-all");
        verifyNoInteractions(acl);
    }

    @Test
    void catalogRecognizesNewCodeAndStillRejectsUnknownCode() {
        assertThat(Permission.codes()).contains("authorization:diagnose").doesNotHaveDuplicates();
        var roles = mock(RolePermissionMapper.class);
        when(roles.findAllPermissionCodes()).thenReturn(List.of("authorization:diagnose"));
        when(roles.findPermissionsByRoleCode("ROLE_ADMIN")).thenReturn(Permission.codes());
        assertThatCode(() -> new RolePermissionValidator(roles).run(null)).doesNotThrowAnyException();
        when(roles.findAllPermissionCodes()).thenReturn(List.of("authorization:unknown"));
        assertThatThrownBy(() -> new RolePermissionValidator(roles).run(null)).isInstanceOf(IllegalStateException.class);
    }
}
