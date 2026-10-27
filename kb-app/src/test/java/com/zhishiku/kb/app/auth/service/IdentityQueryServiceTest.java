package com.zhishiku.kb.app.auth.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhishiku.kb.app.auth.mapper.IdentityQueryMapper;
import com.zhishiku.kb.app.auth.model.IdentityQueryViews.ExternalBinding;
import com.zhishiku.kb.app.auth.model.IdentityQueryViews.PrincipalOption;
import com.zhishiku.kb.app.auth.model.IdentityQueryViews.UserLabel;
import com.zhishiku.kb.app.auth.model.Permission;
import com.zhishiku.kb.app.query.QueryException;
import com.zhishiku.kb.app.query.QueryParameters;
import com.zhishiku.kb.common.security.IdentitySummary;
import com.zhishiku.kb.common.security.PrincipalType;
import com.zhishiku.kb.common.security.RoleSummary;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.LongStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class IdentityQueryServiceTest {
    @Test
    void userSearchPassesEscapedLiteralAndExactDepartmentThenBindsCursor() {
        var mapper = mock(IdentityQueryMapper.class);
        var access = mock(AdminQueryAccess.class);
        var service = new IdentityQueryService(mapper, access);
        var first = new IdentitySummary(2, "a", "甲", 3L, (short) 0, false, 1);
        when(mapper.users("%甲\\%\\_%", 3L, false, null, 2)).thenReturn(List.of(first,
                new IdentitySummary(4, "b", "乙", 3L, (short) 0, true, 1)));
        var page = service.users(Map.of("q", List.of(" 甲%_ "), "departmentId", List.of("3"), "enabled", List.of(
                "false"), "limit", List.of("1")));
        assertThat(page.items()).containsExactly(first);
        verify(access).require(Permission.IDENTITY_USER_READ);
        assertThatThrownBy(() -> service.users(Map.of("q", List.of("changed"), "cursor", List.of(page.nextCursor())))).isInstanceOf(QueryException.class);
        when(mapper.users(null, null, null, null, 21)).thenReturn(List.of());
        assertThat(service.users(Map.of()).items()).isEmpty();
        assertThatThrownBy(() -> service.users(Map.of("q", List.of("字".repeat(129))))).isInstanceOf(QueryException.class);
    }

    @Test
    void persistentRolesIncludeDisabledTargetAndPageCannotMoveToOtherUser() {
        var mapper = mock(IdentityQueryMapper.class);
        var access = mock(AdminQueryAccess.class);
        var service = new IdentityQueryService(mapper, access);
        when(mapper.userExists(8)).thenReturn(true);
        when(mapper.roles(8, null, 2)).thenReturn(List.of(new RoleSummary(1, "R1", "甲"), new RoleSummary(2, "R2",
                "乙")));
        var page = service.roles("8", Map.of("limit", List.of("1")));
        assertThat(page.items().getFirst()).isEqualTo(new RoleSummary(1, "R1", "甲"));
        assertThatThrownBy(() -> service.roles("9", Map.of("cursor", List.of(page.nextCursor())))).isInstanceOf(QueryException.class);
        when(mapper.roles(8, null, 21)).thenReturn(List.of());
        assertThat(service.roles("8", Map.of()).items()).isEmpty();
        assertThatThrownBy(() -> service.roles("9", Map.of())).isInstanceOfSatisfying(QueryException.class,
                e -> assertThat(e.status()).isEqualTo(404));
    }

    @Test
    void externalBindingsUseIndependentReadAndFixedProjection() throws Exception {
        var mapper = mock(IdentityQueryMapper.class);
        var access = mock(AdminQueryAccess.class);
        var service = new IdentityQueryService(mapper, access);
        when(mapper.userExists(8)).thenReturn(true);
        when(mapper.external(8, "oidc", null, 21)).thenReturn(List.of(new ExternalBinding(5, 8, "oidc", false)));
        var result = service.external("8", Map.of("provider", List.of("oidc")));
        var json = new ObjectMapper().valueToTree(result.items().getFirst());
        assertThat(json.size()).isEqualTo(4);
        assertThat(json.toString()).doesNotContain("subject");
        verify(access).require(Permission.IDENTITY_EXTERNAL_READ);
        String managed = "sso-v1:feishu:" + "a".repeat(64);
        when(mapper.external(8, managed, null, 21)).thenReturn(List.of(new ExternalBinding(6, 8, managed, true)));
        assertThat(service.external("8", Map.of("provider", List.of(managed))).items())
                .singleElement().satisfies(binding -> assertThat(binding.provider()).isEqualTo(managed));
        when(mapper.external(8, null, null, 21)).thenReturn(List.of());
        assertThat(service.external("8", Map.of()).items()).isEmpty();
        assertThatThrownBy(() -> service.external("9", Map.of())).isInstanceOfSatisfying(QueryException.class,
                e -> assertThat(e.status()).isEqualTo(404));
    }

    @Test
    void deniedRequestsDoNotProbeTargetOrValidateTargetFirst() {
        var mapper = mock(IdentityQueryMapper.class);
        var access = mock(AdminQueryAccess.class);
        doThrow(QueryException.forbidden()).when(access).require(Permission.IDENTITY_USER_READ);
        doThrow(QueryException.forbidden()).when(access).require(Permission.IDENTITY_EXTERNAL_READ);
        var service = new IdentityQueryService(mapper, access);
        assertThatThrownBy(() -> service.roles("invalid", Map.of())).isInstanceOfSatisfying(QueryException.class,
                e -> assertThat(e.status()).isEqualTo(403));
        assertThatThrownBy(() -> service.external("invalid", Map.of())).isInstanceOfSatisfying(QueryException.class,
                e -> assertThat(e.status()).isEqualTo(403));
        verifyNoInteractions(mapper);
    }

    @Test
    void principalCandidatesAreMinimalAndBoundToResourceAndType() throws Exception {
        var mapper = mock(IdentityQueryMapper.class);
        var service = new IdentityQueryService(mapper, mock(AdminQueryAccess.class));
        for (PrincipalType type : PrincipalType.values()) {
            when(mapper.principals(type, "%\\_%", null, 2)).thenReturn(List.of(
                    new PrincipalOption(type, 4, "候选甲"), new PrincipalOption(type, 5, "候选乙")));
            var page = service.principals("space-principals", 3, candidate(Map.of("principalType",
                    List.of(type.name()), "q", List.of("_"), "limit", List.of("1"))));
            assertThat(new ObjectMapper().valueToTree(page.items().getFirst()).size()).isEqualTo(3);
            assertThatThrownBy(() -> service.principals("space-principals", 8, candidate(Map.of("principalType",
                    List.of(type.name()), "q", List.of("_"), "cursor", List.of(page.nextCursor())))))
                    .isInstanceOf(QueryException.class);
            assertThatThrownBy(() -> service.principals("document-principals", 3, candidate(Map.of("principalType",
                    List.of(type.name()), "q", List.of("_"), "cursor", List.of(page.nextCursor())))))
                    .isInstanceOf(QueryException.class);
            String otherType = type == PrincipalType.USER ? "ROLE" : "USER";
            assertThatThrownBy(() -> service.principals("space-principals", 3, candidate(Map.of("principalType",
                    List.of(otherType), "q", List.of("_"), "cursor", List.of(page.nextCursor())))))
                    .isInstanceOf(QueryException.class);
        }
        assertThatThrownBy(() -> service.principals("space-principals", 3, candidate(Map.of("principalType", List.of(
                "user"))))).isInstanceOf(QueryException.class);
        assertThatThrownBy(() -> service.principals("space-principals", 3, candidate(Map.of()))).isInstanceOf(QueryException.class);
    }

    @Test
    void labelsAreAnExactBoundedBatchAndInconsistentRowsAreFailures() {
        var mapper = mock(IdentityQueryMapper.class);
        var access = mock(AdminQueryAccess.class);
        var service = new IdentityQueryService(mapper, access);
        when(mapper.labels(List.of(7L, 8L))).thenReturn(List.of(new UserLabel(8, "乙"), new UserLabel(7, "甲")));
        assertThat(service.labels(List.of(8L, 7L, 8L))).isEqualTo(Map.of(7L, "甲", 8L, "乙"));
        assertThat(service.labels(List.of())).isEmpty();
        verify(mapper).labels(List.of(7L, 8L));
        // 只取请求 ID；不经管理权限，调用方须已按具体资源授权。
        verifyNoInteractions(access);
        List<Long> tooMany = LongStream.rangeClosed(1, 201).boxed().toList();
        assertThatThrownBy(() -> service.labels(tooMany)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.labels(Arrays.asList(7L, null))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.labels(List.of(0L))).isInstanceOf(IllegalArgumentException.class);
        for (var rows : List.of(List.of(new UserLabel(9, "丙")), List.of(new UserLabel(7, "甲"), new UserLabel(7, "甲")),
                List.of(new UserLabel(7, null)))) {
            when(mapper.labels(List.of(7L))).thenReturn(rows);
            assertThatThrownBy(() -> service.labels(List.of(7L))).isInstanceOf(IllegalStateException.class);
        }
    }

    private static QueryParameters candidate(Map<String, List<String>> parameters) {
        return new QueryParameters(parameters, "principalType", "q", "limit", "cursor");
    }
}
