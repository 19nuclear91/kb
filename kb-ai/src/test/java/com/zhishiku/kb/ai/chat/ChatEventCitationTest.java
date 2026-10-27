package com.zhishiku.kb.ai.chat;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.core.converter.AnnotatedType;
import io.swagger.v3.core.converter.ModelConverters;
import io.swagger.v3.oas.models.media.Schema;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ChatEventCitationTest {

    private final ObjectMapper json = new ObjectMapper();

    @Test
    void versionedEventSerializesAllProvenanceButNoBodyOrStorageDetails() throws Exception {
        RetrievalClient.Citation source = new RetrievalClient.Citation(5000000001L, "架构说明", 3,
                5000004001L, "仅限模型上下文的正文", 5000002001L, 7, 4, "设计 / 权限", "PARAGRAPH",
                10, 18, 120, 340, "section:access", true);
        ChatEvent.Citation event = ChatEvent.Citation.from(source);

        assertThat(event).isEqualTo(new ChatEvent.Citation(5000000001L, "架构说明", 3,
                5000004001L, 5000002001L, 7, 4, "设计 / 权限", "PARAGRAPH", 10, 18, 120, 340,
                "section:access", ChatEvent.ProvenanceStatus.VERSIONED, true));
        assertThat(event.type()).isEqualTo("citation");
        JsonNode tree = json.readTree(json.writeValueAsString(event));
        assertThat(tree).isEqualTo(json.readTree("""
                {
                  "docId":5000000001,"docTitle":"架构说明","pageNo":3,"chunkId":5000004001,
                  "versionId":5000002001,"versionNo":7,"chunkIdx":4,
                  "headingPath":"设计 / 权限","structureType":"PARAGRAPH",
                  "sourceLineStart":10,"sourceLineEnd":18,
                  "sourceCharStart":120,"sourceCharEnd":340,"sourceLocator":"section:access",
                  "provenanceStatus":"VERSIONED","contextTruncated":true
                }
                """));
        assertThat(tree.has("content")).isFalse();
        assertThat(tree.has("objectKey")).isFalse();
        assertThat(tree.has("token")).isFalse();
    }

    @Test
    void legacyConstructorKeepsVersionAndAllUnknownSourcesNull() throws Exception {
        ChatEvent.Citation legacy = new ChatEvent.Citation(10L, "旧来源", null, 20L);
        ChatEvent.Citation converted = ChatEvent.Citation.from(
                new RetrievalClient.Citation(10L, "旧来源", null, 20L, "正文"));

        assertThat(converted).isEqualTo(legacy);
        JsonNode tree = json.readTree(json.writeValueAsString(legacy));
        assertThat(tree.path("provenanceStatus").asText()).isEqualTo("UNVERSIONED");
        assertThat(tree.path("contextTruncated").asBoolean()).isFalse();
        for (String field : new String[]{"versionId", "versionNo", "chunkIdx", "pageNo",
                "headingPath", "structureType", "sourceLineStart", "sourceLineEnd",
                "sourceCharStart", "sourceCharEnd", "sourceLocator"}) {
            assertThat(tree.has(field)).as(field).isTrue();
            assertThat(tree.get(field).isNull()).as(field).isTrue();
        }
    }

    @Test
    void missingDisplayVersionDoesNotDowngradeCompleteIdentity() throws Exception {
        var source = new RetrievalClient.Citation(10L, "说明", null, 20L, "正文", 30L,
                null, null, null, null, null, null, null, null, null, false);
        ChatEvent.Citation event = ChatEvent.Citation.from(source);

        assertThat(event.provenanceStatus()).isEqualTo(ChatEvent.ProvenanceStatus.VERSIONED);
        assertThat(event.versionNo()).isNull();
        JsonNode tree = json.readTree(json.writeValueAsString(event));
        assertThat(tree.get("versionId").isIntegralNumber()).isTrue();
        assertThat(tree.get("versionId").longValue()).isEqualTo(30L);
        assertThat(tree.get("versionNo").isNull()).isTrue();
    }

    @Test
    void displayVersionCannotSubstituteForMissingVersionIdentity() {
        var source = new RetrievalClient.Citation(10L, "旧来源", 1, 20L, "正文", null,
                7, 3, "章节", "PARAGRAPH", 10, 18, null, null, null, true);

        ChatEvent.Citation event = ChatEvent.Citation.from(source);

        assertThat(event.provenanceStatus()).isEqualTo(ChatEvent.ProvenanceStatus.UNVERSIONED);
        assertThat(event.versionId()).isNull();
        assertThat(event.versionNo()).isEqualTo(7);
        assertThat(event.contextTruncated()).isTrue();
    }

    @Test
    void oldDtoCanReadOriginalFieldsWhileIgnoringAdditions() throws Exception {
        var source = new RetrievalClient.Citation(5000000001L, "说明", 3, 5000004001L, "正文",
                5000002001L, 7, 4, null, null, null, null, null, null, null, false);

        String serialized = json.writeValueAsString(ChatEvent.Citation.from(source));

        assertThat(json.readValue(serialized, LegacyCitation.class))
                .isEqualTo(new LegacyCitation(5000000001L, "说明", 3, 5000004001L));
        JsonNode tree = json.readTree(serialized);
        assertThat(tree.get("docId").isIntegralNumber()).isTrue();
        assertThat(tree.get("chunkId").isIntegralNumber()).isTrue();
        assertThat(tree.get("pageNo").isInt()).isTrue();
        assertThat(tree.get("docTitle").isTextual()).isTrue();
    }

    @Test
    void swaggerExposesCitationFieldsWithTheirJsonTypesAndNullability() {
        var resolved = ModelConverters.getInstance()
                .resolveAsResolvedSchema(new AnnotatedType(ChatEvent.Citation.class));
        assertThat(resolved.schema.getProperties()).hasSize(ChatEvent.Citation.class.getRecordComponents().length);
        assertThat(resolved.schema.getProperties().keySet())
                .doesNotContain("content", "objectKey", "token", "signedUrl");
        for (var component : ChatEvent.Citation.class.getRecordComponents()) {
            Schema<?> field = (Schema<?>) resolved.schema.getProperties().get(component.getName());
            assertThat(field).as(component.getName()).isNotNull();
            if (field.get$ref() != null) {
                String reference = field.get$ref();
                field = resolved.referencedSchemas.get(reference.substring(reference.lastIndexOf('/') + 1));
            }
            assertThat(field.getDescription()).as(component.getName()).isNotBlank();
        }
        for (String field : List.of("docId", "chunkId", "versionId")) {
            Schema<?> property = (Schema<?>) resolved.schema.getProperties().get(field);
            assertThat(property.getType()).as(field).isEqualTo("integer");
            assertThat(property.getFormat()).as(field).isEqualTo("int64");
        }
        for (String field : List.of("pageNo", "versionNo", "chunkIdx", "sourceLineStart",
                "sourceLineEnd", "sourceCharStart", "sourceCharEnd")) {
            Schema<?> property = (Schema<?>) resolved.schema.getProperties().get(field);
            assertThat(property.getType()).as(field).isEqualTo("integer");
            assertThat(property.getFormat()).as(field).isEqualTo("int32");
            assertThat(property.getNullable()).as(field).isTrue();
        }
        Schema<?> versionId = (Schema<?>) resolved.schema.getProperties().get("versionId");
        assertThat(versionId.getNullable()).isTrue();
        Schema<?> truncated = (Schema<?>) resolved.schema.getProperties().get("contextTruncated");
        assertThat(truncated.getType()).isEqualTo("boolean");
        var status = ModelConverters.getInstance()
                .resolveAsResolvedSchema(new AnnotatedType(ChatEvent.ProvenanceStatus.class));
        assertThat(status.schema.getEnum()).containsExactly("VERSIONED", "UNVERSIONED");
    }

    /**
     * 能忽略增量字段的旧消费方，仅保留原有引用契约。
     *
     * @param docId    文档 ID
     * @param docTitle 文档标题
     * @param pageNo   来源页码
     * @param chunkId  分块 ID
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    private record LegacyCitation(long docId, String docTitle, Integer pageNo, long chunkId) {
    }
}
