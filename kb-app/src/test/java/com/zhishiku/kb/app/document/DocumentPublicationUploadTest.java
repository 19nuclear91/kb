package com.zhishiku.kb.app.document;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhishiku.kb.app.auth.mapper.SecurityLevelMapper;
import com.zhishiku.kb.app.auth.service.PermissionService;
import com.zhishiku.kb.app.auth.service.SecurityLevelDictionary;
import com.zhishiku.kb.app.document.mapper.DocumentMapper;
import com.zhishiku.kb.app.document.mapper.DocumentWriteMapper;
import com.zhishiku.kb.app.document.model.DocumentEntity;
import com.zhishiku.kb.app.document.model.DocumentSummary;
import com.zhishiku.kb.app.document.model.DocumentUploadResult;
import com.zhishiku.kb.app.document.model.DocumentVersionEntity;
import com.zhishiku.kb.app.document.model.ParseTaskEntity;
import com.zhishiku.kb.app.document.service.DocumentManagementService;
import com.zhishiku.kb.app.document.service.DocumentUploadService;
import com.zhishiku.kb.app.document.service.DocumentWriteService;
import com.zhishiku.kb.app.document.service.ReviewCancellationService;
import com.zhishiku.kb.app.embedding.service.EmbeddingControlService;
import com.zhishiku.kb.app.storage.ObjectStorage;
import com.zhishiku.kb.app.storage.StorageProperties;
import com.zhishiku.kb.common.security.PermissionScope;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 仅验证上传调用链与内存持久层替身，不证明 SQL 隔离或真实提交行为。 */
class DocumentPublicationUploadTest {
    private final DocumentWriteMapper writes = mock(DocumentWriteMapper.class);
    private final DocumentWriteService writer = new DocumentWriteService(writes);
    private final AtomicReference<String> spacePolicy = new AtomicReference<>("AUTO");
    private final List<DocumentVersionEntity> versions = new ArrayList<>();

    private void prepareMemoryWrites() {
        when(writes.lockDocument(10)).thenReturn(1);
        when(writes.nextVersionNo(10)).thenAnswer(call -> versions.size() + 2);
        doAnswer(call -> {
            DocumentEntity document = call.getArgument(0);
            assertThat(document.getStatus()).isEqualTo("draft");
            assertThat(document.getCurrentVersionId()).isNull();
            document.setId(10L);
            return 1;
        }).when(writes).insertDocument(any());
        doAnswer(call -> {
            DocumentVersionEntity version = call.getArgument(0);
            // 替身在插入时读取策略，不在服务调用前或对象存储上传前读取。
            assertThat(version.getPublicationPolicy()).isNull();
            version.setPublicationPolicy(spacePolicy.get());
            version.setId(20L + versions.size());
            versions.add(version);
            return 1;
        }).when(writes).insertVersion(any());
        doAnswer(call -> {
            ((ParseTaskEntity) call.getArgument(0)).setId(30L + versions.size());
            return 1;
        }).when(writes).insertParseTask(any());
    }

    @Test
    void newDocumentIsDraftAndPolicyChangesDoNotRewriteInFlightVersions() {
        prepareMemoryWrites();
        writer.create(7, 3, "标题", (short) 1, "key1", "a.txt", 5, "hash", "text/plain");
        spacePolicy.set("REVIEW_REQUIRED");
        writer.createVersion(8, 10, "标题", "key2", "b.txt", 5, "hash", "text/plain");
        spacePolicy.set("AUTO");
        writer.createVersion(9, 10, "标题", "key3", "c.txt", 5, "hash", "text/plain");
        assertThat(versions).extracting(DocumentVersionEntity::getPublicationPolicy)
                .containsExactly("AUTO", "REVIEW_REQUIRED", "AUTO");
        assertThat(versions).extracting(DocumentVersionEntity::getUploaderUserId).containsExactly(7L, 8L, 9L);
        assertThat(versions).allSatisfy(version -> {
            assertThat(version.getPublicationProtocol()).isEqualTo("VERSIONED_REVIEW");
            assertThat(version.getIndexStatus()).isEqualTo("pending");
        });
    }

    @Test
    void firstUploadWithEditOnlyReturnsOnlyAcceptanceIdentifiersAndStatus() {
        prepareMemoryWrites();
        var permissions = mock(PermissionService.class);
        when(permissions.resolve(7, PermissionService.PERM_EDIT))
                .thenReturn(PermissionScope.of(7, (short) 2, List.of(3L), List.of("1:7")));
        when(permissions.resolve(7, PermissionService.PERM_VIEW)).thenReturn(PermissionScope.denyAll(7));
        var levels = mock(SecurityLevelMapper.class);
        when(levels.listLevels()).thenReturn(List.of((short) 1));
        var service = new DocumentUploadService(mock(ObjectStorage.class),
                new StorageProperties("http://localhost:9000", "us-east-1", "a", "b", "kb", true, 1000),
                permissions, writer, new SecurityLevelDictionary(levels));
        var result = service.upload(7, 3, "私有标题", (short) 1,
                new MockMultipartFile("file", "private.txt", "text/plain", "hello".getBytes(StandardCharsets.UTF_8)));
        assertMinimalAcceptance(result);
    }

    @Test
    void uploadingForAnotherOwnerUsesActorAndPolicyObservedAfterObjectUpload() {
        prepareMemoryWrites();
        var documents = mock(DocumentMapper.class);
        var permissions = mock(PermissionService.class);
        var storage = mock(ObjectStorage.class);
        when(permissions.resolve(7, PermissionService.PERM_EDIT))
                .thenReturn(PermissionScope.of(7, (short) 2, List.of(3L), List.of("1:7")));
        when(permissions.resolve(7, PermissionService.PERM_VIEW)).thenReturn(PermissionScope.denyAll(7));
        when(documents.findSummary(eq(10L), any(), any(), eq((short) 2), eq(PermissionService.PERM_EDIT)))
                .thenReturn(new DocumentSummary(10L, 3L, "标题", (short) 1, "published", 99L,
                        19L, 1, "old.txt", 5L, "text/plain", "indexed", null, null, null));
        doAnswer(call -> {
            spacePolicy.set("REVIEW_REQUIRED");
            return null;
        }).when(storage).put(anyString(), any(), anyLong(), anyString(), anyString());
        var service = new DocumentManagementService(documents, permissions, storage,
                new StorageProperties("http://localhost:9000", "us-east-1", "a", "b", "kb", true, 1000),
                writer, mock(EmbeddingControlService.class), mock(ReviewCancellationService.class));
        var result = service.uploadVersion(7, 10, new MockMultipartFile("file", "new.txt", "text/plain",
                "hello".getBytes(StandardCharsets.UTF_8)));
        assertMinimalAcceptance(result);
        assertThat(versions).singleElement().satisfies(version -> {
            assertThat(version.getUploaderUserId()).isEqualTo(7);
            assertThat(version.getPublicationPolicy()).isEqualTo("REVIEW_REQUIRED");
        });
    }

    private void assertMinimalAcceptance(DocumentUploadResult result) {
        var json = new ObjectMapper().valueToTree(result);
        var fields = new ArrayList<String>();
        json.fieldNames().forEachRemaining(fields::add);
        assertThat(fields).containsExactlyInAnyOrder("documentId", "versionId", "taskId", "indexStatus");
        assertThat(result.documentId()).isEqualTo(10);
        assertThat(result.versionId()).isEqualTo(20);
        assertThat(result.taskId()).isEqualTo(31);
        assertThat(result.indexStatus()).isEqualTo("pending");
    }

    @Test
    void missingActualSpaceInsertCannotBeAcknowledgedOrCreateTask() {
        when(writes.lockDocument(10)).thenReturn(1);
        when(writes.nextVersionNo(10)).thenReturn(2);
        when(writes.insertVersion(any())).thenReturn(0);
        assertThatThrownBy(() -> writer.createVersion(7, 10, "标题", "key", "a.txt", 5, "hash", "text/plain"))
                .isInstanceOf(IllegalStateException.class);
        verify(writes, never()).insertParseTask(any());
    }
}
