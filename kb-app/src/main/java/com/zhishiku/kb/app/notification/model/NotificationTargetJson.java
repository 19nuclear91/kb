package com.zhishiku.kb.app.notification.model;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;

import java.io.IOException;
import java.util.Set;
import java.util.UUID;

/** 目标判别联合的精确 JSON：旧五字段/新二字段，不输出混合空字段或接受扩展载荷。 */
public final class NotificationTargetJson {
    private NotificationTargetJson() {
    }

    public static final class Serializer extends JsonSerializer<NotificationTarget> {
        @Override
        public void serialize(NotificationTarget target, JsonGenerator output, SerializerProvider serializers) throws IOException {
            output.writeStartObject();
            output.writeStringField("kind", target.kind());
            if (NotificationTarget.KNOWLEDGE_GAP.equals(target.kind())) {
                output.writeNumberField("gapId", target.gapId());
            } else {
                output.writeNumberField("documentId", target.documentId());
                output.writeNumberField("versionId", target.versionId());
                identity(output, "reviewId", target.reviewId());
                identity(output, "commentId", target.commentId());
            }
            output.writeEndObject();
        }

        private static void identity(JsonGenerator output, String name, UUID value) throws IOException {
            if (value == null) {
                output.writeNullField(name);
            } else {
                output.writeStringField(name, value.toString());
            }
        }
    }

    public static final class Deserializer extends JsonDeserializer<NotificationTarget> {
        @Override
        public NotificationTarget deserialize(JsonParser input, DeserializationContext context) throws IOException {
            boolean strict = input.isEnabled(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
            input.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
            try {
                JsonNode node = input.getCodec().readTree(input);
                if (node == null || !node.isObject()) {
                    throw invalid(input);
                }
                String kind = text(node, "kind", input);
                var allowed = NotificationTarget.KNOWLEDGE_GAP.equals(kind) ? Set.of("kind", "gapId")
                        : Set.of("kind", "documentId", "versionId", "reviewId", "commentId");
                if (node.size() != allowed.size()) {
                    throw invalid(input);
                }
                var names = node.fieldNames();
                while (names.hasNext()) {
                    if (!allowed.contains(names.next())) {
                        throw invalid(input);
                    }
                }
                if (NotificationTarget.KNOWLEDGE_GAP.equals(kind)) {
                    return NotificationTarget.knowledgeGap(integer(node, "gapId", input));
                }
                return new NotificationTarget(kind, integer(node, "documentId", input), integer(node, "versionId", input),
                        identity(node, "reviewId", input), identity(node, "commentId", input));
            } catch (IllegalArgumentException invalid) {
                throw invalid(input);
            } finally {
                if (!strict) {
                    input.disable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
                }
            }
        }

        private static String text(JsonNode node, String name, JsonParser parser) throws JsonMappingException {
            var value = node.get(name);
            if (value == null || !value.isTextual()) {
                throw invalid(parser);
            }
            return value.textValue();
        }

        private static long integer(JsonNode node, String name, JsonParser parser) throws JsonMappingException {
            var value = node.get(name);
            if (value == null || !value.isIntegralNumber() || !value.canConvertToLong() || value.longValue() <= 0) {
                throw invalid(parser);
            }
            return value.longValue();
        }

        private static UUID identity(JsonNode node, String name, JsonParser parser) throws JsonMappingException {
            var value = node.get(name);
            if (value == null) {
                throw invalid(parser);
            }
            if (value.isNull()) {
                return null;
            }
            String text = text(node, name, parser);
            if (!text.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")) {
                throw invalid(parser);
            }
            return UUID.fromString(text);
        }

        private static JsonMappingException invalid(JsonParser parser) {
            return JsonMappingException.from(parser, "Invalid notification target");
        }
    }
}
