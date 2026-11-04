package com.zhishiku.kb.app.space.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhishiku.kb.app.auth.mapper.PermissionMapper;
import com.zhishiku.kb.app.auth.model.IdentityAuditRecord;
import com.zhishiku.kb.app.auth.service.AuditScope;
import com.zhishiku.kb.app.auth.service.IdentityAuditService;
import com.zhishiku.kb.app.auth.service.IdentityManagementAuthorizer;
import com.zhishiku.kb.app.auth.service.IdentityService;
import com.zhishiku.kb.app.auth.service.PermissionService;
import com.zhishiku.kb.app.space.mapper.SpaceMapper;
import com.zhishiku.kb.app.space.model.SpaceAclRecord;
import com.zhishiku.kb.app.space.model.SpaceAclBatchGrantResult;
import com.zhishiku.kb.app.space.model.SpaceAclPrincipal;
import com.zhishiku.kb.common.security.IdentityException;
import com.zhishiku.kb.common.security.PrincipalType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.stream.LongStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyShort;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
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

    @Test
    void invalidBatchShapeIsRejectedBeforeAuthorizationOrWrites() {
        var principal = new SpaceAclPrincipal(PrincipalType.USER, 2L);
        assertInvalidBatch(null, List.of("view"), "50");
        assertInvalidBatch(List.of(), List.of("view"), "50");
        assertInvalidBatch(LongStream.rangeClosed(1, 51)
                .mapToObj(id -> new SpaceAclPrincipal(PrincipalType.USER, id)).toList(), List.of("view"), "50");
        assertInvalidBatch(List.of(principal, principal), List.of("view"), "重复");
        assertInvalidBatch(Arrays.asList(principal, null), List.of("view"), "主体");
        assertInvalidBatch(List.of(new SpaceAclPrincipal(null, 2)), List.of("view"), "主体类型");
        assertInvalidBatch(List.of(new SpaceAclPrincipal(PrincipalType.USER, 0)), List.of("view"), "正整数");
        assertInvalidBatch(List.of(principal), null, "6");
        assertInvalidBatch(List.of(principal), List.of(), "6");
        assertInvalidBatch(List.of(principal), List.of("view", "download", "edit", "review", "comment", "manage", "share"), "6");
        assertInvalidBatch(List.of(principal), List.of("view", "view"), "重复");
        assertInvalidBatch(List.of(principal), List.of("view", " view "), "重复");
        assertInvalidBatch(List.of(principal), Arrays.asList("view", null), "不能为空");
        assertInvalidBatch(List.of(principal), List.of("view", " "), "不能为空");
        for (String unsupported : List.of("share", "approve", "Comment")) {
            assertInvalidBatch(List.of(principal), List.of("view", unsupported), "不被支持");
        }
        verifyNoInteractions(mapper, authorizer, audit);
    }

    private void assertInvalidBatch(List<SpaceAclPrincipal> principals, List<String> permissions, String message) {
        assertThatThrownBy(() -> service.grantBatch(SPACE, principals, permissions, "batch"))
                .isInstanceOfSatisfying(IdentityException.class, error -> assertThat(error.httpStatus()).isEqualTo(400))
                .hasMessageContaining(message);
        verify(mapper, never()).grantAcl(anyLong(), anyShort(), anyLong(), anyString());
    }

    @Test
    void batchWithManageIsRejectedBeforeRoleLookupAndAnyWrite() {
        when(authorizer.requireGrantManage()).thenThrow(IdentityException.forbidden("缺少 space:grant-manage"));
        assertThatThrownBy(() -> service.grantBatch(SPACE,
                List.of(new SpaceAclPrincipal(PrincipalType.USER, 2), new SpaceAclPrincipal(PrincipalType.ROLE, 99)),
                List.of("view", "manage"), "batch"))
                .isInstanceOfSatisfying(IdentityException.class, error -> assertThat(error.httpStatus()).isEqualTo(403));
        verify(authorizer).requireGrantManage();
        verify(authorizer, never()).requireSpaceManager(anyLong());
        verify(authorizer, never()).requireRoleReference(anyLong());
        verifyNoInteractions(mapper, audit);
    }

    @Test
    void batchWithoutManageUsesSpaceQualificationAndValidatesEveryRoleBeforeWriting() {
        when(authorizer.requireSpaceManager(SPACE)).thenReturn(ADMIN);
        when(mapper.grantAcl(anyLong(), anyShort(), anyLong(), anyString())).thenReturn(1);
        var result = service.grantBatch(SPACE,
                List.of(new SpaceAclPrincipal(PrincipalType.ROLE, 2), new SpaceAclPrincipal(PrincipalType.ROLE, 3)),
                List.of("view", "download"), "batch");
        assertThat(result.grantedCount()).isEqualTo(4);
        var order = inOrder(authorizer, mapper);
        order.verify(authorizer).requireSpaceManager(SPACE);
        order.verify(authorizer).requireRoleReference(2);
        order.verify(authorizer).requireRoleReference(3);
        order.verify(mapper).countAcl(SPACE, PrincipalType.ROLE.code(), 2, "view");
        verify(authorizer, never()).requireGrantManage();
    }

    @Test
    void missingRoleRejectsWholeBatchBeforeEarlierValidPrincipalIsWritten() {
        when(authorizer.requireSpaceManager(SPACE)).thenReturn(ADMIN);
        doThrow(IdentityException.badRequest("角色不存在")).when(authorizer).requireRoleReference(99);
        assertThatThrownBy(() -> service.grantBatch(SPACE,
                List.of(new SpaceAclPrincipal(PrincipalType.USER, 2), new SpaceAclPrincipal(PrincipalType.ROLE, 99)),
                List.of("view", "download"), "batch"))
                .isInstanceOfSatisfying(IdentityException.class, error -> assertThat(error.httpStatus()).isEqualTo(400));
        var order = inOrder(authorizer);
        order.verify(authorizer).requireSpaceManager(SPACE);
        order.verify(authorizer).requireRoleReference(99);
        verifyNoInteractions(mapper);
        verify(audit).existing(AuditScope.SPACE_ACL_BATCH_GRANT, "batch");
        verifyNoMoreInteractions(audit);
    }

    @Test
    void disabledUserOrPrimaryDepartmentRejectsBothBatchQualificationPaths() {
        var identity = mock(IdentityService.class);
        var identityAuthorizer = mock(IdentityManagementAuthorizer.class);
        when(identityAuthorizer.requireLogin()).thenReturn(ADMIN);
        var realAuthorizer = new SpaceAuthorizer(identityAuthorizer, identity,
                new PermissionService(mock(PermissionMapper.class), identity));
        var subject = new SpaceAclService(mapper, realAuthorizer, audit);
        for (String reason : List.of("用户已停用", "用户主部门已停用")) {
            doThrow(IdentityException.disabled(reason)).when(identity).resolvePrincipal(ADMIN);
            for (var permissions : List.of(List.of("view", "download"), List.of("view", "manage"))) {
                assertThatThrownBy(() -> subject.grantBatch(SPACE,
                        List.of(new SpaceAclPrincipal(PrincipalType.USER, 2)), permissions, "batch"))
                        .isInstanceOfSatisfying(IdentityException.class, error -> assertThat(error.httpStatus()).isEqualTo(403))
                        .hasMessage(reason);
            }
        }
        verifyNoInteractions(mapper, audit);
    }

    @Test
    void batchReceiptAndAuditCountNewAndExistingEntriesAndBlockedPrincipals() throws Exception {
        when(authorizer.requireSpaceManager(SPACE)).thenReturn(ADMIN);
        when(mapper.grantAcl(anyLong(), anyShort(), anyLong(), anyString())).thenReturn(1);
        when(mapper.countAcl(SPACE, PrincipalType.DEPT.code(), 2, "view")).thenReturn(1L);
        when(mapper.countAcl(eq(SPACE), eq(PrincipalType.USER.code()), eq(4L), anyString())).thenReturn(1L);
        when(mapper.userIdsForPrincipal(PrincipalType.DEPT.code(), 2)).thenReturn(List.of(20L));
        when(mapper.userIdsForPrincipal(PrincipalType.DEPT.code(), 3)).thenReturn(List.of(30L));
        when(mapper.findUserClearance(20)).thenReturn((short) 1);
        when(mapper.findUserClearance(30)).thenReturn((short) 1);
        when(mapper.countDocumentsAboveClearance(SPACE, (short) 1)).thenReturn(2L);
        var result = service.grantBatch(SPACE, List.of(new SpaceAclPrincipal(PrincipalType.DEPT, 2),
                        new SpaceAclPrincipal(PrincipalType.DEPT, 3), new SpaceAclPrincipal(PrincipalType.USER, 4)),
                List.of("view", "download"), "batch");
        assertThat(result.spaceId()).isEqualTo(SPACE);
        assertThat(result.replayed()).isFalse();
        assertThat(result.items()).containsExactly(
                new SpaceAclBatchGrantResult.Item(PrincipalType.DEPT, 2, "view", true),
                new SpaceAclBatchGrantResult.Item(PrincipalType.DEPT, 2, "download", false),
                new SpaceAclBatchGrantResult.Item(PrincipalType.DEPT, 3, "view", false),
                new SpaceAclBatchGrantResult.Item(PrincipalType.DEPT, 3, "download", false),
                new SpaceAclBatchGrantResult.Item(PrincipalType.USER, 4, "view", true),
                new SpaceAclBatchGrantResult.Item(PrincipalType.USER, 4, "download", true));
        assertThat(result.grantedCount()).isEqualTo(3);
        assertThat(result.alreadyGrantedCount()).isEqualTo(3);
        assertThat(result.clearanceBlockedPrincipalCount()).isEqualTo(2);
        verify(mapper, times(3)).grantAcl(anyLong(), anyShort(), anyLong(), anyString());
        verify(mapper).userIdsForPrincipal(PrincipalType.DEPT.code(), 2);
        verify(mapper).userIdsForPrincipal(PrincipalType.DEPT.code(), 3);
        verify(mapper, never()).userIdsForPrincipal(PrincipalType.USER.code(), 4);
        var after = ArgumentCaptor.forClass(String.class);
        verify(audit).success(eq(AuditScope.SPACE_ACL_BATCH_GRANT), eq("batch"), eq(ADMIN), eq(SPACE),
                eq("batch-grant"), eq("{}"), after.capture());
        var json = new ObjectMapper();
        var snapshot = json.readTree(after.getValue());
        assertThat(snapshot.size()).isEqualTo(5);
        assertThat(snapshot.get("items")).isEqualTo(json.readTree(json.writeValueAsString(result.items())));
        assertThat(snapshot.get("spaceId").longValue()).isEqualTo(SPACE);
        assertThat(snapshot.get("grantedCount").longValue()).isEqualTo(3);
        assertThat(snapshot.get("alreadyGrantedCount").longValue()).isEqualTo(3);
        assertThat(snapshot.get("clearanceBlockedPrincipalCount").longValue()).isEqualTo(2);
        verify(authorizer, never()).requireRoleReference(anyLong());
    }

    @Test
    void batchAtMaximumSizeAcceptsAllSixBitsAndOnlyReadsClearance() {
        when(authorizer.requireGrantManage()).thenReturn(ADMIN);
        when(mapper.grantAcl(anyLong(), anyShort(), anyLong(), anyString())).thenReturn(1);
        when(mapper.userIdsForPrincipal(anyShort(), anyLong())).thenReturn(List.of(77L));
        when(mapper.findUserClearance(77)).thenReturn((short) 2);
        var principals = LongStream.rangeClosed(1, 50)
                .mapToObj(id -> new SpaceAclPrincipal(PrincipalType.USER, id)).toList();
        var result = service.grantBatch(SPACE, principals,
                List.of("view", "download", "edit", "review", "comment", "manage"), "max-batch");
        assertThat(result.items()).hasSize(300);
        assertThat(result.grantedCount()).isEqualTo(300);
        verify(authorizer).requireGrantManage();
        verify(authorizer, never()).requireSpaceManager(anyLong());
        verify(mapper, times(300)).countAcl(anyLong(), anyShort(), anyLong(), anyString());
        verify(mapper, times(300)).grantAcl(anyLong(), anyShort(), anyLong(), anyString());
        verify(mapper, times(50)).userIdsForPrincipal(anyShort(), anyLong());
        verify(mapper, times(50)).findUserClearance(77);
        verify(mapper, times(50)).countDocumentsAboveClearance(SPACE, (short) 2);
        verifyNoMoreInteractions(mapper);
    }

    @Test
    void batchReplayRejectsOtherOrMissingActorAndSpaceBeforeReadingSnapshot() {
        when(authorizer.requireSpaceManager(SPACE)).thenReturn(ADMIN);
        Long[][] bindings = {
                {ADMIN + 1, SPACE}, {ADMIN, SPACE + 1}, {ADMIN + 1, SPACE + 1},
                {null, SPACE}, {ADMIN, null}
        };
        for (Long[] binding : bindings) {
            when(audit.existing(AuditScope.SPACE_ACL_BATCH_GRANT, "bound-batch")).thenReturn(
                    new IdentityAuditRecord(1L, UUID.randomUUID(), "SPACE_ACL_BATCH_GRANT", "bound-batch",
                            binding[0], "space-acl", binding[1], "batch-grant", "SUCCESS", "{}",
                            "unreadable snapshot", null, Instant.EPOCH));
            assertThatThrownBy(() -> service.grantBatch(SPACE,
                    List.of(new SpaceAclPrincipal(PrincipalType.ROLE, 99)), List.of("view"), "bound-batch"))
                    .isInstanceOfSatisfying(IdentityException.class, error -> assertThat(error.httpStatus()).isEqualTo(409))
                    .hasMessage("请求标识已用于不同操作");
        }
        verify(authorizer, times(bindings.length)).requireSpaceManager(SPACE);
        verifyNoMoreInteractions(authorizer);
        verify(audit, times(bindings.length)).existing(AuditScope.SPACE_ACL_BATCH_GRANT, "bound-batch");
        verifyNoMoreInteractions(audit);
        verifyNoInteractions(mapper);
    }

    @Test
    void batchReplayRejectsSnapshotForAnotherSpaceEvenWhenAuditBindingMatches() {
        when(authorizer.requireSpaceManager(SPACE)).thenReturn(ADMIN);
        String snapshot = """
                {"spaceId":2,"items":[{"principalType":"USER","principalId":999,"permission":"manage","alreadyGranted":false}],
                 "grantedCount":1,"alreadyGrantedCount":0,"clearanceBlockedPrincipalCount":0}
                """;
        when(audit.existing(AuditScope.SPACE_ACL_BATCH_GRANT, "bound-batch")).thenReturn(
                new IdentityAuditRecord(1L, UUID.randomUUID(), "SPACE_ACL_BATCH_GRANT", "bound-batch",
                        ADMIN, "space-acl", SPACE, "batch-grant", "SUCCESS", "{}", snapshot, null, Instant.EPOCH));
        assertThatThrownBy(() -> service.grantBatch(SPACE,
                List.of(new SpaceAclPrincipal(PrincipalType.USER, 2)), List.of("view"), "bound-batch"))
                .isInstanceOfSatisfying(IdentityException.class, error -> assertThat(error.httpStatus()).isEqualTo(409))
                .hasMessage("请求标识已用于不同操作");
        verify(audit).existing(AuditScope.SPACE_ACL_BATCH_GRANT, "bound-batch");
        verifyNoMoreInteractions(audit);
        verifyNoInteractions(mapper);
    }

    @Test
    void replayRebuildsFirstReceiptWithoutReadingOrWritingAclOrClearance() {
        when(authorizer.requireSpaceManager(SPACE)).thenReturn(ADMIN);
        when(mapper.grantAcl(anyLong(), anyShort(), anyLong(), anyString())).thenReturn(1);
        when(mapper.countAcl(SPACE, PrincipalType.USER.code(), Long.MAX_VALUE, "view")).thenReturn(1L);
        when(mapper.userIdsForPrincipal(PrincipalType.USER.code(), Long.MAX_VALUE)).thenReturn(List.of(2L));
        when(mapper.findUserClearance(2)).thenReturn((short) 1);
        when(mapper.countDocumentsAboveClearance(SPACE, (short) 1)).thenReturn(2L);
        var principals = List.of(new SpaceAclPrincipal(PrincipalType.USER, Long.MAX_VALUE));
        var permissions = List.of("view", "download");
        var first = service.grantBatch(SPACE, principals, permissions, "replay-batch");
        var after = ArgumentCaptor.forClass(String.class);
        verify(audit).success(eq(AuditScope.SPACE_ACL_BATCH_GRANT), eq("replay-batch"), eq(ADMIN), eq(SPACE),
                eq("batch-grant"), eq("{}"), after.capture());
        when(audit.existing(AuditScope.SPACE_ACL_BATCH_GRANT, "replay-batch")).thenReturn(new IdentityAuditRecord(
                1L, UUID.randomUUID(), "SPACE_ACL_BATCH_GRANT", "replay-batch", ADMIN, "space-acl", SPACE,
                "batch-grant", "SUCCESS", "{}", after.getValue(), null, Instant.EPOCH));
        clearInvocations(mapper, audit);
        var replay = service.grantBatch(SPACE, principals, permissions, "replay-batch");
        assertThat(replay).isEqualTo(new SpaceAclBatchGrantResult(first.spaceId(), first.items(), first.grantedCount(),
                first.alreadyGrantedCount(), first.clearanceBlockedPrincipalCount(), true));
        verifyNoInteractions(mapper);
        verify(audit).existing(AuditScope.SPACE_ACL_BATCH_GRANT, "replay-batch");
        verifyNoMoreInteractions(audit);
    }
}
