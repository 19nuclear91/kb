package com.zhishiku.kb.app.auth.service;

import cn.dev33.satoken.stp.StpUtil;
import com.zhishiku.kb.app.auth.mapper.RolePermissionMapper;
import com.zhishiku.kb.app.auth.model.Permission;
import com.zhishiku.kb.common.security.IdentityException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

class DocumentSecurityPermissionTest {
    @Test
    void onlyExplicitPermissionQualifiesRegardlessOfRoleName() {
        var mapper = mock(RolePermissionMapper.class);
        var authorizer = new IdentityManagementAuthorizer(mapper);
        try (var session = mockStatic(StpUtil.class)) {
            session.when(StpUtil::getLoginIdAsLong).thenReturn(7L);
            when(mapper.findUserPermissions(7L)).thenReturn(List.of("document:security-level:write"));
            assertThat(authorizer.require(Permission.DOCUMENT_SECURITY_LEVEL_WRITE)).isEqualTo(7L);
            for (String other : List.of("ROLE_ADMIN", "identity:clearance:write", "space:manage-all", "manage", "edit"
            )) {
                when(mapper.findUserPermissions(7L)).thenReturn(List.of(other));
                assertThatThrownBy(() -> authorizer.require(Permission.DOCUMENT_SECURITY_LEVEL_WRITE))
                        .isInstanceOf(IdentityException.class);
            }
        }
    }
}
