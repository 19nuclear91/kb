package com.zhishiku.kb.app.search.term;

import cn.dev33.satoken.stp.StpUtil;
import com.zhishiku.kb.app.auth.mapper.IdentityDepartmentMapper;
import com.zhishiku.kb.app.auth.mapper.PermissionMapper;
import com.zhishiku.kb.app.auth.mapper.RolePermissionMapper;
import com.zhishiku.kb.app.auth.model.DepartmentRecord;
import com.zhishiku.kb.app.auth.model.IdentityRecord;
import com.zhishiku.kb.app.auth.model.LocalCredentialRecord;
import com.zhishiku.kb.app.auth.model.Permission;
import com.zhishiku.kb.app.auth.model.RoleRecord;
import com.zhishiku.kb.app.auth.service.AdminBootstrapRunner;
import com.zhishiku.kb.app.auth.service.AdminQueryAccess;
import com.zhishiku.kb.app.auth.service.IdentityAuditService;
import com.zhishiku.kb.app.auth.service.IdentityService;
import com.zhishiku.kb.app.auth.service.PermissionService;
import com.zhishiku.kb.app.auth.service.RolePermissionValidator;
import com.zhishiku.kb.app.auth.service.SecurityLevelDictionary;
import com.zhishiku.kb.app.config.AuthPasswordProperties;
import com.zhishiku.kb.app.config.BootstrapProperties;
import com.zhishiku.kb.app.query.QueryException;
import com.zhishiku.kb.common.security.ResolvedPrincipal;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class TermPermissionTest {
    @Test
    void adminAccessRequiresExactIndependentReadAndWrite() {
        var permissions = mock(PermissionService.class);
        var identities = mock(IdentityDepartmentMapper.class);
        var handle = mock(PermissionService.PrincipalHandle.class);
        when(permissions.principalOf(7)).thenReturn(handle);
        when(identities.findMustChangePasswordById(7)).thenReturn(false);
        var access = new AdminQueryAccess(permissions, identities);
        try (var session = mockStatic(StpUtil.class)) {
            session.when(StpUtil::getLoginIdAsLong).thenReturn(7L);
            for (Permission required : List.of(Permission.SEARCH_TERM_READ, Permission.SEARCH_TERM_WRITE)) {
                when(handle.functionalPermissions()).thenReturn(Set.of(required.code()));
                assertThat(access.require(required)).isSameAs(handle);
                Permission other = required == Permission.SEARCH_TERM_READ ? Permission.SEARCH_TERM_WRITE : Permission.SEARCH_TERM_READ;
                assertThatThrownBy(() -> access.require(other)).isInstanceOf(QueryException.class);
                for (String unrelated : List.of("ROLE_ADMIN", "space:manage-all", "manage", "edit", "document:tag:read",
                        "document:tag:write", "identity:role:write", "search:term:*", "SEARCH:TERM:READ")) {
                    when(handle.functionalPermissions()).thenReturn(Set.of(unrelated));
                    assertThatThrownBy(() -> access.require(required)).isInstanceOf(QueryException.class);
                }
                when(handle.functionalPermissions()).thenReturn(Set.of());
                assertThatThrownBy(() -> access.require(required)).isInstanceOf(QueryException.class);
            }
        }
    }

    @Test
    void principalUsesPersistentRoleUnionAndReloadSeesRevocationWithoutCreatingAcl() {
        var identities = mock(IdentityService.class);
        var roles = mock(RolePermissionMapper.class);
        var acl = mock(PermissionMapper.class);
        when(identities.resolvePrincipal(7)).thenReturn(new ResolvedPrincipal(7, 5, (short) 1, List.of(2L, 3L), List.of(5L)));
        Map<Long, Set<String>> persisted = Map.of(2L, new HashSet<>(Set.of("search:term:read")),
                3L, new HashSet<>(Set.of("search:term:write")));
        when(roles.findUserPermissions(7)).thenAnswer(invocation -> persisted.values().stream().flatMap(Set::stream).distinct().toList());
        var service = new PermissionService(acl, identities, roles);
        assertThat(service.principalOf(7).functionalPermissions()).containsExactlyInAnyOrder("search:term:read", "search:term:write");
        persisted.get(3L).clear();
        assertThat(service.principalOf(7).functionalPermissions()).containsExactly("search:term:read");
        verify(roles, never()).grantPermission(anyLong(), anyString());
        verify(acl, never()).findVisibleSpaceIds(anyList(), anyString());
    }

    @Test
    void bootstrapGrantsCompleteCatalogOnlyToNewRoleAndNeverRepairsRevocation() {
        var identities = mock(IdentityDepartmentMapper.class);
        var permissions = mock(RolePermissionMapper.class);
        var currentRole = new AtomicReference<RoleRecord>();
        Set<String> persisted = new HashSet<>();
        when(identities.findRoleByCode("ROLE_ADMIN")).thenAnswer(invocation -> currentRole.get());
        when(identities.nextRoleId()).thenReturn(11L);
        when(identities.insertRole(anyLong(), anyString(), anyString())).thenAnswer(invocation -> {
            currentRole.set(new RoleRecord(11L, "ROLE_ADMIN", "管理员"));
            return 1;
        });
        when(permissions.grantPermission(anyLong(), anyString())).thenAnswer(invocation -> {
            persisted.add(invocation.getArgument(1));
            return 1;
        });
        when(permissions.findRolePermissions(11)).thenAnswer(invocation -> List.copyOf(persisted));
        LocalCredentialRecord credential = new LocalCredentialRecord();
        credential.setId(7L);
        when(identities.findIdentityByUsername("root")).thenReturn(credential);
        when(identities.findIdentity(7)).thenReturn(new IdentityRecord(7L, "root", "管理员", 5L, (short) 1, true, 0L, null, null));
        when(identities.findDepartmentByCode("HQ")).thenReturn(new DepartmentRecord(5L, null, "HQ", "总部", "/5/", true, 0L, null, null));
        var runner = new AdminBootstrapRunner(new BootstrapProperties(
                new BootstrapProperties.Admin("root", "unit-test-password", "管理员", (short) 1), null),
                identities, mock(IdentityAuditService.class), mock(SecurityLevelDictionary.class),
                mock(BCryptPasswordEncoder.class), new AuthPasswordProperties(8), permissions);
        runner.run(null);
        assertThat(persisted).containsExactlyInAnyOrderElementsOf(Permission.codes());
        assertThat(persisted).contains("search:term:read", "search:term:write");
        persisted.remove("search:term:write");
        clearInvocations(permissions);
        runner.run(null);
        runner.run(null);
        assertThat(persisted).doesNotContain("search:term:write");
        verify(permissions, never()).grantPermission(anyLong(), anyString());
        verify(permissions, never()).revokePermission(anyLong(), anyString());
    }

    @Test
    void startupAcceptsNewCodesWithoutRepairAndStillRejectsUnknownCodes() {
        var permissions = mock(RolePermissionMapper.class);
        when(permissions.findAllPermissionCodes()).thenReturn(List.of("search:term:read", "search:term:write"));
        when(permissions.findPermissionsByRoleCode("ROLE_ADMIN")).thenReturn(List.of("search:term:read"));
        var validator = new RolePermissionValidator(permissions);
        assertThatCode(() -> validator.run(null)).doesNotThrowAnyException();
        verify(permissions).findAllPermissionCodes();
        verify(permissions).findPermissionsByRoleCode("ROLE_ADMIN");
        verifyNoMoreInteractions(permissions);
        when(permissions.findAllPermissionCodes()).thenReturn(List.of("search:term:*"));
        assertThatThrownBy(() -> validator.run(null)).isInstanceOf(IllegalStateException.class);
    }
}
