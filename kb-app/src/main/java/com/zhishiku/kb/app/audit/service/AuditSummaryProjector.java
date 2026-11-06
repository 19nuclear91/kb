package com.zhishiku.kb.app.audit.service;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhishiku.kb.app.audit.model.AuditQueryViews.Change;
import com.zhishiku.kb.app.audit.model.AuditQueryViews.Detail;
import com.zhishiku.kb.app.audit.model.AuditQueryViews.Metadata;
import com.zhishiku.kb.app.auth.model.IdentityAuditRecord;
import com.zhishiku.kb.app.auth.model.Permission;
import com.zhishiku.kb.app.auth.service.AuditScope;
import com.zhishiku.kb.app.auth.sso.SsoAudit;
import com.zhishiku.kb.app.auth.sso.SsoSettings;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 仅注册字段进入输出；未知操作域及异常载荷降级为元数据，无原文日志。
 */
@Component
public class AuditSummaryProjector {
    private static final ObjectMapper JSON = new ObjectMapper(JsonFactory.builder().streamReadConstraints(
            StreamReadConstraints.builder().maxNestingDepth(32).maxStringLength(65536).build()).build())
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    private static final Set<String> PERMISSIONS = Set.copyOf(Permission.codes());

    public Metadata metadata(IdentityAuditRecord row) {
        return new Metadata(row.getId(), row.getEventId(), row.getOperationScope(), row.getRequestId(),
                row.getActorUserId(), row.getTargetType(), row.getTargetId(), row.getOperation(),
                row.getStatus(), row.getCreatedAt(), attemptedUsername(row));
    }

    public Detail detail(IdentityAuditRecord row) {
        Metadata metadata = metadata(row);
        try {
            AuditScope scope = AuditScope.valueOf(row.getOperationScope());
            Map<String, Kind> rules = rules(scope);
            JsonNode before = object(row.getBeforeState()), after = object(row.getAfterState());
            List<Change> changes = new ArrayList<>();
            for (var rule : rules.entrySet()) {
                Object old = scalar(before.get(rule.getKey()), rule.getValue());
                Object value = scalar(after.get(rule.getKey()), rule.getValue());
                if (scope == AuditScope.ROLE_PERMISSION_GRANT || scope == AuditScope.ROLE_PERMISSION_REVOKE) {
                    if (old != null && value != null && !Objects.equals(old, value))
                        throw new IllegalArgumentException();
                    Object permission = value == null ? old : value;
                    if (permission == null) throw new IllegalArgumentException();
                    boolean grant = scope == AuditScope.ROLE_PERMISSION_GRANT;
                    changes.add(new Change("permission", grant ? null : permission, grant ? permission : null));
                } else if (old != null || value != null || (rule.getValue() == Kind.NULLABLE_ID
                        && (before.has(rule.getKey()) || after.has(rule.getKey())))) {
                    // 保留 roleId 等不变的定位字段，防止解除绑定时丢失被解除主体。
                    changes.add(new Change(rule.getKey(), old, value));
                }
            }
            if (login(row) && attemptedUsername(row) == null) return new Detail(metadata, List.of(), false);
            return new Detail(metadata, List.copyOf(changes), true);
        } catch (Exception ignored) {
            return new Detail(metadata, List.of(), false);
        }
    }

    private static String attemptedUsername(IdentityAuditRecord row) {
        if (!login(row)) return null;
        try {
            JsonNode value = object(row.getAfterState()).get("username");
            if (value == null || !value.isTextual()) return null;
            String username = value.textValue();
            return username.codePointCount(0, username.length()) >= 1 && username.codePointCount(0,
                    username.length()) <= 128
                    && username.codePoints().noneMatch(Character::isISOControl) ? username : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    private static boolean login(IdentityAuditRecord row) {
        return "AUTH_LOGIN_SUCCESS".equals(row.getOperationScope()) || "AUTH_LOGIN_FAILURE".equals(row.getOperationScope());
    }

    private static JsonNode object(String raw) throws Exception {
        if (raw == null || raw.length() > 65536) throw new IllegalArgumentException();
        try (var parser = JSON.createParser(raw)) {
            JsonNode value = JSON.readTree(parser);
            if (value == null || !value.isObject() || parser.nextToken() != null) throw new IllegalArgumentException();
            return value;
        }
    }

    private static Map<String, Kind> rules(AuditScope scope) {
        return switch (scope) {
            case USER_CREATE -> fields("departmentId", Kind.ID, "clearanceLevel", Kind.LEVEL, "enabled", Kind.BOOL,
                    "version", Kind.COUNT, "mustChangePassword", Kind.BOOL);
            case USER_MOVE_DEPARTMENT ->
                    fields("departmentId", Kind.ID, "enabled", Kind.BOOL, "version", Kind.COUNT);
            case USER_ENABLE, USER_DISABLE, USER_LIFECYCLE_RESTRICT, USER_LIFECYCLE_RESTORE ->
                    fields("departmentId", Kind.ID, "enabled", Kind.BOOL, "version", Kind.COUNT,
                            "manualEnabled", Kind.BOOL, "authEpoch", Kind.COUNT);
            case USER_CLEARANCE_CHANGE -> fields("clearanceLevel", Kind.LEVEL, "version", Kind.COUNT);
            case USER_LIFECYCLE_MAPPING -> fields("managed", Kind.BOOL, "mappingVersion", Kind.COUNT, "enabled", Kind.BOOL);
            case LIFECYCLE_EVENT_RETRY -> fields("conflicted", Kind.BOOL, "restricted", Kind.COUNT,
                    "review", Kind.COUNT, "unmapped", Kind.COUNT);
            case USER_EMERGENCY_CHANGE -> fields("registered", Kind.BOOL, "mappingVersion", Kind.COUNT,
                    "replacedUserId", Kind.NULLABLE_ID);
            case USER_PASSWORD_CHANGE, USER_PASSWORD_RESET -> fields("mustChangePassword", Kind.BOOL);
            case USER_ROLE_ASSIGN -> fields("roleId", Kind.ID, "created", Kind.BOOL);
            case USER_ROLE_REVOKE -> fields("roleId", Kind.ID, "bound", Kind.BOOL);
            case ROLE_CREATE, ROLE_RENAME, ROLE_DELETE -> fields("roleId", Kind.ID);
            case ROLE_PERMISSION_GRANT, ROLE_PERMISSION_REVOKE -> fields("permission", Kind.FUNCTIONAL_PERMISSION);
            case DEPARTMENT_CREATE, DEPARTMENT_RENAME, DEPARTMENT_MOVE, DEPARTMENT_ENABLE, DEPARTMENT_DISABLE ->
                    fields("parentId", Kind.ID, "enabled", Kind.BOOL, "version", Kind.COUNT);
            case SPACE_CREATE, SPACE_UPDATE, SPACE_ARCHIVE, SPACE_UNARCHIVE, SPACE_DELETE ->
                    fields("archived", Kind.BOOL, "version", Kind.COUNT);
            case SPACE_PUBLICATION_POLICY -> fields("publicationPolicy", Kind.PUBLICATION_POLICY, "version", Kind.COUNT);
            case SPACE_ACL_GRANT, SPACE_ACL_REVOKE ->
                    fields("spaceId", Kind.ID, "principalKey", Kind.PRINCIPAL, "permission", Kind.ACL_PERMISSION);
            case SPACE_ACL_BATCH_GRANT ->
                    fields("spaceId", Kind.ID, "grantedCount", Kind.COUNT, "alreadyGrantedCount", Kind.COUNT,
                            "clearanceBlockedPrincipalCount", Kind.COUNT);
            case SPACE_ACL_BATCH_REVOKE ->
                    fields("spaceId", Kind.ID, "removedCount", Kind.COUNT, "absentCount", Kind.COUNT);
            case DOC_ACL_GRANT, DOC_ACL_REVOKE ->
                    fields("documentId", Kind.ID, "principalKey", Kind.PRINCIPAL, "permission", Kind.ACL_PERMISSION);
            case DOCUMENT_RELOCATE ->
                    fields("spaceId", Kind.ID, "clearedExceptionCount", Kind.COUNT, "clearanceBlockedPrincipalCount",
                            Kind.COUNT, "categoryId", Kind.NULLABLE_ID, "metadataVersion", Kind.COUNT);
            case DOCUMENT_ADMIN_DELETE -> fields("spaceId", Kind.ID, "spaceArchived", Kind.BOOL);
            case DOCUMENT_METADATA_UPDATE -> fields("categoryId", Kind.NULLABLE_ID, "tagIds", Kind.IDS,
                    "metadataVersion", Kind.COUNT, "changedFields", Kind.CHANGED_FIELDS);
            case DOCUMENT_CATEGORY_CREATE, DOCUMENT_CATEGORY_UPDATE, DOCUMENT_CATEGORY_DELETE ->
                    fields("spaceId", Kind.ID, "categoryId", Kind.ID, "parentId", Kind.NULLABLE_ID,
                            "sortOrder", Kind.COUNT, "treeVersion", Kind.COUNT, "nameChanged", Kind.BOOL);
            case DOCUMENT_TAG_CREATE, DOCUMENT_TAG_UPDATE ->
                    fields("tagId", Kind.ID, "enabled", Kind.BOOL, "version", Kind.COUNT, "nameChanged", Kind.BOOL);
            case DOCUMENT_SECURITY_LEVEL_CHANGE ->
                    fields("securityLevel", Kind.LEVEL, "securityVersion", Kind.COUNT, "expectedSecurityVersion",
                            Kind.COUNT);
            case PARSE_DEAD_LETTER_QUERY, PARSE_DEAD_LETTER_REPLAY -> fields("status", Kind.DEAD_LETTER_STATUS);
            case SSO_LOGIN -> fields("platform", Kind.SSO_PLATFORM, "resultCode", Kind.SSO_RESULT);
            case SSO_BIND -> fields("platform", Kind.SSO_PLATFORM, "resultCode", Kind.SSO_RESULT, "bound", Kind.BOOL);
            case EXTERNAL_BIND, EXTERNAL_UNBIND, AUTH_LOGIN_SUCCESS, AUTH_LOGIN_FAILURE -> Map.of();
        };
    }

    private static Map<String, Kind> fields(Object... pairs) {
        Map<String, Kind> result = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) result.put((String) pairs[i], (Kind) pairs[i + 1]);
        return result;
    }

    private static Object scalar(JsonNode value, Kind kind) {
        if (value == null) {
            return null;
        }
        if (kind == Kind.IDS || kind == Kind.CHANGED_FIELDS) {
            if (!value.isArray() || value.size() > (kind == Kind.IDS ? 20 : 4)) {
                throw new IllegalArgumentException();
            }
            List<Object> result = new ArrayList<>();
            Set<Object> seen = new HashSet<>();
            for (JsonNode element : value) {
                Object item;
                if (kind == Kind.IDS && element.isIntegralNumber() && element.canConvertToLong() && element.longValue() > 0) {
                    item = element.longValue();
                } else if (kind == Kind.CHANGED_FIELDS && element.isTextual()
                        && Set.of("title", "summary", "categoryId", "tagIds").contains(element.textValue())) {
                    item = element.textValue();
                } else {
                    throw new IllegalArgumentException();
                }
                if (!seen.add(item)) {
                    throw new IllegalArgumentException();
                }
                result.add(item);
            }
            return List.copyOf(result);
        }
        if (value.isNull()) {
            return null;
        }
        if (kind == Kind.NULLABLE_ID) {
            kind = Kind.ID;
        }
        if (kind == Kind.BOOL && value.isBoolean()) return value.booleanValue();
        if ((kind == Kind.ID || kind == Kind.COUNT || kind == Kind.LEVEL) && value.isIntegralNumber() && value.canConvertToLong()) {
            long number = value.longValue();
            if (number >= (kind == Kind.ID ? 1 : 0) && (kind != Kind.LEVEL || number <= Short.MAX_VALUE)) return number;
        }
        if (value.isTextual()) {
            String text = value.textValue();
            if (kind == Kind.PUBLICATION_POLICY && Set.of("AUTO", "REVIEW_REQUIRED").contains(text)) return text;
            if (kind == Kind.FUNCTIONAL_PERMISSION && PERMISSIONS.contains(text)) return text;
            if (kind == Kind.ACL_PERMISSION && Set.of("view", "download", "edit", "review", "comment", "manage").contains(text)) return text;
            if (kind == Kind.DEAD_LETTER_STATUS && Set.of("OPEN", "REPLAYED", "RESOLVED").contains(text)) return text;
            if (kind == Kind.SSO_PLATFORM && SsoSettings.PLATFORMS.contains(text)) return text;
            if (kind == Kind.SSO_RESULT && SsoAudit.RESULTS.contains(text)) return text;
            if (kind == Kind.PRINCIPAL && text.matches("[123]:[1-9][0-9]{0,18}")) {
                if (Long.parseLong(text.substring(2)) > 0) return text;
            }
        }
        throw new IllegalArgumentException();
    }

    private enum Kind {ID, NULLABLE_ID, IDS, CHANGED_FIELDS, COUNT, LEVEL, BOOL, FUNCTIONAL_PERMISSION, ACL_PERMISSION, PRINCIPAL, DEAD_LETTER_STATUS,
        SSO_PLATFORM, SSO_RESULT, PUBLICATION_POLICY}
}
