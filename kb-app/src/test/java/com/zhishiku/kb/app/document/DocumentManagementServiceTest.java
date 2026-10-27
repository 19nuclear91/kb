package com.zhishiku.kb.app.document;

import com.zhishiku.kb.app.auth.service.PermissionService;
import com.zhishiku.kb.app.document.mapper.DocumentMapper;
import com.zhishiku.kb.app.document.model.DocumentProgress;
import com.zhishiku.kb.app.document.model.DocumentFile;
import com.zhishiku.kb.app.document.model.DocumentSummary;
import com.zhishiku.kb.app.document.model.DocumentVersionView;
import com.zhishiku.kb.app.document.service.DocumentManagementService;
import com.zhishiku.kb.app.document.service.DocumentWriteService;
import com.zhishiku.kb.app.document.service.ReviewCancellationService;
import com.zhishiku.kb.app.embedding.service.EmbeddingControlService;
import com.zhishiku.kb.app.storage.ObjectKeys;
import com.zhishiku.kb.app.storage.ObjectStorage;
import com.zhishiku.kb.app.storage.StorageProperties;
import com.zhishiku.kb.common.api.ApiException;
import com.zhishiku.kb.common.embedding.EmbeddingControl;
import com.zhishiku.kb.common.security.PermissionScope;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockMultipartFile;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyShort;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class DocumentManagementServiceTest {

    @Test
    void hiddenFirstDraftDoesNotExposeMetadataCountsOrVersions() {
        when(permissions.resolve(7L, "view"))
                .thenReturn(PermissionScope.of(7, (short) 2, List.of(3L), List.of("1:7")));
        assertThat(service.list(7, null, 1, 20).getTotal()).isZero();
        assertThatThrownBy(() -> service.detail(7, 10)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> service.versions(7, 10)).isInstanceOf(ApiException.class);
        verify(mapper, never()).listVersions(anyLong(), any(), any(), anyShort(), any());
        verifyNoInteractions(storage, control);
    }

    @Test
    void defaultFileRetainsPublishedVersionAndExplicitHiddenCandidateIsMissing() {
        var scope = PermissionScope.of(7, (short) 2, List.of(3L), List.of("1:7"));
        when(permissions.resolve(7L, "view")).thenReturn(scope);
        when(permissions.resolve(7L, "download")).thenReturn(PermissionScope.denyAll(7));
        var published = new DocumentFile(10L, 20L, 1,
                "documents/v1.txt", "v1.txt", 1L, "text/plain");
        when(mapper.findFile(eq(10L), isNull(), any(), any(), eq((short) 2), eq("view"))).thenReturn(published);
        assertThat(service.file(7, 10, null, "view")).isSameAs(published);
        assertThatThrownBy(() -> service.file(7, 10, 21L, "view")).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> service.file(7, 10, 20L, "download")).isInstanceOf(ApiException.class);
        verifyNoInteractions(storage);
    }

    @Test
    void versionUploadAlsoChecksContentBeforeWritingStorage() {
        when(permissions.resolve(7L, PermissionService.PERM_EDIT))
                .thenReturn(PermissionScope.of(7L, (short) 2, List.of(3L), List.of("1:7")));
        when(mapper.findSummary(eq(10L), any(), any(), eq((short) 2), eq(PermissionService.PERM_EDIT)))
                .thenReturn(new DocumentSummary(10L, 3L, "说明", (short) 1, "published", 7L,
                        20L, 1, "a.txt", 1L, "text/plain", "indexed", null, null, null));
        var file = new MockMultipartFile("file", "payload.html", "text/plain",
                "<script>alert(1)</script>".getBytes());
        assertThatThrownBy(() -> service.uploadVersion(7L, 10L, file)).isInstanceOf(ApiException.class);
        verifyNoInteractions(storage, writer);
    }

    private final DocumentMapper mapper = mock(DocumentMapper.class);
    private final PermissionService permissions = mock(PermissionService.class);
    private final ObjectStorage storage = mock(ObjectStorage.class);
    private final DocumentWriteService writer = mock(DocumentWriteService.class);
    private final ReviewCancellationService cancellation = mock(ReviewCancellationService.class);
    private final EmbeddingControlService control =
            mock(EmbeddingControlService.class);
    private final DocumentManagementService service = new DocumentManagementService(
            mapper, permissions, storage,
            new StorageProperties("http://localhost:9000", "us-east-1", "a", "b", "kb", true, 1000),
            writer, control, cancellation);

    @Test
    void downloadDoesNotTouchStorageWhenDocumentIsNotVisible() {
        when(permissions.resolve(7L, PermissionService.PERM_DOWNLOAD))
                .thenReturn(PermissionScope.denyAll(7L));

        assertThatThrownBy(() -> service.file(7L, 10L, null, PermissionService.PERM_DOWNLOAD))
                .isInstanceOf(ApiException.class);

        verifyNoInteractions(storage);
        verify(mapper).findFile(eq(10L), isNull(), any(), any(), eq((short) 0),
                eq(PermissionService.PERM_DOWNLOAD));
    }

    @Test
    void retryResetsExhaustedTaskAndVersion() {
        when(control.lockShared()).thenReturn(new EmbeddingControl(
                "UNINITIALIZED", null, null, null, null, 0, null));
        PermissionScope edit = PermissionScope.of(7L, (short) 2, List.of(3L), List.of("1:7"));
        when(permissions.resolve(7L, PermissionService.PERM_EDIT)).thenReturn(edit);
        when(permissions.resolve(7L, PermissionService.PERM_VIEW)).thenReturn(edit);
        when(mapper.findVersionForEdit(eq(10L), isNull(), any(), any(), eq((short) 2),
                eq(PermissionService.PERM_EDIT)))
                .thenReturn(new DocumentVersionView(20L, 10L, 1, true, "a.txt", 1L,
                        "hash", "text/plain", "failed", "bad", 30L, "failed", 3, 3,
                        "bad", null, null));
        when(mapper.resetTaskForRetry(20L)).thenReturn(1);
        when(mapper.findProgress(eq(10L), eq(20L), any(), any(), eq((short) 2),
                eq(PermissionService.PERM_VIEW)))
                .thenReturn(new DocumentProgress(10L, 20L, 1, "pending", null,
                        30L, "pending", 0, 3, null));

        DocumentProgress result = service.retry(7L, 10L, null);

        assertThat(result.getIndexStatus()).isEqualTo("pending");
        assertThat(result.getWaitingReason()).isEqualTo("EMBEDDING_MAINTENANCE");
        var order = inOrder(control, mapper);
        order.verify(control).lockShared();
        order.verify(mapper).resetTaskForRetry(20L);
        verify(mapper).resetTaskForRetry(20L);
        verify(mapper).insertReplayEvent(20L, 7L, "用户请求重新解析");
        verify(mapper).resetVersionForRetry(20L);
    }

    @Test
    void editOnlyRetryReturnsAcceptanceWithoutParseIssuesOrFileMetadata() {
        when(permissions.resolve(7L, "edit"))
                .thenReturn(PermissionScope.of(7, (short) 2, List.of(3L), List.of("1:7")));
        when(permissions.resolve(7L, "view")).thenReturn(PermissionScope.denyAll(7));
        var version = new DocumentVersionView();
        version.setId(21L);
        version.setFileName("private.txt");
        version.setParseIssueSummary("private content");
        when(mapper.findVersionForEdit(eq(10L), eq(21L), any(), any(), eq((short) 2), eq("edit")))
                .thenReturn(version);
        when(mapper.resetTaskForRetry(21L)).thenReturn(1);
        when(control.lockShared()).thenReturn(new EmbeddingControl("UNINITIALIZED", null, null, null, null, 0, null));
        var accepted = service.retry(7, 10, 21L);
        assertThat(accepted.getVersionId()).isEqualTo(21);
        assertThat(accepted.getIndexStatus()).isEqualTo("pending");
        assertThat(accepted.getParseIssueSummary()).isNull();
        assertThat(accepted.getIndexError()).isNull();
        assertThat(accepted.getVersionNo()).isNull();
    }

    @Test
    void progressDistinguishesMaintenanceQueueFromIndexedAndFailedVersions() {
        when(permissions.resolve(7L, PermissionService.PERM_VIEW))
                .thenReturn(PermissionScope.of(7L, (short) 2, List.of(3L), List.of("1:7")));
        when(control.current()).thenReturn(new EmbeddingControl(
                "BLOCKED", null, null, "target", "fp", 3, UUID.randomUUID()));
        for (String status : List.of("pending", "parsing", "indexed", "failed")) {
            var view = new DocumentProgress(10L, 20L, 1, status, null, 30L,
                    "parsing".equals(status) ? "running" : status, 2, 3, "old failure");
            when(mapper.findProgress(eq(10L), eq(20L), any(), any(), eq((short) 2), eq(PermissionService.PERM_VIEW)))
                    .thenReturn(view);
            var result = service.progress(7, 10, 20L);
            assertThat(result.getWaitingReason()).isEqualTo(List.of("pending", "parsing").contains(status)
                    ? "EMBEDDING_MAINTENANCE" : null);
            assertThat(result.getIndexStatus()).isEqualTo(status);
            assertThat(result.getAttempts()).isEqualTo(2);
            assertThat(result.getLastError()).isEqualTo("old failure");
        }
        verifyNoInteractions(storage, writer);
    }

    @Test
    void progressDoesNotReadMaintenanceFactsBeforeAuthorization() {
        when(permissions.resolve(7L, PermissionService.PERM_VIEW)).thenReturn(PermissionScope.denyAll(7L));
        assertThatThrownBy(() -> service.progress(7, 10, 20L)).isInstanceOf(ApiException.class);
        verifyNoInteractions(control, storage, writer);
    }

    @Test
    void deleteMarksDocumentBeforeBestEffortObjectCleanup() {
        PermissionScope edit = PermissionScope.of(7L, (short) 2, List.of(3L), List.of("1:7"));
        when(permissions.resolve(7L, PermissionService.PERM_EDIT)).thenReturn(edit);
        when(mapper.findSummary(eq(10L), any(), any(), eq((short) 2), eq(PermissionService.PERM_EDIT)))
                .thenReturn(new DocumentSummary(10L, 3L, "说明", (short) 1, "published", 7L,
                        20L, 1, "a.txt", 1L, "text/plain", "indexed", null, null, null));
        when(mapper.findObjectKeys(10L)).thenReturn(List.of("documents/a.txt"));
        when(mapper.softDelete(10L)).thenReturn(1);

        service.delete(7L, 10L);

        verify(mapper).softDelete(10L);
        verify(mapper).cancelTasks(10L);
        verify(cancellation).cancelDocument(10L, 7L);
        verify(storage).delete("documents/a.txt");
        var order = inOrder(mapper, cancellation, storage);
        order.verify(mapper).cancelTasks(10L);
        order.verify(mapper).softDelete(10L);
        order.verify(cancellation).cancelDocument(10L, 7L);
        order.verify(storage).delete("documents/a.txt");
    }

    /**
     * 版本上传对象键必须落在对账扫描前缀内，与首次上传共用同一常量。
     */
    @Test
    void versionUploadObjectKeyUsesSharedDocumentsPrefix() {
        when(permissions.resolve(7L, PermissionService.PERM_EDIT))
                .thenReturn(PermissionScope.of(7L, (short) 2, List.of(3L), List.of("1:7")));
        when(mapper.findSummary(eq(10L), any(), any(), eq((short) 2), eq(PermissionService.PERM_EDIT)))
                .thenReturn(new DocumentSummary(10L, 3L, "说明", (short) 1, "published", 7L,
                        20L, 1, "a.txt", 1L, "text/plain", "indexed", null, null, null));

        service.uploadVersion(7L, 10L, new MockMultipartFile("file", "a.txt", "text/plain", "hello".getBytes()));

        ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
        verify(storage).put(key.capture(), any(), anyLong(), any(), any());
        assertThat(key.getValue())
                .as("版本上传对象键必须以共享前缀开头，否则对账按前缀扫描时看不见它")
                .startsWith(ObjectKeys.DOCUMENTS_PREFIX)
                .endsWith(".txt");
    }
}
