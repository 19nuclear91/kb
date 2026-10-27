package com.zhishiku.kb.app.document;

import com.zhishiku.kb.app.document.model.DocumentCitationRow;
import com.zhishiku.kb.app.support.MapperRowStub;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 只检查结果映射，不连接数据库。
 */
class DocumentCitationMapperTest {
    private static final String STATEMENT =
            "com.zhishiku.kb.app.document.mapper.DocumentMapper.findCitation";

    @Test
    void matchingChunkMapsAllSourceFields() throws Exception {
        assertThat(read(row())).isEqualTo(new DocumentCitationRow(10L, "说明", 20L, 1, 30L, 0,
                "完整正文", 2, "权限", "PARAGRAPH", 4, 9, 0, 60, "section-1"));
    }

    @Test
    void missingChunkRetainsVisibleParentInsteadOfUsingZeroAsAChunkId() throws Exception {
        Map<String, Object> row = row();
        for (String column : List.of("matched_chunk_id", "chunk_idx", "content", "page_no", "heading_path",
                "structure_type", "source_line_start", "source_line_end", "source_char_start", "source_char_end",
                "source_locator")) {
            row.put(column, null);
        }
        assertThat(read(row)).isEqualTo(new DocumentCitationRow(10L, "说明", 20L, 1,
                null, null, null, null, null, null, null, null, null, null, null));
    }

    @Test
    void legacyChunkKeepsUnknownSourceFieldsNull() throws Exception {
        Map<String, Object> row = row();
        for (String column : List.of("page_no", "heading_path", "structure_type", "source_line_start",
                "source_line_end", "source_char_start", "source_char_end", "source_locator")) {
            row.put(column, null);
        }
        assertThat(read(row)).isEqualTo(new DocumentCitationRow(10L, "说明", 20L, 1,
                30L, 0, "完整正文", null, null, null, null, null, null, null, null));
    }

    private static Map<String, Object> parameters(List<Long> spaces, List<String> principals) {
        return Map.of("documentId", 10L, "versionId", 20L, "chunkId", 30L, "spaceIds", spaces,
                "principalKeys", principals, "clearance", (short) 2, "aclPermission", "download");
    }

    private static DocumentCitationRow read(Map<String, Object> row) throws Exception {
        return MapperRowStub.readOne(MapperRowStub.configuration("/mapper/DocumentMapper.xml"),
                STATEMENT, parameters(List.of(3L), List.of("1:7")), row, DocumentCitationRow.class);
    }

    private static Map<String, Object> row() {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("doc_id", 10L);
        row.put("doc_title", "说明");
        row.put("version_id", 20L);
        row.put("version_no", 1);
        row.put("matched_chunk_id", 30L);
        row.put("chunk_idx", 0);
        row.put("content", "完整正文");
        row.put("page_no", 2);
        row.put("heading_path", "权限");
        row.put("structure_type", "PARAGRAPH");
        row.put("source_line_start", 4);
        row.put("source_line_end", 9);
        row.put("source_char_start", 0);
        row.put("source_char_end", 60);
        row.put("source_locator", "section-1");
        return row;
    }
}
