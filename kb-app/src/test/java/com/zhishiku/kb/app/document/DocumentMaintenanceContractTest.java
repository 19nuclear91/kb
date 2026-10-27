package com.zhishiku.kb.app.document;

import cn.dev33.satoken.stp.StpUtil;
import com.zhishiku.kb.app.auth.service.PermissionService;
import com.zhishiku.kb.app.config.GlobalExceptionHandler;
import com.zhishiku.kb.app.document.mapper.DocumentMapper;
import com.zhishiku.kb.app.document.mapper.DocumentWriteMapper;
import com.zhishiku.kb.app.document.model.DocumentEntity;
import com.zhishiku.kb.app.document.model.DocumentFile;
import com.zhishiku.kb.app.document.model.DocumentProgress;
import com.zhishiku.kb.app.document.model.DocumentSummary;
import com.zhishiku.kb.app.document.model.DocumentVersionEntity;
import com.zhishiku.kb.app.document.model.ParseTaskEntity;
import com.zhishiku.kb.app.document.service.DocumentCitationService;
import com.zhishiku.kb.app.document.service.DocumentManagementService;
import com.zhishiku.kb.app.document.service.DocumentUploadService;
import com.zhishiku.kb.app.document.service.DocumentWriteService;
import com.zhishiku.kb.app.document.service.ReviewCancellationService;
import com.zhishiku.kb.app.document.web.DocumentController;
import com.zhishiku.kb.app.embedding.service.EmbeddingControlService;
import com.zhishiku.kb.app.storage.ObjectStorage;
import com.zhishiku.kb.app.storage.StorageProperties;
import com.zhishiku.kb.common.embedding.EmbeddingControl;
import com.zhishiku.kb.common.security.PermissionScope;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyLong;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class DocumentMaintenanceContractTest {
    @ParameterizedTest
    @ValueSource(strings = {"QUIESCING", "REBUILDING", "VERIFYING", "BLOCKED"})
    void maintenanceVersionUploadStoresOriginalAndQueuesWithoutReplacingIndexedCurrentVersion(String mode) throws Exception {
        var mapper = mock(DocumentMapper.class);
        var writes = mock(DocumentWriteMapper.class);
        var permissions = mock(PermissionService.class);
        var storage = mock(ObjectStorage.class);
        var control = mock(EmbeddingControlService.class);
        var writer = spy(new DocumentWriteService(writes));
        var service = new DocumentManagementService(mapper, permissions, storage,
                new StorageProperties("http://localhost:9000", "us-east-1", "a", "b", "kb", true, 1000), writer,
                control, mock(ReviewCancellationService.class));
        var scope = PermissionScope.of(7L, (short) 2, List.of(3L), List.of("1:7"));
        for (String permission : List.of(PermissionService.PERM_EDIT, PermissionService.PERM_VIEW,
                PermissionService.PERM_DOWNLOAD)) {
            when(permissions.resolve(7L, permission)).thenReturn(scope);
        }
        var current = new DocumentSummary(10L, 3L, "title", (short) 1, "published", 7L,
                20L, 1, "old.txt", 3L, "text/plain", "indexed", null, null, null);
        when(mapper.findSummary(10L, List.of(3L), List.of("1:7"), (short) 2, PermissionService.PERM_EDIT)).thenReturn(current);
        when(control.current()).thenReturn(new EmbeddingControl(mode, "old", "old-fp", "next", "next-fp", 8,
                UUID.randomUUID()));
        var versions = new ArrayList<DocumentVersionEntity>();
        var oldVersion = DocumentVersionEntity.builder().id(20L).docId(10L).versionNo(1)
                .objectKey("documents/old.txt").indexStatus("indexed").build();
        versions.add(oldVersion);
        var tasks = new ArrayList<ParseTaskEntity>();
        var objects = new HashMap<String, byte[]>();
        byte[] oldBytes = "old".getBytes(StandardCharsets.UTF_8);
        objects.put(oldVersion.getObjectKey(), oldBytes);
        byte[] bytes = "new version content".getBytes(StandardCharsets.UTF_8);
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        doAnswer(call -> {
            objects.put(call.getArgument(0), ((InputStream) call.getArgument(1)).readAllBytes());
            return null;
        }).when(storage).put(anyString(), any(InputStream.class), anyLong(), anyString(), anyString());
        when(writes.lockDocument(10L)).thenReturn(1);
        when(writes.nextVersionNo(10L)).thenReturn(2);
        doAnswer(call -> {
            DocumentVersionEntity version = call.getArgument(0);
            version.setId(21L);
            versions.add(version);
            return 1;
        }).when(writes).insertVersion(any());
        doAnswer(call -> {
            ParseTaskEntity task = call.getArgument(0);
            task.setId(31L);
            tasks.add(task);
            return 1;
        }).when(writes).insertParseTask(any());

        var result = service.uploadVersion(7, 10, new MockMultipartFile("file", "new.txt", "text/plain", bytes));

        assertThat(result.getDocumentId()).isEqualTo(10L);
        assertThat(result.getVersionId()).isEqualTo(21L);
        assertThat(result.getTaskId()).isEqualTo(31L);
        assertThat(result.getIndexStatus()).isEqualTo("pending");
        assertThat(versions).hasSize(2);
        var pending = versions.get(1);
        assertThat(pending.getDocId()).isEqualTo(10L);
        assertThat(pending.getVersionNo()).isEqualTo(2);
        assertThat(pending.getIndexStatus()).isEqualTo("pending");
        assertThat(pending.getContentHash()).isEqualTo(hash);
        assertThat(pending.getObjectKey()).isNotEqualTo(oldVersion.getObjectKey());
        assertThat(objects).hasSize(2);
        assertThat(objects.get(pending.getObjectKey())).containsExactly(bytes);
        assertThat(objects.get(oldVersion.getObjectKey())).containsExactly(oldBytes);
        assertThat(tasks).singleElement().satisfies(task -> {
            assertThat(task.getVersionId()).isEqualTo(21L);
            assertThat(task.getStatus()).isEqualTo("pending");
            assertThat(task.getAttempts()).isZero();
            assertThat(task.getMaxAttempts()).isEqualTo(3);
        });
        var order = inOrder(permissions, mapper, storage, writer, writes);
        order.verify(permissions).resolve(7L, PermissionService.PERM_EDIT);
        order.verify(mapper).findSummary(10L, List.of(3L), List.of("1:7"), (short) 2, PermissionService.PERM_EDIT);
        order.verify(storage).put(eq(pending.getObjectKey()), any(InputStream.class), eq((long) bytes.length), eq(
                "text/plain"), eq(hash));
        order.verify(writer).createVersion(7L, 10L, "title", pending.getObjectKey(), "new.txt", bytes.length, hash, "text" +
                "/plain");
        order.verify(writes).lockDocument(10L);
        order.verify(writes).nextVersionNo(10L);
        order.verify(writes).insertVersion(pending);
        order.verify(writes).insertParseTask(tasks.get(0));
        verifyNoMoreInteractions(writes, writer);

        when(mapper.findProgress(10L, 21L, List.of(3L), List.of("1:7"), (short) 2, PermissionService.PERM_VIEW))
                .thenAnswer(call -> new DocumentProgress(10L, pending.getId(), pending.getVersionNo(),
                        pending.getIndexStatus(), null,
                        tasks.get(0).getId(), tasks.get(0).getStatus(), tasks.get(0).getAttempts(), 3, null));
        when(mapper.findProgress(10L, 20L, List.of(3L), List.of("1:7"), (short) 2, PermissionService.PERM_VIEW))
                .thenAnswer(call -> new DocumentProgress(10L, 20L, 1, oldVersion.getIndexStatus(), null, 30L,
                        "succeeded", 1, 3, null));
        var waiting = service.progress(7, 10, 21L);
        assertThat(waiting.getWaitingReason()).isEqualTo("EMBEDDING_MAINTENANCE");
        assertThat(waiting.getAttempts()).isZero();
        var published = service.progress(7, 10, 20L);
        assertThat(published.getIndexStatus()).isEqualTo("indexed");
        assertThat(published.getWaitingReason()).isNull();
        assertThat(current.getCurrentVersionId()).isEqualTo(20L);
        assertThat(current.getCurrentIndexStatus()).isEqualTo("indexed");
        assertThat(current.getStatus()).isEqualTo("published");

        when(mapper.findFile(10L, 20L, List.of(3L), List.of("1:7"), (short) 2, PermissionService.PERM_DOWNLOAD))
                .thenReturn(new DocumentFile(10L, 20L, 1, oldVersion.getObjectKey(), "old.txt", 3L, "text/plain"));
        when(storage.get(oldVersion.getObjectKey())).thenAnswer(call -> new ByteArrayInputStream(objects.get(call.getArgument(0))));
        var file = service.file(7, 10, 20L, PermissionService.PERM_DOWNLOAD);
        try (var input = service.open(file)) {
            assertThat(input.readAllBytes()).containsExactly(oldBytes);
        }
        order.verify(permissions).resolve(7L, PermissionService.PERM_DOWNLOAD);
        order.verify(mapper).findFile(10L, 20L, List.of(3L), List.of("1:7"), (short) 2,
                PermissionService.PERM_DOWNLOAD);
        order.verify(storage).get(oldVersion.getObjectKey());
        verify(mapper).findProgress(10L, 21L, List.of(3L), List.of("1:7"), (short) 2, PermissionService.PERM_VIEW);
        verify(mapper).findProgress(10L, 20L, List.of(3L), List.of("1:7"), (short) 2, PermissionService.PERM_VIEW);
        verifyNoMoreInteractions(mapper, storage, writes);
        assertThat(DocumentWriteService.class.getMethod("createVersion", long.class, long.class, String.class, String.class,
                String.class, long.class, String.class, String.class).getAnnotation(Transactional.class)).isNotNull();
    }

    @Test
    void progressEndpointReturnsWaitingReasonForAuthorizedVersion() throws Exception {
        var management = mock(DocumentManagementService.class);
        var view = new DocumentProgress(10L, 20L, 1, "pending", null, 30L, "pending", 0, 3, null);
        view.setWaitingReason("EMBEDDING_MAINTENANCE");
        when(management.progress(7L, 10L, 20L)).thenReturn(view);
        var mvc = MockMvcBuilders.standaloneSetup(new DocumentController(mock(DocumentUploadService.class), management,
                        mock(DocumentCitationService.class)))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
        try (var session = mockStatic(StpUtil.class)) {
            session.when(StpUtil::getLoginIdAsLong).thenReturn(7L);
            mvc.perform(get("/api/documents/10/progress").param("versionId", "20"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.data.waitingReason").value(
                            "EMBEDDING_MAINTENANCE"))
                    .andExpect(jsonPath("$.data.indexStatus").value("pending"));
            verify(management).progress(7L, 10L, 20L);
        }
    }

    @Test
    void uploadsOnlyCreatePendingVersionsAndDoNotPublishCurrentPointer() throws Exception {
        var mapper = mock(DocumentWriteMapper.class);
        doAnswer(call -> {
            ((DocumentEntity) call.getArgument(0)).setId(10L);
            return 1;
        })
                .when(mapper).insertDocument(any());
        doAnswer(call -> {
            DocumentVersionEntity version = call.getArgument(0);
            assertThat(version.getIndexStatus()).isEqualTo("pending");
            version.setId(20L);
            return 1;
        }).when(mapper).insertVersion(any());
        doAnswer(call -> {
            ParseTaskEntity task = call.getArgument(0);
            assertThat(task.getStatus()).isEqualTo("pending");
            assertThat(task.getAttempts()).isZero();
            task.setId(30L);
            return 1;
        }).when(mapper).insertParseTask(any());
        var result = new DocumentWriteService(mapper).create(7, 3, "title", (short) 1, "key", "a.txt", 10, "hash",
                "text/plain");
        assertThat(result.getIndexStatus()).isEqualTo("pending");
        assertThat(DocumentWriteService.class.getMethod("create", long.class, long.class, String.class, short.class,
                String.class, String.class, long.class, String.class, String.class).getAnnotation(Transactional.class)).isNotNull();
    }
}
