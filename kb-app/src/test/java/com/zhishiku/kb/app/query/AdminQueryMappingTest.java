package com.zhishiku.kb.app.query;

import com.zhishiku.kb.app.auth.mapper.AuditQueryMapper;
import com.zhishiku.kb.app.auth.mapper.IdentityQueryMapper;
import com.zhishiku.kb.app.auth.model.AuditQueryFilter;
import com.zhishiku.kb.app.auth.model.IdentityAuditRecord;
import com.zhishiku.kb.app.auth.model.IdentityQueryViews.ExternalBinding;
import com.zhishiku.kb.app.auth.model.IdentityQueryViews.PrincipalOption;
import com.zhishiku.kb.app.document.mapper.DocumentAdminQueryMapper;
import com.zhishiku.kb.app.document.model.DocumentAdminView;
import com.zhishiku.kb.app.support.MapperRowStub;
import com.zhishiku.kb.common.security.IdentitySummary;
import com.zhishiku.kb.common.security.PrincipalType;
import com.zhishiku.kb.common.security.RoleSummary;
import org.apache.ibatis.mapping.ParameterMapping;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 仅内存构建映射并用 JDBC 结果集 Stub 实例化 DTO，无连接及 SQL 执行。
 */
class AdminQueryMappingTest {
    @Test
    void documentConstructorAndDynamicRangeParametersAreBound() throws Exception {
        var config = configuration(DocumentAdminQueryMapper.class);
        String id = DocumentAdminQueryMapper.class.getName() + ".list";
        var p = new LinkedHashMap<String, Object>();
        p.put("global", false);
        p.put("spaces", List.of(3L));
        p.put("spaceId", 3L);
        p.put("documentId", 9L);
        p.put("archived", true);
        p.put("afterId", 12L);
        p.put("fetch", 21);
        var row = MapperRowStub.readOne(config, id, p, Map.of("document_id", 9L, "space_id", 3L, "space_archived",
                true), DocumentAdminView.class);
        assertThat(row).isEqualTo(new DocumentAdminView(9, 3, true));
        assertThat(properties(config, id, p)).contains("spaceId", "documentId", "archived", "afterId", "fetch");
        p.put("spaces", List.of());
        config.getMappedStatement(id).getBoundSql(p);
        p.put("global", true);
        config.getMappedStatement(id).getBoundSql(p);
    }

    @Test
    void identityConstructorsAndCandidateBranchesReadOnlyTheirProjection() throws Exception {
        var config = configuration(IdentityQueryMapper.class);
        String prefix = IdentityQueryMapper.class.getName() + ".";
        var p = new LinkedHashMap<String, Object>();
        p.put("like", "%\\_%");
        p.put("departmentId", 3L);
        p.put("enabled", false);
        p.put("afterId", 1L);
        p.put("fetch", 21);
        assertThat(MapperRowStub.readOne(config, prefix + "users", p,
                Map.of("user_id", 4L, "username", "u", "display_name", "用户", "department_id", 3L,
                        "clearance_level", (short) 6, "enabled", false, "version", 2L), IdentitySummary.class))
                .isEqualTo(new IdentitySummary(4, "u", "用户", 3L, (short) 6, false, 2));
        assertThat(properties(config, prefix + "users", p)).contains("like", "departmentId", "enabled", "afterId",
                "fetch");
        p.put("userId", 4L);
        p.put("provider", "oidc");
        assertThat(MapperRowStub.readOne(config, prefix + "roles", p, Map.of("role_id", 2L, "code", "R", "name",
                "角色"), RoleSummary.class).roleId()).isEqualTo(2);
        assertThat(MapperRowStub.readOne(config, prefix + "external", p,
                Map.of("identity_id", 9L, "user_id", 4L, "provider", "oidc", "enabled", false), ExternalBinding.class))
                .isEqualTo(new ExternalBinding(9, 4, "oidc", false));
        for (var type : PrincipalType.values()) {
            p.put("type", type);
            assertThat(MapperRowStub.readOne(config, prefix + "principals", p,
                    Map.of("principal_type", type.name(), "principal_id", 2L, "label", "候选"), PrincipalOption.class))
                    .isEqualTo(new PrincipalOption(type, 2, "候选"));
            assertThat(properties(config, prefix + "principals", p)).containsExactly("like", "afterId", "fetch");
        }
    }

    @Test
    void auditMappingPreservesNullableActorAndTargetAndBindsCompositePosition() throws Exception {
        var config = configuration(AuditQueryMapper.class);
        var event = UUID.randomUUID();
        var time = Instant.parse("2026-10-17T00:00:00.123456Z");
        var filter = new AuditQueryFilter(2L, "AUTH_LOGIN_FAILURE", "login", "auth", 3L, "FAILED", "req", "unknown",
                time.minusSeconds(60), time.plusSeconds(60), time, 9L, 21);
        var p = Map.<String, Object>of("f", filter);
        String id = AuditQueryMapper.class.getName() + ".list";
        var columns = new LinkedHashMap<String, Object>();
        columns.put("id", 8L);
        columns.put("event_id", event);
        columns.put("operation_scope", "AUTH_LOGIN_FAILURE");
        columns.put("request_id", "req");
        columns.put("actor_user_id", null);
        columns.put("target_type", "auth");
        columns.put("target_id", null);
        columns.put("operation", "login");
        columns.put("status", "FAILED");
        columns.put("before_state", "{}");
        columns.put("after_state", "{\"username\":\"unknown\"}");
        columns.put("created_at", time);
        var row = MapperRowStub.readOne(config, id, p, columns, IdentityAuditRecord.class);
        assertThat(row.getEventId()).isEqualTo(event);
        assertThat(row.getCreatedAt()).isEqualTo(time);
        assertThat(row.getActorUserId()).isNull();
        assertThat(row.getTargetId()).isNull();
        assertThat(properties(config, id, p)).contains("f.from", "f.to", "f.actorUserId", "f.operationScope", "f" +
                        ".operation",
                "f.targetType", "f.targetId", "f.status", "f.requestId", "f.loginUsername", "f.beforeTime", "f" +
                        ".beforeId", "f.fetch");
        assertThat(MapperRowStub.readOne(config, AuditQueryMapper.class.getName() + ".detail", Map.of("eventId",
                event), columns, IdentityAuditRecord.class).getEventId()).isEqualTo(event);
    }

    private static Configuration configuration(Class<?> mapper) {
        var config = new Configuration();
        config.setMapUnderscoreToCamelCase(true);
        config.addMapper(mapper);
        return config;
    }

    private static List<String> properties(Configuration config, String id, Map<String, Object> parameters) {
        return config.getMappedStatement(id).getBoundSql(parameters).getParameterMappings().stream().map(ParameterMapping::getProperty).toList();
    }
}
