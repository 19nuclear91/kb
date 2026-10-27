package com.zhishiku.kb.app.document;

import com.zhishiku.kb.app.auth.service.PermissionService;
import com.zhishiku.kb.app.document.mapper.DocumentMapper;
import com.zhishiku.kb.app.document.model.DocumentCitationRow;
import com.zhishiku.kb.app.document.model.DocumentCitationView.Status;
import com.zhishiku.kb.app.document.service.DocumentCitationService;
import com.zhishiku.kb.common.api.ApiException;
import com.zhishiku.kb.common.security.PermissionScope;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyShort;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * 生命周期仅由 Mapper 返回夹具模拟，不执行发布、重解析或向量任务。
 */
class DocumentCitationLifecycleTest {
    @Test
    void guessedCandidateReturnsMissingBeforeAnyFileOrDownloadProjection() {
        assertThatThrownBy(() -> service.find(7, 10, 21, 31))
                .isInstanceOfSatisfying(ApiException.class,
                        error -> assertThat(error.httpStatus()).isEqualTo(404));
        verify(mapper, never()).findFile(anyLong(), any(), any(), any(), anyShort(), any());
        verify(permissions, never()).resolve(any(PermissionService.PrincipalHandle.class), eq("download"));
    }
    private final DocumentMapper mapper = mock(DocumentMapper.class);
    private final PermissionService permissions = mock(PermissionService.class);
    private final DocumentCitationService service = new DocumentCitationService(mapper, permissions);

    @BeforeEach
    void viewOnly() {
        when(permissions.principalOf(7L)).thenReturn(mock(PermissionService.PrincipalHandle.class));
        when(permissions.resolve(any(PermissionService.PrincipalHandle.class), eq("view")))
                .thenReturn(PermissionScope.of(7L, (short) 2, List.of(3L), List.of("1:7")));
        when(permissions.resolve(any(PermissionService.PrincipalHandle.class), eq("download")))
                .thenReturn(PermissionScope.denyAll(7L));
    }

    @Test
    void publishingAnotherVersionNeverRedirectsTheOldTriple() {
        var original = row(20L, 1, 30L, "旧标题", "旧版本正文");
        var retitled = row(20L, 1, 30L, "新标题", "旧版本正文");
        when(mapper.findCitation(10L, 20L, 30L, List.of(3L), List.of("1:7"), (short) 2))
                .thenReturn(original, retitled);
        when(mapper.findCitation(10L, 21L, 31L, List.of(3L), List.of("1:7"), (short) 2))
                .thenReturn(row(21L, 2, 31L, "新标题", "新版本正文"));

        var before = service.find(7L, 10L, 20L, 30L);
        var after = service.find(7L, 10L, 20L, 30L);
        assertThat(after.chunk()).isEqualTo(before.chunk());
        assertThat(after.docTitle()).isEqualTo("新标题");
        assertThat(after.versionId()).isEqualTo(20L);
        assertThat(after.versionNo()).isEqualTo(1);
        assertThat(after.chunkId()).isEqualTo(30L);
        assertThat(after.previewPath()).isEqualTo("/api/documents/10/versions/20/preview");
        verify(mapper, times(2)).findCitation(10L, 20L, 30L, List.of(3L), List.of("1:7"), (short) 2);
        verify(mapper, never()).findCitation(10L, 21L, 31L, List.of(3L), List.of("1:7"), (short) 2);
        verifyNoMoreInteractions(mapper);
    }

    @Test
    void reparsingWithTheSameChunkIndexDoesNotRepairTheOldCitation() {
        var original = row(20L, 1, 30L, "说明", "旧分块正文");
        var parentOnly = row(20L, 1, null, "说明", null);
        var replacement = row(20L, 1, 32L, "说明", "替换分块正文");
        when(mapper.findCitation(10L, 20L, 30L, List.of(3L), List.of("1:7"), (short) 2))
                .thenReturn(original, parentOnly);
        when(mapper.findCitation(10L, 20L, 32L, List.of(3L), List.of("1:7"), (short) 2))
                .thenReturn(replacement);

        var before = service.find(7L, 10L, 20L, 30L);
        var missing = service.find(7L, 10L, 20L, 30L);
        var newCitation = service.find(7L, 10L, 20L, 32L);
        assertThat(missing.status()).isEqualTo(Status.CHUNK_UNAVAILABLE);
        assertThat(missing.chunk()).isNull();
        assertThat(missing.chunkId()).isEqualTo(30L);
        assertThat(missing.previewPath()).isEqualTo(before.previewPath());
        assertThat(newCitation.status()).isEqualTo(Status.AVAILABLE);
        assertThat(newCitation.chunk().chunkIdx()).isEqualTo(before.chunk().chunkIdx());
        assertThat(newCitation.chunk().content()).isEqualTo("替换分块正文");
        verify(mapper, times(2)).findCitation(10L, 20L, 30L, List.of(3L), List.of("1:7"), (short) 2);
        verify(mapper).findCitation(10L, 20L, 32L, List.of(3L), List.of("1:7"), (short) 2);
        verifyNoMoreInteractions(mapper);
    }

    @Test
    void onlyDocumentAndPermissionDependenciesAreNeededForLookup() {
        assertThat(DocumentCitationService.class.getConstructors()).hasSize(1);
        assertThat(DocumentCitationService.class.getConstructors()[0].getParameterTypes())
                .containsExactly(DocumentMapper.class, PermissionService.class);
        var stable = row(20L, 1, 30L, "说明", "完整存储正文");
        when(mapper.findCitation(10L, 20L, 30L, List.of(3L), List.of("1:7"), (short) 2)).thenReturn(stable);
        var before = service.find(7L, 10L, 20L, 30L);
        var after = service.find(7L, 10L, 20L, 30L);
        assertThat(after).isEqualTo(before);
        assertThat(after.chunk().pageNo()).isNull();
        verify(mapper, times(2)).findCitation(10L, 20L, 30L, List.of(3L), List.of("1:7"), (short) 2);
        verifyNoMoreInteractions(mapper);
    }

    private static DocumentCitationRow row(long versionId, int versionNo, Long chunkId, String title, String content) {
        return new DocumentCitationRow(10L, title, versionId, versionNo, chunkId, 0, content,
                null, null, null, null, null, null, null, null);
    }
}
