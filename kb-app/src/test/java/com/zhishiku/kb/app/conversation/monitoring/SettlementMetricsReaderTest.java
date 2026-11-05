package com.zhishiku.kb.app.conversation.monitoring;

import org.junit.jupiter.api.Test;
import org.springframework.dao.QueryTimeoutException;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLTimeoutException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SettlementMetricsReaderTest {
    @Test
    void mapsOneReadOnlyAggregateWithBoundedStatementAndClosesResources() throws Exception {
        var ds = mock(DataSource.class);
        var connection = mock(Connection.class);
        var statement = mock(Statement.class);
        var rows = mock(ResultSet.class);
        when(ds.getConnection()).thenReturn(connection);
        when(connection.createStatement()).thenReturn(statement);
        when(statement.executeQuery(SettlementMetricsReader.SQL)).thenReturn(rows);
        when(rows.next()).thenReturn(true, false);
        when(rows.getLong("pending")).thenReturn(7L);
        when(rows.getLong("review")).thenReturn(2L);
        when(rows.getTimestamp("oldest_finalized_at")).thenReturn(Timestamp.from(Instant.ofEpochSecond(100)));
        var reader = new SettlementMetricsReader(ds, 3);
        assertThat(reader.read()).isEqualTo(new SettlementMetricsReader.Summary(7, 2, Instant.ofEpochSecond(100)));
        verify(statement).setQueryTimeout(3);
        verify(statement).setMaxRows(1);
        verify(statement).executeQuery(SettlementMetricsReader.SQL);
        verify(rows).close();
        verify(statement).close();
        verify(connection).close();
    }

    @Test
    void mapsEmptyAggregateAndRejectsMissingPendingTimestamp() throws Exception {
        var rows = mock(ResultSet.class);
        assertThat(SettlementMetricsReader.MAPPER.mapRow(rows, 0)).isEqualTo(new SettlementMetricsReader.Summary(0, 0, null));
        when(rows.getLong("pending")).thenReturn(1L);
        assertThatThrownBy(() -> SettlementMetricsReader.MAPPER.mapRow(rows, 0)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void jdbcTimeoutIsTranslatedWithoutBusinessWrites() throws Exception {
        var ds = mock(DataSource.class);
        var connection = mock(Connection.class);
        var statement = mock(Statement.class);
        when(ds.getConnection()).thenReturn(connection);
        when(connection.createStatement()).thenReturn(statement);
        when(statement.executeQuery(SettlementMetricsReader.SQL)).thenThrow(new SQLTimeoutException("timeout"));
        assertThatThrownBy(() -> new SettlementMetricsReader(ds, 3).read()).isInstanceOf(QueryTimeoutException.class);
        verify(statement).setQueryTimeout(3);
        verify(statement).close();
        verify(connection).close();
    }
}
