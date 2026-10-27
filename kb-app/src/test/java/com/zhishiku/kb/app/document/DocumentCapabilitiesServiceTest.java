package com.zhishiku.kb.app.document;

import com.zhishiku.kb.app.auth.service.AdminQueryAccess;
import com.zhishiku.kb.app.auth.service.PermissionService;
import com.zhishiku.kb.app.document.mapper.DocumentMapper;
import com.zhishiku.kb.app.document.model.DocumentCapabilities;
import com.zhishiku.kb.app.document.model.DocumentSummary;
import com.zhishiku.kb.app.document.service.DocumentCapabilitiesService;
import com.zhishiku.kb.app.query.QueryException;
import com.zhishiku.kb.common.security.PermissionScope;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 文档业务能力单测，无数据库、文件或模型依赖。
 */
class DocumentCapabilitiesServiceTest {
    final DocumentMapper mapper = mock(DocumentMapper.class);
    final AdminQueryAccess access = mock(AdminQueryAccess.class);
    final PermissionService permissions = mock(PermissionService.class);
    final PermissionService.PrincipalHandle handle = mock(PermissionService.PrincipalHandle.class);
    final DocumentCapabilitiesService service = new DocumentCapabilitiesService(mapper, access, permissions);

    DocumentCapabilitiesServiceTest() {
        when(access.current()).thenReturn(handle);
        when(permissions.resolve(eq(handle), anyString())).thenReturn(PermissionScope.of(7, (short) 2, List.of(3L), List.of("1:7")));
    }

    @Test void readDownloadAndEditAreIndependentForAllCombinations() {
        for (boolean download : List.of(false, true)) for (boolean edit : List.of(false, true)) {
            when(mapper.findSummary(eq(9L), anyList(), anyList(), eq((short) 2), eq("view"))).thenReturn(new DocumentSummary());
            when(mapper.findSummary(eq(9L), anyList(), anyList(), eq((short) 2), eq("download"))).thenReturn(download ? new DocumentSummary() : null);
            when(mapper.findSummary(eq(9L), anyList(), anyList(), eq((short) 2), eq("edit"))).thenReturn(edit ? new DocumentSummary() : null);
            assertThat(service.get("9", Map.of())).isEqualTo(new DocumentCapabilities(9, download, edit, edit));
        }
        verify(access, times(4)).current();
    }

    @Test void missingViewDoesNotRevealOtherPermissionsEvenIfTheyWouldBeGranted() {
        assertThatThrownBy(() -> service.get("9", Map.of())).isInstanceOf(QueryException.class).extracting("status").isEqualTo(404);
        verify(permissions).resolve(handle, "view");
        verify(permissions, never()).resolve(handle, "edit");
        verify(permissions, never()).resolve(handle, "download");
    }

    @Test void documentExceptionRangeAndUnpublishedParentReuseExistingPredicate() {
        when(permissions.resolve(eq(handle), anyString())).thenReturn(PermissionScope.of(7, (short) 2, List.of(), List.of("1:7")));
        when(mapper.findSummary(eq(9L), eq(List.of()), eq(List.of("1:7")), eq((short) 2), anyString())).thenReturn(new DocumentSummary());
        assertThat(service.get("9", Map.of())).isEqualTo(new DocumentCapabilities(9, true, true, true));
        verify(mapper, never()).findFile(anyLong(), any(), anyList(), anyList(), anyShort(), anyString());
    }

    @Test void nextRequestRechecksAndDependencyFailureDoesNotProduceFalseCapabilities() {
        when(mapper.findSummary(anyLong(), anyList(), anyList(), anyShort(), anyString())).thenReturn(new DocumentSummary());
        service.get("9", Map.of());
        when(mapper.findSummary(anyLong(), anyList(), anyList(), anyShort(), anyString())).thenReturn(null);
        assertThatThrownBy(() -> service.get("9", Map.of())).isInstanceOf(QueryException.class).extracting("status").isEqualTo(404);
        when(access.current()).thenThrow(new IllegalStateException("private"));
        assertThatThrownBy(() -> service.get("9", Map.of())).isInstanceOf(QueryException.class).extracting("status").isEqualTo(503);
    }

    @Test void invalidIdsAndParametersAreRejectedBeforeDataLookup() {
        for (var id : List.of("0", "-1", "abc", "9223372036854775808"))
            assertThatThrownBy(() -> service.get(id, Map.of())).isInstanceOf(QueryException.class).extracting("status").isEqualTo(400);
        assertThatThrownBy(() -> service.get("9", Map.of("userId", List.of("7")))).isInstanceOf(QueryException.class);
        verifyNoInteractions(mapper);
    }
}
