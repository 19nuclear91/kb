package com.zhishiku.kb.app.document.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.databind.JsonNode;
import com.zhishiku.kb.common.security.IdentityException;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.Set;

/**
 * 调整意图；规范化也由服务层调用，不能依赖 HTTP 校验。
 *
 * @param securityLevel   文档密级
 * @param securityVersion 文档密级的独立乐观锁版本
 * @param reason          操作或状态原因
 * @param requestId       请求幂等标识
 */
@Schema(description = "文档密级调整请求。")
public record DocumentSecurityLevelChange(
        @Schema(description = "文档密级") Short securityLevel,
        @Schema(description = "文档密级的独立乐观锁版本") Long securityVersion,
        @Schema(description = "操作或状态原因") String reason,
        @Schema(description = "请求幂等标识") String requestId) {
    private static final Set<String> FIELDS = Set.of("securityLevel", "securityVersion", "reason", "requestId");

    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    public static DocumentSecurityLevelChange fromJson(JsonNode node) {
        if (!node.isObject()) throw IdentityException.badRequest("请求体必须是对象");
        node.fieldNames().forEachRemaining(field -> {
            if (!FIELDS.contains(field)) throw IdentityException.badRequest("请求含不支持的字段");
        });
        Long level = integer(node.get("securityLevel"));
        if (level != null && (level < Short.MIN_VALUE || level > Short.MAX_VALUE)) {
            throw IdentityException.badRequest("securityLevel 超出取值范围");
        }
        return new DocumentSecurityLevelChange(level == null ? null : level.shortValue(),
                integer(node.get("securityVersion")), string(node.get("reason")), string(node.get("requestId")));
    }

    public DocumentSecurityLevelChange normalized(String header) {
        if (securityLevel == null) throw IdentityException.badRequest("securityLevel 不能为空");
        if (securityVersion == null || securityVersion < 0) {
            throw IdentityException.badRequest("securityVersion 必须为非负数");
        }
        String bodyId = requestId == null ? null : text(requestId, "requestId", 128);
        String headerId = header == null ? null : text(header, "X-Request-Id", 128);
        if (bodyId != null && headerId != null && !bodyId.equals(headerId)) {
            throw IdentityException.badRequest("请求体与请求头的请求标识不一致");
        }
        return new DocumentSecurityLevelChange(securityLevel, securityVersion, text(reason, "reason", 500),
                text(bodyId == null ? headerId : bodyId, "requestId", 128));
    }

    private static String text(String value, String field, int limit) {
        if (value == null || value.isBlank()) throw IdentityException.badRequest(field + " 不能为空");
        String result = value.strip();
        if (result.indexOf('\0') >= 0 || result.codePointCount(0, result.length()) > limit) {
            throw IdentityException.badRequest(field + " 含无效字符或长度超过 " + limit);
        }
        return result;
    }

    private static Long integer(JsonNode node) {
        if (node == null || node.isNull()) return null;
        if (!node.isIntegralNumber() || !node.canConvertToLong()) {
            throw IdentityException.badRequest("密级和版本必须是整数");
        }
        return node.longValue();
    }

    private static String string(JsonNode node) {
        if (node == null || node.isNull()) return null;
        if (!node.isTextual()) throw IdentityException.badRequest("原因和请求标识必须是字符串");
        return node.textValue();
    }
}
