package com.zhishiku.kb.app.document.metadata;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.zhishiku.kb.app.document.metadata.model.MetadataCommandRow;
import com.zhishiku.kb.app.document.metadata.model.MetadataViews;
import com.zhishiku.kb.app.document.metadata.model.TagRow;
import com.zhishiku.kb.app.document.model.DocumentEntity;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MetadataModelTest {
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules()
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    @Test
    void preservesNullableClassificationExactIdsAndMicroseconds() throws Exception {
        OffsetDateTime time = OffsetDateTime.parse("2026-11-01T01:02:03.123456Z");
        var value = new MetadataViews.Metadata(Long.MAX_VALUE, 2, "资料", null, null,
                List.of(new MetadataViews.Tag(Long.MAX_VALUE - 1, "停用", false)), Long.MAX_VALUE, time);
        var restored = json.readValue(json.writeValueAsBytes(value), MetadataViews.Metadata.class);
        assertThat(restored).isEqualTo(value);
        assertThat(json.readTree(json.writeValueAsBytes(value)).get("category").isNull()).isTrue();
    }

    @Test
    void preservesPersistenceFieldsAndMinimalReceipt() throws Exception {
        OffsetDateTime time = OffsetDateTime.parse("2026-11-01T01:02:03.999999Z");
        var tag = new TagRow(Long.MAX_VALUE, "主题", "主题", false, Long.MAX_VALUE, time, time);
        assertThat(json.readValue(json.writeValueAsBytes(tag), TagRow.class)).isEqualTo(tag);
        var row = new MetadataCommandRow("DOCUMENT_METADATA_UPDATE", "请求", 1,
                Long.MAX_VALUE, null, Long.MAX_VALUE, 0L, 1, "a".repeat(64), "{}", time);
        assertThat(json.readValue(json.writeValueAsBytes(row), MetadataCommandRow.class)).isEqualTo(row);
        var entity = DocumentEntity.builder().id(Long.MAX_VALUE).metadataVersion(0L)
                .summary(null).categoryId(null).updatedAt(time).build();
        assertThat(json.readValue(json.writeValueAsBytes(entity), DocumentEntity.class)).isEqualTo(entity);
    }
}
