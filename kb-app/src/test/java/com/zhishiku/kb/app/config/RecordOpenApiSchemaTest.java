package com.zhishiku.kb.app.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhishiku.kb.app.document.model.DocumentSecurityLevelChange;
import com.zhishiku.kb.app.document.model.DocumentSecurityLevelResult;
import com.zhishiku.kb.app.document.model.DocumentSecurityLevelState;
import com.zhishiku.kb.app.embedding.model.EmbeddingProfileView;
import com.zhishiku.kb.app.embedding.model.EmbeddingReceipt;
import com.zhishiku.kb.app.embedding.model.EmbeddingViews;
import com.zhishiku.kb.app.search.embedding.EmbeddingInstanceReadiness;
import com.zhishiku.kb.app.space.model.DocumentAdminDeleteResult;
import com.zhishiku.kb.common.embedding.EmbeddingProfile;
import io.swagger.v3.core.converter.AnnotatedType;
import io.swagger.v3.core.converter.ModelConverters;
import io.swagger.v3.oas.models.media.Schema;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 验证补齐的接口 record 经真实 Swagger 转换器处理后仍保留字段名和说明。
 */
class RecordOpenApiSchemaTest {

    @Test
    void profileViewPreservesTheExistingJsonContract() {
        var profile = new EmbeddingProfile("local-v1", "local", "local", "hash", "1", 1024,
                "cosine", "1", "1", "query", "document", "1");
        var mapper = new ObjectMapper();
        JsonNode actual = mapper.valueToTree(EmbeddingProfileView.from(profile));
        JsonNode expected = mapper.valueToTree(profile);
        assertThat(actual).isEqualTo(expected);
    }

    @Test
    void recordSchemasExposeDescriptionsWithoutChangingPropertyNames() {
        for (Class<?> type : List.of(DocumentSecurityLevelChange.class, DocumentSecurityLevelResult.class,
                DocumentSecurityLevelState.class, EmbeddingReceipt.class, EmbeddingViews.Profile.class,
                EmbeddingViews.Status.class, EmbeddingViews.Run.class, EmbeddingInstanceReadiness.Snapshot.class,
                EmbeddingProfileView.class, DocumentAdminDeleteResult.class)) {
            var resolved = ModelConverters.getInstance().resolveAsResolvedSchema(new AnnotatedType(type));
            assertThat(resolved.schema.getDescription()).as(type.getName()).isNotBlank();
            for (var component : type.getRecordComponents()) {
                Schema<?> property = (Schema<?>) resolved.schema.getProperties().get(component.getName());
                assertThat(property).as(type.getName() + "." + component.getName()).isNotNull();
                if (property.get$ref() != null) {
                    String reference = property.get$ref();
                    property = resolved.referencedSchemas.get(reference.substring(reference.lastIndexOf('/') + 1));
                }
                assertThat(property.getDescription()).as(component.getName()).isNotBlank();
            }
            assertThat(resolved.schema.getProperties()).hasSize(type.getRecordComponents().length);
        }
    }
}
