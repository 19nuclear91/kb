package com.zhishiku.kb.app.conversation;

import com.zhishiku.kb.app.config.UuidTypeHandler;
import com.zhishiku.kb.app.conversation.mapper.ConversationMapper;
import com.zhishiku.kb.app.conversation.model.TurnRow;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.executor.resultset.DefaultResultSetHandler;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.RowBounds;
import org.junit.jupiter.api.Test;

import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ConversationMappingTest {
    @Test
    void productionMappingReadsUuidTimestampNullableUsageAndLongPositionsWithoutConnection() throws Exception {
        var config = new Configuration();
        config.getTypeHandlerRegistry().register(UuidTypeHandler.class);
        try (var stream = Resources.getResourceAsStream("mapper/ConversationMapper.xml")) {
            new XMLMapperBuilder(stream, config, "mapper/ConversationMapper.xml", config.getSqlFragments()).parse();
        }
        assertThat(config.hasMapper(ConversationMapper.class)).isTrue();
        var metadata = mock(ResultSetMetaData.class);
        var rows = mock(ResultSet.class);
        var statement = mock(Statement.class);
        String[] columns = {"id", "created_at", "prompt_tokens", "last_batch_seq", "turn_seq", "saved_codepoints", "auth_epoch", "cancellation_reason"};
        int[] types = {Types.OTHER, Types.TIMESTAMP, Types.BIGINT, Types.BIGINT, Types.BIGINT, Types.INTEGER, Types.BIGINT, Types.VARCHAR};
        when(metadata.getColumnCount()).thenReturn(columns.length);
        for (int i = 0; i < columns.length; i++) {
            when(metadata.getColumnLabel(i + 1)).thenReturn(columns[i]);
            when(metadata.getColumnName(i + 1)).thenReturn(columns[i]);
            when(metadata.getColumnType(i + 1)).thenReturn(types[i]);
        }
        UUID id = UUID.randomUUID();
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        when(rows.getObject("id", UUID.class)).thenReturn(id);
        when(rows.getTimestamp("created_at")).thenReturn(Timestamp.from(now));
        when(rows.getLong("auth_epoch")).thenAnswer(call -> {
            when(rows.wasNull()).thenReturn(false);
            return 7L;
        });
        when(rows.getString("cancellation_reason")).thenReturn("ACCOUNT_REVOKED");
        when(rows.getLong("prompt_tokens")).thenAnswer(call -> {
            when(rows.wasNull()).thenReturn(true);
            return 0L;
        });
        when(rows.getLong("last_batch_seq")).thenAnswer(call -> {
            when(rows.wasNull()).thenReturn(false);
            return 3_000_000_000L;
        });
        when(rows.getLong("turn_seq")).thenAnswer(call -> {
            when(rows.wasNull()).thenReturn(false);
            return 4_000_000_000L;
        });
        when(rows.getInt("saved_codepoints")).thenAnswer(call -> {
            when(rows.wasNull()).thenReturn(false);
            return 1024;
        });
        when(rows.getMetaData()).thenReturn(metadata);
        when(rows.next()).thenReturn(true, false);
        when(statement.getResultSet()).thenReturn(rows);
        when(statement.getUpdateCount()).thenReturn(-1);
        var mapped = config.getMappedStatement(ConversationMapper.class.getName() + ".turn");
        var handler = new DefaultResultSetHandler(null, mapped, null, null, mapped.getBoundSql(null),
                RowBounds.DEFAULT);
        var actual = (TurnRow) handler.handleResultSets(statement).getFirst();
        assertThat(actual.getId()).isEqualTo(id);
        assertThat(actual.getCreatedAt()).isEqualTo(now);
        assertThat(actual.getPromptTokens()).isNull();
        assertThat(actual.getLastBatchSeq()).isEqualTo(3_000_000_000L);
        assertThat(actual.getTurnSeq()).isEqualTo(4_000_000_000L);
        assertThat(actual.getSavedCodepoints()).isEqualTo(1024);
        assertThat(actual.getAuthEpoch()).isEqualTo(7);
        assertThat(actual.getCancellationReason()).isEqualTo("ACCOUNT_REVOKED");
    }
}

