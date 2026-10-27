package com.zhishiku.kb.app.support;

import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.executor.resultset.DefaultResultSetHandler;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.RowBounds;

import java.io.IOException;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 只替换 JDBC 结果集边界，以生产映射构造对象，不连接数据库或执行 SQL。
 */
public final class MapperRowStub {
    private MapperRowStub() {
    }

    public static Configuration configuration(String resource) throws IOException {
        Configuration configuration = new Configuration();
        configuration.setMapUnderscoreToCamelCase(true);
        try (var input = MapperRowStub.class.getResourceAsStream(resource)) {
            assertThat(input).as(resource).isNotNull();
            new XMLMapperBuilder(input, configuration, resource, configuration.getSqlFragments()).parse();
        }
        return configuration;
    }

    public static <T> T readOne(Configuration configuration, String statementId,
                                Map<String, Object> parameters, Map<String, Object> row,
                                Class<T> type) throws Exception {
        ResultSetMetaData metadata = mock(ResultSetMetaData.class);
        ResultSet rows = mock(ResultSet.class);
        Statement statement = mock(Statement.class);
        var columns = new ArrayList<>(row.keySet());
        when(metadata.getColumnCount()).thenReturn(columns.size());
        for (int index = 0; index < columns.size(); index++) {
            String column = columns.get(index);
            Object value = row.get(column);
            int sqlType = value instanceof Long ? Types.BIGINT
                    : value instanceof Integer ? Types.INTEGER
                    : value instanceof Short ? Types.SMALLINT
                    : value instanceof Double ? Types.DOUBLE : value instanceof Boolean ? Types.BOOLEAN
                    : value instanceof Instant ? Types.TIMESTAMP : value instanceof UUID ? Types.OTHER : Types.VARCHAR;
            when(metadata.getColumnLabel(index + 1)).thenReturn(column);
            when(metadata.getColumnName(index + 1)).thenReturn(column);
            when(metadata.getColumnType(index + 1)).thenReturn(sqlType);
            when(metadata.getColumnClassName(index + 1))
                    .thenReturn(value == null ? String.class.getName() : value.getClass().getName());
        }
        AtomicBoolean wasNull = new AtomicBoolean();
        when(rows.getString(anyString())).thenAnswer(call -> {
            Object value = columnValue(row, call.getArgument(0), wasNull);
            return value == null ? null : value.toString();
        });
        when(rows.getLong(anyString())).thenAnswer(call -> {
            Number value = (Number) columnValue(row, call.getArgument(0), wasNull);
            return value == null ? 0L : value.longValue();
        });
        when(rows.getInt(anyString())).thenAnswer(call -> {
            Number value = (Number) columnValue(row, call.getArgument(0), wasNull);
            return value == null ? 0 : value.intValue();
        });
        when(rows.getShort(anyString())).thenAnswer(call -> {
            Number value = (Number) columnValue(row, call.getArgument(0), wasNull);
            return value == null ? (short) 0 : value.shortValue();
        });
        when(rows.getDouble(anyString())).thenAnswer(call -> {
            Number value = (Number) columnValue(row, call.getArgument(0), wasNull);
            return value == null ? 0.0 : value.doubleValue();
        });
        when(rows.getBoolean(anyString())).thenAnswer(call -> {
            Boolean value = (Boolean) columnValue(row, call.getArgument(0), wasNull);
            return Boolean.TRUE.equals(value);
        });
        when(rows.getTimestamp(anyString())).thenAnswer(call -> {
            Instant value = (Instant) columnValue(row, call.getArgument(0), wasNull);
            return value == null ? null : Timestamp.from(value);
        });
        when(rows.getObject(anyString(), eq(UUID.class))).thenAnswer(call -> columnValue(row, call.getArgument(0),
                wasNull));
        when(rows.wasNull()).thenAnswer(call -> wasNull.get());
        when(rows.getMetaData()).thenReturn(metadata);
        when(rows.next()).thenReturn(true, false);
        when(statement.getResultSet()).thenReturn(rows);
        when(statement.getUpdateCount()).thenReturn(-1);
        var mapped = configuration.getMappedStatement(statementId);
        var handler = new DefaultResultSetHandler(null, mapped, null, null,
                mapped.getBoundSql(parameters), RowBounds.DEFAULT);
        var results = handler.handleResultSets(statement);
        assertThat(results).hasSize(1);
        return type.cast(results.getFirst());
    }

    private static Object columnValue(Map<String, Object> row, String column, AtomicBoolean wasNull) {
        assertThat(row).as("结果映射读取的列必须存在").containsKey(column);
        Object value = row.get(column);
        wasNull.set(value == null);
        return value;
    }
}
