package com.zhishiku.kb.app.service.impl.health;

import cn.dev33.satoken.stp.StpUtil;
import cn.dev33.satoken.exception.SaTokenException;
import com.zhishiku.kb.app.auth.mapper.IdentityDepartmentMapper;
import com.zhishiku.kb.app.auth.mapper.PermissionMapper;
import com.zhishiku.kb.app.auth.mapper.RolePermissionMapper;
import com.zhishiku.kb.app.auth.model.Permission;
import com.zhishiku.kb.app.auth.service.AdminQueryAccess;
import com.zhishiku.kb.app.auth.service.IdentityService;
import com.zhishiku.kb.app.auth.service.PermissionService;
import com.zhishiku.kb.app.query.QueryException;
import com.zhishiku.kb.app.service.health.HealthFailure;
import com.zhishiku.kb.app.space.model.SpaceRecord;
import com.zhishiku.kb.app.space.service.SpaceQueryService;
import com.zhishiku.kb.common.security.PermissionScope;
import com.zhishiku.kb.common.security.IdentityException;
import com.zhishiku.kb.common.security.ResolvedPrincipal;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class HealthAccessTest {
    @Test
    void absentSessionIsUnauthorizedBeforeTargetLookup() {
        var permissions = mock(PermissionService.class);
        var identities = mock(IdentityDepartmentMapper.class);
        var spaces = mock(SpaceQueryService.class);
        var access = new HealthAccess(new AdminQueryAccess(permissions, identities), spaces, permissions);
        try (var session = mockStatic(StpUtil.class)) {
            session.when(StpUtil::checkLogin).thenThrow(new SaTokenException("private"));
            assertThatThrownBy(() -> access.require(2)).isInstanceOfSatisfying(IdentityException.class,
                    error -> assertThat(error.httpStatus()).isEqualTo(401));
            verifyNoInteractions(permissions, identities, spaces);
        }
    }

    @Test
    void exactPermissionAndCurrentPasswordStatePrecedeTargetRead() {
        var permissions = mock(PermissionService.class);
        var identities = mock(IdentityDepartmentMapper.class);
        var spaces = mock(SpaceQueryService.class);
        var handle = mock(PermissionService.PrincipalHandle.class);
        when(permissions.principalOf(7)).thenReturn(handle);
        when(identities.findMustChangePasswordById(7)).thenReturn(false);
        var access = new HealthAccess(new AdminQueryAccess(permissions, identities), spaces, permissions);
        try (var session = mockStatic(StpUtil.class)) {
            session.when(StpUtil::getLoginIdAsLong).thenReturn(7L);
            for (String code : List.of("ROLE_ADMIN", "space:manage-all", "operations:reports:read",
                    "authorization:diagnose", "knowledge:health:*")) {
                when(handle.functionalPermissions()).thenReturn(Set.of(code));
                assertThatThrownBy(() -> access.require(2)).isInstanceOf(QueryException.class);
            }
            when(handle.functionalPermissions()).thenReturn(Set.of(Permission.KNOWLEDGE_HEALTH_READ.code()));
            when(identities.findMustChangePasswordById(7)).thenReturn(true);
            assertThatThrownBy(() -> access.require(2)).isInstanceOf(QueryException.class);
            verifyNoInteractions(spaces);
        }
    }

    @Test
    void managedSpaceAndArchiveAreIndependentFromContentScope() {
        var auth = mock(AdminQueryAccess.class);
        var spaces = mock(SpaceQueryService.class);
        var permissions = mock(PermissionService.class);
        var handle = mock(PermissionService.PrincipalHandle.class);
        when(auth.require(Permission.KNOWLEDGE_HEALTH_READ)).thenReturn(handle);
        var access = new HealthAccess(auth, spaces, permissions);
        assertThatThrownBy(() -> access.require(2)).isInstanceOfSatisfying(QueryException.class,
                error -> assertThat(error.status()).isEqualTo(404));
        var space = new SpaceRecord();
        space.setArchived(true);
        when(spaces.findManaged(handle, 2)).thenReturn(space);
        assertThatThrownBy(() -> access.require(2)).isInstanceOfSatisfying(HealthFailure.class,
                error -> assertThat(error.reason()).isEqualTo("SPACE_ARCHIVED"));
        space.setArchived(false);
        var emptyView = PermissionScope.of(7, (short) 1, List.of(), Set.of("1:7"));
        when(permissions.resolve(handle, "view")).thenReturn(emptyView);
        assertThat(access.require(2)).isSameAs(emptyView);
        when(permissions.resolve(handle, "view")).thenThrow(new DataAccessResourceFailureException("private"));
        assertThatThrownBy(() -> access.require(2)).isInstanceOf(DataAccessResourceFailureException.class);
        when(spaces.findManaged(handle, 2)).thenReturn(null);
        assertThatThrownBy(() -> access.require(2)).isInstanceOf(QueryException.class);
    }

    @Test
    void roleUnionIsRebuiltAndDoesNotRestoreRevocation() {
        var identities = mock(IdentityService.class);
        var roles = mock(RolePermissionMapper.class);
        var acl = mock(PermissionMapper.class);
        when(identities.resolvePrincipal(7)).thenReturn(new ResolvedPrincipal(7, 5, (short) 1,
                List.of(2L, 3L), List.of(5L)));
        var first = new HashSet<>(Set.of("knowledge:health:read"));
        var second = new HashSet<>(Set.of("knowledge:health:read", "space:manage-all"));
        when(roles.findUserPermissions(7)).thenAnswer(invocation -> Stream.concat(first.stream(), second.stream()).distinct().toList());
        var permissions = new PermissionService(acl, identities, roles);
        assertThat(permissions.principalOf(7).functionalPermissions()).contains("knowledge:health:read");
        first.clear();
        assertThat(permissions.principalOf(7).functionalPermissions()).contains("knowledge:health:read");
        second.remove("knowledge:health:read");
        assertThat(new PermissionService(acl, identities, roles).principalOf(7).functionalPermissions()).containsExactly("space:manage-all");
        verifyNoInteractions(acl);
    }
}
