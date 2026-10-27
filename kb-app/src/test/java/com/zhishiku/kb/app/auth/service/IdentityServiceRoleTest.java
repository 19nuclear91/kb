package com.zhishiku.kb.app.auth.service;

import com.zhishiku.kb.app.auth.mapper.IdentityDepartmentMapper;
import com.zhishiku.kb.app.auth.model.IdentityAuditRecord;
import com.zhishiku.kb.app.auth.model.IdentityRecord;
import com.zhishiku.kb.app.auth.model.RoleRecord;
import com.zhishiku.kb.app.auth.model.UserRolePermission;
import com.zhishiku.kb.common.security.IdentityException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 角色分配与解除（任务 4.3）：分配、重复分配、解除、解除未绑定、重放五个分支，
 * 另加"分配不存在的角色"这一 spec 场景（取值错误）。
 *
 * <p>全部用 stub mapper，不连库。角色资格"下一次请求即生效"由无缓存的
 * 服务端读取保证（{@code SaTokenRoleProvider} 每次请求查库），不在这里断言。
 */
class IdentityServiceRoleTest {

    private static final long USER_ID = 7L;
    private static final long ROLE_ID = 2L;
    private static final long ACTOR = 1L;

    private IdentityDepartmentMapper mapper;
    private IdentityAuditService audit;
    private IdentityService service;

    @BeforeEach
    void setUp() {
        mapper = mock(IdentityDepartmentMapper.class);
        when(mapper.findEnabledManagementPermissions()).thenReturn(List.of(
                new UserRolePermission(999L, 999L, "identity:role:assign"),
                new UserRolePermission(999L, 999L, "identity:role:write")));
        audit = mock(IdentityAuditService.class);
        service = new IdentityService(mapper, null, audit);
    }

    @Test
    @DisplayName("分配角色：写入绑定，审计含操作者、目标与角色标识")
    void assignCreatesBindingAndAuditsRoleIdentity() {
        when(mapper.findIdentity(USER_ID)).thenReturn(user());
        when(mapper.findRoleById(ROLE_ID)).thenReturn(role());
        when(mapper.insertUserRole(USER_ID, ROLE_ID)).thenReturn(1);

        service.assignRole(USER_ID, ROLE_ID, ACTOR, "req-1");

        verify(mapper).insertUserRole(USER_ID, ROLE_ID);
        ArgumentCaptor<String> after = ArgumentCaptor.forClass(String.class);
        verify(audit).success(eq(AuditScope.USER_ROLE_ASSIGN), eq("req-1"), eq(ACTOR),
                eq(USER_ID), eq("assign_role"), eq("{}"), after.capture());
        assertThat(after.getValue())
                .contains("\"roleId\":2")
                .contains("ROLE_EDITOR")
                .contains("\"created\":true");
    }

    @Test
    @DisplayName("重复分配：幂等成功，审计标记本次未新建绑定")
    void duplicateAssignIsIdempotentSuccess() {
        when(mapper.findIdentity(USER_ID)).thenReturn(user());
        when(mapper.findRoleById(ROLE_ID)).thenReturn(role());
        when(mapper.insertUserRole(USER_ID, ROLE_ID)).thenReturn(0);

        service.assignRole(USER_ID, ROLE_ID, ACTOR, "req-2");

        ArgumentCaptor<String> after = ArgumentCaptor.forClass(String.class);
        verify(audit).success(eq(AuditScope.USER_ROLE_ASSIGN), eq("req-2"), eq(ACTOR),
                eq(USER_ID), eq("assign_role"), eq("{}"), after.capture());
        assertThat(after.getValue())
                .as("重复分配不是错误，但审计要能区分它没有改变任何状态")
                .contains("\"created\":false");
    }

    @Test
    @DisplayName("分配不存在的角色：取值错误，不写绑定也不写审计")
    void assignUnknownRoleIsRejected() {
        when(mapper.findIdentity(USER_ID)).thenReturn(user());
        when(mapper.findRoleById(ROLE_ID)).thenReturn(null);

        assertThatThrownBy(() -> service.assignRole(USER_ID, ROLE_ID, ACTOR, "req-3"))
                .isInstanceOf(IdentityException.class)
                .satisfies(e -> assertThat(((IdentityException) e).httpStatus()).isEqualTo(400))
                .hasMessageContaining("不存在");

        verify(mapper, never()).insertUserRole(anyLong(), anyLong());
        verify(audit, never()).success(any(), anyString(), any(), any(), anyString(),
                anyString(), anyString());
    }

    @Test
    @DisplayName("解除角色：删除绑定并写审计")
    void revokeDeletesBindingAndAudits() {
        when(mapper.findIdentity(USER_ID)).thenReturn(user());
        when(mapper.deleteUserRole(USER_ID, ROLE_ID)).thenReturn(1);

        service.revokeRole(USER_ID, ROLE_ID, ACTOR, "req-4");

        verify(mapper).deleteUserRole(USER_ID, ROLE_ID);
        ArgumentCaptor<String> before = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> after = ArgumentCaptor.forClass(String.class);
        verify(audit).success(eq(AuditScope.USER_ROLE_REVOKE), eq("req-4"), eq(ACTOR),
                eq(USER_ID), eq("revoke_role"), before.capture(), after.capture());
        assertThat(before.getValue()).contains("\"bound\":true");
        assertThat(after.getValue()).contains("\"bound\":false");
    }

    @Test
    @DisplayName("解除未绑定的角色：404，不写审计")
    void revokeUnboundRoleReturnsNotFound() {
        when(mapper.findIdentity(USER_ID)).thenReturn(user());
        when(mapper.deleteUserRole(USER_ID, ROLE_ID)).thenReturn(0);

        assertThatThrownBy(() -> service.revokeRole(USER_ID, ROLE_ID, ACTOR, "req-5"))
                .isInstanceOf(IdentityException.class)
                .satisfies(e -> assertThat(((IdentityException) e).httpStatus()).isEqualTo(404))
                .hasMessageContaining("未绑定");

        verify(audit, never()).success(any(), anyString(), any(), any(), anyString(),
                anyString(), anyString());
    }

    @Test
    @DisplayName("同一请求标识重放：不再触碰绑定，也不再写审计")
    void replayedRequestsDoNotTouchBindings() {
        when(audit.existing(AuditScope.USER_ROLE_ASSIGN, "req-6"))
                .thenReturn(auditEvent(AuditScope.USER_ROLE_ASSIGN, "req-6"));
        when(audit.existing(AuditScope.USER_ROLE_REVOKE, "req-6"))
                .thenReturn(auditEvent(AuditScope.USER_ROLE_REVOKE, "req-6"));

        service.assignRole(USER_ID, ROLE_ID, ACTOR, "req-6");
        service.revokeRole(USER_ID, ROLE_ID, ACTOR, "req-6");

        verify(mapper, never()).insertUserRole(anyLong(), anyLong());
        verify(mapper, never()).deleteUserRole(anyLong(), anyLong());
        verify(audit, never()).success(any(), anyString(), any(), any(), anyString(),
                anyString(), anyString());
    }

    private static IdentityRecord user() {
        return new IdentityRecord(USER_ID, "bob", "Bob", 2L, (short) 1, true, 0L, null, null);
    }

    private static RoleRecord role() {
        return new RoleRecord(ROLE_ID, "ROLE_EDITOR", "编辑");
    }

    private static IdentityAuditRecord auditEvent(AuditScope scope, String requestId) {
        return new IdentityAuditRecord(null, null, scope.name(), requestId, ACTOR, "user",
                USER_ID, "assign_role", "SUCCESS", "{}", "{}", null, null);
    }
}
