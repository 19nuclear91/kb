package com.zhishiku.kb.app.auth.service;

import cn.dev33.satoken.exception.SaTokenException;
import cn.dev33.satoken.stp.StpUtil;
import com.zhishiku.kb.app.auth.mapper.IdentityDepartmentMapper;
import com.zhishiku.kb.app.auth.mapper.PermissionMapper;
import com.zhishiku.kb.app.auth.mapper.RolePermissionMapper;
import com.zhishiku.kb.app.auth.model.Permission;
import com.zhishiku.kb.app.query.QueryException;
import com.zhishiku.kb.common.security.IdentityException;
import com.zhishiku.kb.common.security.ResolvedPrincipal;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

class AdminQueryAccessTest {
    private final IdentityService identity = mock(IdentityService.class);
    private final IdentityDepartmentMapper mapper = mock(IdentityDepartmentMapper.class);
    private final RolePermissionMapper roles = mock(RolePermissionMapper.class);
    private final PermissionService permissions = new PermissionService(mock(PermissionMapper.class), identity, roles);
    private final AdminQueryAccess access = new AdminQueryAccess(permissions, mapper);

    @Test
    void aiReadsAreIndependentAndRoleNamesCannotAuthorizeThem() {
        when(identity.resolvePrincipal(7)).thenReturn(new ResolvedPrincipal(7, 2L, (short) 1, List.of(3L), List.of(2L)));
        when(mapper.findMustChangePasswordById(7)).thenReturn(false);
        try (var login = mockStatic(StpUtil.class)) {
            login.when(StpUtil::getLoginIdAsLong).thenReturn(7L);
            for (Permission allowed : List.of(Permission.AI_USAGE_READ, Permission.AI_AUDIT_READ)) {
                when(roles.findUserPermissions(7)).thenReturn(List.of(allowed.code()));
                assertThat(access.require(allowed).userId()).isEqualTo(7);
                Permission denied = allowed == Permission.AI_USAGE_READ ? Permission.AI_AUDIT_READ : Permission.AI_USAGE_READ;
                assertThatThrownBy(() -> access.require(denied)).isInstanceOf(QueryException.class);
            }
            when(roles.findUserPermissions(7)).thenReturn(List.of("ROLE_ADMIN", "audit:read"));
            assertThatThrownBy(() -> access.require(Permission.AI_USAGE_READ)).isInstanceOf(QueryException.class);
            assertThatThrownBy(() -> access.require(Permission.AI_AUDIT_READ)).isInstanceOf(QueryException.class);
        }
    }

    @Test
    void newReadsAreIndependentAndRevocationUsesSameSession() {
        when(identity.resolvePrincipal(7)).thenReturn(new ResolvedPrincipal(7, 2L, (short) 1, List.of(3L, 4L),
                List.of(2L)));
        when(mapper.findMustChangePasswordById(7)).thenReturn(false);
        try (var login = mockStatic(StpUtil.class)) {
            login.when(StpUtil::getLoginIdAsLong).thenReturn(7L);
            when(roles.findUserPermissions(7)).thenReturn(List.of("identity:audit:read", "identity:external:write"));
            assertThatThrownBy(() -> access.require(Permission.AUDIT_READ)).isInstanceOf(QueryException.class);
            assertThatThrownBy(() -> access.require(Permission.IDENTITY_EXTERNAL_READ)).isInstanceOf(QueryException.class);
            when(roles.findUserPermissions(7)).thenReturn(List.of("audit:read", "audit:read", "identity:external:read"
            ));
            assertThat(access.require(Permission.AUDIT_READ).functionalPermissions()).hasSize(2);
            assertThat(access.require(Permission.IDENTITY_EXTERNAL_READ).userId()).isEqualTo(7);
            when(roles.findUserPermissions(7)).thenReturn(List.of());
            assertThatThrownBy(() -> access.require(Permission.AUDIT_READ)).isInstanceOf(QueryException.class);
        }
    }

    @Test
    void disabledDepartmentAndPasswordChangeAreRejectedWithoutReadingPermissions() {
        try (var login = mockStatic(StpUtil.class)) {
            login.when(StpUtil::getLoginIdAsLong).thenReturn(7L);
            when(identity.resolvePrincipal(7)).thenThrow(IdentityException.disabled("部门停用"));
            assertThatThrownBy(access::current).isInstanceOf(IdentityException.class);
            doReturn(new ResolvedPrincipal(7, 2L, (short) 1, List.of(), List.of(2L))).when(identity).resolvePrincipal(7);
            when(mapper.findMustChangePasswordById(7)).thenReturn(true);
            assertThatThrownBy(access::current).isInstanceOf(QueryException.class);
            when(mapper.findMustChangePasswordById(7)).thenReturn(null);
            assertThatThrownBy(access::current).isInstanceOf(QueryException.class);
        }
    }

    @Test
    void unrelatedManagementPermissionsDoNotGrantNewReadsAndFailuresRemainFailures() {
        when(identity.resolvePrincipal(7)).thenReturn(new ResolvedPrincipal(7, 2L, (short) 1, List.of(3L),
                List.of(2L)));
        when(mapper.findMustChangePasswordById(7)).thenReturn(false);
        when(roles.findUserPermissions(7)).thenReturn(Permission.codes().stream()
                .filter(code -> !code.equals("audit:read") && !code.equals("identity:external:read")).toList());
        try (var login = mockStatic(StpUtil.class)) {
            login.when(StpUtil::getLoginIdAsLong).thenReturn(7L);
            assertThatThrownBy(() -> access.require(Permission.AUDIT_READ)).isInstanceOf(QueryException.class);
            assertThatThrownBy(() -> access.require(Permission.IDENTITY_EXTERNAL_READ)).isInstanceOf(QueryException.class);
            when(roles.findUserPermissions(7)).thenThrow(new DataAccessResourceFailureException("private-jdbc"));
            assertThatThrownBy(() -> access.require(Permission.AUDIT_READ)).isInstanceOf(DataAccessResourceFailureException.class);
            login.when(StpUtil::checkLogin).thenThrow(new SaTokenException("private-token"));
            assertThatThrownBy(access::current).isInstanceOfSatisfying(IdentityException.class,
                    e -> assertThat(e.httpStatus()).isEqualTo(401));
        }
    }
}
