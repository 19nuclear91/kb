package com.zhishiku.kb.app.auth.service;

import cn.dev33.satoken.stp.StpUtil;
import com.zhishiku.kb.app.auth.mapper.PermissionMapper;
import com.zhishiku.kb.app.auth.mapper.RolePermissionMapper;
import com.zhishiku.kb.app.auth.model.Permission;
import com.zhishiku.kb.common.security.IdentityException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.anyList;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EmbeddingPermissionTest {
    @Test
    void readAndRebuildRequireExactIndependentGrants() {
        var mapper = mock(RolePermissionMapper.class);
        var authorizer = new IdentityManagementAuthorizer(mapper);
        try (var session = mockStatic(StpUtil.class)) {
            session.when(StpUtil::getLoginIdAsLong).thenReturn(7L);
            for (Permission required : List.of(Permission.EMBEDDING_READ, Permission.EMBEDDING_REBUILD)) {
                when(mapper.findUserPermissions(7L)).thenReturn(List.of(required.code()));
                assertThat(authorizer.require(required)).isEqualTo(7L);
                for (String other : List.of(required == Permission.EMBEDDING_READ ? "embedding:rebuild" : "embedding" +
                                ":read",
                        "ROLE_ADMIN", "space:manage-all", "parse:deadletter:replay", "embedding:*", "document" +
                                ":security-level:write")) {
                    when(mapper.findUserPermissions(7L)).thenReturn(List.of(other));
                    assertThatThrownBy(() -> authorizer.require(required)).isInstanceOf(IdentityException.class);
                }
                when(mapper.findUserPermissions(7L)).thenReturn(List.of());
                assertThatThrownBy(() -> authorizer.require(required)).isInstanceOf(IdentityException.class);
            }
        }
    }

    @Test
    void functionalGrantDoesNotCreateContentAcl() {
        var mapper = mock(PermissionMapper.class);
        when(mapper.findClearanceLevel(7L)).thenReturn((short) 1);
        when(mapper.findRoleIds(7L)).thenReturn(List.of(2L));
        when(mapper.findDeptChainIds(7L)).thenReturn(List.of());
        when(mapper.findVisibleSpaceIds(anyList(), anyString())).thenReturn(List.of());
        var service = new PermissionService(mapper);
        assertThat(service.resolve(7L, "view").visibleSpaceIdList()).isEmpty();
        assertThat(service.resolve(7L, "download").visibleSpaceIdList()).isEmpty();
        verify(mapper).findVisibleSpaceIds(anyList(), eq("view"));
        verify(mapper).findVisibleSpaceIds(anyList(), eq("download"));
    }
}
