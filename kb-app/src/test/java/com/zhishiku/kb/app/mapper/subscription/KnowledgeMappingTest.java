package com.zhishiku.kb.app.mapper.subscription;

import com.zhishiku.kb.app.config.UuidTypeHandler;
import com.zhishiku.kb.app.domain.dto.subscription.KnowledgeRows;
import com.zhishiku.kb.app.domain.dto.subscription.KnowledgeWork;
import com.zhishiku.kb.app.domain.dto.subscription.SubscriptionQuery;
import com.zhishiku.kb.app.enums.subscription.SubscriptionKind;
import com.zhishiku.kb.app.support.MapperRowStub;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.parsing.XPathParser;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class KnowledgeMappingTest {
    private Configuration load() throws Exception {
        var configuration = MapperRowStub.configuration("/mapper/DocumentMapper.xml");
        configuration.getTypeHandlerRegistry().register(UUID.class, UuidTypeHandler.class);
        for (String file : List.of("KnowledgeQueryMapper", "KnowledgeDerivationMapper", "SubscriptionQueryMapper")) {
            parse(configuration, file);
        }
        return configuration;
    }

    private void parse(Configuration configuration, String file) throws Exception {
        String path = "/mapper/subscription/" + file + ".xml";
        try (var stream = getClass().getResourceAsStream(path)) {
            new XMLMapperBuilder(stream, configuration, path, configuration.getSqlFragments()).parse();
        }
    }

    @Test
    void realMappingPreservesClaimTokensNullableWeeklyIdentityAndExactUpdateIds() throws Exception {
        var configuration = load();
        var values = new LinkedHashMap<String, Object>();
        UUID id = UUID.randomUUID();
        values.put("id", id);
        values.put("week_start", null);
        values.put("token", 9007199254740993L);
        values.put("recipient_cursor", 22L);
        values.put("attempts", 3);
        values.put("takeover", true);
        var claim = MapperRowStub.readOne(configuration, KnowledgeDerivationMapper.class.getName() + ".claimMatches",
                Map.of("owner", "app", "limit", 50, "leaseSeconds", 60), values, KnowledgeWork.Claim.class);
        assertThat(claim.token()).isEqualTo(9007199254740993L);
        assertThat(claim.weekStart()).isNull();
        values.clear();
        values.put("update_id", id);
        values.put("position", 32L);
        values.put("document_id", 9007199254740993L);
        values.put("version_id", 9007199254740995L);
        values.put("version_no", 3);
        values.put("title", "本人可读标题");
        values.put("published_at", Instant.EPOCH);
        values.put("week_start", LocalDate.of(2026, 11, 23));
        values.put("mode", "WEEKLY");
        var update = MapperRowStub.readOne(configuration, KnowledgeQueryMapper.class.getName() + ".update", parameters(), values, KnowledgeRows.Update.class);
        assertThat(update.versionId()).isEqualTo(9007199254740995L);
        assertThat(update.updateId()).isEqualTo(id);
    }

    @Test
    void allDerivationStatementsBindParametersWithoutDatabaseAndNoRawUserInputExpansion() throws Exception {
        var configuration = load();
        var parameters = parameters();
        var claim = new KnowledgeWork.Claim(UUID.randomUUID(), LocalDate.of(2026, 11, 23), 3, 10, 0, false);
        parameters.putAll(Map.of("claim", claim, "owner", "app", "leaseSeconds", 60, "eventId", claim.id(), "after", 10L,
                "users", List.of(11L), "week", claim.weekStart(), "position", 20L, "at", Instant.EPOCH, "done", false));
        parameters.putAll(Map.of("cursor", 11L, "notified", true, "stage", "match", "delay", 30, "failed", true,
                "id", claim.id(), "userId", 11L, "mode", "WEEKLY", "generationId", UUID.randomUUID(), "kind", "WEEKLY"));
        for (var method : KnowledgeDerivationMapper.class.getMethods()) {
            var bound = configuration.getMappedStatement(KnowledgeDerivationMapper.class.getName() + "." + method.getName()).getBoundSql(parameters);
            assertThat(bound.getSql()).isNotBlank();
            for (var mapping : bound.getParameterMappings()) {
                assertThat(mapping.getTypeHandler()).isNotNull();
            }
        }
        for (var kind : SubscriptionKind.values()) {
            var query = new SubscriptionQuery(7, List.of(3L), List.of("1:7"), (short) 1, kind, 12L, "%literal\\_%",
                    UUID.randomUUID(), 10L, null, 21, false);
            var bound = configuration.getMappedStatement(SubscriptionQueryMapper.class.getName() + ".candidates").getBoundSql(query);
            assertThat(bound.getParameterMappings()).extracting("property").contains("pattern", "actor", "kind", "afterId", "limit");
        }
    }

    @Test
    void updateDigestAndNotificationQueriesUseTheSameAuthoritativeVisibilityFragment() throws Exception {
        var configuration = MapperRowStub.configuration("/mapper/DocumentMapper.xml");
        String predicate = "com.zhishiku.kb.app.document.mapper.DocumentMapper.versionInteractivePredicate";
        configuration.getSqlFragments().replace(predicate, new XPathParser("<sql id='versionInteractivePredicate'>#{visibilityProbe} = 1</sql>").evalNode("/sql"));
        parse(configuration, "KnowledgeQueryMapper");
        for (String statement : List.of("updates", "update", "eligible", "digests", "digest", "groups", "members")) {
            var bound = configuration.getMappedStatement(KnowledgeQueryMapper.class.getName() + "." + statement).getBoundSql(parameters());
            assertThat(bound.getParameterMappings()).extracting("property").contains("visibilityProbe", "actor");
        }
    }

    private Map<String, Object> parameters() {
        var values = new HashMap<String, Object>();
        values.putAll(Map.of("actor", 7L, "spaceIds", List.of(3L), "principalKeys", List.of("1:7"), "clearance", (short) 2,
                "updateId", UUID.randomUUID(), "digestId", UUID.randomUUID(), "weekStart", LocalDate.of(2026, 11, 23), "limit", 21));
        values.putAll(Map.of("documentId", 9007199254740993L, "dispatchPosition", 50L, "beforePosition", 30L,
                "beforeDocumentId", 15L, "beforeWeek", LocalDate.of(2026, 11, 16)));
        return values;
    }
}
