package com.zhishiku.kb.app.auth.service;

import cn.dev33.satoken.stp.StpUtil;
import com.zhishiku.kb.app.auth.mapper.PermissionMapper;
import com.zhishiku.kb.app.auth.mapper.RolePermissionMapper;
import com.zhishiku.kb.app.auth.model.Permission;
import com.zhishiku.kb.common.security.IdentityException;
import com.zhishiku.kb.common.security.ResolvedPrincipal;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class IdentityManagementAuthorizerTest {
    @Test
    void exactMatchDoesNotImplyAdjacentPermissionOrExpandWildcard() {
        var mapper = mock(RolePermissionMapper.class);
        var authorizer = new IdentityManagementAuthorizer(mapper);
        try (var login = mockStatic(StpUtil.class)) {
            login.when(StpUtil::isLogin).thenReturn(true);
            login.when(StpUtil::getLoginIdAsLong).thenReturn(7L);
            when(mapper.findUserPermissions(7L)).thenReturn(List.of("identity:user:read", "space:*"));
            assertThat(authorizer.require(Permission.IDENTITY_USER_READ)).isEqualTo(7L);
            assertThat(authorizer.has(Permission.IDENTITY_USER_WRITE)).isFalse();
            assertThat(authorizer.has(Permission.SPACE_CREATE)).isFalse();
            assertThatThrownBy(() -> authorizer.require(Permission.IDENTITY_USER_WRITE))
                    .isInstanceOf(IdentityException.class);
        }
    }

    @Test
    void roleUnionIsResolvedAgainAfterRevocationWithoutChangingSession() {
        var mapper = mock(RolePermissionMapper.class);
        var authorizer = new IdentityManagementAuthorizer(mapper);
        try (var login = mockStatic(StpUtil.class)) {
            login.when(StpUtil::isLogin).thenReturn(true);
            login.when(StpUtil::getLoginIdAsLong).thenReturn(7L);
            when(mapper.findUserPermissions(7L)).thenReturn(List.of("identity:user:read", "identity:dept:write"));
            assertThat(authorizer.has(Permission.IDENTITY_USER_READ)).isTrue();
            assertThat(authorizer.has(Permission.IDENTITY_DEPT_WRITE)).isTrue();
            when(mapper.findUserPermissions(7L)).thenReturn(List.of("identity:dept:write"));
            assertThat(authorizer.has(Permission.IDENTITY_USER_READ)).isFalse();
            assertThat(authorizer.has(Permission.IDENTITY_DEPT_WRITE)).isTrue();
            when(mapper.findUserPermissions(7L)).thenReturn(List.of());
            assertThatThrownBy(() -> authorizer.require(Permission.IDENTITY_DEPT_WRITE))
                    .isInstanceOfSatisfying(IdentityException.class, e -> assertThat(e.httpStatus()).isEqualTo(403));
        }
    }

    @Test
    void handleOverloadQueriesFunctionalPermissionsOnceForRepeatedChecks() {
        var mapper = mock(RolePermissionMapper.class);
        when(mapper.findUserPermissions(7L)).thenReturn(List.of("identity:user:read"));
        var authorizer = new IdentityManagementAuthorizer(mapper);
        var identities = mock(IdentityService.class);
        when(identities.resolvePrincipal(7L))
                .thenReturn(new ResolvedPrincipal(7L, 5L, (short) 2, List.of(), List.of(5L)));
        var permissions = new PermissionService(mock(PermissionMapper.class), identities, mapper);

        var handle = permissions.principalOf(7L);

        assertThat(authorizer.has(handle, Permission.IDENTITY_USER_READ)).isTrue();
        assertThat(authorizer.has(handle, Permission.IDENTITY_USER_READ)).isTrue();
        assertThat(authorizer.has(handle, Permission.SPACE_CREATE)).isFalse();
        verify(mapper, times(1)).findUserPermissions(7L);
    }

    @Test
    void liveOverloadWithoutHandleQueriesPermissionsPerJudgement() {
        var mapper = mock(RolePermissionMapper.class);
        when(mapper.findUserPermissions(7L)).thenReturn(List.of("identity:user:read"));
        var authorizer = new IdentityManagementAuthorizer(mapper);
        try (var login = mockStatic(StpUtil.class)) {
            login.when(StpUtil::isLogin).thenReturn(true);
            login.when(StpUtil::getLoginIdAsLong).thenReturn(7L);
            assertThat(authorizer.has(Permission.IDENTITY_USER_READ)).isTrue();
            assertThat(authorizer.has(Permission.IDENTITY_USER_READ)).isTrue();
        }
        verify(mapper, times(2)).findUserPermissions(7L);
    }
}
