package com.zhishiku.kb.app.document.applicability;

import com.zhishiku.kb.app.document.applicability.mapper.ApplicabilityMapper;
import com.zhishiku.kb.app.document.applicability.mapper.ValidityCommandMapper;
import com.zhishiku.kb.app.support.MapperRowStub;
import com.zhishiku.kb.common.conversation.SourceKey;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ApplicabilityMappingTest {
    static Configuration configuration() throws Exception {
        var configuration = MapperRowStub.configuration("/mapper/DocumentMapper.xml");
        for (String resource : List.of("/mapper/applicability/ApplicabilityMapper.xml", "/mapper/applicability/ValidityCommandMapper.xml")) {
            try (var input = ApplicabilityMappingTest.class.getResourceAsStream(resource)) {
                new XMLMapperBuilder(input, configuration, resource, configuration.getSqlFragments()).parse();
            }
        }
        return configuration;
    }

    @Test
    void locksOnlyVersionAndUsesBoundIdentityAndThreeSecondTimeouts() throws Exception {
        var configuration = configuration();
        var statement = configuration.getMappedStatement(ApplicabilityMapper.class.getName() + ".lockVersion");
        var sql = statement.getBoundSql(Map.of("documentId", 9007199254740993L, "versionId", Long.MAX_VALUE));
        assertThat(sql.getSql()).contains("FOR UPDATE OF v").doesNotContain("FOR UPDATE OF d", "9007199254740993");
        assertThat(sql.getParameterMappings()).extracting(mapping -> mapping.getProperty()).containsExactly("documentId", "versionId");
        for (String name : List.of("target", "lockVersion", "get", "batch", "insert", "update", "appendChange", "history", "hasPermission")) {
            assertThat(configuration.getMappedStatement(ApplicabilityMapper.class.getName() + "." + name).getTimeout()).isEqualTo(3);
        }
        for (String name : List.of("lockRequest", "get", "insert")) {
            assertThat(configuration.getMappedStatement(ValidityCommandMapper.class.getName() + "." + name).getTimeout()).isEqualTo(3);
        }
    }

    @Test
    void historyHasExplicitProjectionWithoutActorAndBindsCursor() throws Exception {
        var configuration = configuration();
        var statement = configuration.getMappedStatement(ApplicabilityMapper.class.getName() + ".history");
        var sql = statement.getBoundSql(Map.of("documentId", 1L, "versionId", 2L,
                "beforeTime", OffsetDateTime.parse("2026-11-15T00:00:00Z"), "beforeId", 3L, "limit", 21));
        assertThat(sql.getSql()).doesNotContain("actor", "user_id", "SELECT *");
        assertThat(sql.getParameterMappings()).extracting(mapping -> mapping.getProperty())
                .containsExactly("documentId", "versionId", "beforeTime", "beforeId", "limit");
        assertThat(statement.getResultMaps().getFirst().getConstructorResultMappings())
                .extracting(mapping -> mapping.getColumn()).contains("changed_at", "reason").doesNotContain("actor_user_id");
    }

    @Test
    void batchOnlyExpandsSuppliedPairsAndEmptyBatchFailsClosed() throws Exception {
        var configuration = configuration();
        var statement = configuration.getMappedStatement(ApplicabilityMapper.class.getName() + ".batch");
        BoundSql sql = statement.getBoundSql(Map.of("sources", List.of(new SourceKey(11, 21), new SourceKey(12, 22))));
        assertThat(sql.getParameterMappings()).hasSize(4);
        var values = sql.getParameterMappings().stream().map(mapping -> sql.getAdditionalParameter(mapping.getProperty())).toList();
        assertThat(values).containsExactly(11L, 21L, 12L, 22L);
        assertThat(statement.getBoundSql(Map.of("sources", List.of())).getSql()).endsWith("FALSE");
        assertThat(configuration.getMappedStatement(ValidityCommandMapper.class.getName() + ".get")
                .getBoundSql(Map.of("scope", "DOCUMENT_APPLICABILITY_UPDATE", "requestId", "private-request"))
                .getSql()).doesNotContain("private-request");
    }
}
