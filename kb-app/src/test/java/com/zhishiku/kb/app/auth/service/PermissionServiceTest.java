package com.zhishiku.kb.app.auth.service;

import com.zhishiku.kb.app.auth.mapper.PermissionMapper;
import com.zhishiku.kb.app.auth.mapper.RolePermissionMapper;
import com.zhishiku.kb.common.security.IdentityException;
import com.zhishiku.kb.common.security.PermissionScope;
import com.zhishiku.kb.common.security.ResolvedPrincipal;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PermissionServiceTest {

    private final PermissionMapper mapper = mock(PermissionMapper.class);
    private final PermissionService service = new PermissionService(mapper);

    @Test
    void preservesUserRoleAndDepartmentAncestorsForHistoricalSubject() {
        when(mapper.findClearanceLevel(1L)).thenReturn((short) 2);
        when(mapper.findRoleIds(1L)).thenReturn(List.of(5L));
        when(mapper.findDeptChainIds(1L)).thenReturn(List.of(1L, 2L, 4L));
        when(mapper.findVisibleSpaceIds(ArgumentMatchers.anyList(), ArgumentMatchers.eq("view")))
                .thenReturn(List.of(9L));

        PermissionScope scope = service.resolve(1L, PermissionService.PERM_VIEW);

        assertThat(scope.visibleSpaceIdList()).containsExactly(9L);
        assertThat(scope.principalKeys()).contains("1:1", "2:5", "3:1", "3:2", "3:4");
        assertThat(scope.clearanceLevel()).isEqualTo((short) 2);
    }

    @Test
    void disabledOrMissingUserDeniesAllResources() {
        when(mapper.findClearanceLevel(1L)).thenReturn(null);

        assertThat(service.resolve(1L, PermissionService.PERM_VIEW).seesNothing()).isTrue();
    }

    @Test
    void identityPathReusesResolvedPrincipalAndQueriesEachFactOnce() {
        var permissionMapper = mock(PermissionMapper.class);
        var identities = mock(IdentityService.class);
        when(identities.resolvePrincipal(7L)).thenReturn(
                new ResolvedPrincipal(7L, 5L, (short) 2, List.of(8L), List.of(5L, 9L)));
        when(permissionMapper.findVisibleSpaceIds(ArgumentMatchers.anyList(), ArgumentMatchers.eq("view")))
                .thenReturn(List.of(3L));
        var permissionService = new PermissionService(permissionMapper, identities);

        PermissionScope scope = permissionService.resolve(7L, PermissionService.PERM_VIEW);

        assertThat(scope.userId()).isEqualTo(7L);
        assertThat(scope.clearanceLevel()).isEqualTo((short) 2);
        assertThat(scope.principalKeys()).contains("1:7", "2:8", "3:5", "3:9");
        assertThat(scope.visibleSpaceIdList()).containsExactly(3L);
        verify(identities, times(1)).resolvePrincipal(7L);
        verify(permissionMapper, never()).findClearanceLevel(ArgumentMatchers.anyLong());
        verify(permissionMapper, never()).findRoleIds(ArgumentMatchers.anyLong());
        verify(permissionMapper, never()).findDeptChainIds(ArgumentMatchers.anyLong());
        verify(permissionMapper, times(1)).findVisibleSpaceIds(
                ArgumentMatchers.anyList(), ArgumentMatchers.eq("view"));
    }

    @Test
    void bothAssemblyPathsProduceTheSameScopeForTheSameFacts() {
        var nullPathMapper = mock(PermissionMapper.class);
        when(nullPathMapper.findClearanceLevel(7L)).thenReturn((short) 2);
        when(nullPathMapper.findRoleIds(7L)).thenReturn(List.of(8L));
        when(nullPathMapper.findDeptChainIds(7L)).thenReturn(List.of(5L, 9L));
        when(nullPathMapper.findVisibleSpaceIds(ArgumentMatchers.anyList(), ArgumentMatchers.eq("view")))
                .thenReturn(List.of(3L));
        PermissionScope fromNullPath = new PermissionService(nullPathMapper)
                .resolve(7L, PermissionService.PERM_VIEW);

        var identityPathMapper = mock(PermissionMapper.class);
        when(identityPathMapper.findVisibleSpaceIds(ArgumentMatchers.anyList(), ArgumentMatchers.eq("view")))
                .thenReturn(List.of(3L));
        var identities = mock(IdentityService.class);
        when(identities.resolvePrincipal(7L)).thenReturn(
                new ResolvedPrincipal(7L, 5L, (short) 2, List.of(8L), List.of(5L, 9L)));
        PermissionScope fromIdentityPath = new PermissionService(identityPathMapper, identities)
                .resolve(7L, PermissionService.PERM_VIEW);

        assertThat(fromNullPath.userId()).isEqualTo(fromIdentityPath.userId());
        assertThat(fromNullPath.clearanceLevel()).isEqualTo(fromIdentityPath.clearanceLevel());
        assertThat(fromNullPath.visibleSpaceIdList()).isEqualTo(fromIdentityPath.visibleSpaceIdList());
        assertThat(fromNullPath.principalKeys()).containsExactlyInAnyOrder(fromIdentityPath.principalKeys());
    }

    @Test
    void identityFailureDowngradesToDenyAllWithoutSpaceQuery() {
        var permissionMapper = mock(PermissionMapper.class);
        var identities = mock(IdentityService.class);
        when(identities.resolvePrincipal(7L)).thenThrow(IdentityException.disabled("用户已停用"));
        var permissionService = new PermissionService(permissionMapper, identities);

        PermissionScope scope = permissionService.resolve(7L, PermissionService.PERM_VIEW);

        assertThat(scope.seesNothing()).isTrue();
        assertThat(scope.userId()).isEqualTo(7L);
        verify(permissionMapper, never()).findVisibleSpaceIds(
                ArgumentMatchers.anyList(), ArgumentMatchers.anyString());
    }

    @Test
    void missingClearanceDowngradesToDenyAllWithoutSpaceQuery() {
        when(mapper.findClearanceLevel(2L)).thenReturn(null);

        PermissionScope scope = service.resolve(2L, PermissionService.PERM_VIEW);

        assertThat(scope.seesNothing()).isTrue();
        verify(mapper, never()).findVisibleSpaceIds(
                ArgumentMatchers.anyList(), ArgumentMatchers.anyString());
    }

    @Test
    void principalHandleCannotBeForgedOutsidePermissionService() {
        var constructors = PermissionService.PrincipalHandle.class.getDeclaredConstructors();
        assertThat(constructors).isNotEmpty();
        for (var constructor : constructors) {
            assertThat(Modifier.isPrivate(constructor.getModifiers()))
                    .as("句柄构造器必须私有：%s", constructor)
                    .isTrue();
        }

        var publicFactories = Arrays.stream(PermissionService.PrincipalHandle.class.getDeclaredMethods())
                .filter(method -> Modifier.isPublic(method.getModifiers())
                        && Modifier.isStatic(method.getModifiers())
                        && PermissionService.PrincipalHandle.class.isAssignableFrom(method.getReturnType()))
                .map(Method::getName)
                .toList();
        assertThat(publicFactories).as("句柄不得暴露公开的静态铸造入口").isEmpty();

        var publicMinters = Arrays.stream(PermissionService.class.getDeclaredMethods())
                .filter(method -> Modifier.isPublic(method.getModifiers())
                        && PermissionService.PrincipalHandle.class.isAssignableFrom(method.getReturnType()))
                .map(Method::getName)
                .toList();
        assertThat(publicMinters).as("句柄的唯一铸造入口是 principalOf").containsExactly("principalOf");
    }

    @Test
    void failedHandleRethrowsTheCapturedIdentityException() {
        var identities = mock(IdentityService.class);
        var captured = IdentityException.disabled("用户已停用");
        when(identities.resolvePrincipal(7L)).thenThrow(captured);
        var permissionService = new PermissionService(mock(PermissionMapper.class), identities);

        var handle = permissionService.principalOf(7L);

        assertThatThrownBy(handle::requireEnabled).isSameAs(captured);
        assertThat(handle.functionalPermissions()).as("失败态句柄不持有任何功能权限").isEmpty();
    }

    @Test
    void oneHandleQueriesIdentityFactsOnceAndReusesThemAcrossPermissions() {
        var permissionMapper = mock(PermissionMapper.class);
        var identities = mock(IdentityService.class);
        when(identities.resolvePrincipal(7L)).thenReturn(
                new ResolvedPrincipal(7L, 5L, (short) 2, List.of(8L), List.of(5L)));
        when(permissionMapper.findVisibleSpaceIds(ArgumentMatchers.anyList(), ArgumentMatchers.eq("view")))
                .thenReturn(List.of(3L));
        when(permissionMapper.findVisibleSpaceIds(ArgumentMatchers.anyList(), ArgumentMatchers.eq("download")))
                .thenReturn(List.of(4L));
        var permissionService = new PermissionService(permissionMapper, identities);

        var handle = permissionService.principalOf(7L);
        handle.requireEnabled();
        assertThat(permissionService.resolve(handle, "view").visibleSpaceIdList()).containsExactly(3L);
        assertThat(permissionService.resolve(handle, "download").visibleSpaceIdList()).containsExactly(4L);

        verify(identities, times(1)).resolvePrincipal(7L);
        verify(permissionMapper, never()).findClearanceLevel(ArgumentMatchers.anyLong());
        verify(permissionMapper, times(2)).findVisibleSpaceIds(
                ArgumentMatchers.anyList(), ArgumentMatchers.anyString());
    }

    @Test
    void independentHandlesNeverReuseFactsAcrossCalls() {
        var permissionMapper = mock(PermissionMapper.class);
        var identities = mock(IdentityService.class);
        when(identities.resolvePrincipal(7L)).thenReturn(
                new ResolvedPrincipal(7L, 5L, (short) 2, List.of(8L), List.of(5L)));
        when(permissionMapper.findVisibleSpaceIds(ArgumentMatchers.anyList(), ArgumentMatchers.eq("view")))
                .thenReturn(List.of(3L));
        var permissionService = new PermissionService(permissionMapper, identities);

        permissionService.resolve(7L, "view");
        permissionService.resolve(7L, "view");

        verify(identities, times(2)).resolvePrincipal(7L);
        verify(permissionMapper, times(2)).findVisibleSpaceIds(
                ArgumentMatchers.anyList(), ArgumentMatchers.eq("view"));
    }

    @Test
    void functionalPermissionsAreQueriedOncePerHandle() {
        var rolePermissions = mock(RolePermissionMapper.class);
        when(rolePermissions.findUserPermissions(7L)).thenReturn(List.of("space:manage-all"));
        var identities = mock(IdentityService.class);
        when(identities.resolvePrincipal(7L)).thenReturn(
                new ResolvedPrincipal(7L, 5L, (short) 2, List.of(), List.of(5L)));
        var permissionService = new PermissionService(mock(PermissionMapper.class), identities, rolePermissions);

        var handle = permissionService.principalOf(7L);

        assertThat(handle.functionalPermissions()).contains("space:manage-all");
        assertThat(handle.functionalPermissions()).contains("space:manage-all");
        verify(rolePermissions, times(1)).findUserPermissions(7L);
    }

    @Test
    void functionalPermissionsDoNotCrossHandles() {
        var rolePermissions = mock(RolePermissionMapper.class);
        when(rolePermissions.findUserPermissions(7L)).thenReturn(List.of("space:manage-all"));
        var identities = mock(IdentityService.class);
        when(identities.resolvePrincipal(7L)).thenReturn(
                new ResolvedPrincipal(7L, 5L, (short) 2, List.of(), List.of(5L)));
        var permissionService = new PermissionService(mock(PermissionMapper.class), identities, rolePermissions);

        assertThat(permissionService.principalOf(7L).functionalPermissions()).contains("space:manage-all");
        assertThat(permissionService.principalOf(7L).functionalPermissions()).contains("space:manage-all");

        verify(rolePermissions, times(2)).findUserPermissions(7L);
    }
}
