package com.zhishiku.kb.app.mapper.answercard;

import com.zhishiku.kb.app.domain.answercard.AnswerCard;
import com.zhishiku.kb.app.domain.dto.answercard.AnswerCardSourceFact;
import com.zhishiku.kb.app.domain.dto.answercard.AnswerCardSourceKey;
import com.zhishiku.kb.app.support.MapperRowStub;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AnswerCardSourceMappingTest {
    @Test
    void dateRevisionZeroAndHistoricalPublishedVersionMapWithoutInventingCurrentIdentity() throws Exception {
        var configuration = configuration();
        var row = new LinkedHashMap<String, Object>();
        row.put("document_id", 9007199254740993L);
        row.put("version_id", 9007199254740994L);
        row.put("space_id", 9L);
        row.put("title", "可读旧发布依据");
        row.put("version_no", 1);
        row.put("current_version_id", 9007199254740995L);
        row.put("applicability_version", 0L);
        row.put("effective_from", null);
        row.put("until_kind", "UNDECLARED");
        row.put("effective_until", null);
        var params = Map.<String, Object>of("sources", List.of(new AnswerCardSourceKey(9007199254740993L, 9007199254740994L, 0)),
                "spaceIds", List.of(9L), "principalKeys", List.of("1:7"), "clearance", (short) 2);
        var result = MapperRowStub.readOne(configuration, AnswerCardSourceMapper.class.getName() + ".facts", params, row, AnswerCardSourceFact.class);
        assertThat(result.applicabilityVersion()).isZero();
        assertThat(result.effectiveFrom()).isNull();
        assertThat(result.versionId()).isEqualTo(9007199254740994L);
        assertThat(result.currentVersionId()).isEqualTo(9007199254740995L);
        row.put("applicability_version", 2L);
        row.put("effective_from", LocalDate.of(2027, 1, 1));
        row.put("until_kind", "DATE");
        row.put("effective_until", LocalDate.of(2027, 12, 31));
        result = MapperRowStub.readOne(configuration, AnswerCardSourceMapper.class.getName() + ".facts", params, row, AnswerCardSourceFact.class);
        assertThat(result.applicabilityVersion()).isEqualTo(2);
        assertThat(result.effectiveUntil()).isEqualTo(LocalDate.of(2027, 12, 31));
    }

    @Test
    void draftCardMappingKeepsNullablePublishedPointerAndExactConcurrencyVersion() throws Exception {
        var configuration = configuration();
        var row = new LinkedHashMap<String, Object>();
        row.put("id", 9007199254740993L);
        row.put("space_id", 9L);
        row.put("card_version", 9007199254740994L);
        row.put("published_revision_id", null);
        row.put("active_branch_id", 5L);
        row.put("created_at", OffsetDateTime.parse("2026-11-26T10:00:00+08:00"));
        var result = MapperRowStub.readOne(configuration, AnswerCardMapper.class.getName() + ".card", Map.of("id", 9007199254740993L), row, AnswerCard.class);
        assertThat(result.publishedRevisionId()).isNull();
        assertThat(result.cardVersion()).isEqualTo(9007199254740994L);
        assertThat(result.activeBranchId()).isEqualTo(5);
    }

    private Configuration configuration() throws Exception {
        var configuration = new Configuration();
        configuration.setMapUnderscoreToCamelCase(true);
        for (String resource : List.of("AuthorizationRules.xml", "DocumentMapper.xml", "DocumentReviewEligibilityMapper.xml",
                "answercard/AnswerCardSourceMapper.xml", "answercard/AnswerCardMapper.xml", "answercard/AnswerCardQueryMapper.xml")) {
            try (var input = getClass().getResourceAsStream("/mapper/" + resource)) {
                new XMLMapperBuilder(input, configuration, resource, configuration.getSqlFragments()).parse();
            }
        }
        return configuration;
    }
}
