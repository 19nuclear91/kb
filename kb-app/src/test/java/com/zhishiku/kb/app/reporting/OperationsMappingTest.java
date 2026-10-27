package com.zhishiku.kb.app.reporting;

import com.zhishiku.kb.app.support.MapperRowStub;
import org.apache.ibatis.mapping.SqlCommandType;
import org.apache.ibatis.scripting.defaults.DefaultParameterHandler;
import org.junit.jupiter.api.Test;

import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class OperationsMappingTest {
    private static final String PREFIX = "com.zhishiku.kb.app.reporting.mapper.OperationsReportMapper.";

    @Test
    void readOnlyStatementsHaveTimeoutNoSharedCacheAndMapSafeExactValues() throws Exception {
        var config = MapperRowStub.configuration("/mapper/OperationsReportMapper.xml");
        Instant now = Instant.parse("2026-10-27T00:00:00.123456Z");
        assertThat(MapperRowStub.readOne(config, PREFIX + "asOf", Map.of(), Map.of("statement_timestamp", now), Instant.class)).isEqualTo(now);
        for (String statement : List.of("asOf", "documents", "versions", "pendingReviews", "coverage", "events")) {
            var mapped = config.getMappedStatement(PREFIX + statement);
            assertThat(mapped.getSqlCommandType()).isEqualTo(SqlCommandType.SELECT);
            assertThat(mapped.getTimeout()).isEqualTo(5);
            assertThat(mapped.isUseCache()).isFalse();
        }
        var documents = MapperRowStub.readOne(config, PREFIX + "documents", Map.of(),
                Map.of("documentCount", "9007199254740993", "archivedDocumentCount", "1"), Map.class);
        assertThat(documents.get("documentCount")).isEqualTo("9007199254740993");
        var versions = MapperRowStub.readOne(config, PREFIX + "versions", Map.of(),
                Map.of("format", "UNKNOWN", "parsingState", "failed", "versionCount", "2"), Map.class);
        assertThat(versions.get("format")).isEqualTo("UNKNOWN");
        assertThat(versions.get("versionCount")).isEqualTo("2");
        var coverage = MapperRowStub.readOne(config, PREFIX + "coverage", Map.of(),
                Map.of("domain", "COMMENT", "protocolVersion", 1, "startedAt", now), Map.class);
        assertThat(coverage.get("domain")).isEqualTo("COMMENT");
        var event = MapperRowStub.readOne(config, PREFIX + "events", Map.of("from", now.minusSeconds(1), "to", now),
                Map.of("day", "2026-10-26", "approvedCount", "2", "decisionElapsedMsTotal", "18446744073709551616"), Map.class);
        assertThat(event.get("decisionElapsedMsTotal")).isEqualTo("18446744073709551616");
    }

    @Test
    void sharedDefinitionKeepsEveryTimeValueBoundIncludingUnknownCoverage() throws Exception {
        var config = MapperRowStub.configuration("/mapper/OperationsReportMapper.xml");
        var statement = config.getMappedStatement(PREFIX + "events");
        Instant start = Instant.parse("2026-10-26T00:00:00.123456Z");
        var parameters = new HashMap<String, Object>();
        parameters.put("from", start);
        parameters.put("to", start.plusSeconds(86400));
        parameters.put("reviewFrom", null);
        parameters.put("commentFrom", start.plusSeconds(3600));
        var bound = statement.getBoundSql(parameters);
        assertThat(bound.getParameterMappings()).extracting(mapping -> mapping.getProperty())
                .containsExactly("from", "to", "reviewFrom", "commentFrom");
        var jdbc = mock(PreparedStatement.class);
        new DefaultParameterHandler(statement, parameters, bound).setParameters(jdbc);
        verify(jdbc).setTimestamp(1, Timestamp.from(start));
        verify(jdbc).setTimestamp(2, Timestamp.from(start.plusSeconds(86400)));
        verify(jdbc).setNull(3, Types.TIMESTAMP);
        verify(jdbc).setTimestamp(4, Timestamp.from(start.plusSeconds(3600)));
    }
}
