package com.zhishiku.kb.app.document;

import com.zhishiku.kb.app.auth.service.AdminQueryAccess;
import com.zhishiku.kb.app.auth.service.PermissionService;
import com.zhishiku.kb.app.document.mapper.DocumentFavoriteMapper;
import com.zhishiku.kb.app.document.model.DocumentFavoriteViews;
import com.zhishiku.kb.app.document.service.DocumentFavoriteService;
import com.zhishiku.kb.common.api.ApiException;
import com.zhishiku.kb.common.security.IdentityException;
import com.zhishiku.kb.common.security.PermissionScope;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyShort;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class DocumentFavoriteServiceTest {
    private final AdminQueryAccess access = mock(AdminQueryAccess.class);
    private final PermissionService permissions = mock(PermissionService.class);
    private final PermissionService.PrincipalHandle handle = mock(PermissionService.PrincipalHandle.class);
    private final DocumentFavoriteMapper mapper = mock(DocumentFavoriteMapper.class);
    private final DocumentFavoriteService service = new DocumentFavoriteService(access, permissions, mapper);

    @BeforeEach
    void setup() {
        when(access.current()).thenReturn(handle);
        when(handle.userId()).thenReturn(7L);
        when(permissions.resolve(handle, "view"))
                .thenReturn(PermissionScope.of(7, (short) 2, List.of(), List.of("1:7")));
    }

    @Test
    void putUsesDocumentExceptionScopeAndDoesNotRequireAPublishedVersion() {
        when(mapper.put(7, 11, List.of(), List.of("1:7"), (short) 2)).thenReturn(99L);
        assertThat(service.put("11")).isEqualTo(new DocumentFavoriteViews.State(11, true));
        assertThat(service.put("11").favorited()).isTrue();
        verify(access, times(2)).current();
        verify(mapper, times(2)).put(7, 11, List.of(), List.of("1:7"), (short) 2);
    }

    @Test
    void revokedPutStillChecksAuthorizationEvenForAnExistingFavorite() {
        when(mapper.put(7, 11, List.of(), List.of("1:7"), (short) 2)).thenReturn(99L, (Long) null);
        service.put("11");
        assertThatThrownBy(() -> service.put("11")).isInstanceOfSatisfying(ApiException.class,
                error -> assertThat(error.httpStatus()).isEqualTo(404));
    }

    @Test
    void emptyScopeCannotWrite() {
        when(permissions.resolve(handle, "view")).thenReturn(PermissionScope.denyAll(7));
        assertThatThrownBy(() -> service.put("11")).isInstanceOfSatisfying(ApiException.class,
                error -> assertThat(error.httpStatus()).isEqualTo(404));
        verifyNoInteractions(mapper);
    }

    @Test
    void disabledPrincipalCannotPutOrDelete() {
        doThrow(IdentityException.disabled("停用")).when(handle).requireEnabled();
        assertThatThrownBy(() -> service.put("11")).isInstanceOf(IdentityException.class);
        assertThatThrownBy(() -> service.delete("11")).isInstanceOf(IdentityException.class);
        verifyNoInteractions(mapper, permissions);
    }

    @Test
    void authenticationAndPasswordGatePrecedeAnyWrite() {
        when(access.current()).thenThrow(IdentityException.unauthorized("未登录"),
                IdentityException.passwordChangeRequired("改密"));
        assertThatThrownBy(() -> service.put("11")).isInstanceOfSatisfying(ApiException.class,
                error -> assertThat(error.httpStatus()).isEqualTo(401));
        assertThatThrownBy(() -> service.delete("11")).isInstanceOfSatisfying(ApiException.class,
                error -> assertThat(error.httpStatus()).isEqualTo(403));
        verifyNoInteractions(mapper, permissions);
    }

    @Test
    void dependencyFailureIsUnavailableRatherThanAbsent() {
        when(mapper.put(anyLong(), anyLong(), anyList(), anyList(), anyShort()))
                .thenThrow(new IllegalStateException("private sql"));
        assertThatThrownBy(() -> service.put("11")).isInstanceOfSatisfying(ApiException.class, error -> {
            assertThat(error.httpStatus()).isEqualTo(503);
            assertThat(error.getMessage()).doesNotContain("private sql");
            assertThat(error.getCause()).isNull();
        });
    }

    @Test
    void deleteAlwaysTargetsOnlyCurrentUserAndDoesNotResolveView() {
        when(mapper.delete(7, Long.MAX_VALUE)).thenReturn(1, 0);
        assertThat(service.delete(Long.toString(Long.MAX_VALUE)))
                .isEqualTo(new DocumentFavoriteViews.State(Long.MAX_VALUE, false));
        assertThat(service.delete(Long.toString(Long.MAX_VALUE)).favorited()).isFalse();
        verify(mapper, times(2)).delete(7, Long.MAX_VALUE);
        verifyNoInteractions(permissions);
    }

    @Test
    void failedDeleteIsNotReportedAsSuccess() {
        when(mapper.delete(7, 11)).thenThrow(new IllegalStateException("private sql"));
        assertThatThrownBy(() -> service.delete("11")).isInstanceOfSatisfying(ApiException.class,
                error -> assertThat(error.httpStatus()).isEqualTo(503));
    }
}
