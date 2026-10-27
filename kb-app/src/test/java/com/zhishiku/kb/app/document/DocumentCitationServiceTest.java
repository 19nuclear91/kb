package com.zhishiku.kb.app.document;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhishiku.kb.app.auth.service.PermissionService;
import com.zhishiku.kb.app.document.mapper.DocumentMapper;
import com.zhishiku.kb.app.document.model.DocumentCitationRow;
import com.zhishiku.kb.app.document.model.DocumentCitationView;
import com.zhishiku.kb.app.document.model.DocumentCitationView.Status;
import com.zhishiku.kb.app.document.model.DocumentFile;
import com.zhishiku.kb.app.document.service.DocumentCitationService;
import com.zhishiku.kb.common.api.ApiException;
import com.zhishiku.kb.common.security.PermissionScope;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * 单独验证引用服务的权限调用、参数和返回分支，所有外部依赖均为 mock。
 */
class DocumentCitationServiceTest {
    private final DocumentMapper mapper = mock(DocumentMapper.class);
    private final PermissionService permissions = mock(PermissionService.class);
    private final DocumentCitationService service = new DocumentCitationService(mapper, permissions);

    @BeforeEach
    void sameSubjectAcrossPermissionBits() {
        when(permissions.principalOf(7L)).thenReturn(mock(PermissionService.PrincipalHandle.class));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void downloadUsesItsOwnScopeAndNeverSerializesStorageInformation(boolean chunkExists) throws Exception {
        allowView();
        when(mapper.findCitation(10L, 20L, 30L, List.of(3L), List.of("1:7"), (short) 2))
                .thenReturn(row(chunkExists ? 30L : null));
        when(permissions.resolve(any(PermissionService.PrincipalHandle.class), eq("download")))
                .thenReturn(PermissionScope.of(7L, (short) 1, List.of(4L), List.of("2:8")));
        when(mapper.findFile(10L, 20L, List.of(4L), List.of("2:8"), (short) 1, "download"))
                .thenReturn(new DocumentFile(10L, 20L, 1, "private/key", "original.docx", 123L, "application/test"));

        var result = service.find(7L, 10L, 20L, 30L);
        assertThat(result.downloadPath()).isEqualTo("/api/documents/10/versions/20/download");
        assertThat(result.previewPath()).isEqualTo("/api/documents/10/versions/20/preview");
        assertThat(result.status()).isEqualTo(chunkExists ? Status.AVAILABLE : Status.CHUNK_UNAVAILABLE);
        assertThat(new ObjectMapper().writeValueAsString(result))
                .doesNotContain("objectKey", "private/key", "original.docx", "satoken", "https://");
        verify(mapper).findFile(10L, 20L, List.of(4L), List.of("2:8"), (short) 1, "download");
    }

    @Test
    void downloadScopeWithoutMatchingFileDoesNotRejectView() {
        allowView();
        when(mapper.findCitation(10L, 20L, 30L, List.of(3L), List.of("1:7"), (short) 2)).thenReturn(row(30L));
        when(permissions.resolve(any(PermissionService.PrincipalHandle.class), eq("download")))
                .thenReturn(PermissionScope.of(7L, (short) 1, List.of(), List.of("1:7")));
        assertThat(service.find(7L, 10L, 20L, 30L).downloadPath()).isNull();
        verify(mapper).findFile(10L, 20L, List.of(), List.of("1:7"), (short) 1, "download");
    }

    @Test
    void downloadAloneDoesNotAuthorizeCitationLookup() {
        when(permissions.resolve(any(PermissionService.PrincipalHandle.class), eq("view")))
                .thenReturn(PermissionScope.denyAll(7L));
        when(permissions.resolve(any(PermissionService.PrincipalHandle.class), eq("download")))
                .thenReturn(PermissionScope.of(7L, (short) 2, List.of(3L), List.of("1:7")));
        assertThatThrownBy(() -> service.find(7L, 10L, 20L, 30L))
                .isInstanceOfSatisfying(ApiException.class, error -> assertThat(error.httpStatus()).isEqualTo(404));
        verifyNoInteractions(mapper);
        verify(permissions, never()).resolve(any(PermissionService.PrincipalHandle.class), eq("download"));
    }

    @Test
    void downloadLookupFailuresAreNotDisguisedAsMissingPermissions() {
        allowView();
        when(mapper.findCitation(10L, 20L, 30L, List.of(3L), List.of("1:7"), (short) 2)).thenReturn(row(30L));
        when(permissions.resolve(any(PermissionService.PrincipalHandle.class), eq("download")))
                .thenReturn(PermissionScope.of(7L, (short) 2, List.of(3L), List.of("1:7")));
        var failure = new IllegalStateException("storage metadata unavailable");
        when(mapper.findFile(10L, 20L, List.of(3L), List.of("1:7"), (short) 2, "download"))
                .thenThrow(failure);
        assertThatThrownBy(() -> service.find(7L, 10L, 20L, 30L)).isSameAs(failure);
    }

    @Test
    void authorizedMatchingChunkReturnsFullStoredTextAndSourceHints() {
        allowView();
        DocumentCitationRow row = row(30L);
        when(mapper.findCitation(10L, 20L, 30L, List.of(3L), List.of("1:7"), (short) 2)).thenReturn(row);

        DocumentCitationView result = service.find(7L, 10L, 20L, 30L);
        assertThat(result.status()).isEqualTo(Status.AVAILABLE);
        assertThat(result.docId()).isEqualTo(10L);
        assertThat(result.versionId()).isEqualTo(20L);
        assertThat(result.versionNo()).isEqualTo(1);
        assertThat(result.chunkId()).isEqualTo(30L);
        assertThat(result.chunk()).isEqualTo(new DocumentCitationView.Chunk(0, "完整存储正文", 2,
                "权限", "PARAGRAPH", 4, 9, 0, 60, "source"));
        assertThat(result.previewPath()).isEqualTo("/api/documents/10/versions/20/preview");
        assertThat(result.downloadPath()).isNull();
        var order = inOrder(permissions, mapper);
        order.verify(permissions).principalOf(7L);
        order.verify(permissions).resolve(any(PermissionService.PrincipalHandle.class), eq("view"));
        order.verify(mapper).findCitation(10L, 20L, 30L, List.of(3L), List.of("1:7"), (short) 2);
        order.verify(permissions).resolve(any(PermissionService.PrincipalHandle.class), eq("download"));
        verifyNoMoreInteractions(mapper, permissions);
    }

    @Test
    void missingChunkOnlyEchoesRequestedIdAndDoesNotGuessAnotherChunk() {
        allowView();
        when(mapper.findCitation(10L, 20L, 999L, List.of(3L), List.of("1:7"), (short) 2))
                .thenReturn(row(null));

        DocumentCitationView result = service.find(7L, 10L, 20L, 999L);
        assertThat(result.status()).isEqualTo(Status.CHUNK_UNAVAILABLE);
        assertThat(result.chunk()).isNull();
        assertThat(result.chunkId()).isEqualTo(999L);
        assertThat(result.versionId()).isEqualTo(20L);
        assertThat(result.previewPath()).isEqualTo("/api/documents/10/versions/20/preview");
        verify(mapper).findCitation(10L, 20L, 999L, List.of(3L), List.of("1:7"), (short) 2);
        verifyNoMoreInteractions(mapper);
    }

    @Test
    void invisibleParentHasTheSame404AndNeverChecksDownloads() {
        allowView();
        assertThatThrownBy(() -> service.find(7L, 10L, 20L, 30L))
                .isInstanceOfSatisfying(ApiException.class, error -> {
                    assertThat(error.httpStatus()).isEqualTo(404);
                    assertThat(error.getMessage()).isEqualTo("文档或版本不存在，或当前用户无权访问");
                });
        verify(permissions, never()).resolve(any(PermissionService.PrincipalHandle.class), eq("download"));
        verify(mapper).findCitation(10L, 20L, 30L, List.of(3L), List.of("1:7"), (short) 2);
        verifyNoMoreInteractions(mapper);
    }

    @Test
    void disabledOrUnresolvedIdentityCannotQueryAnyResource() {
        when(permissions.resolve(any(PermissionService.PrincipalHandle.class), eq("view")))
                .thenReturn(PermissionScope.denyAll(7L));
        assertThatThrownBy(() -> service.find(7L, 10L, 20L, 30L))
                .isInstanceOfSatisfying(ApiException.class, error -> assertThat(error.httpStatus()).isEqualTo(404));
        verifyNoInteractions(mapper);
        verify(permissions, never()).resolve(any(PermissionService.PrincipalHandle.class), eq("download"));
    }

    @ParameterizedTest
    @CsvSource({"0,20,30", "10,-1,30", "10,20,0"})
    void invalidIdsDoNotFallBackToCurrentVersion(long documentId, long versionId, long chunkId) {
        assertThatThrownBy(() -> service.find(7L, documentId, versionId, chunkId))
                .isInstanceOfSatisfying(ApiException.class, error -> assertThat(error.httpStatus()).isEqualTo(400));
        verifyNoInteractions(permissions, mapper);
    }

    private void allowView() {
        when(permissions.resolve(any(PermissionService.PrincipalHandle.class), eq("view")))
                .thenReturn(PermissionScope.of(7L, (short) 2, List.of(3L), List.of("1:7")));
        when(permissions.resolve(any(PermissionService.PrincipalHandle.class), eq("download")))
                .thenReturn(PermissionScope.denyAll(7L));
    }

    private static DocumentCitationRow row(Long chunkId) {
        return new DocumentCitationRow(10L, "说明", 20L, 1, chunkId, 0, "完整存储正文", 2,
                "权限", "PARAGRAPH", 4, 9, 0, 60, "source");
    }
}
