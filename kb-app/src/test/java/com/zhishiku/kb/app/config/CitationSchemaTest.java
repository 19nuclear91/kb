package com.zhishiku.kb.app.config;

import com.zhishiku.kb.app.document.model.DocumentCitationView;
import com.zhishiku.kb.app.search.model.ChunkHit;
import io.swagger.v3.core.converter.AnnotatedType;
import io.swagger.v3.core.converter.ModelConverters;
import io.swagger.v3.oas.models.media.Schema;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 在进程内检查引用的公开字段与 Swagger 类型，不启动应用。
 */
class CitationSchemaTest {
    @Test
    void documentCitationAndChunkSchemasExposeNoStorageFields() {
        for (Class<?> type : List.of(DocumentCitationView.class, DocumentCitationView.Chunk.class)) {
            var resolved = ModelConverters.getInstance().resolveAsResolvedSchema(new AnnotatedType(type));
            assertThat(resolved.schema.getProperties()).hasSize(type.getRecordComponents().length);
            assertThat(resolved.schema.getProperties().keySet()).doesNotContain("objectKey", "signedUrl", "token");
            for (var component : type.getRecordComponents()) {
                Schema<?> field = (Schema<?>) resolved.schema.getProperties().get(component.getName());
                assertThat(field).as(component.getName()).isNotNull();
                if (field.get$ref() != null) {
                    String reference = field.get$ref();
                    field = resolved.referencedSchemas.get(reference.substring(reference.lastIndexOf('/') + 1));
                }
                assertThat(field.getDescription()).as(component.getName()).isNotBlank();
            }
        }
        var status = ModelConverters.getInstance()
                .resolveAsResolvedSchema(new AnnotatedType(DocumentCitationView.Status.class));
        assertThat(status.schema.getEnum()).containsExactly("AVAILABLE", "CHUNK_UNAVAILABLE");
    }

    @Test
    void searchSchemaDescribesEveryFieldAndVersionTypes() {
        var resolved = ModelConverters.getInstance().resolveAsResolvedSchema(new AnnotatedType(ChunkHit.class));
        assertThat(resolved.schema.getProperties()).hasSize(ChunkHit.class.getRecordComponents().length);
        for (var component : ChunkHit.class.getRecordComponents()) {
            Schema<?> field = (Schema<?>) resolved.schema.getProperties().get(component.getName());
            assertThat(field).as(component.getName()).isNotNull();
            assertThat(field.getDescription()).as(component.getName()).isNotBlank();
        }
        Schema<?> versionId = (Schema<?>) resolved.schema.getProperties().get("versionId");
        assertThat(versionId.getType()).isEqualTo("integer");
        assertThat(versionId.getFormat()).isEqualTo("int64");
        Schema<?> versionNo = (Schema<?>) resolved.schema.getProperties().get("versionNo");
        assertThat(versionNo.getType()).isEqualTo("integer");
        assertThat(versionNo.getFormat()).isEqualTo("int32");
    }
}
