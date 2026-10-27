package com.zhishiku.kb.worker.embedding;

import com.baomidou.mybatisplus.autoconfigure.MybatisPlusAutoConfiguration;
import com.zhishiku.kb.common.embedding.EmbeddingControl;
import com.zhishiku.kb.common.embedding.EmbeddingProfile;
import com.zhishiku.kb.common.embedding.EmbeddingReadiness;
import com.zhishiku.kb.common.embedding.EmbeddingRole;
import com.zhishiku.kb.common.embedding.EmbeddingRun;
import com.zhishiku.kb.worker.config.UuidTypeHandler;
import com.zhishiku.kb.worker.embedding.mapper.EmbeddingLeaseMapper;
import com.zhishiku.kb.worker.embedding.mapper.EmbeddingReadinessMapper;
import com.zhishiku.kb.worker.embedding.mapper.EmbeddingStateMapper;
import com.zhishiku.kb.worker.embedding.model.RebuildBatch;
import com.zhishiku.kb.worker.embedding.model.RebuildBatch.Item;
import org.apache.ibatis.executor.result.ResultMapException;
import org.apache.ibatis.executor.resultset.DefaultResultSetHandler;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.RowBounds;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.type.JdbcType;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;

import javax.sql.DataSource;
import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.RETURNS_DEFAULTS;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EmbeddingUuidMappingTest {
    @org.springframework.context.annotation.Configuration(proxyBeanMethods = false)
    @ComponentScan(basePackageClasses = UuidTypeHandler.class, useDefaultFilters = false,
            includeFilters = @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = UuidTypeHandler.class))
    @MapperScan(basePackageClasses = EmbeddingReadinessMapper.class)
    static class MappingConfiguration {
    }

    private void withConfiguration(Consumer<Configuration> check) {
        DataSource source = mock(DataSource.class, invocation -> {
            if (invocation.getMethod().getName().equals("getConnection")) {
                throw new AssertionError("offline mapping test must not open a connection");
            }
            return RETURNS_DEFAULTS.answer(invocation);
        });
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(MybatisPlusAutoConfiguration.class))
                .withUserConfiguration(MappingConfiguration.class)
                .withBean(DataSource.class, () -> source)
                .withPropertyValues("mybatis-plus.configuration.map-underscore-to-camel-case=true")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    var configuration = context.getBean(SqlSessionFactory.class).getConfiguration();
                    check.accept(configuration);
                    verify(source, never()).getConnection();
                    verify(source, never()).getConnection(anyString(), anyString());
                });
    }

    @Test
    void autoConfigurationRegistersUuidBeforeScanningEveryEmbeddingMapper() {
        withConfiguration(config -> {
            assertThat(config.getTypeHandlerRegistry().getTypeHandler(UUID.class)).isInstanceOf(UuidTypeHandler.class);
            assertThat(config.getTypeHandlerRegistry().getTypeHandler(UUID.class, JdbcType.OTHER))
                    .isInstanceOf(UuidTypeHandler.class);
            assertThat(config.hasMapper(EmbeddingReadinessMapper.class)).isTrue();
            assertThat(config.hasMapper(EmbeddingStateMapper.class)).isTrue();
            assertThat(config.getMappedStatementNames()).isNotEmpty();
        });
    }

    @Test
    void bindsReadinessAndRunIdentifiersAsNativeUuid() {
        withConfiguration(config -> {
            UUID id = UUID.randomUUID();
            var row = new EmbeddingReadiness(id, EmbeddingRole.DOCUMENT, "p", "fp", 1, true,
                    Instant.parse("2026-01-01T00:00:00Z"), Instant.parse("2026-01-01T00:01:00Z"));
            var save = bind(config, EmbeddingReadinessMapper.class, "save", row);
            var run = bind(config, EmbeddingStateMapper.class, "run", Map.of("id", id));
            try {
                verify(save).setObject(1, id, Types.OTHER);
                verify(run).setObject(1, id, Types.OTHER);
                verify(save, never()).setString(1, id.toString());
            } catch (SQLException error) {
                throw new AssertionError(error);
            }
        });
    }

    @Test
    void readsReadinessControlAndRunRecordsIncludingNullableUuid() {
        withConfiguration(config -> {
            UUID id = UUID.randomUUID();
            Instant now = Instant.parse("2026-01-01T00:00:00Z");
            var readiness = new EmbeddingReadiness(id, EmbeddingRole.DOCUMENT, "p", "fp", 1, true,
                    now, now.plusSeconds(60));
            assertThat(read(config, EmbeddingReadinessMapper.class, "findFresh", readiness)).isEqualTo(readiness);
            var control = new EmbeddingControl("REBUILDING", null, null, "p", "fp", 3, id);
            assertThat(read(config, EmbeddingStateMapper.class, "control", control)).isEqualTo(control);
            var empty = new EmbeddingControl("UNINITIALIZED", null, null, null, null, 0, null);
            assertThat(read(config, EmbeddingStateMapper.class, "control", empty)).isEqualTo(empty);
            var run = new EmbeddingRun(id, "p", 7, "REBUILDING", 1, null, 2, UUID.randomUUID(),
                    OffsetDateTime.parse("2026-01-01T00:00:00Z"), 1, 3, null, 2, 0, 0, null);
            assertThat(read(config, EmbeddingStateMapper.class, "run", run)).isEqualTo(run);
            var unleased = new EmbeddingRun(id, "p", 7, "REBUILDING", 1, null, 2, null,
                    null, 1, 3, null, 2, 0, 0, null);
            assertThat(read(config, EmbeddingStateMapper.class, "run", unleased)).isEqualTo(unleased);
        });
    }

    @Test
    void bindsLeaseOwnerAndRunUuidInDirectAndNestedParameters() {
        withConfiguration(config -> {
            UUID id = UUID.randomUUID();
            UUID owner = UUID.randomUUID();
            var claim = bind(config, EmbeddingLeaseMapper.class, "claimRun",
                    Map.of("id", id, "owner", owner, "seconds", 300));
            var profile = new EmbeddingProfile("p", "local", "source", "local-hash-v1", "1", 1024,
                    "cosine", "identity-v1", "identity-v1", "query", "document", "local-hash-v1");
            var item = new Item(1, 2, 3, "digest", 1, 1);
            var batch = new RebuildBatch(id, 1, 2, owner, profile, List.of(item));
            var renew = bind(config, EmbeddingLeaseMapper.class, "renewRun", Map.of("b", batch, "seconds", 300));
            var renewItem = bind(config, EmbeddingLeaseMapper.class, "renewItem",
                    Map.of("b", batch, "i", item, "seconds", 300));
            try {
                verify(claim).setObject(1, owner, Types.OTHER);
                verify(claim).setObject(3, id, Types.OTHER);
                verify(renew).setObject(2, id, Types.OTHER);
                verify(renew).setObject(4, owner, Types.OTHER);
                verify(renewItem).setObject(2, id, Types.OTHER);
                verify(renewItem).setObject(6, owner, Types.OTHER);
            } catch (SQLException error) {
                throw new AssertionError(error);
            }
        });
    }

    @Test
    void handlerSupportsNullsEveryReadOverloadAndSqlFailures() throws Exception {
        var handler = new UuidTypeHandler();
        UUID id = UUID.randomUUID();
        ResultSet rows = mock(ResultSet.class);
        CallableStatement call = mock(CallableStatement.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        when(rows.getObject("id", UUID.class)).thenReturn(id, (UUID) null);
        when(rows.getObject(1, UUID.class)).thenReturn(id, (UUID) null);
        when(call.getObject(1, UUID.class)).thenReturn(id, (UUID) null);
        assertThat(handler.getResult(rows, "id")).isEqualTo(id);
        assertThat(handler.getResult(rows, "id")).isNull();
        assertThat(handler.getResult(rows, 1)).isEqualTo(id);
        assertThat(handler.getResult(rows, 1)).isNull();
        assertThat(handler.getResult(call, 1)).isEqualTo(id);
        assertThat(handler.getResult(call, 1)).isNull();
        handler.setParameter(statement, 1, null, JdbcType.OTHER);
        verify(statement).setNull(1, Types.OTHER);
        when(rows.getObject("bad", UUID.class)).thenThrow(new SQLException("invalid uuid"));
        assertThatThrownBy(() -> handler.getResult(rows, "bad"))
                .isInstanceOf(ResultMapException.class).hasCauseInstanceOf(SQLException.class);
        doThrow(new SQLException("binding failed")).when(statement).setObject(1, id, Types.OTHER);
        assertThatThrownBy(() -> handler.setParameter(statement, 1, id, JdbcType.OTHER))
                .hasCauseInstanceOf(SQLException.class);
    }

    private PreparedStatement bind(Configuration config, Class<?> mapper, String method, Object parameters) {
        try {
            var mapped = config.getMappedStatement(mapper.getName() + "." + method);
            var statement = mock(PreparedStatement.class);
            config.newParameterHandler(mapped, parameters, mapped.getBoundSql(parameters)).setParameters(statement);
            return statement;
        } catch (SQLException error) {
            throw new AssertionError(error);
        }
    }

    // 让真实 MyBatis 结果处理器构造 record，只替换 JDBC 边界，不 Mock Mapper。
    private Object read(Configuration config, Class<?> mapper, String method, Record expected) {
        try {
            var components = expected.getClass().getRecordComponents();
            var metadata = mock(ResultSetMetaData.class);
            var rows = mock(ResultSet.class);
            var statement = mock(Statement.class);
            when(metadata.getColumnCount()).thenReturn(components.length);
            for (int i = 0; i < components.length; i++) {
                var component = components[i];
                String column = component.getName().replaceAll("([a-z])([A-Z])", "$1_$2").toLowerCase(Locale.ROOT);
                Object value = component.getAccessor().invoke(expected);
                Class<?> type = component.getType();
                int sqlType = type == UUID.class ? Types.OTHER : type == long.class ? Types.BIGINT
                        : type == int.class ? Types.INTEGER : type == boolean.class ? Types.BOOLEAN
                        : type == Instant.class || type == OffsetDateTime.class ? Types.TIMESTAMP_WITH_TIMEZONE :
                        Types.VARCHAR;
                when(metadata.getColumnLabel(i + 1)).thenReturn(column);
                when(metadata.getColumnName(i + 1)).thenReturn(column);
                when(metadata.getColumnType(i + 1)).thenReturn(sqlType);
                when(metadata.getColumnClassName(i + 1)).thenReturn(type.getName());
                if (type == UUID.class) when(rows.getObject(column, UUID.class)).thenReturn((UUID) value);
                else if (type == Instant.class)
                    when(rows.getTimestamp(column)).thenReturn(value == null ? null : Timestamp.from((Instant) value));
                else if (type == OffsetDateTime.class)
                    when(rows.getObject(column, OffsetDateTime.class)).thenReturn((OffsetDateTime) value);
                else if (type == long.class) when(rows.getLong(column)).thenReturn((long) value);
                else if (type == int.class) when(rows.getInt(column)).thenReturn((int) value);
                else if (type == boolean.class) when(rows.getBoolean(column)).thenReturn((boolean) value);
                else when(rows.getString(column)).thenReturn(value == null ? null : value.toString());
            }
            when(rows.getMetaData()).thenReturn(metadata);
            when(rows.next()).thenReturn(true, false);
            when(statement.getResultSet()).thenReturn(rows);
            when(statement.getUpdateCount()).thenReturn(-1);
            var mapped = config.getMappedStatement(mapper.getName() + "." + method);
            var handler = new DefaultResultSetHandler(null, mapped, null, null, mapped.getBoundSql(null),
                    RowBounds.DEFAULT);
            return handler.handleResultSets(statement).getFirst();
        } catch (Exception error) {
            throw new AssertionError(error);
        }
    }
}
