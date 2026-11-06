package com.zhishiku.kb.app.auth.service;

import com.zhishiku.kb.app.auth.mapper.IdentityDepartmentMapper;
import com.zhishiku.kb.app.auth.model.AuthenticationIdentity;
import com.zhishiku.kb.app.config.UuidTypeHandler;
import org.apache.ibatis.executor.resultset.DefaultResultSetHandler;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.RowBounds;
import org.junit.jupiter.api.Test;

import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AuthenticationMappingTest {
    @Test
    void actualMapperReturnsDatabaseAnchorThroughCanonicalRecordConstructorWithoutDatabase() throws Exception {
        var config = new Configuration();
        config.setMapUnderscoreToCamelCase(true);
        config.getTypeHandlerRegistry().register(UuidTypeHandler.class);
        config.addMapper(IdentityDepartmentMapper.class);
        var metadata = mock(ResultSetMetaData.class);
        var rows = mock(ResultSet.class);
        var statement = mock(Statement.class);
        String[] columns = {"user_id", "auth_epoch", "enabled", "department_enabled", "must_change_password", "checked_at"};
        int[] types = {Types.BIGINT, Types.BIGINT, Types.BOOLEAN, Types.BOOLEAN, Types.BOOLEAN, Types.TIMESTAMP};
        when(metadata.getColumnCount()).thenReturn(columns.length);
        for (int i = 0; i < columns.length; i++) {
            when(metadata.getColumnLabel(i + 1)).thenReturn(columns[i]);
            when(metadata.getColumnName(i + 1)).thenReturn(columns[i]);
            when(metadata.getColumnType(i + 1)).thenReturn(types[i]);
        }
        Instant anchor = Instant.parse("2026-11-06T00:00:00Z");
        when(rows.getLong("user_id")).thenReturn(7L);
        when(rows.getLong("auth_epoch")).thenReturn(3L);
        when(rows.getBoolean("enabled")).thenReturn(true);
        when(rows.getBoolean("department_enabled")).thenReturn(true);
        when(rows.getTimestamp("checked_at")).thenReturn(Timestamp.from(anchor));
        when(rows.getMetaData()).thenReturn(metadata);
        when(rows.next()).thenReturn(true, false);
        when(statement.getResultSet()).thenReturn(rows);
        when(statement.getUpdateCount()).thenReturn(-1);
        var mapped = config.getMappedStatement(IdentityDepartmentMapper.class.getName() + ".findAuthenticationIdentity");
        var handler = new DefaultResultSetHandler(null, mapped, null, null, mapped.getBoundSql(Map.of("userId", 7)), RowBounds.DEFAULT);
        var actual = (AuthenticationIdentity) handler.handleResultSets(statement).getFirst();
        assertThat(actual).isEqualTo(new AuthenticationIdentity(7, 3, true, true, false, anchor));
    }
}
