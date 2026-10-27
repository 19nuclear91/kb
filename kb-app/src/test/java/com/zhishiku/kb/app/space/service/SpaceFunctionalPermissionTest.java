package com.zhishiku.kb.app.space.service;

import cn.dev33.satoken.stp.StpUtil;
import com.zhishiku.kb.app.auth.mapper.PermissionMapper;
import com.zhishiku.kb.app.auth.mapper.RolePermissionMapper;
import com.zhishiku.kb.app.auth.model.Permission;
import com.zhishiku.kb.app.auth.service.IdentityManagementAuthorizer;
import com.zhishiku.kb.app.auth.service.IdentityService;
import com.zhishiku.kb.app.auth.service.PermissionService;
import com.zhishiku.kb.app.space.mapper.SpaceMapper;
import com.zhishiku.kb.app.space.model.SpaceRecord;
import com.zhishiku.kb.common.security.IdentityException;
import com.zhishiku.kb.common.security.PrincipalType;
import com.zhishiku.kb.common.security.ResolvedPrincipal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class SpaceFunctionalPermissionTest {
    private static final long OPERATOR = 7L;

    private final RolePermissionMapper permissions = mock(RolePermissionMapper.class);
    private final IdentityService identities = mock(IdentityService.class);
    private final PermissionMapper permissionMapper = mock(PermissionMapper.class);
    private final PermissionService acl =
            new PermissionService(permissionMapper, identities, permissions);
    private final SpaceMapper mapper = mock(SpaceMapper.class);
    private final SpaceAuthorizer authorizer = new SpaceAuthorizer(
            new IdentityManagementAuthorizer(permissions), identities, acl);

    @BeforeEach
    void enabledOperator() {
        when(identities.resolvePrincipal(OPERATOR))
                .thenReturn(new ResolvedPrincipal(OPERATOR, 5L, (short) 2, List.of(), List.of(5L)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"manage", " manage "})
    void manageAllDoesNotAllowGrantingManage(String permission) {
        try (var login = mockStatic(StpUtil.class)) {
            login.when(StpUtil::getLoginIdAsLong).thenReturn(7L);
            login.when(StpUtil::isLogin).thenReturn(true);
            when(permissions.findUserPermissions(7L)).thenReturn(List.of(Permission.SPACE_MANAGE_ALL.code()));
            var service = new SpaceAclService(mapper, authorizer);
            assertThatThrownBy(() -> service.grant(2L, PrincipalType.USER, 9L, permission, 7L, "grant"))
                    .isInstanceOfSatisfying(IdentityException.class, e -> assertThat(e.httpStatus()).isEqualTo(403));
            assertThatThrownBy(() -> service.revoke(2L, PrincipalType.USER, 9L, permission, 7L, "revoke"))
                    .isInstanceOfSatisfying(IdentityException.class, e -> assertThat(e.httpStatus()).isEqualTo(403));
            verifyNoInteractions(mapper);
        }
    }

    @Test
    void deadLetterReaderRemainsFilteredByAclForListAndDetail() {
        try (var login = mockStatic(StpUtil.class)) {
            login.when(StpUtil::getLoginIdAsLong).thenReturn(7L);
            login.when(StpUtil::isLogin).thenReturn(true);
            when(permissions.findUserPermissions(7L)).thenReturn(List.of(Permission.PARSE_DEADLETTER_READ.code()));
            when(permissionMapper.findVisibleSpaceIds(anyList(), eq("view"))).thenReturn(List.of(2L));
            when(permissionMapper.findVisibleSpaceIds(anyList(), eq("manage"))).thenReturn(List.of(3L));
            var service = new SpaceQueryService(mapper, acl, authorizer);
            assertThat(service.list(7L)).isEmpty();
            verify(mapper).listVisible(List.of(2L), List.of(3L), false);
            assertThatThrownBy(() -> service.get(7L, 99L)).isInstanceOf(IdentityException.class);
            verify(mapper).findById(99L, List.of(2L), List.of(3L), false);
        }
    }

    @Test
    void manageAllBypassesAclEvenWhenNoGrantsExist() {
        try (var login = mockStatic(StpUtil.class)) {
            login.when(StpUtil::getLoginIdAsLong).thenReturn(7L);
            login.when(StpUtil::isLogin).thenReturn(true);
            when(permissions.findUserPermissions(7L)).thenReturn(List.of(Permission.SPACE_MANAGE_ALL.code()));
            assertThat(authorizer.requireSpaceManager(99L)).isEqualTo(7L);
            new SpaceQueryService(mapper, acl, authorizer).list(7L);
            verify(mapper).listVisible(List.of(), List.of(), true);
            verify(permissionMapper, never()).findVisibleSpaceIds(anyList(), anyString());
        }
    }

    @Test
    void createPermissionAloneCreatesSpaceAndKeepsTransaction() throws Exception {
        try (var login = mockStatic(StpUtil.class)) {
            login.when(StpUtil::getLoginIdAsLong).thenReturn(7L);
            login.when(StpUtil::isLogin).thenReturn(true);
            when(permissions.findUserPermissions(7L)).thenReturn(List.of(Permission.SPACE_CREATE.code()));
            when(mapper.nextSpaceId()).thenReturn(2L);
            when(mapper.insertSpace(any())).thenReturn(1);
            var space = new SpaceRecord();
            space.setId(2L);
            space.setCode("test");
            space.setName("测试");
            when(mapper.findRawById(2L)).thenReturn(space);
            assertThat(new SpaceWriteService(mapper, authorizer).create("test", "测试", null, 7L, "create").spaceId()).isEqualTo(2L);
            verify(mapper).grantAcl(2L, PrincipalType.USER.code(), 7L, "manage");
            assertThat(SpaceWriteService.class.getMethod("create", String.class, String.class, String.class,
                    Long.class, String.class).isAnnotationPresent(Transactional.class)).isTrue();
        }
    }
}
