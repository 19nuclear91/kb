package com.zhishiku.kb.app.controller.subscription;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhishiku.kb.app.auth.service.EpochSessionService;
import com.zhishiku.kb.app.config.EpochAuthenticationInterceptor;
import com.zhishiku.kb.app.query.QueryParameters;
import com.zhishiku.kb.app.service.impl.subscription.SubscriptionRules;
import com.zhishiku.kb.app.service.subscription.SubscriptionFailure;
import com.zhishiku.kb.common.security.IdentityException;
import jakarta.servlet.http.HttpServletRequest;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;

/** 本人订阅入口严格解码，未知字段、重复键和超限在业务服务前拒绝。 */
public final class SubscriptionInput {
    private static final ObjectMapper JSON = new ObjectMapper(JsonFactory.builder().streamReadConstraints(
            StreamReadConstraints.builder().maxNestingDepth(8).maxStringLength(16384).maxNumberLength(20).build()).build())
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);

    private SubscriptionInput() {
    }

    public static long actor(HttpServletRequest request) {
        if (!(request.getAttribute(EpochAuthenticationInterceptor.VERIFIED) instanceof EpochSessionService.Session session)) {
            throw IdentityException.unauthorized("登录状态无效");
        }
        if (session.mustChangePassword()) {
            throw IdentityException.passwordChangeRequired("必须先完成密码修改");
        }
        return session.userId();
    }

    public static JsonNode body(HttpServletRequest request, Set<String> allowed, Set<String> required) {
        if (request.getQueryString() != null || !request.getParameterMap().isEmpty()) {
            throw SubscriptionFailure.invalid();
        }
        try {
            byte[] bytes = request.getInputStream().readNBytes(16385);
            if (bytes.length > 16384) {
                throw new SubscriptionFailure(413, "BODY_TOO_LARGE");
            }
            try (var parser = JSON.createParser(bytes)) {
                JsonNode node = JSON.readTree(parser);
                if (node == null || !node.isObject() || parser.nextToken() != null) {
                    throw SubscriptionFailure.invalid();
                }
                var fields = node.fieldNames();
                while (fields.hasNext()) {
                    if (!allowed.contains(fields.next())) {
                        throw SubscriptionFailure.invalid();
                    }
                }
                for (String field : required) {
                    if (!node.hasNonNull(field)) {
                        throw SubscriptionFailure.invalid();
                    }
                }
                return node;
            }
        } catch (IOException ignored) {
            throw SubscriptionFailure.invalid();
        }
    }

    public static String text(JsonNode node) {
        if (node == null || !node.isTextual()) {
            throw SubscriptionFailure.invalid();
        }
        SubscriptionRules.text(node.textValue(), 0, 16384);
        return node.textValue();
    }

    public static long number(JsonNode node, long minimum) {
        return SubscriptionRules.number(text(node), minimum);
    }

    public static <T extends Enum<T>> T enumeration(Class<T> type, String value) {
        if (value == null) {
            throw SubscriptionFailure.invalid();
        }
        try {
            return Enum.valueOf(type, value);
        } catch (IllegalArgumentException ignored) {
            throw SubscriptionFailure.invalid();
        }
    }

    public static int limit(JsonNode node) {
        if (!node.has("limit")) {
            return 20;
        }
        var value = node.get("limit");
        if (!value.isIntegralNumber() || !value.canConvertToInt()) {
            throw SubscriptionFailure.invalid();
        }
        return SubscriptionRules.limit(value.intValue());
    }

    public static String optional(JsonNode node, String field) {
        return node.hasNonNull(field) ? text(node.get(field)) : null;
    }

    public static QueryParameters query(HttpServletRequest request, String... allowed) {
        var parameters = new LinkedHashMap<String, List<String>>();
        request.getParameterMap().forEach((key, values) -> parameters.put(key, Arrays.asList(values)));
        try {
            if (request.getInputStream().read() != -1) {
                throw SubscriptionFailure.invalid();
            }
        } catch (IOException ignored) {
            throw SubscriptionFailure.invalid();
        }
        return new QueryParameters(parameters, allowed);
    }

    public static String requestId(HttpServletRequest request) {
        var values = Collections.list(request.getHeaders("X-Request-Id"));
        if (values.size() != 1 || values.getFirst().contains(",")) {
            throw SubscriptionFailure.invalid();
        }
        return SubscriptionRules.text(values.getFirst(), 1, 128);
    }
}
