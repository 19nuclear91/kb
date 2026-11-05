package com.zhishiku.kb.worker.embedding;

import com.zhishiku.kb.worker.telemetry.MonitoringJdbc;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EmbeddingMetricsReaderTest {
    @Test
    void fixedStateProgressMappingAndMissingRunAreExplicit() throws Exception {
        var rows = mock(ResultSet.class);
        when(rows.getString("state")).thenReturn("UNINITIALIZED");
        assertThat(EmbeddingMetricsReader.STATE_MAPPER.mapRow(rows, 0))
                .isEqualTo(new EmbeddingMetricsReader.State("UNINITIALIZED", null, 0, 0, 0));
        var id = UUID.randomUUID();
        when(rows.getObject("run_id")).thenReturn(id);
        assertThatThrownBy(() -> EmbeddingMetricsReader.STATE_MAPPER.mapRow(rows, 0)).isInstanceOf(IllegalStateException.class);
        when(rows.getObject("found_run_id")).thenReturn(id);
        when(rows.getString("state")).thenReturn("REBUILDING");
        when(rows.getString("error_code")).thenReturn("LEASE_LOST");
        when(rows.getLong("total_count")).thenReturn(10L);
        when(rows.getLong("succeeded_count")).thenReturn(4L);
        when(rows.getLong("skipped_count")).thenReturn(1L);
        assertThat(EmbeddingMetricsReader.STATE_MAPPER.mapRow(rows, 0))
                .isEqualTo(new EmbeddingMetricsReader.State("REBUILDING", "LEASE_LOST", 10, 4, 1));
        when(rows.getLong("skipped_count")).thenThrow(new SQLException("private"));
        assertThatThrownBy(() -> EmbeddingMetricsReader.STATE_MAPPER.mapRow(rows, 0)).isInstanceOf(SQLException.class);
    }

    @Test
    void readerUsesFixedQueriesAndNeverConvertsMissingCountToSuccessfulZero() {
        var jdbc = mock(JdbcTemplate.class);
        var bounded = mock(MonitoringJdbc.class);
        when(bounded.template()).thenReturn(jdbc);
        var reader = new EmbeddingMetricsReader(bounded);
        var state = new EmbeddingMetricsReader.State("ACTIVE", null, 0, 0, 0);
        when(jdbc.queryForObject(EmbeddingMetricsReader.STATE_SQL, EmbeddingMetricsReader.STATE_MAPPER)).thenReturn(state);
        assertThat(reader.state()).isEqualTo(state);
        when(jdbc.queryForObject(anyString(), eq(Long.class), eq("START"))).thenReturn(8L, null);
        assertThat(reader.events("START")).isEqualTo(8);
        assertThatThrownBy(() -> reader.events("START")).isInstanceOf(IllegalStateException.class);
        verify(jdbc).queryForObject(EmbeddingMetricsReader.STATE_SQL, EmbeddingMetricsReader.STATE_MAPPER);
    }
}
