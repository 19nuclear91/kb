package com.zhishiku.kb.app.aiusage;

import com.zhishiku.kb.app.aiusage.model.AiCallRow;
import com.zhishiku.kb.app.aiusage.model.AiEventRow;
import com.zhishiku.kb.app.support.MapperRowStub;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class AiUsageMappingTest {
    @Test
    void coverageAndDeletionTimeUseInstantMappingWithoutConnection() throws Exception {
        var config = MapperRowStub.configuration("/mapper/AiUsageMapper.xml");
        Instant time = Instant.parse("2026-10-24T00:00:00.123456Z");
        var cleared = MapperRowStub.readOne(config, "com.zhishiku.kb.app.aiusage.mapper.AiUsageMapper.clearedThrough",
                Map.of(), Map.of("history_cleared_through", time), Instant.class);
        var boundary = MapperRowStub.readOne(config, "com.zhishiku.kb.app.aiusage.mapper.AiUsageMapper.deletionBoundary",
                Map.of("id", UUID.randomUUID().toString()), Map.of("greatest", time), Instant.class);
        assertThat(cleared).isEqualTo(time); assertThat(boundary).isEqualTo(time);
    }

    @Test
    void realMyBatisMappingReadsIdentityAndNullableUsageWithoutConnection() throws Exception {
        var config = MapperRowStub.configuration("/mapper/AiUsageMapper.xml");
        config.setMapUnderscoreToCamelCase(true);
        String id = UUID.randomUUID().toString();
        var row = MapperRowStub.readOne(config, "com.zhishiku.kb.app.aiusage.mapper.AiUsageMapper.call",
                Map.of("id", id), Map.of("id", 7L, "call_id", id, "mode", "SINGLE", "usage_status", "UNKNOWN",
                        "user_id", 9007199254740993L, "department_id", 8L), AiCallRow.class);
        assertThat(row.getCallId()).isEqualTo(id);
        assertThat(row.getUserId()).isEqualTo(9007199254740993L);
        assertThat(row.getPromptTokens()).isNull();
        var event = MapperRowStub.readOne(config, "com.zhishiku.kb.app.aiusage.mapper.AiUsageMapper.event",
                Map.of("id", id), Map.of("id", 9L, "event_id", id, "call_id", id, "payload", "{}"), AiEventRow.class);
        assertThat(event.getPayload()).isEqualTo("{}");
        assertThat(event.getEventId()).isEqualTo(id);
    }
}
