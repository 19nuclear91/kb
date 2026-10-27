package com.zhishiku.kb.app.document;

import com.zhishiku.kb.app.config.UuidTypeHandler;
import com.zhishiku.kb.app.document.mapper.DocumentReviewEligibilityMapper;
import com.zhishiku.kb.app.document.mapper.DocumentReviewQueryMapper;
import com.zhishiku.kb.app.document.mapper.DocumentReviewMapper;
import com.zhishiku.kb.app.document.model.ReviewVersionState;
import com.zhishiku.kb.app.document.model.ReviewListRow;
import com.zhishiku.kb.app.document.model.ReviewerQualification;
import com.zhishiku.kb.app.support.MapperRowStub;
import com.zhishiku.kb.common.review.PublicationPolicy;
import com.zhishiku.kb.common.review.ReviewState;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 使用真实 MyBatis 映射与替代 JDBC 结果集，不连接数据库或断言 SQL 文本。 */
class DocumentReviewMappingTest {
    @Test
    void documentLockIsAnIndependentCallBeforeVersionLock() {
        var mapper = mock(DocumentReviewMapper.class, CALLS_REAL_METHODS);
        when(mapper.lockDocument(10)).thenReturn(10L);
        when(mapper.lockVersionRow(10, 20)).thenReturn(new ReviewVersionState());
        mapper.lockVersion(10, 20);
        var order = inOrder(mapper);
        order.verify(mapper).lockDocument(10);
        order.verify(mapper).lockVersionRow(10, 20);
    }

    @Test
    void mapsExactIdsNullableMinimalFieldsAndPendingPosition() throws Exception {
        var configuration = MapperRowStub.configuration("/mapper/DocumentReviewEligibilityMapper.xml");
        configuration.getTypeHandlerRegistry().register(UUID.class, UuidTypeHandler.class);
        try (var input = getClass().getResourceAsStream("/mapper/DocumentReviewQueryMapper.xml")) {
            new XMLMapperBuilder(input, configuration, "/mapper/DocumentReviewQueryMapper.xml", configuration.getSqlFragments()).parse();
        }
        var row = new LinkedHashMap<String, Object>();
        row.put("document_id", 9007199254740993L);
        row.put("version_id", 9007199254740994L);
        row.put("version_no", 2);
        row.put("state_version", 9007199254740995L);
        row.put("index_status", "indexed");
        row.put("publication_policy", "REVIEW_REQUIRED");
        row.put("publication_evidence", "NONE");
        UUID review = UUID.randomUUID();
        row.put("review_id", review);
        row.put("round_no", 1);
        row.put("review_state", "IN_REVIEW");
        row.put("submitted_at", Instant.EPOCH);
        row.put("can_read", false);
        row.put("title", null);
        var result = MapperRowStub.readOne(configuration, DocumentReviewQueryMapper.class.getName() + ".mine",
                Map.of("actor", 7, "limit", 21), row, ReviewListRow.class);
        assertThat(result.getVersionId()).isEqualTo(9007199254740994L);
        assertThat(result.getReviewId()).isEqualTo(review);
        assertThat(result.getStateVersion()).isEqualTo(9007199254740995L);
        assertThat(result.getPublicationPolicy()).isEqualTo(PublicationPolicy.REVIEW_REQUIRED);
        assertThat(result.getReviewState()).isEqualTo(ReviewState.IN_REVIEW);
        assertThat(result.getSubmittedAt()).isEqualTo(Instant.EPOCH);
        assertThat(result.getTitle()).isNull();
    }

    @Test
    void qualificationProjectionMapsEveryIndependentFact() throws Exception {
        var configuration = MapperRowStub.configuration("/mapper/DocumentReviewEligibilityMapper.xml");
        var row = Map.<String, Object>of("user_id", 7L, "enabled", true, "department_enabled", true,
                "clearance", (short) 2, "security_level", (short) 2, "can_view", true, "can_review", true);
        var result = MapperRowStub.readOne(configuration, DocumentReviewEligibilityMapper.class.getName() + ".candidates",
                Map.of("documentId", 10L), row, ReviewerQualification.class);
        assertThat(result.eligible(8L, 9L)).isTrue();
        assertThat(result.eligible(7L, 9L)).isFalse();
    }
}
