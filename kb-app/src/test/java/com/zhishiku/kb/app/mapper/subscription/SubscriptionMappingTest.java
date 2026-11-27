package com.zhishiku.kb.app.mapper.subscription;

import com.zhishiku.kb.app.config.UuidTypeHandler;
import com.zhishiku.kb.app.domain.subscription.KnowledgeSubscription;
import com.zhishiku.kb.app.domain.subscription.SubscriptionCommand;
import com.zhishiku.kb.app.enums.subscription.SubscriptionKind;
import com.zhishiku.kb.app.enums.subscription.SubscriptionState;
import com.zhishiku.kb.app.support.MapperRowStub;
import com.zhishiku.kb.common.review.PublicationOrder;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class SubscriptionMappingTest {
    private Configuration configuration() {
        Configuration configuration = new Configuration();
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.getTypeHandlerRegistry().register(UUID.class, UuidTypeHandler.class);
        configuration.addMapper(SubscriptionMapper.class);
        configuration.addMapper(SubscriptionCommandMapper.class);
        configuration.addMapper(KnowledgeOrderMapper.class);
        return configuration;
    }

    @Test
    void mapsExactSubscriptionIdentityWithoutDatabase() throws Exception {
        var id = UUID.randomUUID();
        var generation = UUID.randomUUID();
        var at = Instant.parse("2026-11-27T08:00:00Z");
        var row = new LinkedHashMap<String, Object>();
        row.put("id", id);
        row.put("user_id", 9007199254740993L);
        row.put("kind", "TAG");
        row.put("target_id", 9007199254740995L);
        row.put("state", "ACTIVE");
        row.put("generation_id", generation);
        row.put("state_version", 3L);
        row.put("created_at", at);
        row.put("updated_at", at);
        var result = MapperRowStub.readOne(configuration(), SubscriptionMapper.class.getName() + ".find",
                Map.of("userId", 9007199254740993L, "id", id), row, KnowledgeSubscription.class);
        assertThat(result.id()).isEqualTo(id);
        assertThat(result.generationId()).isEqualTo(generation);
        assertThat(result.userId()).isEqualTo(9007199254740993L);
        assertThat(result.targetId()).isEqualTo(9007199254740995L);
        assertThat(result.kind()).isEqualTo(SubscriptionKind.TAG);
        assertThat(result.state()).isEqualTo(SubscriptionState.ACTIVE);
        assertThat(result.updatedAt()).isEqualTo(at);
    }

    @Test
    void mapsOrderAndNullableCommandReceipt() throws Exception {
        var at = Instant.parse("2026-11-27T08:00:00Z");
        var orderRow = new LinkedHashMap<String, Object>();
        orderRow.put("position", 9007199254740993L);
        orderRow.put("business_time", at);
        var order = MapperRowStub.readOne(configuration(), KnowledgeOrderMapper.class.getName() + ".next",
                Map.of(), orderRow, PublicationOrder.class);
        assertThat(order.position()).isEqualTo(9007199254740993L);
        assertThat(order.businessTime()).isEqualTo(at);
        var commandRow = new LinkedHashMap<String, Object>();
        commandRow.put("actor_id", 10L);
        commandRow.put("request_id", "request");
        commandRow.put("operation", "PREFERENCE");
        commandRow.put("input_digest", "a".repeat(64));
        commandRow.put("receipt", null);
        commandRow.put("created_at", at);
        var command = MapperRowStub.readOne(configuration(), SubscriptionCommandMapper.class.getName() + ".find",
                Map.of("actor", 10L, "requestId", "request"), commandRow, SubscriptionCommand.class);
        assertThat(command.receipt()).isNull();
        assertThat(command.actorId()).isEqualTo(10L);
    }

    @Test
    void bindsCancellationToOriginalOwnerGenerationAndOrder() {
        var configuration = configuration();
        var sql = configuration.getMappedStatement(SubscriptionMapper.class.getName() + ".closePeriod")
                .getBoundSql(Map.of("userId", 10L, "generationId", UUID.randomUUID(), "position", 12L,
                        "at", Instant.parse("2026-11-27T08:00:00Z")));
        assertThat(sql.getParameterMappings()).extracting(mapping -> mapping.getProperty())
                .containsExactly("position", "at", "generationId", "userId");
    }
}
