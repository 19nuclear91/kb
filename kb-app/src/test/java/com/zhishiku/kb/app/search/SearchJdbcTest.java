package com.zhishiku.kb.app.search;

import com.zhishiku.kb.app.search.config.SearchExecutionProperties;
import com.zhishiku.kb.app.search.execution.SearchBudget;
import com.zhishiku.kb.app.search.execution.SearchContext;
import com.zhishiku.kb.app.search.execution.SearchDataSource;
import com.zhishiku.kb.app.search.execution.SearchStatementInterceptor;
import com.zhishiku.kb.app.search.model.SearchFailure;
import org.apache.ibatis.executor.statement.StatementHandler;
import org.apache.ibatis.plugin.Invocation;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class SearchJdbcTest {
    private final AtomicLong clock = new AtomicLong();
    private final SearchBudget budget = new SearchBudget(SearchExecutionProperties.defaults(), clock::get);
    private final SearchContext context = mock(SearchContext.class);
    private final DataSource source = mock(DataSource.class);
    private final Connection connection = mock(Connection.class);

    private void configure() throws Exception {
        when(source.getConnection()).thenReturn(connection);
        when(context.window()).thenReturn(budget.work());
        doAnswer(call -> {
            budget.work().check();
            return null;
        }).when(context).check();
    }

    @Test
    void lateConnectionIsClosedBeforeAnySqlAndNextNonSearchCallIsUnchanged() throws Exception {
        configure();
        when(source.getConnection()).thenAnswer(call -> {
            clock.set(8_000_000_000L);
            return connection;
        });
        var bounded = new SearchDataSource(source);
        try (var current = mockStatic(SearchContext.class)) {
            current.when(SearchContext::current).thenReturn(context);
            assertThatThrownBy(bounded::getConnection).isInstanceOf(SearchFailure.class);
            verify(connection).close();
            verify(connection, never()).createStatement();
            current.when(SearchContext::current).thenReturn(null);
            assertThat(bounded.getConnection()).isSameAs(connection);
        }
    }

    @Test
    void statementsAreCancellableAndZeroTimeoutCannotEscapeTheRemainingBudget() throws Exception {
        configure();
        clock.set(7_400_000_000L);
        var statement = mock(PreparedStatement.class);
        when(connection.prepareStatement("query")).thenReturn(statement);
        var cancel = new AtomicReference<Runnable>();
        var registration = mock(SearchContext.Scope.class);
        when(context.onCancel(any())).thenAnswer(call -> {
            cancel.set(call.getArgument(0));
            return registration;
        });
        try (var current = mockStatic(SearchContext.class)) {
            current.when(SearchContext::current).thenReturn(context);
            var wrapped = new SearchDataSource(source).getConnection().prepareStatement("query");
            wrapped.setQueryTimeout(0);
            wrapped.execute();
            verify(statement, never()).setQueryTimeout(0);
            verify(statement).execute();
            cancel.get().run();
            verify(statement).cancel();
            clock.set(8_000_000_000L);
            assertThatThrownBy(wrapped::executeQuery).isInstanceOf(SearchFailure.class);
            verify(statement, never()).executeQuery();
            wrapped.close();
            verify(statement).close();
            verify(registration).close();
        }
    }

    @Test
    void cancelledContextDoesNotAcquireAnotherConnection() throws Exception {
        configure();
        clock.set(8_000_000_000L);
        try (var current = mockStatic(SearchContext.class)) {
            current.when(SearchContext::current).thenReturn(context);
            assertThatThrownBy(() -> new SearchDataSource(source).getConnection()).isInstanceOf(SearchFailure.class);
            verifyNoInteractions(source, connection);
        }
    }

    @Test
    void mybatisPreparationUsesTheRemainingSecondsAndLeavesOtherCallsAlone() throws Throwable {
        configure();
        var handler = mock(StatementHandler.class);
        var statement = mock(Statement.class);
        when(handler.prepare(connection, null)).thenReturn(statement);
        when(statement.getQueryTimeout()).thenReturn(30);
        var invocation = new Invocation(handler, StatementHandler.class.getMethod("prepare", Connection.class, Integer.class),
                new Object[]{connection, null});
        var interceptor = new SearchStatementInterceptor();
        try (var current = mockStatic(SearchContext.class)) {
            assertThat(interceptor.intercept(invocation)).isSameAs(statement);
            verifyNoInteractions(statement);
            current.when(SearchContext::current).thenReturn(context);
            clock.set(7_400_000_000L);
            assertThat(interceptor.intercept(invocation)).isSameAs(statement);
            verify(statement).setQueryTimeout(1);
        }
    }
}
