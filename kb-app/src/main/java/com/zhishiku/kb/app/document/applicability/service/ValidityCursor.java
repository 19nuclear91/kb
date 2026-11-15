package com.zhishiku.kb.app.document.applicability.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhishiku.kb.app.document.applicability.support.ValidityInput;

import java.io.ByteArrayInputStream;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.Set;

/** 条件摘要绑定身份、目标与视图；游标不延续授权。 */
public record ValidityCursor(String context, long id, LocalDate date, OffsetDateTime time) {
    private static final ObjectMapper JSON = new ObjectMapper();

    public static String context(String purpose, long actor, long target, String filter) {
        return ValidityCommands.digest(purpose + ":" + actor + ":" + target + ":" + filter);
    }

    public String encode() {
        var node = JSON.createObjectNode().put("context", context).put("id", id);
        node.put("date", date == null ? null : date.toString());
        node.put("time", time == null ? null : time.toString());
        try {
            return Base64.getUrlEncoder().withoutPadding().encodeToString(JSON.writeValueAsBytes(node));
        } catch (Exception impossible) {
            throw new IllegalStateException("游标序列化失败");
        }
    }

    public static ValidityCursor decode(String token, String expectedContext) {
        if (token == null) {
            return null;
        }
        try {
            if (!token.matches("[A-Za-z0-9_-]{1,4096}")) {
                throw ValidityInput.invalid();
            }
            var node = ValidityInput.body(new ByteArrayInputStream(Base64.getUrlDecoder().decode(token)));
            var keys = Set.of("context", "id", "date", "time");
            ValidityInput.fields(node, keys, keys);
            var cursor = new ValidityCursor(ValidityInput.rawText(node.get("context")), ValidityInput.number(node.get("id"), 1, Long.MAX_VALUE),
                    node.get("date").isNull() ? null : LocalDate.parse(ValidityInput.rawText(node.get("date"))),
                    node.get("time").isNull() ? null : OffsetDateTime.parse(ValidityInput.rawText(node.get("time"))));
            if (!cursor.context.equals(expectedContext)) {
                throw ValidityInput.invalid();
            }
            return cursor;
        } catch (RuntimeException ignored) {
            throw ValidityInput.invalid();
        }
    }
}
