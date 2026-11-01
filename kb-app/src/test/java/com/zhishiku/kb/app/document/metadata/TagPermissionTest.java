package com.zhishiku.kb.app.document.metadata;

import cn.dev33.satoken.stp.StpUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhishiku.kb.app.auth.mapper.IdentityDepartmentMapper;
import com.zhishiku.kb.app.auth.mapper.RolePermissionMapper;
import com.zhishiku.kb.app.auth.model.Permission;
import com.zhishiku.kb.app.auth.model.RoleRecord;
import com.zhishiku.kb.app.auth.service.IdentityAuditService;
import com.zhishiku.kb.app.auth.service.IdentityManagementAuthorizer;
import com.zhishiku.kb.app.auth.service.RoleManagementService;
import com.zhishiku.kb.common.security.IdentityException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TagPermissionTest {
    @Test
    void readAndWriteAreIndependentAndRevocationTakesEffect() {
        var mapper = mock(RolePermissionMapper.class);
        var auth = new IdentityManagementAuthorizer(mapper);
        try (var session = mockStatic(StpUtil.class)) {
            session.when(StpUtil::getLoginIdAsLong).thenReturn(7L);
            when(mapper.findUserPermissions(7)).thenReturn(List.of("document:tag:read"));
            assertThat(auth.require(Permission.DOCUMENT_TAG_READ)).isEqualTo(7);
            assertThatThrownBy(() -> auth.require(Permission.DOCUMENT_TAG_WRITE)).isInstanceOf(IdentityException.class);
            when(mapper.findUserPermissions(7)).thenReturn(List.of("document:tag:write"));
            assertThat(auth.require(Permission.DOCUMENT_TAG_WRITE)).isEqualTo(7);
            assertThatThrownBy(() -> auth.require(Permission.DOCUMENT_TAG_READ)).isInstanceOf(IdentityException.class);
            when(mapper.findUserPermissions(7)).thenReturn(List.of("space:manage-all", "ROLE_ADMIN", "edit"));
            assertThatThrownBy(() -> auth.require(Permission.DOCUMENT_TAG_WRITE)).isInstanceOf(IdentityException.class);
        }
    }

    @Test
    void ordinaryRoleCanReceiveAnExplicitTagPermission() {
        var identities = mock(IdentityDepartmentMapper.class);
        var permissions = mock(RolePermissionMapper.class);
        var role = new RoleRecord(2L, "EDITOR", "编辑者");
        when(permissions.lockRole(2)).thenReturn(role);
        when(identities.findRoleById(2)).thenReturn(role);
        when(permissions.findRolePermissions(2)).thenReturn(List.of(), List.of("document:tag:write"));
        var service = new RoleManagementService(identities, permissions, mock(IdentityAuditService.class), new ObjectMapper());
        assertThat(service.setPermission(2, "document:tag:write", true, 7L, "grant").permissions())
                .containsExactly("document:tag:write");
        verify(permissions).grantPermission(2, "document:tag:write");
    }
}
