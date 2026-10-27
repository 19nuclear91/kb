package com.zhishiku.kb.ai.conversation;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;
import java.util.Set;

/**
 * 请求不接受数字到文本、浮点到序号等隐式转换。
 */
public class TurnRequestDeserializer extends JsonDeserializer<MultiTurnChatController.TurnRequest> {
    @Override
    public MultiTurnChatController.TurnRequest deserialize(JsonParser parser, DeserializationContext context) throws IOException {
        JsonNode input = parser.getCodec().readTree(parser);
        if (!input.isObject()) throw invalid(parser);
        var fields = input.fieldNames();
        while (fields.hasNext())
            if (!Set.of("question", "requestId", "expectedLastTurnSeq").contains(fields.next())) throw invalid(parser);
        if (!input.path("question").isTextual() || !input.path("expectedLastTurnSeq").isIntegralNumber()
                || !input.path("expectedLastTurnSeq").canConvertToLong()) throw invalid(parser);
        var result = new MultiTurnChatController.TurnRequest();
        result.setQuestion(input.get("question").textValue());
        result.setExpectedLastTurnSeq(input.get("expectedLastTurnSeq").longValue());
        var request = input.path("requestId");
        if (!request.isMissingNode() && !request.isNull()) {
            if (!request.isTextual()) throw invalid(parser);
            result.setRequestId(request.textValue());
        }
        return result;
    }

    private JsonMappingException invalid(JsonParser parser) {
        return JsonMappingException.from(parser, "多轮请求参数无效");
    }
}

