package com.zhishiku.kb.app.space.service;

import com.zhishiku.kb.app.auth.model.IdentityAuditRecord;
import com.zhishiku.kb.app.auth.service.AuditScope;
import com.zhishiku.kb.app.auth.service.IdentityAuditService;
import com.zhishiku.kb.app.document.metadata.mapper.MetadataMapper;
import com.zhishiku.kb.app.document.model.DocumentEntity;
import com.zhishiku.kb.app.document.service.DocumentManagementService;
import com.zhishiku.kb.app.space.mapper.SpaceMapper;
import com.zhishiku.kb.app.space.model.DocumentAdminDeleteResult;
import com.zhishiku.kb.app.space.model.SpaceRecord;
import com.zhishiku.kb.common.api.ApiException;
import com.zhishiku.kb.common.security.IdentityErrorCode;
import com.zhishiku.kb.common.security.IdentityException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 管理端删除的行为契约：资格按当前归属的 manage 判定、无权与不存在同形、幂等重放先核对资格、
 * 审计与回执只含定位字段。删除效果本身由 {@code DocumentManagementServiceTest} 覆盖，这里只
 * 确认共用主体被以正确的参数调用、且它抛出的 404/409 不留下成功审计。
 */
class DocumentAdminDeletionServiceTest {

    private static final long DOC = 10L;
    private static final long SPACE = 3L;
    private static final long ADMIN = 7L;
    private static final String REQUEST = "req-1";

    private MetadataMapper metadata;
    private SpaceMapper spaces;
    private SpaceAuthorizer authorizer;
    private IdentityAuditService audit;
    private DocumentManagementService documents;
    private DocumentAdminDeletionService service;
    private DocumentEntity document;

    @BeforeEach
    void setUp() {
        metadata = mock(MetadataMapper.class);
        spaces = mock(SpaceMapper.class);
        authorizer = mock(SpaceAuthorizer.class);
        audit = mock(IdentityAuditService.class);
        documents = mock(DocumentManagementService.class);
        service = new DocumentAdminDeletionService(metadata, spaces, authorizer, audit, documents);

        document = DocumentEntity.builder().id(DOC).spaceId(SPACE).status("published")
                .title("private-title").summary("private-summary").build();
        when(metadata.document(DOC)).thenAnswer(call -> document);
        when(authorizer.requireEnabledUser()).thenReturn(ADMIN);
        when(authorizer.requireSpaceManager(SPACE)).thenReturn(ADMIN);
        when(spaces.findRawById(SPACE)).thenReturn(space(false));
    }

    private DocumentAdminDeleteResult delete() {
        return service.delete(DOC, REQUEST);
    }

    private static SpaceRecord space(boolean archived) {
        SpaceRecord record = new SpaceRecord();
        record.setId(SPACE);
        record.setArchived(archived);
        record.setName("private-space");
        return record;
    }

    private static IdentityAuditRecord firstDeletion(String before) {
        return new IdentityAuditRecord(1L, UUID.randomUUID(), "DOCUMENT_ADMIN_DELETE", REQUEST, ADMIN, "document",
                DOC, "delete", "SUCCESS", before, "{}", null, Instant.EPOCH);
    }

    private void verifyNothingDeletedOrAudited() {
        verify(documents, never()).deleteAuthorized(anyLong(), anyLong(), any());
        verify(audit, never()).appendSuccess(any(), anyString(), anyLong(), anyLong(), anyString(), anyString(), anyString());
        verify(audit, never()).success(any(), anyString(), any(), any(), anyString(), anyString(), anyString());
    }

    private static void assertHidden(Throwable error) {
        assertThat(error).isInstanceOfSatisfying(IdentityException.class, e -> {
            assertThat(e.httpStatus()).isEqualTo(404);
            assertThat(e.errorCode()).isEqualTo(IdentityErrorCode.NOT_FOUND);
            assertThat(e.getMessage()).isEqualTo(DocumentAdminDeletionService.NOT_FOUND_MESSAGE);
        });
    }

    @Test
    @DisplayName("空间管理员没有 edit 也能删：资格按实际归属判 manage，共用主体带空间前置条件")
    void spaceManagerWithoutEditDeletes() {
        DocumentAdminDeleteResult result = delete();

        assertThat(result.documentId()).isEqualTo(DOC);
        assertThat(result.spaceId()).isEqualTo(SPACE);
        assertThat(result.replayed()).isFalse();
        verify(authorizer).requireSpaceManager(SPACE);
        verify(documents).deleteAuthorized(DOC, ADMIN, SPACE);
        var order = inOrder(authorizer, metadata, documents, audit);
        order.verify(authorizer).requireEnabledUser();
        order.verify(audit).existing(AuditScope.DOCUMENT_ADMIN_DELETE, REQUEST);
        order.verify(metadata).document(DOC);
        order.verify(authorizer).requireSpaceManager(SPACE);
        order.verify(documents).deleteAuthorized(DOC, ADMIN, SPACE);
        order.verify(audit).appendSuccess(eq(AuditScope.DOCUMENT_ADMIN_DELETE), eq(REQUEST), eq(ADMIN), eq(DOC),
                eq("delete"), anyString(), eq("{}"));
    }

    @Test
    @DisplayName("只有 edit 或例外、没有 manage：与不存在同形的 404，不删不审计")
    void editOnlyIsHiddenAsNotFound() {
        when(authorizer.requireSpaceManager(SPACE)).thenThrow(IdentityException.forbidden("无权管理该空间"));

        assertThatThrownBy(this::delete).satisfies(DocumentAdminDeletionServiceTest::assertHidden);

        verifyNothingDeletedOrAudited();
        verify(spaces, never()).findRawById(anyLong());
    }

    @Test
    @DisplayName("所属空间已归档：仍可删，审计快照记录归档状态")
    void archivedSpaceIsStillDeletable() {
        when(spaces.findRawById(SPACE)).thenReturn(space(true));

        DocumentAdminDeleteResult result = delete();

        assertThat(result.replayed()).isFalse();
        verify(documents).deleteAuthorized(DOC, ADMIN, SPACE);
        verify(audit).appendSuccess(eq(AuditScope.DOCUMENT_ADMIN_DELETE), eq(REQUEST), eq(ADMIN), eq(DOC),
                eq("delete"), contains("\"spaceArchived\":true"), eq("{}"));
    }

    @Test
    @DisplayName("停用用户或主部门停用：在任何读取之前拒绝，不写入")
    void disabledActorIsRejectedBeforeAnyRead() {
        when(authorizer.requireEnabledUser()).thenThrow(IdentityException.disabled("用户已停用"));

        assertThatThrownBy(this::delete).isInstanceOfSatisfying(IdentityException.class,
                e -> assertThat(e.errorCode()).isEqualTo(IdentityErrorCode.DISABLED));

        verifyNoInteractions(metadata, spaces, documents);
        verify(audit, never()).existing(any(), anyString());
        verifyNothingDeletedOrAudited();
    }

    @Test
    @DisplayName("停用主体在 manage 判定阶段抛出：原样传播，不被改写成 404")
    void disabledFromManagerCheckIsNotRewritten() {
        when(authorizer.requireSpaceManager(SPACE)).thenThrow(IdentityException.disabled("主部门已停用"));

        assertThatThrownBy(this::delete).isInstanceOfSatisfying(IdentityException.class,
                e -> assertThat(e.errorCode()).isEqualTo(IdentityErrorCode.DISABLED));

        verifyNothingDeletedOrAudited();
    }

    @Test
    @DisplayName("不存在与无权管理返回同一条 404，响应不泄露存在性")
    void missingAndUnmanagedShareTheSameNotFound() {
        when(metadata.document(DOC)).thenReturn(null);
        assertThatThrownBy(this::delete).satisfies(DocumentAdminDeletionServiceTest::assertHidden);
        verify(authorizer, never()).requireSpaceManager(anyLong());

        when(metadata.document(DOC)).thenAnswer(call -> document);
        when(authorizer.requireSpaceManager(SPACE)).thenThrow(IdentityException.forbidden("无权管理该空间"));
        assertThatThrownBy(this::delete).satisfies(DocumentAdminDeletionServiceTest::assertHidden);

        verifyNothingDeletedOrAudited();
    }

    @Test
    @DisplayName("已被用户端删除：404，不产生审计事件")
    void alreadyDeletedByUserSideIsNotFoundWithoutAudit() {
        document.setStatus("deleted");

        assertThatThrownBy(this::delete).satisfies(DocumentAdminDeletionServiceTest::assertHidden);

        verify(authorizer, never()).requireSpaceManager(anyLong());
        verifyNothingDeletedOrAudited();
    }

    @Test
    @DisplayName("预读后被用户端删除，共用主体报 404：改写成与本地判定同形的 404，不写审计")
    void sharedBodyNotFoundPropagatesWithoutAudit() {
        doThrow(ApiException.notFound("文档不存在或已经删除")).when(documents).deleteAuthorized(DOC, ADMIN, SPACE);

        assertThatThrownBy(this::delete).satisfies(DocumentAdminDeletionServiceTest::assertHidden);

        verify(audit, never()).appendSuccess(any(), anyString(), anyLong(), anyLong(), anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("判定后被搬迁，共用主体报 409：改写成身份领域 CONFLICT 以便投影 reasonCode，不写审计")
    void sharedBodyConflictPropagatesWithoutAudit() {
        doThrow(ApiException.conflict("文档归属已变化")).when(documents).deleteAuthorized(DOC, ADMIN, SPACE);

        assertThatThrownBy(this::delete).isInstanceOfSatisfying(IdentityException.class, e -> {
            assertThat(e.httpStatus()).isEqualTo(409);
            assertThat(e.errorCode()).isEqualTo(IdentityErrorCode.CONFLICT);
            assertThat(e.reason()).isEmpty();
        });

        verify(audit, never()).appendSuccess(any(), anyString(), anyLong(), anyLong(), anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("相同请求标识重放：先对首次归属核对 manage，返回首次回执，不再读文档、不再写入")
    void replayChecksManageOnSnapshotSpaceAndWritesNothing() {
        when(audit.existing(AuditScope.DOCUMENT_ADMIN_DELETE, REQUEST))
                .thenReturn(firstDeletion("{\"documentId\":10,\"spaceId\":3,\"spaceArchived\":false}"));

        DocumentAdminDeleteResult result = delete();

        assertThat(result.replayed()).isTrue();
        assertThat(result.documentId()).isEqualTo(DOC);
        assertThat(result.spaceId()).isEqualTo(SPACE);
        verify(authorizer).requireSpaceManager(SPACE);
        verify(metadata, never()).document(anyLong());
        verifyNothingDeletedOrAudited();
    }

    @Test
    @DisplayName("重放时已失去首次归属的 manage：404，不返回回执")
    void replayWithoutManageIsHidden() {
        when(audit.existing(AuditScope.DOCUMENT_ADMIN_DELETE, REQUEST))
                .thenReturn(firstDeletion("{\"documentId\":10,\"spaceId\":3,\"spaceArchived\":true}"));
        when(authorizer.requireSpaceManager(SPACE)).thenThrow(IdentityException.forbidden("无权管理该空间"));

        assertThatThrownBy(this::delete).satisfies(DocumentAdminDeletionServiceTest::assertHidden);

        verifyNothingDeletedOrAudited();
    }

    @Test
    @DisplayName("请求标识被用于其他文档：409，不按快照空间放行")
    void replayForAnotherDocumentIsConflict() {
        when(audit.existing(AuditScope.DOCUMENT_ADMIN_DELETE, REQUEST))
                .thenReturn(firstDeletion("{\"documentId\":99,\"spaceId\":3,\"spaceArchived\":false}"));

        assertThatThrownBy(this::delete).isInstanceOfSatisfying(IdentityException.class,
                e -> assertThat(e.httpStatus()).isEqualTo(409));

        verify(authorizer, never()).requireSpaceManager(anyLong());
        verifyNothingDeletedOrAudited();
    }

    @Test
    @DisplayName("回执与审计只含定位字段：标题、摘要与空间名称都不出现")
    void receiptAndAuditCarryOnlyLocatingFields() {
        DocumentAdminDeleteResult result = delete();

        assertThat(DocumentAdminDeleteResult.class.getRecordComponents())
                .extracting(java.lang.reflect.RecordComponent::getName)
                .containsExactly("documentId", "spaceId", "replayed");
        assertThat(result.toString()).doesNotContain("private-");

        ArgumentCaptor<String> before = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> after = ArgumentCaptor.forClass(String.class);
        verify(audit).appendSuccess(eq(AuditScope.DOCUMENT_ADMIN_DELETE), eq(REQUEST), eq(ADMIN), eq(DOC),
                eq("delete"), before.capture(), after.capture());
        assertThat(before.getValue())
                .contains("\"documentId\":10", "\"spaceId\":3", "\"spaceArchived\":false")
                .doesNotContain("private-", "title", "summary", "name", "objectKey", "fileName");
        assertThat(after.getValue()).isEqualTo("{}");
    }

    @Test
    @DisplayName("缺少请求标识：400，一次库都不碰")
    void missingRequestIdIsBadRequest() {
        for (String missing : new String[]{null, "", "   "}) {
            assertThatThrownBy(() -> service.delete(DOC, missing)).isInstanceOfSatisfying(IdentityException.class,
                    e -> assertThat(e.httpStatus()).isEqualTo(400));
        }
        verifyNoInteractions(metadata, spaces, authorizer, audit, documents);
    }

    @Test
    @DisplayName("非正整数文档 ID：400，一次库都不碰")
    void nonPositiveDocumentIdIsBadRequest() {
        assertThatThrownBy(() -> service.delete(0, REQUEST)).isInstanceOfSatisfying(IdentityException.class,
                e -> assertThat(e.httpStatus()).isEqualTo(400));
        verifyNoInteractions(metadata, spaces, authorizer, audit, documents);
    }
}
