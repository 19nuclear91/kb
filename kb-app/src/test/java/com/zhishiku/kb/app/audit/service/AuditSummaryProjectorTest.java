package com.zhishiku.kb.app.audit.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhishiku.kb.app.audit.model.AuditQueryViews.Change;
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
                case SPACE_ACL_BATCH_GRANT ->
                        "{\"spaceId\":2,\"grantedCount\":3,\"alreadyGrantedCount\":1,\"clearanceBlockedPrincipalCount\":1," +
                                "\"items\":[{\"principalType\":\"USER\",\"principalId\":3,\"permission\":\"view\",\"alreadyGranted\":true}]}";
                case DOC_ACL_GRANT, DOC_ACL_REVOKE ->
                        "{\"documentId\":2,\"principalKey\":\"3:3\",\"permission\":\"view\"}";
                case DOCUMENT_RELOCATE ->
                        "{\"spaceId\":2,\"clearedExceptionCount\":4,\"clearanceBlockedPrincipalCount\":2," +
                                "\"clearedExceptions\":[\"private-name\"]}";
                case DOCUMENT_ADMIN_DELETE ->
                        "{\"documentId\":10,\"spaceId\":2,\"spaceArchived\":false,\"title\":\"private-title\"," +
                                "\"fileName\":\"private-file\",\"objectKey\":\"documents/private-key\"}";
                case DOCUMENT_SECURITY_LEVEL_CHANGE ->
                        "{\"securityLevel\":4,\"securityVersion\":1,\"expectedSecurityVersion\":0," +
                                "\"title\":\"private-title\"}";
                case DOCUMENT_METADATA_UPDATE ->
                        "{\"categoryId\":null,\"tagIds\":[1,2],\"metadataVersion\":1,\"changedFields\":[\"title\"],\"title\":\"private-title\"}";
                case DOCUMENT_CATEGORY_CREATE, DOCUMENT_CATEGORY_UPDATE, DOCUMENT_CATEGORY_DELETE ->
                        "{\"categoryId\":1,\"spaceId\":2,\"parentId\":null,\"sortOrder\":0,\"treeVersion\":1,\"nameChanged\":true,\"name\":\"private-name\"}";
                case DOCUMENT_TAG_CREATE, DOCUMENT_TAG_UPDATE ->
                        "{\"tagId\":1,\"version\":0,\"enabled\":true,\"nameChanged\":true,\"name\":\"private-name\"}";
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
    void batchGrantProjectsCountsWithoutPrincipalsAndKeepsSingleGrantRules() {
        var projector = new AuditSummaryProjector();
        String after = """
                {"spaceId":9223372036854775807,"grantedCount":3,"alreadyGrantedCount":1,
                 "clearanceBlockedPrincipalCount":1,"principalKey":"1:3","permission":"view",
                 "items":[{"principalType":"USER","principalId":3,"permission":"view","alreadyGranted":true}]}
                """;
        var detail = projector.detail(row("SPACE_ACL_BATCH_GRANT", "{}", after));
        assertThat(detail.summaryAvailable()).isTrue();
        assertThat(detail.changes()).containsExactly(
                new Change("spaceId", null, Long.MAX_VALUE),
                new Change("grantedCount", null, 3L),
                new Change("alreadyGrantedCount", null, 1L),
                new Change("clearanceBlockedPrincipalCount", null, 1L));
        assertThat(projector.detail(row("SPACE_ACL_GRANT", "{}", after)).changes()).containsExactly(
                new Change("spaceId", null, Long.MAX_VALUE),
                new Change("principalKey", null, "1:3"),
                new Change("permission", null, "view"));
        assertThat(AuditScope.SPACE_ACL_BATCH_GRANT.targetType()).isEqualTo("space-acl");
        assertThat(projector.detail(row("SPACE_ACL_BATCH_GRANT", "{}", "{\"grantedCount\":-1}"))
                .summaryAvailable()).isFalse();
    }

    @Test
    void commentPermissionIsProjectedForSpaceAndDocumentAclEvents() {
        var projector = new AuditSummaryProjector();
        for (String scope : List.of("SPACE_ACL_GRANT", "SPACE_ACL_REVOKE")) {
            var detail = projector.detail(row(scope, "{}", "{\"spaceId\":2,\"principalKey\":\"3:4\",\"permission\":\"comment\"}"));
            assertThat(detail.summaryAvailable()).as(scope).isTrue();
            assertThat(detail.changes()).as(scope).contains(new Change("permission", null, "comment"));
        }
        for (String scope : List.of("DOC_ACL_GRANT", "DOC_ACL_REVOKE")) {
            var detail = projector.detail(row(scope, "{}", "{\"documentId\":2,\"principalKey\":\"1:3\",\"permission\":\"comment\"}"));
            assertThat(detail.summaryAvailable()).as(scope).isTrue();
            assertThat(detail.changes()).as(scope).contains(new Change("permission", null, "comment"));
        }
        assertThat(projector.detail(row("SPACE_ACL_GRANT", "{}", "{\"spaceId\":2,\"principalKey\":\"1:3\",\"permission\":\"share\"}"))
                .summaryAvailable()).isFalse();
    }

    @Test
    void metadataCollectionsAreBoundedExactAndDoNotExposeContent() throws Exception {
        var projector = new AuditSummaryProjector();
        var detail = projector.detail(row("DOCUMENT_METADATA_UPDATE",
                "{\"tagIds\":[9223372036854775807],\"categoryId\":null,\"metadataVersion\":0}",
                "{\"tagIds\":[],\"categoryId\":null,\"metadataVersion\":1,\"changedFields\":[\"title\",\"tagIds\"],\"summary\":\"private-content\"}"));
        assertThat(detail.summaryAvailable()).isTrue();
        assertThat(detail.changes()).anySatisfy(change -> {
            assertThat(change.field()).isEqualTo("tagIds");
            assertThat(change.before()).isEqualTo(List.of(Long.MAX_VALUE));
            assertThat(change.after()).isEqualTo(List.of());
        });
        assertThat(new ObjectMapper().findAndRegisterModules().writeValueAsString(detail)).doesNotContain("private-content");
        for (String bad : List.of("{\"tagIds\":[1,1]}", "{\"tagIds\":[null]}", "{\"tagIds\":[{}]}",
                "{\"tagIds\":[\"name\"]}", "{\"changedFields\":[\"spaceId\"]}", "{\"changedFields\":[\"title\",\"title\"]}")) {
            assertThat(projector.detail(row("DOCUMENT_METADATA_UPDATE", "{}", bad)).summaryAvailable()).isFalse();
        }
    }

    @Test
    void historicalMissingCategoryDoesNotBecomeExplicitUncategorized() {
        var projector = new AuditSummaryProjector();
        assertThat(projector.detail(row("DOCUMENT_RELOCATE", "{\"spaceId\":1}", "{\"spaceId\":2}")).changes())
                .noneMatch(change -> change.field().equals("categoryId") || change.field().equals("metadataVersion"));
        assertThat(projector.detail(row("DOCUMENT_RELOCATE", "{\"categoryId\":null}", "{\"categoryId\":null}")).changes())
                .anySatisfy(change -> {
                    assertThat(change.field()).isEqualTo("categoryId");
                    assertThat(change.before()).isNull();
                    assertThat(change.after()).isNull();
                });
    }

    @Test
    void invalidSsoEnumsDegradeAndNeverAddLoginUsername() {
        var projector = new AuditSummaryProjector();
        assertThat(projector.detail(row("SSO_LOGIN", "{}", "{\"platform\":\"attacker\",\"resultCode\":\"OK\"}")).summaryAvailable()).isFalse();
        assertThat(projector.detail(row("SSO_BIND", "{}", "{\"platform\":\"wecom\",\"resultCode\":\"private\"}")).summaryAvailable()).isFalse();
        assertThat(projector.metadata(row("SSO_LOGIN", "{}", "{\"username\":\"private\"}")).attemptedUsername()).isNull();
    }

    /**
     * 管理端删除的生产形状：{@code before_state} 含定位字段与可能被夹带的敏感值，
     * {@code after_state} 为空对象。投影只能露出所属空间与归档状态。
     */
    @Test
    void adminDeleteProjectsOnlyLocatingFieldsAndLeavesOtherScopesUnchanged() throws Exception {
        var projector = new AuditSummaryProjector();
        String before = "{\"documentId\":10,\"spaceId\":2,\"spaceArchived\":true,\"title\":\"private-title\"," +
                "\"fileName\":\"private-file\",\"objectKey\":\"documents/private-key\"}";
        var detail = projector.detail(row("DOCUMENT_ADMIN_DELETE", before, "{}"));
        assertThat(detail.summaryAvailable()).isTrue();
        assertThat(detail.changes()).extracting(Change::field).containsExactly("spaceId", "spaceArchived");
        assertThat(detail.changes()).anySatisfy(change -> {
            assertThat(change.field()).isEqualTo("spaceId");
            assertThat(change.before()).isEqualTo(2L);
            assertThat(change.after()).isNull();
        });
        assertThat(detail.changes()).anySatisfy(change -> {
            assertThat(change.field()).isEqualTo("spaceArchived");
            assertThat(change.before()).isEqualTo(true);
            assertThat(change.after()).isNull();
        });
        String json = new ObjectMapper().findAndRegisterModules().writeValueAsString(detail);
        assertThat(json).doesNotContain("private-", "documents/", "title", "fileName", "objectKey");
        assertThat(projector.detail(row("DOCUMENT_ADMIN_DELETE", "{\"spaceId\":0}", "{}")).summaryAvailable()).isFalse();
        assertThat(projector.detail(row("DOCUMENT_ADMIN_DELETE", "{\"spaceArchived\":\"true\"}", "{}")).summaryAvailable()).isFalse();
        // 同一载荷喂给既有操作域：spaceArchived 不会被别的规则捡走，DOC_ACL 仍只投影自己的字段。
        assertThat(projector.detail(row("DOC_ACL_REVOKE", before, "{}")).changes())
                .extracting(Change::field).containsExactly("documentId");
        assertThat(projector.detail(row("DOCUMENT_RELOCATE", before, "{}")).changes())
                .extracting(Change::field).containsExactly("spaceId");
    }
}
