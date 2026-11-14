package com.zhishiku.kb.app.knowledgegap;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhishiku.kb.app.auth.mapper.KnowledgeGapAssigneeMapper;
import com.zhishiku.kb.app.auth.mapper.PermissionMapper;
import com.zhishiku.kb.app.auth.mapper.RolePermissionMapper;
import com.zhishiku.kb.app.auth.model.KnowledgeGapAssigneeViews;
import com.zhishiku.kb.app.auth.service.KnowledgeGapAssigneeService;
import com.zhishiku.kb.app.auth.service.PermissionService;
import com.zhishiku.kb.app.query.QueryException;
import com.zhishiku.kb.app.support.MapperRowStub;
import com.zhishiku.kb.common.api.ApiException;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** 无数据库、身份目录或网络客户端的受控候选测试。 */
class KnowledgeGapAssigneeTest {
    private final KnowledgeGapAssigneeMapper mapper = mock(KnowledgeGapAssigneeMapper.class);
    private final KnowledgeGapAssigneeService service = new KnowledgeGapAssigneeService(mapper);
    private final PermissionMapper principals = mock(PermissionMapper.class);
    private final RolePermissionMapper roles = mock(RolePermissionMapper.class);
    private final PermissionService permissions = new PermissionService(principals, null, roles);

    @Test
    void everyEligibilityFactIsRequired() {
        assertThat(new KnowledgeGapAssigneeViews.Qualification(7, true, true, false, true).eligible()).isTrue();
        for (var qualification : List.of(
                new KnowledgeGapAssigneeViews.Qualification(7, false, true, false, true),
                new KnowledgeGapAssigneeViews.Qualification(7, true, false, false, true),
                new KnowledgeGapAssigneeViews.Qualification(7, true, true, true, true),
                new KnowledgeGapAssigneeViews.Qualification(7, true, true, false, false))) {
            when(mapper.qualification(7)).thenReturn(qualification);
            assertStatus(409, () -> service.requireEligible(7));
        }
        when(mapper.qualification(7)).thenReturn(null);
        assertStatus(409, () -> service.requireEligible(7));
    }

    @Test
    void candidateOnlyContainsExactIdentityAndLabelWithoutDirectoryPermission() throws Exception {
        var actor = actor();
        when(mapper.candidates("%名字\\%\\_%", null, 21)).thenReturn(List.of(
                new KnowledgeGapAssigneeViews.Candidate(9007199254740993L, "名字%_")));
        var results = service.candidates(actor, " 名字%_ ", null, 21);
        assertThat(results).hasSize(1);
        assertThat(results.getFirst().userId()).isEqualTo(9007199254740993L);
        var json = new ObjectMapper().valueToTree(results.getFirst());
        assertThat(json.size()).isEqualTo(2);
        assertThat(json.has("userId")).isTrue();
        assertThat(json.has("label")).isTrue();
        assertThat(actor.functionalPermissions()).doesNotContain("identity:user:read");
        verify(mapper).candidates("%名字\\%\\_%", null, 21);
    }

    @Test
    void candidateSnapshotDoesNotAuthorizeLaterAssignment() {
        var actor = actor();
        when(mapper.candidates(null, null, 21)).thenReturn(List.of(new KnowledgeGapAssigneeViews.Candidate(7, "处理人")));
        when(mapper.qualification(7)).thenReturn(new KnowledgeGapAssigneeViews.Qualification(7, true, true, false, true));
        service.candidates(actor, null, null, 21);
        service.requireEligible(7);
        when(mapper.qualification(7)).thenReturn(new KnowledgeGapAssigneeViews.Qualification(7, true, true, false, false));
        assertStatus(409, () -> service.requireEligible(7));
        verify(mapper, times(2)).qualification(7);
    }

    @Test
    void unauthorizedSearchAndUnsafeDependencyResultsAreRejected() {
        actor();
        when(roles.findUserPermissions(9)).thenReturn(List.of("knowledge:gap:read", "identity:user:read"));
        assertStatus(403, () -> service.candidates(permissions.principalOf(9), null, null, 21));
        verifyNoInteractions(mapper);
        when(roles.findUserPermissions(9)).thenReturn(List.of("knowledge:gap:assign"));
        when(mapper.candidates(null, null, 21)).thenReturn(List.of(new KnowledgeGapAssigneeViews.Candidate(8, "A"),
                new KnowledgeGapAssigneeViews.Candidate(8, "A")));
        assertStatus(503, () -> service.candidates(permissions.principalOf(9), null, null, 21));
        when(mapper.qualification(7)).thenThrow(new DataAccessResourceFailureException("sensitive-jdbc"));
        assertThatThrownBy(() -> service.requireEligible(7)).hasMessage("处理人候选暂不可用");
    }

    @Test
    void mybatisUsesRoleUnionAndMinimumProjectionBeforePagination() throws Exception {
        var configuration = MapperRowStub.configuration("/mapper/KnowledgeGapAssigneeMapper.xml");
        String namespace = KnowledgeGapAssigneeMapper.class.getName() + ".";
        var parameters = new LinkedHashMap<String, Object>();
        parameters.put("like", "%名字%");
        parameters.put("afterId", 3L);
        parameters.put("fetch", 21);
        var bound = configuration.getMappedStatement(namespace + "candidates").getBoundSql(parameters);
        String sql = bound.getSql().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
        assertThat(sql).contains("select u.id as user_id, u.display_name as label", "u.enabled and d.enabled",
                "not u.must_change_password", "rp.permission = 'knowledge:gap:handle'", "exists (select 1",
                "u.id > ?", "order by u.id asc limit ?");
        assertThat(sql).doesNotContain("role_admin", "username", "clearance", "sys_user_identity");
        assertThat(bound.getParameterMappings()).extracting("property").containsExactly("like", "afterId", "fetch");
        var mapped = MapperRowStub.readOne(configuration, namespace + "candidates", parameters,
                Map.of("user_id", 9007199254740993L, "label", "最小标签"), KnowledgeGapAssigneeViews.Candidate.class);
        assertThat(mapped.userId()).isEqualTo(9007199254740993L);
        var qualification = MapperRowStub.readOne(configuration, namespace + "qualification", Map.of("userId", 7L),
                Map.of("user_id", 7L, "enabled", true, "department_enabled", true,
                        "password_change_required", false, "handle_permission", true),
                KnowledgeGapAssigneeViews.Qualification.class);
        assertThat(qualification.eligible()).isTrue();
    }

    @Test
    void nameAndFetchAreBoundedWithoutTruncatingUnicode() {
        assertThat(KnowledgeGapAssigneeService.normalizeName("😀".repeat(128))).hasSize(256);
        for (String name : new String[]{"😀".repeat(129), "\uD800", "a\0b"}) {
            assertThatThrownBy(() -> KnowledgeGapAssigneeService.normalizeName(name)).isInstanceOf(QueryException.class);
        }
        var actor = actor();
        assertThatThrownBy(() -> service.candidates(actor, null, null, 102)).isInstanceOf(QueryException.class);
        verifyNoInteractions(mapper);
    }

    private PermissionService.PrincipalHandle actor() {
        when(principals.findClearanceLevel(9)).thenReturn((short) 1);
        when(principals.findRoleIds(9)).thenReturn(List.of(3L, 4L));
        when(principals.findDeptChainIds(9)).thenReturn(List.of(2L));
        when(roles.findUserPermissions(9)).thenReturn(List.of("knowledge:gap:assign", "knowledge:gap:assign"));
        return permissions.principalOf(9);
    }

    private static void assertStatus(int status, Runnable action) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(ApiException.class,
                failure -> assertThat(failure.httpStatus()).isEqualTo(status));
    }
}
