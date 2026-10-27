package com.zhishiku.kb.app.auth.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhishiku.kb.app.auth.mapper.IdentityDepartmentMapper;
import com.zhishiku.kb.app.auth.mapper.RolePermissionMapper;
import com.zhishiku.kb.app.auth.model.IdentityAuditRecord;
import com.zhishiku.kb.app.auth.model.RoleRecord;
import com.zhishiku.kb.app.auth.model.UserRolePermission;
import com.zhishiku.kb.common.security.IdentityConflictReason;
import com.zhishiku.kb.common.security.IdentityErrorCode;
import com.zhishiku.kb.common.security.IdentityException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.dao.DuplicateKeyException;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyLong;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.contains;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RoleManagementServiceTest {
    private final IdentityDepartmentMapper identities = mock(IdentityDepartmentMapper.class);
    private final RolePermissionMapper mapper = mock(RolePermissionMapper.class);
    private final IdentityAuditService audit = mock(IdentityAuditService.class);
    private final RoleManagementService service = new RoleManagementService(identities, mapper, audit,
            new ObjectMapper());

    @Test
    void createStartsWithoutAnyQualificationAndReplayDoesNotWriteAgain() {
        when(identities.nextRoleId()).thenReturn(3L);
        when(identities.insertRole(3L, "READER", "读取者")).thenReturn(1);
        var created = service.create("READER", "读取者", 1L, "create");
        assertThat(created.permissions()).isEmpty();
        verify(mapper, never()).grantPermission(anyLong(), anyString());
        verify(audit).success(eq(AuditScope.ROLE_CREATE), eq("create"), eq(1L), eq(3L), eq("create_role"), eq("{}"),
                contains("READER"));
        var previous = new IdentityAuditRecord();
        previous.setTargetId(3L);
        when(audit.existing(AuditScope.ROLE_CREATE, "create")).thenReturn(previous);
        when(identities.findRoleById(3L)).thenReturn(new RoleRecord(3L, "READER", "读取者"));
        assertThat(service.create("READER", "读取者", 1L, "create")).isEqualTo(created);
        verify(identities, times(1)).insertRole(anyLong(), anyString(), anyString());
        verify(audit, times(1)).success(any(), anyString(), anyLong(), anyLong(), anyString(), anyString(),
                anyString());
    }

    @Test
    void duplicateCodeRejectsBeforeInsert() {
        when(identities.findRoleByCode("READER")).thenReturn(new RoleRecord(3L, "READER", "读取者"));
        assertThatThrownBy(() -> service.create("READER", "新名称", 1L, "duplicate"))
                .isInstanceOfSatisfying(IdentityException.class, e -> {
                    assertThat(e.httpStatus()).isEqualTo(409);
                    assertThat(e.errorCode()).isEqualTo(IdentityErrorCode.CONFLICT);
                    assertThat(e.reason()).contains(IdentityConflictReason.IDENTIFIER_CONFLICT);
                });
        verify(identities, never()).insertRole(anyLong(), anyString(), anyString());
        verify(audit, never()).success(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void databaseUniqueConflictAlsoReturns409() {
        when(identities.insertRole(anyLong(), anyString(), anyString())).thenThrow(new DuplicateKeyException(
                "duplicate"));
        assertThatThrownBy(() -> service.create("READER", "新名称", 1L, "duplicate"))
                .isInstanceOfSatisfying(IdentityException.class, e -> {
                    assertThat(e.httpStatus()).isEqualTo(409);
                    assertThat(e.getMessage()).isEqualTo("角色 code 已存在");
                    assertThat(e.reason()).as("约束兜底不从文案推断原因").isEmpty();
                });
    }

    @Test
    void revokingTheLastManagerPermissionCarriesReasonWithoutWriting() {
        when(mapper.lockRole(3L)).thenReturn(new RoleRecord(3L, "ADMIN", "管理员"));
        when(mapper.findRolePermissions(3L)).thenReturn(List.of("identity:role:assign", "identity:role:write"));
        when(identities.findEnabledManagementPermissions()).thenReturn(List.of(
                new UserRolePermission(7L, 3L, "identity:role:assign"),
                new UserRolePermission(7L, 3L, "identity:role:write")));
        assertThatThrownBy(() -> service.setPermission(3L, "identity:role:write", false, 1L, "revoke"))
                .isInstanceOfSatisfying(IdentityException.class, e -> {
                    assertThat(e.httpStatus()).isEqualTo(409);
                    assertThat(e.errorCode()).isEqualTo(IdentityErrorCode.CONFLICT);
                    assertThat(e.reason()).contains(IdentityConflictReason.LAST_MANAGER_REQUIRED);
                });
        verify(mapper, never()).revokePermission(anyLong(), anyString());
        verify(audit, never()).success(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void codeCannotBeChangedAndNoUpdateOccurs() {
        when(mapper.lockRole(3L)).thenReturn(new RoleRecord(3L, "READER", "读取者"));
        assertThatThrownBy(() -> service.rename(3L, "新名称", "ROLE_ADMIN", 1L, "rename"))
                .isInstanceOfSatisfying(IdentityException.class, e -> assertThat(e.httpStatus()).isEqualTo(400));
        verify(mapper, never()).renameRole(anyLong(), anyString());
    }

    @Test
    void renameChangesOnlyNameAndAuditsBeforeAndAfter() {
        when(mapper.lockRole(3L)).thenReturn(new RoleRecord(3L, "READER", "读取者"));
        when(mapper.findRolePermissions(3L)).thenReturn(List.of("identity:user:read"));
        when(mapper.renameRole(3L, "新名称")).thenReturn(1);
        var result = service.rename(3L, "新名称", "READER", 1L, "rename");
        assertThat(result.code()).isEqualTo("READER");
        assertThat(result.name()).isEqualTo("新名称");
        assertThat(result.permissions()).containsExactly("identity:user:read");
        verify(audit).success(eq(AuditScope.ROLE_RENAME), eq("rename"), eq(1L), eq(3L), eq("rename_role"), contains(
                "读取者"), contains("新名称"));
    }

    @ParameterizedTest
    @CsvSource({"0,0", "1,0", "0,1", "1,1"})
    void deletionRequiresBothReferenceSetsEmpty(long users, long acls) {
        when(mapper.lockRole(3L)).thenReturn(new RoleRecord(3L, "READER", "读取者"));
        when(mapper.countBoundUsers(3L)).thenReturn(users);
        when(mapper.countAclReferences(3L)).thenReturn(acls);
        when(mapper.deleteRole(3L)).thenReturn(1);
        if (users == 0 && acls == 0) {
            service.delete(3L, 1L, "delete");
            verify(mapper).deleteRole(3L);
            verify(audit).success(eq(AuditScope.ROLE_DELETE), eq("delete"), eq(1L), eq(3L), eq("delete_role"),
                    contains("READER"), eq("{}"));
        } else {
            assertThatThrownBy(() -> service.delete(3L, 1L, "delete"))
                    .isInstanceOfSatisfying(IdentityException.class, e -> {
                        assertThat(e.httpStatus()).isEqualTo(409);
                        assertThat(e.errorCode()).isEqualTo(IdentityErrorCode.CONFLICT);
                        assertThat(e.reason()).contains(IdentityConflictReason.ROLE_IN_USE);
                        if (users > 0) assertThat(e.getMessage()).contains("用户绑定");
                        if (acls > 0) assertThat(e.getMessage()).contains("授权记录");
                    });
            verify(mapper, never()).deleteRole(anyLong());
        }
        verify(mapper).countBoundUsers(3L);
        verify(mapper).countAclReferences(3L);
    }
}
