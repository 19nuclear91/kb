package com.zhishiku.kb.app.space.service;

import com.zhishiku.kb.app.auth.mapper.PermissionMapper;
import com.zhishiku.kb.app.auth.model.Permission;
import com.zhishiku.kb.app.auth.service.IdentityManagementAuthorizer;
import com.zhishiku.kb.app.auth.service.IdentityService;
import com.zhishiku.kb.app.auth.service.PermissionService;
import com.zhishiku.kb.common.security.IdentityErrorCode;
import com.zhishiku.kb.common.security.IdentityException;
import com.zhishiku.kb.common.security.ResolvedPrincipal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 空间级鉴权的四条路径。
 *
 * <p>第 4 条（已停用操作者）不是回归测试的摆设：曾经的实现依赖
 * {@code PermissionService.resolve} 的启用校验，而后者把 {@code disabled}
 * 吞成 {@code denyAll}，于是"已停用 + 会话里留着旧 ROLE_ADMIN"会被全局
 * 管理员短路直接放行。这些用例把这个洞钉住。
 *
 * <p>这里的 {@code PermissionService} 是接上 mock mapper 的真件，
 * 因此"一次 {@code requireSpaceManager} 只解析一次主体"可以直接按查询次数断言。
 */
class SpaceAuthorizerTest {

    private static final long OPERATOR = 7L;
    private static final long OWN_SPACE = 3L;
    private static final long OTHER_SPACE = 4L;

    private IdentityManagementAuthorizer identityAuthorizer;
    private IdentityService identityService;
    private PermissionMapper permissionMapper;
    private PermissionService permissionService;
    private SpaceAuthorizer authorizer;

    @BeforeEach
    void setUp() {
        identityAuthorizer = mock(IdentityManagementAuthorizer.class);
        identityService = mock(IdentityService.class);
        permissionMapper = mock(PermissionMapper.class);
        permissionService = new PermissionService(permissionMapper, identityService);
        authorizer = new SpaceAuthorizer(identityAuthorizer, identityService, permissionService);
        when(identityAuthorizer.requireLogin()).thenReturn(OPERATOR);
    }

    @Test
    @DisplayName("路径1：空间管理员操作本空间放行，且无需全局管理角色")
    void spaceManagerMayManageOwnSpace() {
        when(identityAuthorizer.has(any(PermissionService.PrincipalHandle.class),
                eq(Permission.SPACE_MANAGE_ALL))).thenReturn(false);
        when(identityService.resolvePrincipal(OPERATOR))
                .thenReturn(new ResolvedPrincipal(OPERATOR, 5L, (short) 2, List.of(), List.of(5L)));
        when(permissionMapper.findVisibleSpaceIds(anyList(), eq(PermissionService.PERM_MANAGE)))
                .thenReturn(List.of(OWN_SPACE));

        assertThat(authorizer.requireSpaceManager(OWN_SPACE)).isEqualTo(OPERATOR);

        verify(identityService, times(1)).resolvePrincipal(OPERATOR);
        verify(permissionMapper, never()).findClearanceLevel(anyLong());
        verify(permissionMapper, never()).findRoleIds(anyLong());
        verify(permissionMapper, never()).findDeptChainIds(anyLong());
        verify(permissionMapper, times(1)).findVisibleSpaceIds(
                anyList(), eq(PermissionService.PERM_MANAGE));
    }

    @Test
    @DisplayName("路径2：空间管理员操作其他空间被拒，且不产生任何授权副作用")
    void spaceManagerMayNotManageOtherSpace() {
        when(identityAuthorizer.has(any(PermissionService.PrincipalHandle.class),
                eq(Permission.SPACE_MANAGE_ALL))).thenReturn(false);
        when(identityService.resolvePrincipal(OPERATOR))
                .thenReturn(new ResolvedPrincipal(OPERATOR, 5L, (short) 2, List.of(), List.of(5L)));
        when(permissionMapper.findVisibleSpaceIds(anyList(), eq(PermissionService.PERM_MANAGE)))
                .thenReturn(List.of(OWN_SPACE));

        assertThatThrownBy(() -> authorizer.requireSpaceManager(OTHER_SPACE))
                .isInstanceOf(IdentityException.class)
                .extracting(e -> ((IdentityException) e).errorCode())
                .isEqualTo(IdentityErrorCode.FORBIDDEN);
        verify(permissionMapper, times(1)).findVisibleSpaceIds(
                anyList(), eq(PermissionService.PERM_MANAGE));
    }

    @Test
    @DisplayName("路径3：全局管理员在该空间无显式 manage 时兜底放行")
    void globalManagerFallsBackWithoutExplicitGrant() {
        when(identityAuthorizer.has(any(PermissionService.PrincipalHandle.class),
                eq(Permission.SPACE_MANAGE_ALL))).thenReturn(true);
        when(identityService.resolvePrincipal(OPERATOR))
                .thenReturn(new ResolvedPrincipal(OPERATOR, 5L, (short) 2, List.of(), List.of(5L)));

        assertThat(authorizer.requireSpaceManager(OTHER_SPACE)).isEqualTo(OPERATOR);
        // 兜底不再走 ACL 判定：全局管理员本来就没有显式记录
        verify(permissionMapper, never()).findVisibleSpaceIds(anyList(), any());
    }

    @Test
    @DisplayName("路径4：已停用操作者被拒绝，即使会话里还留着 ROLE_ADMIN")
    void disabledOperatorIsRejectedEvenWithStaleAdminRole() {
        when(identityAuthorizer.has(any(PermissionService.PrincipalHandle.class),
                eq(Permission.SPACE_MANAGE_ALL))).thenReturn(true);
        when(identityService.resolvePrincipal(OPERATOR))
                .thenThrow(IdentityException.disabled("用户已停用"));

        assertThatThrownBy(() -> authorizer.requireSpaceManager(OWN_SPACE))
                .isInstanceOf(IdentityException.class)
                .hasMessage("用户已停用")
                .extracting(e -> ((IdentityException) e).httpStatus())
                .isEqualTo(403);
        assertThatThrownBy(authorizer::requireGrantManage)
                .isInstanceOf(IdentityException.class);
        verify(identityAuthorizer, never()).has(any(PermissionService.PrincipalHandle.class),
                eq(Permission.SPACE_MANAGE_ALL));
    }

    @Test
    @DisplayName("启用校验先于全局管理员短路：停用账户不得借旧角色放行")
    void enabledCheckRunsBeforeGlobalManagerShortCircuit() {
        when(identityAuthorizer.has(any(PermissionService.PrincipalHandle.class),
                eq(Permission.SPACE_MANAGE_ALL))).thenReturn(true);
        when(identityService.resolvePrincipal(OPERATOR))
                .thenThrow(IdentityException.disabled("用户主部门已停用"));

        assertThatThrownBy(() -> authorizer.requireSpaceManager(OTHER_SPACE))
                .isInstanceOf(IdentityException.class);
        verify(identityAuthorizer, never()).has(any(PermissionService.PrincipalHandle.class),
                eq(Permission.SPACE_MANAGE_ALL));
        verify(permissionMapper, never()).findVisibleSpaceIds(anyList(), any());
    }

    @Test
    @DisplayName("停用判定必须来自身份解析的异常，而不是 resolve 的 denyAll 降级")
    void disabledCheckMustNotRelyOnResolveWhichDowngradesToDenyAll() {
        when(identityAuthorizer.has(any(PermissionService.PrincipalHandle.class),
                eq(Permission.SPACE_MANAGE_ALL))).thenReturn(true);
        when(identityService.resolvePrincipal(OPERATOR))
                .thenThrow(IdentityException.disabled("用户已停用"));
        // 若实现改回依赖 resolve 的降级结果，这里会得到"无权管理该空间"的 FORBIDDEN，
        // 而不是停用异常本身——消息断言会把它抓住。
        when(permissionMapper.findVisibleSpaceIds(anyList(), eq(PermissionService.PERM_MANAGE)))
                .thenReturn(List.of(OWN_SPACE));

        assertThatThrownBy(() -> authorizer.requireSpaceManager(OWN_SPACE))
                .isInstanceOf(IdentityException.class)
                .hasMessage("用户已停用");
        verify(permissionMapper, never()).findVisibleSpaceIds(
                anyList(), eq(PermissionService.PERM_MANAGE));
    }
}
