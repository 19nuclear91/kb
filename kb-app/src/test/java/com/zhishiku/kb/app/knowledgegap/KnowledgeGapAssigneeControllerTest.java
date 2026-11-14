package com.zhishiku.kb.app.knowledgegap;

import cn.dev33.satoken.stp.StpUtil;
import com.zhishiku.kb.app.auth.mapper.IdentityDepartmentMapper;
import com.zhishiku.kb.app.auth.mapper.KnowledgeGapAssigneeMapper;
import com.zhishiku.kb.app.auth.mapper.PermissionMapper;
import com.zhishiku.kb.app.auth.mapper.RolePermissionMapper;
import com.zhishiku.kb.app.auth.model.KnowledgeGapAssigneeViews;
import com.zhishiku.kb.app.auth.service.AdminQueryAccess;
import com.zhishiku.kb.app.auth.service.IdentityService;
import com.zhishiku.kb.app.auth.service.KnowledgeGapAssigneeService;
import com.zhishiku.kb.app.auth.service.PermissionService;
import com.zhishiku.kb.app.config.KnowledgeGapResponseFilter;
import com.zhishiku.kb.app.knowledgegap.mapper.KnowledgeGapMapper;
import com.zhishiku.kb.app.knowledgegap.model.KnowledgeGapCategory;
import com.zhishiku.kb.app.knowledgegap.model.KnowledgeGapInputs;
import com.zhishiku.kb.app.knowledgegap.model.KnowledgeGapRows;
import com.zhishiku.kb.app.knowledgegap.model.KnowledgeGapSource;
import com.zhishiku.kb.app.knowledgegap.model.KnowledgeGapState;
import com.zhishiku.kb.app.knowledgegap.service.KnowledgeGapAccess;
import com.zhishiku.kb.app.knowledgegap.service.KnowledgeGapAssigneeQueryService;
import com.zhishiku.kb.app.knowledgegap.web.KnowledgeGapAdvice;
import com.zhishiku.kb.app.knowledgegap.web.KnowledgeGapAssigneeController;
import com.zhishiku.kb.app.query.QueryCursor;
import com.zhishiku.kb.app.query.QueryException;
import com.zhishiku.kb.app.query.QueryParameters;
import com.zhishiku.kb.common.api.ApiException;
import com.zhishiku.kb.common.security.IdentityException;
import com.zhishiku.kb.common.security.ResolvedPrincipal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** 内存请求/响应和身份、仓储替身，不启动 Spring、HTTP 或任何外部服务。 */
class KnowledgeGapAssigneeControllerTest {
    private final IdentityService identity = mock(IdentityService.class);
    private final IdentityDepartmentMapper identities = mock(IdentityDepartmentMapper.class);
    private final RolePermissionMapper roles = mock(RolePermissionMapper.class);
    private final KnowledgeGapMapper gaps = mock(KnowledgeGapMapper.class);
    private final KnowledgeGapAssigneeMapper assignees = mock(KnowledgeGapAssigneeMapper.class);
    private final KnowledgeGapAccess access = new KnowledgeGapAccess(new AdminQueryAccess(
            new PermissionService(mock(PermissionMapper.class), identity, roles), identities), gaps);
    private final KnowledgeGapAssigneeController controller = new KnowledgeGapAssigneeController(access,
            new KnowledgeGapAssigneeQueryService(access, new KnowledgeGapAssigneeService(assignees)));

    @BeforeEach
    void setUp() {
        when(identity.resolvePrincipal(7)).thenReturn(new ResolvedPrincipal(7, 2L, (short) 1, List.of(3L), List.of(2L)));
        when(identities.findMustChangePasswordById(7)).thenReturn(false);
        when(roles.findUserPermissions(7)).thenReturn(List.of("knowledge:gap:assign", "knowledge:gap:read"));
        when(gaps.findVisible(11, 7)).thenReturn(new KnowledgeGapRows.Gap(11, 8, KnowledgeGapCategory.OTHER,
                KnowledgeGapSource.MANUAL, "问题", "GAP_SHARE_V1", KnowledgeGapState.IN_PROGRESS,
                1, 9L, 1, null, null, Instant.EPOCH, Instant.EPOCH));
    }

    @Test
    void postBodySearchWorksWithoutIdentityDirectoryAndUsesScopedCursor() throws Exception {
        try (var login = mockStatic(StpUtil.class)) {
            login.when(StpUtil::getLoginIdAsLong).thenReturn(7L);
            when(assignees.candidates("%处理%", null, 2)).thenReturn(List.of(
                    new KnowledgeGapAssigneeViews.Candidate(9, "处理人A"), new KnowledgeGapAssigneeViews.Candidate(10, "处理人B")));
            var result = controller.search("11", request("{\"nameQuery\":\"处理\",\"limit\":1}"));
            assertThat(result.code()).isEqualTo(200);
            assertThat(result.data().items()).containsExactly(new KnowledgeGapAssigneeViews.Candidate(9, "处理人A"));
            assertThat(result.data().hasMore()).isTrue();
            var cursor = QueryCursor.decode(result.data().nextCursor(), "knowledge-gap-assignees");
            cursor.verify("knowledge-gap-assignees", QueryParameters.filters("actor", 7L, "gapId", 11L, "nameQuery", "处理"));
            verify(assignees).candidates("%处理%", null, 2);
        }
    }

    @Test
    void missingAssignRejectsBeforeTargetOrBodyReads() {
        try (var login = mockStatic(StpUtil.class)) {
            login.when(StpUtil::getLoginIdAsLong).thenReturn(7L);
            when(roles.findUserPermissions(7)).thenReturn(List.of("knowledge:gap:read", "identity:user:read"));
            assertThatThrownBy(() -> controller.search("11", request("not json")))
                    .isInstanceOfSatisfying(ApiException.class, error -> assertThat(error.httpStatus()).isEqualTo(403));
            verifyNoInteractions(gaps, assignees);
        }
    }

    @Test
    void unknownDuplicateTrailingAndNonIntegerInputAreRejected() {
        try (var login = mockStatic(StpUtil.class)) {
            login.when(StpUtil::getLoginIdAsLong).thenReturn(7L);
            for (String body : List.of("{\"userId\":8}", "{\"nameQuery\":\"A\",\"nameQuery\":\"B\"}", "{} {}",
                    "{\"limit\":1.5}", "{\"limit\":\"20\"}", "{\"limit\":101}", "{\"nameQuery\":[]}",
                    "{\"nameQuery\":\"\\uD800\"}")) {
                assertThatThrownBy(() -> controller.search("11", request(body))).isInstanceOf(ApiException.class);
            }
            var query = request("{}");
            query.setQueryString("nameQuery=私密名称");
            assertThatThrownBy(() -> controller.search("11", query)).isInstanceOf(ApiException.class);
            verifyNoInteractions(assignees);
        }
    }

    @Test
    void crossActorGapOrNameCursorFailsBeforePersonnelQuery() {
        try (var login = mockStatic(StpUtil.class)) {
            login.when(StpUtil::getLoginIdAsLong).thenReturn(7L);
            for (Map<String, Object> filters : List.of(
                    QueryParameters.filters("actor", 8L, "gapId", 11L, "nameQuery", "A"),
                    QueryParameters.filters("actor", 7L, "gapId", 12L, "nameQuery", "A"),
                    QueryParameters.filters("actor", 7L, "gapId", 11L, "nameQuery", "B"))) {
                String cursor = QueryCursor.encode("knowledge-gap-assignees", filters, 9);
                assertThatThrownBy(() -> controller.search("11", request("{\"nameQuery\":\"A\",\"cursor\":\"" + cursor + "\"}")))
                        .isInstanceOf(QueryException.class);
            }
            verifyNoInteractions(assignees);
        }
    }

    @Test
    void bodyLimitAndIllegalTargetAreRejectedWithoutCandidateReads() {
        try (var login = mockStatic(StpUtil.class)) {
            login.when(StpUtil::getLoginIdAsLong).thenReturn(7L);
            assertThatThrownBy(() -> controller.search("11", request(" ".repeat(KnowledgeGapInputs.MAX_BODY_BYTES) + "{}")))
                    .isInstanceOfSatisfying(QueryException.class, error -> assertThat(error.status()).isEqualTo(413));
            for (String target : List.of("0", "abc", "01", "9223372036854775808")) {
                assertThatThrownBy(() -> controller.search(target, request("{}"))).isInstanceOf(QueryException.class);
            }
            verifyNoInteractions(assignees);
        }
    }

    @Test
    void responsePolicyAndFixedErrorProjectionDoNotExposeNames() throws Exception {
        var response = new MockHttpServletResponse();
        new KnowledgeGapResponseFilter().doFilter(request("{}"), response, (req, res) -> response.setStatus(401));
        assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
        var advice = new KnowledgeGapAdvice();
        List<Exception> errors = List.of(ApiException.badRequest("秘密名称"), IdentityException.unauthorized("秘密会话"),
                ApiException.forbidden("秘密角色"), ApiException.notFound("秘密问题"), ApiException.conflict("秘密状态"),
                new QueryException(413, "秘密正文"), new IllegalStateException("秘密SQL"));
        List<Integer> statuses = List.of(400, 401, 403, 404, 409, 413, 503);
        for (int index = 0; index < errors.size(); index++) {
            var projected = advice.failure(errors.get(index));
            assertThat(projected.getStatusCode().value()).isEqualTo(statuses.get(index));
            assertThat(projected.getHeaders().getCacheControl()).isEqualTo("no-store");
            assertThat(projected.getBody().message()).doesNotContain("秘密");
            assertThat(projected.getBody().data()).isNull();
        }
    }

    private static MockHttpServletRequest request(String body) {
        var request = new MockHttpServletRequest("POST", "/api/admin/knowledge-gaps/11/assignees/search");
        request.setContentType("application/json");
        request.setContent(body.getBytes(StandardCharsets.UTF_8));
        return request;
    }
}
