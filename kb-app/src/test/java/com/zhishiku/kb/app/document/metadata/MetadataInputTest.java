package com.zhishiku.kb.app.document.metadata;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.zhishiku.kb.app.document.metadata.service.MetadataException;
import com.zhishiku.kb.app.document.metadata.support.MetadataInput;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MetadataInputTest {
    private static final String UPDATE = "{\"title\":\"  标题  \",\"summary\":null,\"categoryId\":null,\"tagIds\":[9,2],\"expectedMetadataVersion\":0}";

    @Test
    void explicitNullClearsAndTagOrderNormalizes() {
        var value = MetadataInput.update(body(UPDATE));
        assertThat(value.title()).isEqualTo("标题");
        assertThat(value.summary()).isNull();
        assertThat(value.categoryId()).isNull();
        assertThat(value.tagIds()).containsExactly(2L, 9L);
        ObjectNode missing = body(UPDATE);
        missing.remove("categoryId");
        assertThatThrownBy(() -> MetadataInput.update(missing)).isInstanceOf(MetadataException.class);
    }

    @Test
    void rejectsAmbiguousJsonAndSmuggledFields() {
        for (String raw : List.of("{\"title\":1,\"title\":2}", "{} {}", "[]", "null", "{", "")) {
            assertThatThrownBy(() -> body(raw)).isInstanceOf(MetadataException.class);
        }
        for (String field : List.of("spaceId", "securityLevel", "ownerId")) {
            ObjectNode value = body(UPDATE);
            value.put(field, 7);
            assertThatThrownBy(() -> MetadataInput.update(value)).isInstanceOf(MetadataException.class);
        }
        assertThatThrownBy(() -> MetadataInput.update(body(UPDATE.replace("[9,2]", "[2,2]"))))
                .isInstanceOf(MetadataException.class);
    }

    @Test
    void unicodeCountsCodePointsAndRejectsBrokenSurrogates() {
        ObjectNode value = body(UPDATE);
        value.put("title", "😀".repeat(512));
        value.put("summary", "\u00a0 \n ");
        assertThat(MetadataInput.update(value).summary()).isNull();
        value.put("title", "😀".repeat(513));
        assertThatThrownBy(() -> MetadataInput.update(value)).isInstanceOf(MetadataException.class);
        for (String title : List.of("\uD800", "\uDC00", "a\u0000b")) {
            value.put("title", title);
            assertThatThrownBy(() -> MetadataInput.update(value)).isInstanceOf(MetadataException.class);
        }
        var name = MetadataInput.tag(body("{\"name\":\"  E\\u0301  \"}"), true);
        assertThat(name.name()).isEqualTo("É");
        assertThat(name.normalizedName()).isEqualTo("é");
    }

    @Test
    void rejectsNonIntegerAndOverlongInputWithoutEcho() {
        for (String version : List.of("-1", "1.0", "\"1\"", "9223372036854775808", "null")) {
            assertThatThrownBy(() -> MetadataInput.update(body(UPDATE.replace(":0}", ":" + version + "}"))))
                    .isInstanceOf(MetadataException.class);
        }
        assertThatThrownBy(() -> MetadataInput.body(new ByteArrayInputStream(new byte[16385]), 16384))
                .isInstanceOfSatisfying(MetadataException.class,
                        e -> assertThat(e.reason()).isEqualTo(MetadataException.Reason.PAYLOAD_TOO_LARGE));
        assertThatThrownBy(() -> MetadataInput.requestId("a\nb")).isInstanceOf(MetadataException.class);
        assertThat(MetadataInput.requestId(" 请求 ")).isEqualTo("请求");
    }

    @Test
    void queryBoundsAndTimezoneAreExact() {
        var query = MetadataInput.query(body("{\"page\":2147483647,\"size\":100,\"tagIds\":[9223372036854775807]}"));
        assertThat(query.offset()).isEqualTo(214748364600L);
        assertThat(query.tagIds()).containsExactly(Long.MAX_VALUE);
        for (String raw : List.of("{\"categoryId\":1,\"uncategorized\":true}", "{\"size\":101}",
                "{\"updatedFrom\":\"2026-11-01T00:00:00\"}",
                "{\"updatedFrom\":\"2026-11-01T00:00:00Z\",\"updatedTo\":\"2026-11-01T01:00:00+01:00\"}")) {
            assertThatThrownBy(() -> MetadataInput.query(body(raw))).isInstanceOf(MetadataException.class);
        }
        assertThat(MetadataInput.literalPattern("%_\\")).isEqualTo("%\\%\\_\\\\%");
        assertThatThrownBy(() -> MetadataInput.categoryQuery(Map.of("limit", new String[]{"1", "2"})))
                .isInstanceOf(MetadataException.class);
    }

    private static ObjectNode body(String raw) {
        return MetadataInput.body(new ByteArrayInputStream(raw.getBytes(StandardCharsets.UTF_8)), MetadataInput.WRITE_LIMIT);
    }
}
