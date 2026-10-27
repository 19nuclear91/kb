package com.zhishiku.kb.app.space.service;

import com.zhishiku.kb.app.auth.service.AdminQueryAccess;
import com.zhishiku.kb.app.auth.service.PermissionService;
import com.zhishiku.kb.app.query.QueryCursor;
import com.zhishiku.kb.app.query.QueryException;
import com.zhishiku.kb.app.space.mapper.UploadSpaceMapper;
import com.zhishiku.kb.app.space.model.UploadSpaceCandidate;
import com.zhishiku.kb.common.security.IdentityException;
import com.zhishiku.kb.common.security.PermissionScope;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.*;

/**
 * 查询服务单元测试，身份、权限和数据依赖全部为替身。
 */
class UploadSpaceQueryServiceTest {
    final UploadSpaceMapper mapper = mock(UploadSpaceMapper.class);
    final AdminQueryAccess access = mock(AdminQueryAccess.class);
    final PermissionService permissions = mock(PermissionService.class);
    final PermissionService.PrincipalHandle handle = mock(PermissionService.PrincipalHandle.class);
    final UploadSpaceQueryService service = new UploadSpaceQueryService(mapper, access, permissions);

    UploadSpaceQueryServiceTest() {
        when(access.current()).thenReturn(handle);
        when(permissions.resolve(handle, "edit")).thenReturn(PermissionScope.of(7, (short) 2, List.of(3L), List.of("1:7")));
    }

    @Test void editOnlyRangeAndLimitPlusOneAreUsedWithoutManagementLookup() {
        when(mapper.list(List.of(3L), null, 2)).thenReturn(List.of(new UploadSpaceCandidate(3, "空间"), new UploadSpaceCandidate(4, "下一项")));
        var page = service.list(Map.of("limit", List.of("1")));
        assertThat(page.items()).containsExactly(new UploadSpaceCandidate(3, "空间"));
        assertThat(page.hasMore()).isTrue();
        assertThat(QueryCursor.decode(page.nextCursor(), "upload-spaces").id()).isEqualTo(3);
        verify(permissions).resolve(handle, "edit");
        verifyNoMoreInteractions(permissions);
        verify(handle).requireEnabled();
    }

    @Test void noSpaceEditIncludingDocumentOnlyExceptionsCannotEnumerateSpaces() {
        when(permissions.resolve(handle, "edit")).thenReturn(PermissionScope.of(7, (short) 2, List.of(), List.of("1:7")));
        assertThat(service.list(Map.of()).items()).isEmpty();
        verifyNoInteractions(mapper);
    }

    @Test void cursorDoesNotRetainRevokedPermissionsAndDefaultFetchIsBounded() {
        when(mapper.list(anyList(), isNull(), eq(21))).thenReturn(List.of());
        service.list(Map.of());
        verify(mapper).list(List.of(3L), null, 21);
        when(permissions.resolve(handle, "edit")).thenReturn(PermissionScope.denyAll(7));
        clearInvocations(mapper);
        var cursor = QueryCursor.encode("upload-spaces", Map.of(), 1);
        assertThat(service.list(Map.of("cursor", List.of(cursor))).items()).isEmpty();
        verifyNoInteractions(mapper);
    }

    @Test void acceptsUpperBoundAndRejectsUnknownRepeatedAndForeignCursor() {
        when(mapper.list(anyList(), any(), anyInt())).thenReturn(List.of());
        service.list(Map.of("limit", List.of("100")));
        verify(mapper).list(List.of(3L), null, 101);
        for (var input : List.of(Map.of("q", List.of("x")), Map.of("limit", List.of("1", "2")),
                Map.of("limit", List.of("0")), Map.of("limit", List.of("101")),
                Map.of("cursor", List.of(QueryCursor.encode("documents", Map.of(), 1))))) {
            assertThatThrownBy(() -> service.list(input)).isInstanceOf(QueryException.class).extracting("status").isEqualTo(400);
        }
    }

    @Test void dependencyFailureIsNotAnEmptySuccessAndDisabledIdentityStopsLookup() {
        when(mapper.list(anyList(), any(), anyInt())).thenThrow(new IllegalStateException("sensitive"));
        assertThatThrownBy(() -> service.list(Map.of())).isInstanceOf(QueryException.class)
                .extracting("status").isEqualTo(503);
        reset(mapper);
        doThrow(IdentityException.disabled("停用")).when(handle).requireEnabled();
        assertThatThrownBy(() -> service.list(Map.of())).isInstanceOf(IdentityException.class);
        verifyNoInteractions(mapper);
    }
}
