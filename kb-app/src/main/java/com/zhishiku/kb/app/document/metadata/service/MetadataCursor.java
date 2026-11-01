package com.zhishiku.kb.app.document.metadata.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhishiku.kb.app.document.metadata.support.MetadataInput;

import java.io.ByteArrayInputStream;
import java.util.Base64;
import java.util.Set;

import static com.zhishiku.kb.app.document.metadata.service.MetadataException.Reason.INVALID_INPUT;
import static com.zhishiku.kb.app.document.metadata.service.MetadataException.Reason.METADATA_VERSION_CONFLICT;
import static com.zhishiku.kb.app.document.metadata.service.MetadataException.Reason.TREE_VERSION_CONFLICT;

/** 游标只绑定分页条件，每次读取均重新授权。 */
public record MetadataCursor(String context, Long spaceId, long treeVersion, int sortOrder, long id) {
    private static final ObjectMapper JSON = new ObjectMapper();

    public static String context(String purpose, Long resource, Long parent, String filters) {
        return MetadataCommands.digest(purpose + ":" + resource + ":" + parent + ":" + filters);
    }

    public String encode() {
        var node = JSON.createObjectNode().put("context", context).put("treeVersion", treeVersion)
                .put("sortOrder", sortOrder).put("id", id);
        if (spaceId == null) {
            node.putNull("spaceId");
        } else {
            node.put("spaceId", spaceId);
        }
        try {
            return Base64.getUrlEncoder().withoutPadding().encodeToString(JSON.writeValueAsBytes(node));
        } catch (Exception impossible) {
            throw new IllegalStateException("游标序列化失败");
        }
    }

    public static MetadataCursor decode(String token, String context, Long space, long treeVersion) {
        if (token == null) {
            return null;
        }
        if (!token.matches("[A-Za-z0-9_-]{1,4096}")) {
            throw new MetadataException(INVALID_INPUT);
        }
        MetadataCursor cursor;
        try {
            var node = MetadataInput.body(new ByteArrayInputStream(Base64.getUrlDecoder().decode(token)), 2048);
            var fields = Set.of("context", "spaceId", "treeVersion", "sortOrder", "id");
            MetadataInput.fields(node, fields, fields);
            cursor = new MetadataCursor(MetadataInput.rawText(node.get("context")), MetadataInput.nullableId(node.get("spaceId")),
                    MetadataInput.number(node.get("treeVersion"), 0, Long.MAX_VALUE),
                    (int) MetadataInput.number(node.get("sortOrder"), 0, Integer.MAX_VALUE),
                    MetadataInput.number(node.get("id"), 1, Long.MAX_VALUE));
        } catch (RuntimeException ignored) {
            throw new MetadataException(INVALID_INPUT);
        }
        if (!cursor.context.equals(context)) {
            throw new MetadataException(INVALID_INPUT);
        }
        if (!java.util.Objects.equals(cursor.spaceId, space)) {
            throw new MetadataException(METADATA_VERSION_CONFLICT);
        }
        if (cursor.treeVersion != treeVersion) {
            throw new MetadataException(TREE_VERSION_CONFLICT);
        }
        return cursor;
    }
}
