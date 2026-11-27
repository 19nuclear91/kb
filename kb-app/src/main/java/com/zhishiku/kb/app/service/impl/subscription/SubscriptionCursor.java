package com.zhishiku.kb.app.service.impl.subscription;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhishiku.kb.app.service.subscription.SubscriptionFailure;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.Set;

/** 有界游标绑定本人、用途和过滤条件，位置仍由每次授权查询验证。 */
public final class SubscriptionCursor {
    private static final ObjectMapper JSON = new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);

    private SubscriptionCursor() {
    }

    public static String context(long actor, String purpose, Object... filters) {
        try {
            return SubscriptionRules.digest(JSON.writeValueAsString(Arrays.asList(actor, purpose, Arrays.asList(filters))));
        } catch (Exception ignored) {
            throw SubscriptionFailure.unavailable();
        }
    }

    public static String encode(String context, String position) {
        var node = JSON.createObjectNode().put("context", context).put("position", position);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(node.toString().getBytes(StandardCharsets.UTF_8));
    }

    public static String decode(String token, String context) {
        if (token == null) {
            return null;
        }
        if (!token.matches("[A-Za-z0-9_-]{1,2731}")) {
            throw SubscriptionFailure.invalid();
        }
        try (var parser = JSON.createParser(Base64.getUrlDecoder().decode(token))) {
            JsonNode node = JSON.readTree(parser);
            if (parser.nextToken() != null || node == null || !node.isObject() || node.size() != 2
                    || !node.path("context").isTextual() || !context.equals(node.path("context").textValue())
                    || !node.path("position").isTextual() || node.path("position").textValue().length() > 256) {
                throw SubscriptionFailure.invalid();
            }
            var fields = node.fieldNames();
            while (fields.hasNext()) {
                if (!Set.of("context", "position").contains(fields.next())) {
                    throw SubscriptionFailure.invalid();
                }
            }
            return node.path("position").textValue();
        } catch (Exception ignored) {
            throw SubscriptionFailure.invalid();
        }
    }
}
