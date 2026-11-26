package com.zhishiku.kb.app.mapper.answercard;

import com.zhishiku.kb.app.domain.answercard.AnswerCardCommand;
import com.zhishiku.kb.app.enums.answercard.AnswerCardOperation;
import com.zhishiku.kb.app.support.MapperRowStub;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AnswerCardCommandMappingTest {
    @Test
    void mapsNullableCreateIdentityAndExactVersionsWithoutDatabase() throws Exception {
        Configuration configuration = new Configuration();
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.addMapper(AnswerCardCommandMapper.class);
        var row = new LinkedHashMap<String, Object>();
        row.put("operation", "ANSWER_CARD_CREATE");
        row.put("request_id", "request");
        row.put("actor_id", 9007199254740993L);
        row.put("space_id", 9007199254740994L);
        row.put("card_id", null);
        row.put("branch_id", null);
        row.put("revision_id", null);
        row.put("review_id", null);
        row.put("expected_card_version", null);
        row.put("digest_version", 1);
        row.put("input_digest", "a".repeat(64));
        row.put("receipt", "{\"cardId\":\"42\"}");
        row.put("created_at", OffsetDateTime.parse("2026-11-26T08:00:00+08:00"));
        var result = MapperRowStub.readOne(configuration, AnswerCardCommandMapper.class.getName() + ".find",
                Map.of("operation", "ANSWER_CARD_CREATE", "requestId", "request"), row, AnswerCardCommand.class);
        assertThat(result.operation()).isEqualTo(AnswerCardOperation.ANSWER_CARD_CREATE);
        assertThat(result.actorId()).isEqualTo(9007199254740993L);
        assertThat(result.spaceId()).isEqualTo(9007199254740994L);
        assertThat(result.expectedCardVersion()).isNull();
        assertThat(result.cardId()).isNull();
        assertThat(result.receipt()).isEqualTo("{\"cardId\":\"42\"}");
    }
}
