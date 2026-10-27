package com.zhishiku.kb.app.audit.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhishiku.kb.app.auth.model.IdentityAuditRecord;
import com.zhishiku.kb.app.auth.service.AuditScope;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class AuditSummaryProjectorTest {
    @Test
    void eachRegisteredScopeAcceptsProductionShapeAndDropsFreeTextAndUnknownNesting() throws Exception {
        var projector = new AuditSummaryProjector();
        for (AuditScope scope : AuditScope.values()) {
            String after = switch (scope) {
                case AUTH_LOGIN_SUCCESS, AUTH_LOGIN_FAILURE ->
                        "{\"username\":\"attempted\",\"token\":\"private-token\"}";
                case SSO_LOGIN -> "{\"platform\":\"feishu\",\"resultCode\":\"OK\",\"subject\":\"private-subject\"}";
                case SSO_BIND -> "{\"platform\":\"wecom\",\"resultCode\":\"ALREADY_BOUND\",\"bound\":true}";
                case USER_CREATE ->
                        "{\"username\":\"private-name\",\"departmentId\":2,\"clearanceLevel\":4,\"enabled\":true," +
                                "\"version\":0,\"mustChangePassword\":true}";
                case USER_MOVE_DEPARTMENT, USER_ENABLE, USER_DISABLE ->
                        "{\"departmentId\":2,\"enabled\":true,\"version\":1}";
                case USER_CLEARANCE_CHANGE -> "{\"clearanceLevel\":4,\"version\":2}";
                case USER_PASSWORD_CHANGE, USER_PASSWORD_RESET ->
                        "{\"mustChangePassword\":true,\"password\":\"private-password\"}";
                case USER_ROLE_ASSIGN -> "{\"roleId\":3,\"roleCode\":\"private-code\",\"created\":true}";
                case USER_ROLE_REVOKE -> "{\"roleId\":3,\"bound\":false}";
                case ROLE_CREATE, ROLE_RENAME, ROLE_DELETE ->
                        "{\"roleId\":3,\"code\":\"private-code\",\"name\":\"private-name\"," +
                                "\"permissions\":[\"private-permission\"]}";
                case ROLE_PERMISSION_GRANT, ROLE_PERMISSION_REVOKE ->
                        "{\"permission\":\"audit:read\",\"permissions\":[\"private-permission\"]}";
                case EXTERNAL_BIND, EXTERNAL_UNBIND ->
                        "{\"provider\":\"private-provider\",\"subject\":\"private-subject\"}";
                case DEPARTMENT_CREATE, DEPARTMENT_RENAME, DEPARTMENT_MOVE, DEPARTMENT_ENABLE, DEPARTMENT_DISABLE ->
                        "{\"parentId\":2,\"enabled\":true,\"version\":1,\"name\":\"private-name\"}";
                case SPACE_CREATE, SPACE_UPDATE, SPACE_ARCHIVE, SPACE_UNARCHIVE, SPACE_DELETE ->
                        "{\"archived\":true,\"version\":1,\"name\":\"private-name\"}";
                case SPACE_PUBLICATION_POLICY -> "{\"publicationPolicy\":\"REVIEW_REQUIRED\",\"version\":1}";
                case SPACE_ACL_GRANT, SPACE_ACL_REVOKE ->
                        "{\"spaceId\":2,\"principalKey\":\"1:3\",\"permission\":\"manage\"}";
                case DOC_ACL_GRANT, DOC_ACL_REVOKE ->
                        "{\"documentId\":2,\"principalKey\":\"3:3\",\"permission\":\"view\"}";
                case DOCUMENT_RELOCATE ->
                        "{\"spaceId\":2,\"clearedExceptionCount\":4,\"clearanceBlockedPrincipalCount\":2," +
                                "\"clearedExceptions\":[\"private-name\"]}";
                case DOCUMENT_SECURITY_LEVEL_CHANGE ->
                        "{\"securityLevel\":4,\"securityVersion\":1,\"expectedSecurityVersion\":0," +
                                "\"title\":\"private-title\"}";
                case PARSE_DEAD_LETTER_QUERY, PARSE_DEAD_LETTER_REPLAY ->
                        "{\"status\":\"REPLAYED\",\"reason\":\"private-reason\"}";
            };
            var row = row(scope.name(), "{}", after);
            var detail = projector.detail(row);
            assertThat(detail.summaryAvailable()).as(scope.name()).isTrue();
            String json = new ObjectMapper().findAndRegisterModules().writeValueAsString(detail);
            assertThat(json).doesNotContain("private-", "beforeState", "afterState", "subject", "objectKey",
                    "password\"", "reason\"");
        }
    }

    @Test
    void malformedUnknownAndWronglyTypedSummariesSafelyDegrade() {
        var projector = new AuditSummaryProjector();
        for (String raw : List.of("[]", "{", "null", "{} {}", "{\"enabled\":\"true\"}",
                "{\"version\":9223372036854775808}", "{\"version\":-1}", "{\"enabled\":true,\"enabled\":false}")) {
            var detail = projector.detail(row("USER_ENABLE", "{}", raw));
            assertThat(detail.summaryAvailable()).as(raw).isFalse();
            assertThat(detail.changes()).isEmpty();
        }
        assertThat(projector.detail(row("FUTURE_SCOPE", "{}", "{}")).summaryAvailable()).isFalse();
        assertThat(projector.detail(row("USER_ENABLE", "{}",
                "{\"enabled\":true,\"unknown\":{\"token\":\"private\"}}")).changes())
                .hasSize(1).allSatisfy(change -> assertThat(change.field()).isEqualTo("enabled"));
        assertThat(projector.metadata(row("AUTH_LOGIN_FAILURE", "{}", "{\"username\":\"" + "x".repeat(129) + "\"}")).attemptedUsername()).isNull();
        assertThat(projector.metadata(row("USER_CREATE", "{}", "{\"username\":\"private\"}")).attemptedUsername()).isNull();
    }

    @Test
    void rolePermissionDirectionAndUnchangedBindingIdentifiersRemainVisible() {
        var projector = new AuditSummaryProjector();
        String grantedBefore = "{\"permission\":\"audit:read\",\"permissions\":[]}";
        String grantedAfter = "{\"permission\":\"audit:read\",\"permissions\":[\"audit:read\"]}";
        var grant = projector.detail(row("ROLE_PERMISSION_GRANT", grantedBefore, grantedAfter)).changes().getFirst();
        assertThat(grant.field()).isEqualTo("permission");
        assertThat(grant.before()).isNull();
        assertThat(grant.after()).isEqualTo("audit:read");
        var revoke = projector.detail(row("ROLE_PERMISSION_REVOKE", grantedAfter, grantedBefore)).changes().getFirst();
        assertThat(revoke.before()).isEqualTo("audit:read");
        assertThat(revoke.after()).isNull();
        var unbind = projector.detail(row("USER_ROLE_REVOKE", "{\"roleId\":7,\"bound\":true}", "{\"roleId\":7," +
                "\"bound\":false}"));
        assertThat(unbind.changes()).anySatisfy(change -> {
            assertThat(change.field()).isEqualTo("roleId");
            assertThat(change.before()).isEqualTo(7L);
            assertThat(change.after()).isEqualTo(7L);
        });
    }

    private static IdentityAuditRecord row(String scope, String before, String after) {
        return new IdentityAuditRecord(1L, UUID.randomUUID(), scope, "request", null, "user", null,
                "operation", "SUCCESS", before, after, "private-reason", Instant.EPOCH);
    }

    @Test
    void invalidSsoEnumsDegradeAndNeverAddLoginUsername() {
        var projector = new AuditSummaryProjector();
        assertThat(projector.detail(row("SSO_LOGIN", "{}", "{\"platform\":\"attacker\",\"resultCode\":\"OK\"}")).summaryAvailable()).isFalse();
        assertThat(projector.detail(row("SSO_BIND", "{}", "{\"platform\":\"wecom\",\"resultCode\":\"private\"}")).summaryAvailable()).isFalse();
        assertThat(projector.metadata(row("SSO_LOGIN", "{}", "{\"username\":\"private\"}")).attemptedUsername()).isNull();
    }
}
