package com.zhishiku.kb.app.auth.service.diagnostics;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhishiku.kb.app.auth.model.diagnostics.DiagnosticEntry;
import com.zhishiku.kb.app.space.service.DocumentAclService;
import com.zhishiku.kb.app.space.service.SpaceAclService;
import com.zhishiku.kb.common.security.IdentityException;
import com.zhishiku.kb.common.security.PrincipalType;
import com.zhishiku.kb.app.space.model.SpaceAclBatchRevokeItem;
import com.zhishiku.kb.app.space.model.SpaceAclPrincipal;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

/** 严格、有限的 JSON 输入；ID 只接受精确 JSON 整数。 */
public final class DiagnosticInput {
    private static final ObjectMapper JSON = new ObjectMapper(JsonFactory.builder()
            .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(12).maxStringLength(8192).maxNumberLength(20).build())
            .build()).enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);

    public record Intent(
            String type,
            String action,
            List<DiagnosticEntry> entries
    ) {
        public Intent {
            entries = entries.stream().sorted(Comparator.comparing(DiagnosticEntry::key)).toList();
        }

        public boolean changesManage() {
            return entries.stream().anyMatch(entry -> "manage".equals(entry.permission()));
        }
    }

    public record Request(
            long userId,
            Long documentId,
            Intent intent
    ) {
    }

    public record Search(
            String q,
            int limit,
            String cursor
    ) {
    }

    private DiagnosticInput() {
    }

    public static JsonNode read(InputStream input, int limit) {
        try {
            byte[] bytes = input.readNBytes(limit + 1);
            if (bytes.length > limit) {
                throw new DiagnosticException(DiagnosticException.Reason.PAYLOAD_TOO_LARGE);
            }
            try (var parser = JSON.createParser(bytes)) {
                JsonNode value = JSON.readTree(parser);
                if (value == null || !value.isObject() || parser.nextToken() != null) {
                    throw invalid();
                }
                return value;
            }
        } catch (IOException failure) {
            throw invalid();
        }
    }

    public static Request request(JsonNode node, String kind, boolean preview) {
        fields(node, "userId", "documentId", "intent");
        if ((!"SPACE".equals(kind) && node.has("documentId")) || preview != node.has("intent")) {
            throw invalid();
        }
        return new Request(number(node.get("userId")), node.has("documentId") ? number(node.get("documentId")) : null,
                preview ? intent(node.get("intent"), kind) : null);
    }

    public static Search search(JsonNode node) {
        fields(node, "q", "limit", "cursor");
        String q = node.has("q") ? text(node.get("q")).strip() : "";
        if (q.codePointCount(0, q.length()) > 128 || q.codePoints().anyMatch(Character::isISOControl)) {
            throw invalid();
        }
        long limit = node.has("limit") ? number(node.get("limit")) : 20;
        if (limit > 100) {
            throw invalid();
        }
        String cursor = node.has("cursor") ? text(node.get("cursor")) : null;
        if (cursor != null && (cursor.isBlank() || cursor.length() > 2731)) {
            throw invalid();
        }
        return new Search(q, (int) limit, cursor);
    }

    private static Intent intent(JsonNode node, String kind) {
        if (node == null || !node.isObject()) {
            throw invalid();
        }
        String type = text(node.get("type"));
        if (!Set.of("SPACE_GRANT", "SPACE_REVOKE", "DOCUMENT_GRANT", "DOCUMENT_REVOKE").contains(type)
                || !type.startsWith(kind + "_")) {
            throw invalid();
        }
        List<DiagnosticEntry> entries = new ArrayList<>();
        try {
            if (type.equals("SPACE_GRANT")) {
                fields(node, "type", "principals", "permissions");
                List<SpaceAclPrincipal> principals = new ArrayList<>();
                for (JsonNode principal : array(node.get("principals"), 50)) {
                    fields(principal, "principalType", "principalId");
                    principals.add(new SpaceAclPrincipal(principalType(principal), number(principal.get("principalId"))));
                }
                List<String> permissions = new ArrayList<>();
                for (JsonNode permission : array(node.get("permissions"), 6)) {
                    permissions.add(text(permission));
                }
                List<String> normalized = SpaceAclService.validateBatch(principals, permissions);
                for (SpaceAclPrincipal principal : principals) {
                    for (String permission : normalized) {
                        entries.add(new DiagnosticEntry("SPACE", principal.principalType(), principal.principalId(), permission));
                    }
                }
            } else if (type.equals("SPACE_REVOKE")) {
                fields(node, "type", "items");
                List<SpaceAclBatchRevokeItem> items = new ArrayList<>();
                for (JsonNode item : array(node.get("items"), 300)) {
                    fields(item, "principalType", "principalId", "permission");
                    items.add(new SpaceAclBatchRevokeItem(principalType(item), number(item.get("principalId")), text(item.get("permission"))));
                }
                for (var item : SpaceAclService.validateRevokeBatch(items)) {
                    entries.add(new DiagnosticEntry("SPACE", item.principalType(), item.principalId(), item.permission()));
                }
            } else {
                fields(node, "type", "principalType", "principalId", "permission");
                entries.add(new DiagnosticEntry("DOCUMENT", principalType(node), number(node.get("principalId")),
                        DocumentAclService.requireWideningPermission(text(node.get("permission")))));
            }
        } catch (IdentityException failure) {
            throw invalid();
        }
        return new Intent(type, type.endsWith("_GRANT") ? "GRANT" : "REVOKE", entries);
    }

    public static long id(String value) {
        if (value == null || !value.matches("[1-9][0-9]{0,18}")) {
            throw invalid();
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException failure) {
            throw invalid();
        }
    }

    private static long number(JsonNode node) {
        if (node == null || !node.isIntegralNumber() || !node.canConvertToLong() || node.longValue() <= 0) {
            throw invalid();
        }
        return node.longValue();
    }

    private static String text(JsonNode node) {
        if (node == null || !node.isTextual()) {
            throw invalid();
        }
        return node.textValue();
    }

    private static PrincipalType principalType(JsonNode node) {
        try {
            return PrincipalType.valueOf(text(node.get("principalType")));
        } catch (IllegalArgumentException failure) {
            throw invalid();
        }
    }

    private static JsonNode array(JsonNode node, int max) {
        if (node == null || !node.isArray() || node.isEmpty() || node.size() > max) {
            throw invalid();
        }
        return node;
    }

    private static void fields(JsonNode node, String... allowed) {
        if (node == null || !node.isObject()) {
            throw invalid();
        }
        Set<String> names = Set.of(allowed);
        var fields = node.fieldNames();
        while (fields.hasNext()) {
            if (!names.contains(fields.next())) {
                throw invalid();
            }
        }
    }

    private static DiagnosticException invalid() {
        return new DiagnosticException(DiagnosticException.Reason.INVALID_INPUT);
    }
}
