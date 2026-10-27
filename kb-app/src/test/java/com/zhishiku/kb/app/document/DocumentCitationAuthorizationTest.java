package com.zhishiku.kb.app.document;

import com.zhishiku.kb.app.auth.mapper.PermissionMapper;
import com.zhishiku.kb.app.auth.service.IdentityService;
import com.zhishiku.kb.app.auth.service.PermissionService;
import com.zhishiku.kb.app.document.mapper.DocumentMapper;
import com.zhishiku.kb.app.document.model.DocumentCitationRow;
import com.zhishiku.kb.app.document.model.DocumentCitationView.Status;
import com.zhishiku.kb.app.document.service.DocumentCitationService;
import com.zhishiku.kb.common.api.ApiException;
import com.zhishiku.kb.common.security.PermissionScope;
import com.zhishiku.kb.common.security.ResolvedPrincipal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyShort;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 用独立权限与查询替身验证每请求鉴权，不执行实际 ACL、密级或空间变更。
 */
class DocumentCitationAuthorizationTest {
    private final DocumentMapper mapper = mock(DocumentMapper.class);
    private final PermissionService permissions = mock(PermissionService.class);
    private final DocumentCitationService service = new DocumentCitationService(mapper, permissions);

    @BeforeEach
    void sameSubjectAcrossPermissionBits() {
        when(permissions.principalOf(7L)).thenReturn(mock(PermissionService.PrincipalHandle.class));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("revocations")
    void nextRequestUsesNewPermissionFactsAndNeverReturnsFallbackAfterRevocation(
            String change, PermissionScope after) {
        PermissionScope before = PermissionScope.of(7L, (short) 2, List.of(3L), List.of("1:7", "2:8", "3:9"));
        when(permissions.resolve(any(PermissionService.PrincipalHandle.class), eq("view")))
                .thenReturn(before, after);
        when(permissions.resolve(any(PermissionService.PrincipalHandle.class), eq("download")))
                .thenReturn(PermissionScope.denyAll(7L));
        when(mapper.findCitation(anyLong(), anyLong(), anyLong(), anyList(), anyList(), anyShort()))
                .thenReturn(visibleRow(), (DocumentCitationRow) null);

        assertThat(service.find(7L, 10L, 20L, 30L).status()).isEqualTo(Status.AVAILABLE);
        assertThatThrownBy(() -> service.find(7L, 10L, 20L, 30L)).as(change)
                .isInstanceOfSatisfying(ApiException.class, error -> {
                    assertThat(error.httpStatus()).isEqualTo(404);
                    assertThat(error.getMessage()).isEqualTo("文档或版本不存在，或当前用户无权访问");
                });
        verify(permissions, times(2)).principalOf(7L);
        verify(permissions, times(2)).resolve(any(PermissionService.PrincipalHandle.class), eq("view"));
        verify(permissions, times(1)).resolve(any(PermissionService.PrincipalHandle.class), eq("download"));
        verify(permissions, never()).resolve(any(PermissionService.PrincipalHandle.class), eq("manage"));
        if (after.seesNothing()) {
            verify(mapper, times(1)).findCitation(anyLong(), anyLong(), anyLong(), anyList(), anyList(), anyShort());
        } else {
            verify(mapper, times(2)).findCitation(anyLong(), anyLong(), anyLong(), anyList(), anyList(), anyShort());
            verify(mapper, times(sameValues(before, after) ? 2 : 1))
                    .findCitation(10L, 20L, 30L, after.visibleSpaceIdList(), Arrays.asList(after.principalKeys()),
                            after.clearanceLevel());
        }
    }

    @Test
    void managementScopeIsNeverUsedToAuthorizeBodyReads() {
        when(permissions.resolve(any(PermissionService.PrincipalHandle.class), eq("view")))
                .thenReturn(PermissionScope.denyAll(7L));
        when(permissions.resolve(any(PermissionService.PrincipalHandle.class), eq("manage")))
                .thenReturn(PermissionScope.of(7L, (short) 3, List.of(3L), List.of("1:7")));
        assertThatThrownBy(() -> service.find(7L, 10L, 20L, 30L)).isInstanceOf(ApiException.class);
        verify(permissions, never()).resolve(any(PermissionService.PrincipalHandle.class), eq("manage"));
        verify(permissions, never()).resolve(any(PermissionService.PrincipalHandle.class), eq("download"));
        verifyNoInteractions(mapper);
    }

    @Test
    void unarchivingIsObservedByTheNextQueryWithoutReusingARejection() {
        var scope = PermissionScope.of(7L, (short) 2, List.of(), List.of("1:7"));
        when(permissions.resolve(any(PermissionService.PrincipalHandle.class), eq("view"))).thenReturn(scope);
        when(permissions.resolve(any(PermissionService.PrincipalHandle.class), eq("download")))
                .thenReturn(PermissionScope.denyAll(7L));
        when(mapper.findCitation(10L, 20L, 30L, List.of(), List.of("1:7"), (short) 2))
                .thenReturn(null, visibleRow());
        assertThatThrownBy(() -> service.find(7L, 10L, 20L, 30L)).isInstanceOf(ApiException.class);
        assertThat(service.find(7L, 10L, 20L, 30L).status()).isEqualTo(Status.AVAILABLE);
        verify(permissions, times(2)).resolve(any(PermissionService.PrincipalHandle.class), eq("view"));
    }

    @Test
    void oneReadResolvesSubjectOnceAndEachPermissionBitOnce() {
        var permissionMapper = mock(PermissionMapper.class);
        var identities = mock(IdentityService.class);
        when(identities.resolvePrincipal(7L))
                .thenReturn(new ResolvedPrincipal(7L, 5L, (short) 2, List.of(), List.of()));
        when(permissionMapper.findVisibleSpaceIds(anyList(), eq("view"))).thenReturn(List.of(3L));
        when(permissionMapper.findVisibleSpaceIds(anyList(), eq("download"))).thenReturn(List.of());
        var realPermissions = new PermissionService(permissionMapper, identities);
        var citationService = new DocumentCitationService(mapper, realPermissions);
        when(mapper.findCitation(10L, 20L, 30L, List.of(3L), List.of("1:7"), (short) 2)).thenReturn(visibleRow());

        assertThat(citationService.find(7L, 10L, 20L, 30L).status()).isEqualTo(Status.AVAILABLE);

        verify(identities, times(1)).resolvePrincipal(7L);
        verify(permissionMapper, never()).findClearanceLevel(anyLong());
        verify(permissionMapper, never()).findRoleIds(anyLong());
        verify(permissionMapper, never()).findDeptChainIds(anyLong());
        verify(permissionMapper, times(2)).findVisibleSpaceIds(anyList(), anyString());
    }

    private static boolean sameValues(PermissionScope first, PermissionScope second) {
        return first.clearanceLevel() == second.clearanceLevel()
                && first.visibleSpaceIdList().equals(second.visibleSpaceIdList())
                && Arrays.equals(first.principalKeys(), second.principalKeys());
    }

    private static Stream<Arguments> revocations() {
        return Stream.of(
                Arguments.of("role removed", PermissionScope.of(7L, (short) 2, List.of(), List.of("1:7", "3:9"))),
                Arguments.of("department changed", PermissionScope.of(7L, (short) 2, List.of(), List.of("1:7",
                        "3:12"))),
                Arguments.of("space ACL revoked", PermissionScope.of(7L, (short) 2, List.of(), List.of("1:7", "2:8",
                        "3:9"))),
                Arguments.of("document exception revoked", PermissionScope.of(7L, (short) 2, List.of(),
                        List.of("1:7"))),
                Arguments.of("user disabled", PermissionScope.denyAll(7L)),
                Arguments.of("primary department disabled", PermissionScope.denyAll(7L)),
                Arguments.of("user clearance lowered", PermissionScope.of(7L, (short) 0, List.of(3L), List.of("1:7"))),
                Arguments.of("document security raised", PermissionScope.of(7L, (short) 2, List.of(3L), List.of("1:7"
                        , "2:8", "3:9"))),
                Arguments.of("document relocated", PermissionScope.of(7L, (short) 2, List.of(3L), List.of("1:7", "2:8"
                        , "3:9"))),
                Arguments.of("space archived despite exception", PermissionScope.of(7L, (short) 2, List.of(),
                        List.of("1:7"))));
    }

    private static DocumentCitationRow visibleRow() {
        return new DocumentCitationRow(10L, "说明", 20L, 1, 30L, 0, "正文",
                null, null, null, null, null, null, null, null);
    }
}
