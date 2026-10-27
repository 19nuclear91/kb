package com.zhishiku.kb.app.document;

import com.zhishiku.kb.app.document.model.DocumentProgress;
import com.zhishiku.kb.app.document.model.DocumentVersionView;
import com.zhishiku.kb.app.support.MapperRowStub;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** 仅核对生产结果映射，不执行数据库查询。 */
class DocumentVersionProjectionTest {
    @Test
    void indexedCandidateRemainsUnpublishedInVersionAndProgressProjection() throws Exception {
        var configuration = MapperRowStub.configuration("/mapper/DocumentMapper.xml");
        var row = new LinkedHashMap<String, Object>();
        row.put("id", 21L);
        row.put("doc_id", 10L);
        row.put("version_no", 2);
        row.put("current", false);
        row.put("publication_policy", "REVIEW_REQUIRED");
        row.put("publication_evidence", "NONE");
        row.put("review_state", "IN_REVIEW");
        row.put("state_version", 4L);
        row.put("index_status", "indexed");
        row.put("file_name", "v2.txt");
        Map<String, Object> parameters = Map.of("documentId", 10L, "versionId", 21L,
                "spaceIds", List.of(3L), "principalKeys", List.of("1:7"), "clearance", (short) 2, "aclPermission", "view");
        var version = MapperRowStub.readOne(configuration,
                "com.zhishiku.kb.app.document.mapper.DocumentMapper.listVersions", parameters, row, DocumentVersionView.class);
        assertThat(version.getCurrent()).isFalse();
        assertThat(version.getPublicationEvidence()).isEqualTo("NONE");
        assertThat(version.getReviewState()).isEqualTo("IN_REVIEW");
        assertThat(version.getStateVersion()).isEqualTo(4L);
        row.put("version_id", row.remove("id"));
        row.remove("current");
        row.remove("file_name");
        var progress = MapperRowStub.readOne(configuration,
                "com.zhishiku.kb.app.document.mapper.DocumentMapper.findProgress", parameters, row, DocumentProgress.class);
        assertThat(progress.getVersionId()).isEqualTo(21L);
        assertThat(progress.getIndexStatus()).isEqualTo("indexed");
        assertThat(progress.getPublicationPolicy()).isEqualTo("REVIEW_REQUIRED");
        assertThat(progress.getPublicationEvidence()).isEqualTo("NONE");
        assertThat(progress.getReviewState()).isEqualTo("IN_REVIEW");
    }
}
