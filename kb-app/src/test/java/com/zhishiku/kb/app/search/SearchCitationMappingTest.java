package com.zhishiku.kb.app.search;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.zhishiku.kb.app.search.model.ChunkHit;
import com.zhishiku.kb.app.search.model.SearchFilters;
import com.zhishiku.kb.app.support.MapperRowStub;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 生产构造器映射与 JSON 兼容性测试，JDBC 仅使用内存替身。
 */
class SearchCitationMappingTest {
    private static final String STATEMENT =
            "com.zhishiku.kb.app.search.mapper.SearchMapper.filteredSearch";

    @Test
    void mapsVersionIdentityAndEverySourceFieldFromTheSameRow() throws Exception {
        ChunkHit hit = read(row());
        assertThat(hit).isEqualTo(new ChunkHit(4001L, 1001L, 2001L, 2, "说明", 3L,
                (short) 1, 0, "正文", 7, "权限/读取", "PARAGRAPH", 10, 18, 0, 96,
                "section-2", 0.25));
    }

    @Test
    void unknownSourcesStayNullAndDoNotBecomeZero() throws Exception {
        Map<String, Object> row = row();
        for (String column : List.of("page_no", "heading_path", "structure_type", "source_line_start",
                "source_line_end", "source_char_start", "source_char_end", "source_locator")) {
            row.put(column, null);
        }
        ChunkHit hit = read(row);
        assertThat(hit.versionId()).isEqualTo(2001L);
        assertThat(hit.versionNo()).isEqualTo(2);
        assertThat(hit.pageNo()).isNull();
        assertThat(hit.headingPath()).isNull();
        assertThat(hit.structureType()).isNull();
        assertThat(hit.sourceLineStart()).isNull();
        assertThat(hit.sourceLineEnd()).isNull();
        assertThat(hit.sourceCharStart()).isNull();
        assertThat(hit.sourceCharEnd()).isNull();
        assertThat(hit.sourceLocator()).isNull();
        assertThat(hit.chunkIdx()).isZero();
    }

    @Test
    void jsonOnlyAddsVersionFieldsToTheExistingContract() throws Exception {
        ChunkHit hit = read(row());
        ChunkHit legacy = new ChunkHit(hit.chunkId(), hit.docId(), hit.docTitle(), hit.spaceId(),
                hit.securityLevel(), hit.chunkIdx(), hit.content(), hit.pageNo(), hit.headingPath(),
                hit.structureType(), hit.sourceLineStart(), hit.sourceLineEnd(), hit.sourceCharStart(),
                hit.sourceCharEnd(), hit.sourceLocator(), hit.score());
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode actual = mapper.valueToTree(hit);
        assertThat(actual.get("versionId").isIntegralNumber()).isTrue();
        assertThat(actual.get("versionNo").isIntegralNumber()).isTrue();
        ObjectNode expected = mapper.valueToTree(legacy);
        actual.remove(List.of("versionId", "versionNo"));
        expected.remove(List.of("versionId", "versionNo"));
        assertThat(actual).isEqualTo(expected);
        assertThat(legacy.versionId()).isNull();
        assertThat(legacy.versionNo()).isNull();

        ChunkHit oldest = new ChunkHit(4001L, 1001L, "说明", 3L, (short) 1, 0, "正文", null, 0.25);
        assertThat(oldest.versionId()).isNull();
        assertThat(oldest.versionNo()).isNull();
        assertThat(oldest.pageNo()).isNull();
    }

    private static ChunkHit read(Map<String, Object> row) throws Exception {
        return MapperRowStub.readOne(MapperRowStub.configuration("/mapper/SearchMapper.xml"),
                STATEMENT, Map.of("spaceIds", List.of(), "principalKeys", List.of(), "filters", SearchFilters.empty()), row, ChunkHit.class);
    }

    private static Map<String, Object> row() {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("chunk_id", 4001L);
        row.put("doc_id", 1001L);
        row.put("version_id", 2001L);
        row.put("version_no", 2);
        row.put("doc_title", "说明");
        row.put("space_id", 3L);
        row.put("security_level", (short) 1);
        row.put("chunk_idx", 0);
        row.put("content", "正文");
        row.put("page_no", 7);
        row.put("heading_path", "权限/读取");
        row.put("structure_type", "PARAGRAPH");
        row.put("source_line_start", 10);
        row.put("source_line_end", 18);
        row.put("source_char_start", 0);
        row.put("source_char_end", 96);
        row.put("source_locator", "section-2");
        row.put("score", 0.25);
        return row;
    }
}
