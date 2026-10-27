package com.zhishiku.kb.app.space.service;

import com.zhishiku.kb.app.auth.model.IdentityAuditRecord;
import com.zhishiku.kb.app.auth.service.AuditScope;
import com.zhishiku.kb.app.auth.service.IdentityAuditService;
import com.zhishiku.kb.app.auth.service.PermissionService;
import com.zhishiku.kb.app.space.mapper.DocumentAclMapper;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyShort;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DocumentAclServiceTest {

    /**
     * 文档 10 属于空间 1；文档 20 属于空间 2。
     */
    private static final long DOC_IN_MY_SPACE = 10L;
    private static final long DOC_IN_OTHER_SPACE = 20L;
    private static final long MY_SPACE = 1L;
    private static final long OTHER_SPACE = 2L;
    private static final long ADMIN = 7L;
    private static final long OUTSIDE_DEPT = 5L;

    private DocumentAclMapper mapper;
    private SpaceAuthorizer authorizer;
    private IdentityAuditService audit;
    private DocumentAclService service;

    @BeforeEach
    void setUp() {
        mapper = mock(DocumentAclMapper.class);
        authorizer = mock(SpaceAuthorizer.class);
        audit = mock(IdentityAuditService.class);
        service = new DocumentAclService(mapper, authorizer, audit);
    }

    // ---- 6.1 鉴权依据是文档当前所属空间 ----

    @Test
    @DisplayName("为本空间文档开设例外：鉴权用的是文档所属空间，不是调用方声称的空间")
    void grantingForDocumentInOwnSpaceChecksOwningSpace() {
        when(mapper.findOwningSpaceId(DOC_IN_MY_SPACE)).thenReturn(MY_SPACE);
        when(authorizer.requireSpaceManager(MY_SPACE)).thenReturn(ADMIN);
        when(mapper.countAcl(DOC_IN_MY_SPACE, PrincipalType.DEPT.code(), OUTSIDE_DEPT, "view"))
                .thenReturn(0L);
        when(mapper.listAclUserIds(PrincipalType.DEPT.code(), OUTSIDE_DEPT)).thenReturn(List.of(5L));
        when(mapper.findUserClearance(5L)).thenReturn((short) 2);
        when(mapper.countDocumentAboveClearance(DOC_IN_MY_SPACE, (short) 2)).thenReturn(0L);

        var result = service.grant(DOC_IN_MY_SPACE, PrincipalType.DEPT, OUTSIDE_DEPT, "view", null, "req-1");

        assertThat(result.alreadyGranted()).isFalse();
        assertThat(result.clearanceBlockedPrincipalCount()).isZero();
        verify(mapper).grantAcl(DOC_IN_MY_SPACE, PrincipalType.DEPT.code(), OUTSIDE_DEPT, "view");
        // 关键：鉴权目标是与文档实际所属空间一致的，调用方无法指定另一个空间
        verify(authorizer).requireSpaceManager(MY_SPACE);
        verify(authorizer, never()).requireSpaceManager(OTHER_SPACE);
    }

    @Test
    @DisplayName("为其他空间文档开设例外返回禁止，且不写入记录")
    void grantingForDocumentInOtherSpaceIsForbidden() {
        when(mapper.findOwningSpaceId(DOC_IN_OTHER_SPACE)).thenReturn(OTHER_SPACE);
        when(authorizer.requireSpaceManager(OTHER_SPACE))
                .thenThrow(IdentityException.forbidden("无权管理该空间"));

        assertThatThrownBy(() -> service.grant(DOC_IN_OTHER_SPACE, PrincipalType.DEPT, OUTSIDE_DEPT,
                "view", null, "req-2"))
                .isInstanceOf(IdentityException.class)
                .hasMessageContaining("无权管理该空间");
        verify(mapper, never()).grantAcl(anyLong(), anyShort(), anyLong(), anyString());
    }

    @Test
    void spaceReviewNeverEnablesDocumentReviewExceptions() {
        when(mapper.findOwningSpaceId(DOC_IN_MY_SPACE)).thenReturn(MY_SPACE);
        when(authorizer.requireSpaceManager(MY_SPACE)).thenReturn(ADMIN);
        assertThatThrownBy(() -> service.grant(DOC_IN_MY_SPACE, PrincipalType.USER, 8, "review", null, "grant-review"))
                .isInstanceOf(IdentityException.class);
        assertThatThrownBy(() -> service.revoke(DOC_IN_MY_SPACE, PrincipalType.USER, 8, "review", null, "revoke-review"))
                .isInstanceOf(IdentityException.class);
        verify(mapper, never()).grantAcl(anyLong(), anyShort(), anyLong(), anyString());
        verify(mapper, never()).revokeAcl(anyLong(), anyShort(), anyLong(), anyString());
    }

    @Test
    @DisplayName("文档 comment 例外只扩权：按所属空间管理资格授予与撤销，适用于用户、角色和部门")
    void documentCommentExceptionWidensOnlyThatDocument() {
        when(mapper.findOwningSpaceId(DOC_IN_MY_SPACE)).thenReturn(MY_SPACE);
        when(authorizer.requireSpaceManager(MY_SPACE)).thenReturn(ADMIN);
        for (PrincipalType type : PrincipalType.values()) {
            var result = service.grant(DOC_IN_MY_SPACE, type, OUTSIDE_DEPT, "comment", null, "comment-" + type);
            assertThat(result.acl().permission()).isEqualTo("comment");
            verify(mapper).grantAcl(DOC_IN_MY_SPACE, type.code(), OUTSIDE_DEPT, "comment");
            service.revoke(DOC_IN_MY_SPACE, type, OUTSIDE_DEPT, "comment", null, "uncomment-" + type);
            verify(mapper).revokeAcl(DOC_IN_MY_SPACE, type.code(), OUTSIDE_DEPT, "comment");
        }
        verify(authorizer, never()).requireSpaceManager(OTHER_SPACE);
        // 只写例外表的该文档行，从不写入 view 或其他文档。
        verify(mapper, never()).grantAcl(anyLong(), anyShort(), anyLong(), eq("view"));
        verify(mapper, never()).grantAcl(eq(DOC_IN_OTHER_SPACE), anyShort(), anyLong(), anyString());
    }

    @Test
    @DisplayName("文档不存在或已删除返回 404，鉴权根本不会发生")
    void missingDocumentIsNotFound() {
        when(mapper.findOwningSpaceId(99L)).thenReturn(null);

        assertThatThrownBy(() -> service.grant(99L, PrincipalType.DEPT, OUTSIDE_DEPT, "view", null, "req-3"))
                .isInstanceOf(IdentityException.class)
                .hasMessageContaining("文档不存在");
        verify(authorizer, never()).requireSpaceManager(anyLong());
    }

    @Test
    @DisplayName("列出例外同样依据文档所属空间")
    void listingChecksOwningSpace() {
        when(mapper.findOwningSpaceId(DOC_IN_OTHER_SPACE)).thenReturn(OTHER_SPACE);
        when(authorizer.requireSpaceManager(OTHER_SPACE))
                .thenThrow(IdentityException.forbidden("无权管理该空间"));

        assertThatThrownBy(() -> service.list(DOC_IN_OTHER_SPACE)).isInstanceOf(IdentityException.class);
        verify(mapper, never()).listAcl(anyLong());
    }

    // ---- 6.2 例外只能扩权 ----

    @Test
    @DisplayName("借例外收紧既有空间授权：manage 在文档级被拒，且既有空间授权效果不变")
    void tighteningViaExceptionIsRejectedAndChangesNothing() {
        when(mapper.findOwningSpaceId(DOC_IN_MY_SPACE)).thenReturn(MY_SPACE);
        when(authorizer.requireSpaceManager(MY_SPACE)).thenReturn(ADMIN);

        assertThatThrownBy(() -> service.grant(DOC_IN_MY_SPACE, PrincipalType.DEPT, OUTSIDE_DEPT,
                PermissionService.PERM_MANAGE, null, "req-4"))
                .isInstanceOf(IdentityException.class)
                .hasMessageContaining("只能扩权");

        // 既有空间授权效果不变：kb_document_acl 没有任何写入或删除
        verify(mapper, never()).grantAcl(anyLong(), anyShort(), anyLong(), anyString());
        verify(mapper, never()).revokeAcl(anyLong(), anyShort(), anyLong(), anyString());
        verify(audit, never()).success(any(), anyString(), anyLong(), anyLong(), anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("无功能执行的权限位同样被拒，错误信息说明可授予范围是扩权语义")
    void unexecutedPermissionBitIsRejected() {
        when(mapper.findOwningSpaceId(DOC_IN_MY_SPACE)).thenReturn(MY_SPACE);
        when(authorizer.requireSpaceManager(MY_SPACE)).thenReturn(ADMIN);

        assertThatThrownBy(() -> service.grant(DOC_IN_MY_SPACE, PrincipalType.USER, 8L,
                "approve", null, "req-5"))
                .isInstanceOf(IdentityException.class)
                .hasMessageContaining("view")
                .hasMessageContaining("download")
                .hasMessageContaining("edit")
                .hasMessageContaining("comment");
        verify(mapper, never()).grantAcl(anyLong(), anyShort(), anyLong(), anyString());
    }

    @Test
    @DisplayName("撤销路径同样拒绝 manage，避免用撤销达成收紧")
    void revokeRejectsManageAsWell() {
        when(mapper.findOwningSpaceId(DOC_IN_MY_SPACE)).thenReturn(MY_SPACE);
        when(authorizer.requireSpaceManager(MY_SPACE)).thenReturn(ADMIN);

        assertThatThrownBy(() -> service.revoke(DOC_IN_MY_SPACE, PrincipalType.DEPT, OUTSIDE_DEPT,
                PermissionService.PERM_MANAGE, null, "req-6"))
                .isInstanceOf(IdentityException.class)
                .hasMessageContaining("只能扩权");
        verify(mapper, never()).revokeAcl(anyLong(), anyShort(), anyLong(), anyString());
    }

    // ---- 6.3 例外生效范围：例外与密级是"与"关系 ----

    @Test
    @DisplayName("例外已授予但密级不足：回执如实告知该主体当下不可见")
    void exceptionGrantedButClearanceInsufficientIsReported() {
        when(mapper.findOwningSpaceId(DOC_IN_MY_SPACE)).thenReturn(MY_SPACE);
        when(authorizer.requireSpaceManager(MY_SPACE)).thenReturn(ADMIN);
        when(mapper.countAcl(DOC_IN_MY_SPACE, PrincipalType.DEPT.code(), OUTSIDE_DEPT, "view"))
                .thenReturn(0L);
        when(mapper.listAclUserIds(PrincipalType.DEPT.code(), OUTSIDE_DEPT)).thenReturn(List.of(5L));
        // 空间外部门成员密级 1，而该文档密级 3
        when(mapper.findUserClearance(5L)).thenReturn((short) 1);
        when(mapper.countDocumentAboveClearance(DOC_IN_MY_SPACE, (short) 1)).thenReturn(1L);

        var result = service.grant(DOC_IN_MY_SPACE, PrincipalType.DEPT, OUTSIDE_DEPT, "view", null, "req-7");

        // 例外写入成功——扩权的意图达成了；但生效范围受限，回执不为 0
        assertThat(result.alreadyGranted()).isFalse();
        assertThat(result.clearanceBlockedPrincipalCount()).isEqualTo(1L);
        verify(mapper).grantAcl(DOC_IN_MY_SPACE, PrincipalType.DEPT.code(), OUTSIDE_DEPT, "view");
    }

    @Test
    @DisplayName("混合密级部门中只要有成员密级足够，就不算被密级拦截")
    void mixedClearanceDepartmentIsNotReportedAsBlocked() {
        when(mapper.findOwningSpaceId(DOC_IN_MY_SPACE)).thenReturn(MY_SPACE);
        when(authorizer.requireSpaceManager(MY_SPACE)).thenReturn(ADMIN);
        when(mapper.countAcl(DOC_IN_MY_SPACE, PrincipalType.DEPT.code(), OUTSIDE_DEPT, "download"))
                .thenReturn(0L);
        when(mapper.listAclUserIds(PrincipalType.DEPT.code(), OUTSIDE_DEPT))
                .thenReturn(List.of(5L, 6L));
        when(mapper.findUserClearance(5L)).thenReturn((short) 1);
        when(mapper.findUserClearance(6L)).thenReturn((short) 4);
        when(mapper.countDocumentAboveClearance(DOC_IN_MY_SPACE, (short) 4)).thenReturn(0L);

        var result = service.grant(DOC_IN_MY_SPACE, PrincipalType.DEPT, OUTSIDE_DEPT,
                "download", null, "req-8");

        assertThat(result.clearanceBlockedPrincipalCount()).isZero();
    }

    @Test
    @DisplayName("主体没有任何启用成员时回执为 0，不误报为密级拦截")
    void principalWithoutEnabledMembersIsNotReportedAsBlocked() {
        when(mapper.findOwningSpaceId(DOC_IN_MY_SPACE)).thenReturn(MY_SPACE);
        when(authorizer.requireSpaceManager(MY_SPACE)).thenReturn(ADMIN);
        when(mapper.countAcl(DOC_IN_MY_SPACE, PrincipalType.ROLE.code(), 9L, "view")).thenReturn(0L);
        when(mapper.listAclUserIds(PrincipalType.ROLE.code(), 9L)).thenReturn(List.of());

        var result = service.grant(DOC_IN_MY_SPACE, PrincipalType.ROLE, 9L, "view", null, "req-9");

        assertThat(result.clearanceBlockedPrincipalCount()).isZero();
    }

    // ---- 6.4 审计与幂等 ----

    @Test
    @DisplayName("撤销事件的 after 状态记录被移除的主体与权限位")
    void revokeAuditsRemovedPrincipalAndPermission() {
        when(mapper.findOwningSpaceId(DOC_IN_MY_SPACE)).thenReturn(MY_SPACE);
        when(authorizer.requireSpaceManager(MY_SPACE)).thenReturn(ADMIN);
        when(mapper.revokeAcl(DOC_IN_MY_SPACE, PrincipalType.DEPT.code(), OUTSIDE_DEPT, "download"))
                .thenReturn(1);

        service.revoke(DOC_IN_MY_SPACE, PrincipalType.DEPT, OUTSIDE_DEPT, "download", null, "req-10");

        verify(mapper).revokeAcl(DOC_IN_MY_SPACE, PrincipalType.DEPT.code(), OUTSIDE_DEPT, "download");
        verify(audit).success(eq(AuditScope.DOC_ACL_REVOKE), eq("req-10"), eq(ADMIN),
                eq(DOC_IN_MY_SPACE), eq("revoke"), anyString(),
                ArgumentMatchers.contains("\"principalKey\":\"3:5\""));
        verify(audit).success(eq(AuditScope.DOC_ACL_REVOKE), eq("req-10"), eq(ADMIN),
                eq(DOC_IN_MY_SPACE), eq("revoke"), anyString(),
                ArgumentMatchers.contains("\"permission\":\"download\""));
    }

    @Test
    @DisplayName("撤销不存在的例外不报错但仍留审计")
    void revokingMissingExceptionIsIdempotentButAudited() {
        when(mapper.findOwningSpaceId(DOC_IN_MY_SPACE)).thenReturn(MY_SPACE);
        when(authorizer.requireSpaceManager(MY_SPACE)).thenReturn(ADMIN);
        when(mapper.revokeAcl(DOC_IN_MY_SPACE, PrincipalType.DEPT.code(), OUTSIDE_DEPT, "edit"))
                .thenReturn(0);

        service.revoke(DOC_IN_MY_SPACE, PrincipalType.DEPT, OUTSIDE_DEPT, "edit", null, "req-11");

        verify(audit).success(eq(AuditScope.DOC_ACL_REVOKE), eq("req-11"), eq(ADMIN),
                eq(DOC_IN_MY_SPACE), eq("revoke"), anyString(), eq("{}"));
    }

    @Test
    @DisplayName("同一 requestId 重复提交不再写第二次，重复授予落为单条记录")
    void repeatedRequestIsIdempotent() {
        when(mapper.findOwningSpaceId(DOC_IN_MY_SPACE)).thenReturn(MY_SPACE);
        when(authorizer.requireSpaceManager(MY_SPACE)).thenReturn(ADMIN);
        when(audit.existing(AuditScope.DOC_ACL_GRANT, "req-12"))
                .thenReturn(mock(IdentityAuditRecord.class));

        var result = service.grant(DOC_IN_MY_SPACE, PrincipalType.DEPT, OUTSIDE_DEPT, "view", null, "req-12");

        assertThat(result.alreadyGranted()).isTrue();
        verify(mapper, never()).grantAcl(anyLong(), anyShort(), anyLong(), anyString());
        verify(audit, never()).success(any(), anyString(), anyLong(), anyLong(),
                anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("重复授予同一条例外落为单条记录且不返回失败")
    void repeatedGrantOfSameExceptionIsIdempotent() {
        when(mapper.findOwningSpaceId(DOC_IN_MY_SPACE)).thenReturn(MY_SPACE);
        when(authorizer.requireSpaceManager(MY_SPACE)).thenReturn(ADMIN);
        when(mapper.countAcl(DOC_IN_MY_SPACE, PrincipalType.USER.code(), OUTSIDE_DEPT, "view"))
                .thenReturn(1L);

        var result = service.grant(DOC_IN_MY_SPACE, PrincipalType.USER, OUTSIDE_DEPT, "view", null, "req-13");

        assertThat(result.alreadyGranted()).isTrue();
        verify(mapper, never()).grantAcl(anyLong(), anyShort(), anyLong(), anyString());
        verify(audit).success(eq(AuditScope.DOC_ACL_GRANT), eq("req-13"), eq(ADMIN),
                eq(DOC_IN_MY_SPACE), eq("grant"), anyString(), anyString());
    }

    @Test
    @DisplayName("例外条目保留主体键与主体名，便于按主体检索")
    void listingReturnsPrincipalKeyAndName() {
        SpaceAclRecord record = new SpaceAclRecord();
        record.setId(3L);
        record.setSpaceId(DOC_IN_MY_SPACE);
        record.setPrincipalType(PrincipalType.DEPT.code());
        record.setPrincipalId(OUTSIDE_DEPT);
        record.setPermission("view");
        record.setPrincipalKey("3:5");
        record.setPrincipalName("市场部");
        when(mapper.findOwningSpaceId(DOC_IN_MY_SPACE)).thenReturn(MY_SPACE);
        when(authorizer.requireSpaceManager(MY_SPACE)).thenReturn(ADMIN);
        when(mapper.listAcl(DOC_IN_MY_SPACE)).thenReturn(List.of(record));

        var listed = service.list(DOC_IN_MY_SPACE);

        assertThat(listed).hasSize(1);
        assertThat(listed.get(0).principalKey()).isEqualTo("3:5");
        assertThat(listed.get(0).principalType()).isEqualTo(PrincipalType.DEPT);
        assertThat(listed.get(0).principalName()).isEqualTo("市场部");
    }
}
