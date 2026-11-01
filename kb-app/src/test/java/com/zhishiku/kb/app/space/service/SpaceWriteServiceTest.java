package com.zhishiku.kb.app.space.service;

import com.zhishiku.kb.app.auth.model.IdentityAuditRecord;
import com.zhishiku.kb.app.auth.service.AuditScope;
import com.zhishiku.kb.app.auth.service.IdentityAuditService;
import com.zhishiku.kb.app.auth.service.PermissionService;
import com.zhishiku.kb.app.space.mapper.SpaceMapper;
import com.zhishiku.kb.app.space.model.SpaceRecord;
import com.zhishiku.kb.common.security.IdentityConflictReason;
import com.zhishiku.kb.common.security.IdentityErrorCode;
import com.zhishiku.kb.common.security.IdentityException;
import com.zhishiku.kb.common.security.PrincipalType;
import com.zhishiku.kb.common.security.SpaceSummary;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyShort;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SpaceWriteServiceTest {

    private static final long ACTOR = 7L;
    private static final long SPACE = 3L;

    private SpaceMapper mapper;
    private SpaceAuthorizer authorizer;
    private IdentityAuditService audit;
    private SpaceWriteService service;

    @BeforeEach
    void setUp() {
        mapper = mock(SpaceMapper.class);
        authorizer = mock(SpaceAuthorizer.class);
        audit = mock(IdentityAuditService.class);
        service = new SpaceWriteService(mapper, authorizer, audit);
    }

    @Test
    @DisplayName("创建空间在同一事务内为创建者授予 manage")
    void createGrantsManageToCreator() {
        when(authorizer.requireCreate()).thenReturn(ACTOR);
        when(mapper.findByCode("ops")).thenReturn(null);
        when(mapper.nextSpaceId()).thenReturn(SPACE);
        when(mapper.insertSpace(any())).thenReturn(1);
        when(mapper.findRawById(SPACE)).thenReturn(space(SPACE, false, 0L));

        SpaceSummary created = service.create("ops", "运维", null, null, "req-1");

        assertThat(created.spaceId()).isEqualTo(SPACE);
        verify(mapper).createCategoryTree(SPACE);
        // 授权与创建必须落在同一事务：这里断言它确实被写入了
        verify(mapper).grantAcl(eq(SPACE), eq((short) PrincipalType.USER.code()),
                eq(ACTOR), eq(PermissionService.PERM_MANAGE));
        verify(audit).success(eq(AuditScope.SPACE_CREATE), eq("req-1"), eq(ACTOR), eq(SPACE),
                eq("create"), anyString(), anyString());
    }

    @Test
    @DisplayName("创建资格为全局管理员，普通用户被拒且不落库")
    void createRequiresGlobalManager() {
        when(authorizer.requireCreate())
                .thenThrow(IdentityException.forbidden("该操作仅限全局身份管理员"));

        assertThatThrownBy(() -> service.create("ops", "运维", null, null, "req-1"))
                .isInstanceOf(IdentityException.class);
        verify(mapper, never()).insertSpace(any());
        verify(mapper, never()).grantAcl(anyLong(), anyShort(), anyLong(), anyString());
    }

    @Test
    @DisplayName("编码重复返回 409，不产生空间也不产生授权")
    void duplicateCodeIsConflict() {
        when(authorizer.requireCreate()).thenReturn(ACTOR);
        when(mapper.findByCode("ops")).thenReturn(space(1L, false, 0L));

        assertThatThrownBy(() -> service.create("ops", "运维", null, null, "req-1"))
                .isInstanceOf(IdentityException.class)
                .hasMessageContaining("编码已存在")
                .satisfies(ex -> assertConflict(ex, IdentityConflictReason.IDENTIFIER_CONFLICT));
        verify(mapper, never()).nextSpaceId();
        verify(mapper, never()).insertSpace(any());
        verify(mapper, never()).grantAcl(anyLong(), anyShort(), anyLong(), anyString());
        verifyNoSuccessAudit();
    }

    @Test
    @DisplayName("插入失败保留通用冲突，不写授权或成功审计")
    void failedInsertRemainsGenericConflict() {
        when(authorizer.requireCreate()).thenReturn(ACTOR);
        when(mapper.nextSpaceId()).thenReturn(SPACE);

        assertThatThrownBy(() -> service.create("ops", "运维", null, null, "req-insert"))
                .isInstanceOf(IdentityException.class)
                .satisfies(ex -> assertConflict(ex, null));
        verify(mapper, never()).grantAcl(anyLong(), anyShort(), anyLong(), anyString());
        verifyNoSuccessAudit();
    }

    @Test
    @DisplayName("基于过期 version 的更新返回版本冲突，且不触达 UPDATE 语句")
    void staleVersionUpdateIsRejected() {
        when(authorizer.requireSpaceManager(SPACE)).thenReturn(ACTOR);
        when(audit.existing(AuditScope.SPACE_UPDATE, "req-2")).thenReturn(null);
        when(mapper.findByIdForUpdate(SPACE)).thenReturn(space(SPACE, false, 5L));

        assertThatThrownBy(() -> service.updateMetadata(SPACE, "新名", null, 4L, null, "req-2"))
                .isInstanceOf(IdentityException.class)
                .hasMessageContaining("其他操作更新");
        verify(mapper, never()).updateMetadata(anyLong(), anyString(), any(), anyLong());
    }

    @Test
    @DisplayName("归档只改 archived 位，不动名称与描述")
    void archiveTouchesOnlyArchivedFlag() {
        when(authorizer.requireSpaceManager(SPACE)).thenReturn(ACTOR);
        when(mapper.findByIdForUpdate(SPACE)).thenReturn(space(SPACE, false, 2L));
        when(mapper.setArchived(SPACE, true, 2L)).thenReturn(1);
        when(mapper.findRawById(SPACE)).thenReturn(space(SPACE, true, 3L));

        SpaceSummary archived = service.archive(SPACE, 2L, null, "req-3");

        assertThat(archived.archived()).isTrue();
        verify(mapper, never()).updateMetadata(anyLong(), anyString(), any(), anyLong());
        verify(audit).success(eq(AuditScope.SPACE_ARCHIVE), eq("req-3"), eq(ACTOR), eq(SPACE),
                eq("archive"), anyString(), anyString());
    }

    @Test
    @DisplayName("归档已是目标状态时不递增版本，但仍留一条审计")
    void archivingAnAlreadyArchivedSpaceIsANoOpButAudited() {
        when(authorizer.requireSpaceManager(SPACE)).thenReturn(ACTOR);
        when(mapper.findByIdForUpdate(SPACE)).thenReturn(space(SPACE, true, 2L));
        when(mapper.findRawById(SPACE)).thenReturn(space(SPACE, true, 2L));

        assertThat(service.archive(SPACE, 2L, null, "req-4").archived()).isTrue();
        verify(mapper, never()).setArchived(anyLong(), anyBoolean(), anyLong());
        verify(audit).success(eq(AuditScope.SPACE_ARCHIVE), eq("req-4"), eq(ACTOR), eq(SPACE),
                eq("archive"), anyString(), anyString());
    }

    @Test
    @DisplayName("删除仍有文档的空间返回冲突并提示改用归档")
    void deleteWithDocumentsIsConflict() {
        when(authorizer.requireSpaceManager(SPACE)).thenReturn(ACTOR);
        when(mapper.findByIdForUpdate(SPACE)).thenReturn(space(SPACE, false, 0L));
        when(mapper.countReferencedDocuments(SPACE)).thenReturn(4L);

        assertThatThrownBy(() -> service.delete(SPACE, null, "req-5"))
                .isInstanceOf(IdentityException.class)
                .hasMessageContaining("归档")
                .satisfies(ex -> assertConflict(ex, IdentityConflictReason.SPACE_NOT_EMPTY));
        verify(mapper, never()).deleteSpace(anyLong());
        verifyNoSuccessAudit();
    }

    @Test
    @DisplayName("删除空空间成功，授权记录由外键级联移除")
    void deleteEmptySpaceSucceeds() {
        when(authorizer.requireSpaceManager(SPACE)).thenReturn(ACTOR);
        when(mapper.findByIdForUpdate(SPACE)).thenReturn(space(SPACE, false, 0L));
        when(mapper.countReferencedDocuments(SPACE)).thenReturn(0L);
        when(mapper.deleteSpace(SPACE)).thenReturn(1);

        service.delete(SPACE, null, "req-6");

        verify(mapper).deleteSpace(SPACE);
        // 级联发生在数据库层（kb_space_acl.space_id ON DELETE CASCADE），
        // 因此这里不期望应用层再发一条 DELETE。
        verify(audit).success(eq(AuditScope.SPACE_DELETE), eq("req-6"), eq(ACTOR), eq(SPACE),
                eq("delete"), anyString(), anyString());
    }

    @Test
    @DisplayName("同一 requestId 重复提交只产生一次变更与一条审计")
    void repeatedRequestIsIdempotent() {
        when(authorizer.requireSpaceManager(SPACE)).thenReturn(ACTOR);
        when(audit.existing(AuditScope.SPACE_ARCHIVE, "req-7")).thenReturn(new IdentityAuditRecord(
                null, null, "SPACE_ARCHIVE", "req-7", ACTOR, "space", SPACE, "archive",
                "SUCCESS", "{}", "{}", null, null));
        when(mapper.findRawById(SPACE)).thenReturn(space(SPACE, true, 3L));

        SpaceSummary replay = service.archive(SPACE, 2L, null, "req-7");

        assertThat(replay.archived()).isTrue();
        verify(mapper, never()).setArchived(anyLong(), anyBoolean(), anyLong());
        verify(audit, never()).success(any(), anyString(), any(), any(), anyString(), anyString(), anyString());
    }

    private void verifyNoSuccessAudit() {
        verify(audit, never()).success(any(), anyString(), any(), any(), anyString(), anyString(), anyString());
    }

    @Test
    void treeInitializationFailurePropagatesBeforeGrantAndAudit() {
        when(authorizer.requireCreate()).thenReturn(ACTOR);
        when(mapper.nextSpaceId()).thenReturn(SPACE);
        when(mapper.insertSpace(any())).thenReturn(1);
        doThrow(new IllegalStateException("目录初始化失败")).when(mapper).createCategoryTree(SPACE);
        assertThatThrownBy(() -> service.create("ops", "运维", null, null, "tree-failure"))
                .isInstanceOf(IllegalStateException.class);
        verify(mapper, never()).grantAcl(anyLong(), anyShort(), anyLong(), anyString());
        verifyNoSuccessAudit();
    }

    @Test
    void archiveReauthorizesAfterTreeLockBeforeLockingSpace() {
        when(authorizer.requireSpaceManager(SPACE)).thenReturn(ACTOR)
                .thenThrow(IdentityException.forbidden("资格已撤销"));
        assertThatThrownBy(() -> service.archive(SPACE, 0, null, "revoked"))
                .isInstanceOf(IdentityException.class);
        var order = inOrder(authorizer, mapper);
        order.verify(authorizer).requireSpaceManager(SPACE);
        order.verify(mapper).lockCategoryTree(SPACE);
        order.verify(authorizer).requireSpaceManager(SPACE);
        verify(mapper, never()).findByIdForUpdate(SPACE);
        verifyNoSuccessAudit();
    }

    @Test
    void unarchiveUsesTreeThenSpaceOrder() {
        when(authorizer.requireSpaceManager(SPACE)).thenReturn(ACTOR);
        when(mapper.findByIdForUpdate(SPACE)).thenReturn(space(SPACE, true, 2));
        when(mapper.setArchived(SPACE, false, 2)).thenReturn(1);
        when(mapper.findRawById(SPACE)).thenReturn(space(SPACE, false, 3));
        assertThat(service.unarchive(SPACE, 2, null, "unarchive").archived()).isFalse();
        var order = inOrder(mapper);
        order.verify(mapper).lockCategoryTree(SPACE);
        order.verify(mapper).findByIdForUpdate(SPACE);
        order.verify(mapper).setArchived(SPACE, false, 2);
    }

    private static void assertConflict(Throwable ex, IdentityConflictReason reason) {
        IdentityException failure = (IdentityException) ex;
        assertThat(failure.httpStatus()).isEqualTo(409);
        assertThat(failure.errorCode()).isEqualTo(IdentityErrorCode.CONFLICT);
        if (reason == null) assertThat(failure.reason()).isEmpty();
        else assertThat(failure.reason()).contains(reason);
    }

    private static SpaceRecord space(Long id, boolean archived, long version) {
        SpaceRecord record = new SpaceRecord();
        record.setId(id);
        record.setCode("space-" + id);
        record.setName("空间" + id);
        record.setArchived(archived);
        record.setVersion(version);
        return record;
    }
}
