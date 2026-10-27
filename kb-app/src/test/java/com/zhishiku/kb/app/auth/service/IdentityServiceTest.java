package com.zhishiku.kb.app.auth.service;

import com.zhishiku.kb.app.auth.mapper.IdentityDepartmentMapper;
import com.zhishiku.kb.app.auth.model.DepartmentRecord;
import com.zhishiku.kb.app.auth.model.ExternalIdentityRecord;
import com.zhishiku.kb.app.auth.model.IdentityAuditRecord;
import com.zhishiku.kb.app.auth.model.IdentityRecord;
import com.zhishiku.kb.common.security.IdentityException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class IdentityServiceTest {

    private final IdentityDepartmentMapper mapper = mock(IdentityDepartmentMapper.class);
    private final IdentityService service = new IdentityService(mapper);

    @Test
    void reservedSsoNamespaceCannotBeCreatedEvenWithAdminAuditReplay() {
        var audit = mock(IdentityAuditService.class);
        var audited = new IdentityService(mapper, null, audit);
        assertThatThrownBy(() -> audited.bindExternal(7, " SSO-V1:feishu:raw ", "subject", 1L, "old-request"))
                .isInstanceOf(IdentityException.class);
        verify(mapper, never()).insertExternalIdentity(any());
        verify(audit, never()).existing(AuditScope.EXTERNAL_BIND, "old-request");
    }

    @Test
    void managedRowsRemainReadableAndAdminUnbindKeepsOriginalAuditScope() {
        var audit = mock(IdentityAuditService.class);
        var audited = new IdentityService(mapper, null, audit);
        when(mapper.findExternalIdentityById(9)).thenReturn(new ExternalIdentityRecord(9L, 7L, "sso-v1:feishu:scope",
                "private-subject", true));
        when(mapper.deleteExternalIdentity(9)).thenReturn(1);
        audited.unbindExternal(7, 9, 1L, "admin-unbind");
        verify(mapper).deleteExternalIdentity(9);
        verify(audit).success(AuditScope.EXTERNAL_UNBIND, "admin-unbind", 1L, 7L, "unbind",
                "{\"provider\":\"sso-v1:feishu:scope\"}", "{}");
    }

    @Test
    void movesUserOnlyToEnabledDepartmentWithVersionCondition() {
        IdentityRecord before = user(7L, 2L, true, 4L);
        IdentityRecord after = user(7L, 3L, true, 5L);
        when(mapper.findIdentityForUpdate(7L)).thenReturn(before);
        when(mapper.findDepartment(3L)).thenReturn(department(3L, true));
        when(mapper.moveUserToDepartment(7L, 3L, 4L)).thenReturn(1);
        when(mapper.findIdentity(7L)).thenReturn(after);

        assertThat(service.moveToDepartment(7L, 3L, 4L).departmentId()).isEqualTo(3L);
        verify(mapper).moveUserToDepartment(7L, 3L, 4L);
    }

    @Test
    void rejectsDisabledTargetWithoutChangingUser() {
        when(mapper.findIdentityForUpdate(7L)).thenReturn(user(7L, 2L, true, 4L));
        when(mapper.findDepartment(3L)).thenReturn(department(3L, false));

        assertThatThrownBy(() -> service.moveToDepartment(7L, 3L, 4L))
                .isInstanceOf(IdentityException.class)
                .hasMessageContaining("停用");
        verify(mapper, never()).moveUserToDepartment(any(Long.class), any(Long.class), any(Long.class));
    }

    @Test
    void rejectsExternalIdentityAlreadyOwnedByAnotherUser() {
        when(mapper.findIdentity(7L)).thenReturn(user(7L, 2L, true, 1L));
        when(mapper.findExternalIdentity("oidc", "subject-1"))
                .thenReturn(new ExternalIdentityRecord(9L, 8L, "oidc", "subject-1", true));

        assertThatThrownBy(() -> service.bindExternal(7L, "OIDC", "subject-1"))
                .isInstanceOf(IdentityException.class)
                .hasMessageContaining("已绑定");
        verify(mapper, never()).insertExternalIdentity(any());
    }

    @Test
    void disabledUserCannotResolveQuotaPrincipal() {
        when(mapper.findIdentity(7L)).thenReturn(user(7L, 2L, false, 1L));

        assertThatThrownBy(() -> service.quotaPrincipal(7L))
                .isInstanceOf(IdentityException.class)
                .hasMessageContaining("停用");
    }

    @Test
    void repeatedRequestIdDoesNotOverwriteNewerDepartment() {
        IdentityAuditService audit = mock(IdentityAuditService.class);
        IdentityService audited = new IdentityService(mapper, null, audit);
        when(audit.existing(AuditScope.USER_MOVE_DEPARTMENT, "retry-1"))
                .thenReturn(new IdentityAuditRecord(null, null, "USER_MOVE_DEPARTMENT", "retry-1",
                        1L, "user", 7L, "move_department", "SUCCESS", "{}", "{}", null, null));
        when(mapper.findIdentity(7L)).thenReturn(user(7L, 3L, true, 5L));

        assertThat(audited.moveToDepartment(7L, 2L, 4L, 1L, "retry-1").departmentId())
                .isEqualTo(3L);
        verify(mapper, never()).moveUserToDepartment(any(Long.class), any(Long.class), any(Long.class));
    }

    private static IdentityRecord user(long id, long dept, boolean enabled, long version) {
        return new IdentityRecord(id, "user-" + id, "User", dept, (short) 1,
                enabled, version, null, null);
    }

    private static DepartmentRecord department(long id, boolean enabled) {
        return new DepartmentRecord(id, null, "d-" + id, "Dept", "/" + id + "/",
                enabled, 0L, null, null);
    }
}
