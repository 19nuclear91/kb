package com.zhishiku.kb.app.space.web.dto;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.zhishiku.kb.app.space.model.SpaceAclBatchRevokeItem;
import com.zhishiku.kb.common.security.PrincipalType;

import java.io.IOException;

/** 批量撤销按实际 JSON 类型绑定条目，正数约束与业务资格由服务校验。 */
public final class SpaceAclBatchRevokeItemDeserializer extends JsonDeserializer<SpaceAclBatchRevokeItem> {
    @Override
    public SpaceAclBatchRevokeItem deserialize(JsonParser parser, DeserializationContext context) throws IOException {
        JsonNode item = parser.getCodec().readTree(parser);
        if (!item.isObject()) {
            throw invalid(parser);
        }
        return new SpaceAclBatchRevokeItem(principalType(item.get("principalType"), parser),
                principalId(item.get("principalId"), parser), text(item.get("permission"), parser));
    }

    private static PrincipalType principalType(JsonNode node, JsonParser parser) throws JsonMappingException {
        String value = text(node, parser);
        if (value == null) {
            return null;
        }
        try {
            return PrincipalType.valueOf(value);
        } catch (IllegalArgumentException error) {
            throw invalid(parser);
        }
    }

    private static long principalId(JsonNode node, JsonParser parser) throws JsonMappingException {
        if (node == null || node.isNull()) {
            return 0;
        }
        if (!node.isIntegralNumber() || !node.canConvertToLong()) {
            throw invalid(parser);
        }
        return node.longValue();
    }

    private static String text(JsonNode node, JsonParser parser) throws JsonMappingException {
        if (node == null || node.isNull()) {
            return null;
        }
        if (!node.isTextual()) {
            throw invalid(parser);
        }
        return node.textValue();
    }

    private static JsonMappingException invalid(JsonParser parser) {
        return JsonMappingException.from(parser, "批量撤销条目类型无效；主体类型须为 USER、ROLE、DEPT，主体 ID 须为整数");
    }
}
