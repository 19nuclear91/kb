package com.zhishiku.kb.app.space.service;

import com.zhishiku.kb.app.auth.service.AuditScope;
import com.zhishiku.kb.app.auth.service.IdentityAuditService;
import com.zhishiku.kb.app.auth.service.PermissionService;
import com.zhishiku.kb.app.space.mapper.SpaceMapper;
import com.zhishiku.kb.app.space.model.SpaceAclRecord;
import com.zhishiku.kb.common.security.IdentityException;
import com.zhishiku.kb.common.security.PrincipalType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyShort;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SpaceAclServiceTest {

    private static final long SPACE = 1L;
    private static final long ADMIN = 1L;
    private static final long DEPT_RESEARCH = 2L;

    private SpaceMapper mapper;
    private SpaceAuthorizer authorizer;
    private IdentityAuditService audit;
    private SpaceAclService service;

    @BeforeEach
    void setUp() {
        mapper = mock(SpaceMapper.class);
        authorizer = mock(SpaceAuthorizer.class);
        audit = mock(IdentityAuditService.class);
        service = new SpaceAclService(mapper, authorizer, audit);
    }

    @Test
    @DisplayName("白名单外的权限位返回参数错误且不写入记录")
    void rejectingUnexecutedPermissionBitWritesNothing() {
        when(authorizer.requireSpaceManager(SPACE)).thenReturn(ADMIN);

        for (String bit : List.of("approve", "Comment", "comment-all")) {
            assertThatThrownBy(() -> service.grant(SPACE, PrincipalType.DEPT, DEPT_RESEARCH,
                    bit, null, "req-1"))
                    .isInstanceOf(IdentityException.class)
                    .hasMessageContaining("不被支持");
        }
        verify(mapper, never()).grantAcl(anyLong(), anyShort(), anyLong(), anyString());
    }

    @Test
    @DisplayName("空间 comment 可授给用户、角色或部门并独立撤销，不需要 manage 授出资格")
    void commentCanBeGrantedAndRevokedForAllPrincipalTypes() {
        when(authorizer.requireSpaceManager(SPACE)).thenReturn(ADMIN);
        for (PrincipalType type : PrincipalType.values()) {
            var granted = service.grant(SPACE, type, DEPT_RESEARCH, "comment", null, "comment-" + type);
            assertThat(granted.acl().permission()).isEqualTo("comment");
            verify(mapper).grantAcl(SPACE, type.code(), DEPT_RESEARCH, "comment");
            service.revoke(SPACE, type, DEPT_RESEARCH, "comment", null, "uncomment-" + type);
            verify(mapper).revokeAcl(SPACE, type.code(), DEPT_RESEARCH, "comment");
        }
        verify(authorizer, never()).requireGrantManage();
    }

    @Test
    @DisplayName("授予 view 或 review 只写该权限位，既有授权不会被自动补授 comment")
    void otherGrantsNeverImplyComment() {
        when(authorizer.requireSpaceManager(SPACE)).thenReturn(ADMIN);
        for (String bit : List.of("view", "download", "edit", "review")) {
            service.grant(SPACE, PrincipalType.USER, 8, bit, null, "grant-" + bit);
        }
        verify(mapper, never()).grantAcl(anyLong(), anyShort(), anyLong(), eq("comment"));
    }

    @Test
    @DisplayName("空间 review 可授给用户、角色或部门并独立撤销")
    void reviewCanBeGrantedAndRevokedForAllPrincipalTypes() {
        when(authorizer.requireSpaceManager(SPACE)).thenReturn(ADMIN);
        for (PrincipalType type : PrincipalType.values()) {
            var granted = service.grant(SPACE, type, DEPT_RESEARCH, "review", null, "grant-" + type);
            assertThat(granted.acl().permission()).isEqualTo("review");
            verify(mapper).grantAcl(SPACE, type.code(), DEPT_RESEARCH, "review");
            service.revoke(SPACE, type, DEPT_RESEARCH, "review", null, "revoke-" + type);
            verify(mapper).revokeAcl(SPACE, type.code(), DEPT_RESEARCH, "review");
        }
        verify(authorizer, never()).requireGrantManage();
    }

    @Test
    @DisplayName("仅持空间 manage 的操作者授出 manage 被拒绝")
    void spaceManagerMayNotGrantManage() {
        when(authorizer.requireGrantManage())
                .thenThrow(IdentityException.forbidden("该操作仅限全局身份管理员"));

        assertThatThrownBy(() -> service.grant(SPACE, PrincipalType.DEPT, DEPT_RESEARCH,
                PermissionService.PERM_MANAGE, null, "req-2"))
                .isInstanceOf(IdentityException.class);
        verify(mapper, never()).grantAcl(anyLong(), anyShort(), anyLong(), anyString());
    }

    @Test
    @DisplayName("全局管理员授出 manage 成功")
    void globalManagerMayGrantManage() {
        when(authorizer.requireGrantManage()).thenReturn(ADMIN);
        when(mapper.countAcl(SPACE, PrincipalType.DEPT.code(), DEPT_RESEARCH,
                PermissionService.PERM_MANAGE)).thenReturn(0L);
        when(mapper.userIdsForPrincipal(PrincipalType.DEPT.code(), DEPT_RESEARCH))
                .thenReturn(List.of(1L));
        when(mapper.findUserClearance(1L)).thenReturn((short) 2);
        when(mapper.countDocumentsAboveClearance(SPACE, (short) 2)).thenReturn(0L);

        var result = service.grant(SPACE, PrincipalType.DEPT, DEPT_RESEARCH,
                PermissionService.PERM_MANAGE, null, "req-3");

        assertThat(result.alreadyGranted()).isFalse();
        verify(mapper).grantAcl(SPACE, PrincipalType.DEPT.code(), DEPT_RESEARCH,
                PermissionService.PERM_MANAGE);
        verify(audit).success(eq(AuditScope.SPACE_ACL_GRANT), eq("req-3"), eq(ADMIN), eq(SPACE),
                eq("grant"), anyString(), anyString());
    }

    @Test
    @DisplayName("主体密级不足时回执计数为 1，但授权记录仍被写入")
    void clearanceBlockedPrincipalIsReportedButStillGranted() {
        when(authorizer.requireSpaceManager(SPACE)).thenReturn(ADMIN);
        when(mapper.countAcl(SPACE, PrincipalType.DEPT.code(), DEPT_RESEARCH, "view")).thenReturn(0L);
        when(mapper.userIdsForPrincipal(PrincipalType.DEPT.code(), DEPT_RESEARCH))
                .thenReturn(List.of(2L));
        // 研发中心最低成员密级 1，而空间内有密级 2 的文档
        when(mapper.findUserClearance(2L)).thenReturn((short) 1);
        when(mapper.countDocumentsAboveClearance(SPACE, (short) 1)).thenReturn(2L);

        var result = service.grant(SPACE, PrincipalType.DEPT, DEPT_RESEARCH, "view", null, "req-4");

        assertThat(result.clearanceBlockedPrincipalCount()).isEqualTo(1L);
        verify(mapper).grantAcl(SPACE, PrincipalType.DEPT.code(), DEPT_RESEARCH, "view");
    }

    @Test
    @DisplayName("重复授予同一条授权是幂等的，只落一条记录且不返回失败")
    void repeatedGrantIsIdempotent() {
        when(authorizer.requireSpaceManager(SPACE)).thenReturn(ADMIN);
        when(mapper.countAcl(SPACE, PrincipalType.DEPT.code(), DEPT_RESEARCH, "edit")).thenReturn(1L);

        var result = service.grant(SPACE, PrincipalType.DEPT, DEPT_RESEARCH, "edit", null, "req-5");

        assertThat(result.alreadyGranted()).isTrue();
        verify(mapper, never()).grantAcl(anyLong(), anyShort(), anyLong(), anyString());
    }

    @Test
    @DisplayName("撤销后审计仍可查，且记录被移除的主体与权限位")
    void revokeAuditsRemovedPrincipalAndPermission() {
        when(authorizer.requireSpaceManager(SPACE)).thenReturn(ADMIN);
        when(mapper.revokeAcl(SPACE, PrincipalType.DEPT.code(), DEPT_RESEARCH, "view")).thenReturn(1);

        service.revoke(SPACE, PrincipalType.DEPT, DEPT_RESEARCH, "view", null, "req-6");

        verify(mapper).revokeAcl(SPACE, PrincipalType.DEPT.code(), DEPT_RESEARCH, "view");
        verify(audit).success(eq(AuditScope.SPACE_ACL_REVOKE), eq("req-6"), eq(ADMIN), eq(SPACE),
                eq("revoke"), anyString(),
                ArgumentMatchers.contains("\"principalKey\":\"3:2\""));
    }

    @Test
    @DisplayName("撤销不存在的授权不报错，是幂等的")
    void revokingAMissingGrantDoesNotFail() {
        when(authorizer.requireSpaceManager(SPACE)).thenReturn(ADMIN);
        when(mapper.revokeAcl(SPACE, PrincipalType.DEPT.code(), DEPT_RESEARCH, "view")).thenReturn(0);

        service.revoke(SPACE, PrincipalType.DEPT, DEPT_RESEARCH, "view", null, "req-7");

        verify(audit).success(eq(AuditScope.SPACE_ACL_REVOKE), eq("req-7"), eq(ADMIN), eq(SPACE),
                eq("revoke"), anyString(), anyString());
    }

    @Test
    @DisplayName("列出授权需要该空间的 manage")
    void listingRequiresManage() {
        when(authorizer.requireSpaceManager(SPACE))
                .thenThrow(IdentityException.forbidden("无权管理该空间"));

        assertThatThrownBy(() -> service.list(SPACE)).isInstanceOf(IdentityException.class);
        verify(mapper, never()).listAcl(anyLong());
    }

    @Test
    @DisplayName("断言记录被移除的主体与权限位：撤销响应包含主体键")
    void revokeStateCarriesPrincipalKey() {
        SpaceAclRecord record = new SpaceAclRecord();
        record.setId(9L);
        record.setSpaceId(SPACE);
        record.setPrincipalType(PrincipalType.DEPT.code());
        record.setPrincipalId(DEPT_RESEARCH);
        record.setPermission("view");
        record.setPrincipalKey("3:2");
        when(authorizer.requireSpaceManager(SPACE)).thenReturn(ADMIN);
        when(mapper.listAcl(SPACE)).thenReturn(List.of(record));

        var listed = service.list(SPACE);
        assertThat(listed).hasSize(1);
        assertThat(listed.get(0).principalKey()).isEqualTo("3:2");
        assertThat(listed.get(0).principalType()).isEqualTo(PrincipalType.DEPT);
    }
}
