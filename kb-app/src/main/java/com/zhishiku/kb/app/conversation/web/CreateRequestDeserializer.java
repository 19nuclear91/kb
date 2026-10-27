package com.zhishiku.kb.app.conversation.web;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;
import java.util.Set;

/**
 * 创建请求只接受文本与缺省值，不做类型猜测。
 */
public class CreateRequestDeserializer extends JsonDeserializer<ConversationController.CreateRequest> {
    @Override
    public ConversationController.CreateRequest deserialize(JsonParser parser, DeserializationContext context) throws IOException {
        JsonNode input = parser.getCodec().readTree(parser);
        if (!input.isObject()) throw invalid(parser);
        var fields = input.fieldNames();
        while (fields.hasNext()) if (!Set.of("title", "requestId").contains(fields.next())) throw invalid(parser);
        var result = new ConversationController.CreateRequest();
        result.setTitle(text(input.path("title"), parser));
        result.setRequestId(text(input.path("requestId"), parser));
        return result;
    }

    private String text(JsonNode node, JsonParser parser) throws JsonMappingException {
        if (node.isNull() || node.isMissingNode()) return null;
        if (!node.isTextual()) throw invalid(parser);
        return node.textValue();
    }

    private JsonMappingException invalid(JsonParser parser) {
        return JsonMappingException.from(parser, "会话请求参数无效");
    }
}

