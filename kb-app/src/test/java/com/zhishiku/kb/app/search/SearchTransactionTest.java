package com.zhishiku.kb.app.search;

import com.zhishiku.kb.app.search.config.SearchExecutionProperties;
import com.zhishiku.kb.app.search.execution.SearchBudget;
import com.zhishiku.kb.app.search.execution.SearchContext;
import com.zhishiku.kb.app.search.execution.SearchTransaction;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class SearchTransactionTest {
    @Test
    void refusedConnectionUsesTheTransactionFailureContractWithoutStartingRecall() throws Exception {
        var source = mock(DataSource.class);
        when(source.getConnection()).thenThrow(new SQLException("controlled refusal"));
        var context = mock(SearchContext.class);
        when(context.window()).thenReturn(new SearchBudget(SearchExecutionProperties.defaults(), System::nanoTime).work());
        var recall = mock(Runnable.class);
        try (var current = mockStatic(SearchContext.class)) {
            current.when(SearchContext::current).thenReturn(context);
            var transaction = new SearchTransaction(new DataSourceTransactionManager(source), source);
            assertThatThrownBy(() -> transaction.execute(() -> {
                recall.run();
                return "rows";
            })).isInstanceOf(CannotCreateTransactionException.class).hasRootCauseInstanceOf(SQLException.class);
        }
        verifyNoInteractions(recall);
        assertThat(TransactionSynchronizationManager.hasResource(source)).isFalse();
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
    }

    @Test
    void localSettingsAndRecallUseOneConnectionUntilCommitOrRollback() throws Exception {
        for (boolean fails : new boolean[]{false, true}) {
            var source = mock(DataSource.class);
            var connection = mock(Connection.class);
            var settings = mock(PreparedStatement.class);
            when(source.getConnection()).thenReturn(connection);
            when(connection.getAutoCommit()).thenReturn(true);
            when(connection.prepareStatement(anyString())).thenReturn(settings);
            var clock = new AtomicLong();
            var budget = new SearchBudget(SearchExecutionProperties.defaults(), clock::get);
            var window = budget.begin(SearchBudget.Stage.RETRIEVAL);
            clock.set(2_900_000_000L);
            var context = mock(SearchContext.class);
            when(context.window()).thenReturn(window);
            var transaction = new SearchTransaction(new DataSourceTransactionManager(source), source);
            Runnable recall = mock(Runnable.class);
            try (var current = mockStatic(SearchContext.class)) {
                current.when(SearchContext::current).thenReturn(context);
                Runnable run = () -> transaction.execute(() -> {
                    assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
                    assertThat(DataSourceUtils.getConnection(source)).isSameAs(connection);
                    recall.run();
                    if (fails) {
                        throw new IllegalStateException("controlled failure");
                    }
                    return "rows";
                });
                if (fails) {
                    assertThatThrownBy(run::run).isInstanceOf(IllegalStateException.class);
                } else {
                    run.run();
                }
                assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
                assertThat(TransactionSynchronizationManager.hasResource(source)).isFalse();
            }
            var order = inOrder(connection, settings, recall);
            order.verify(connection).setAutoCommit(false);
            order.verify(connection).prepareStatement(anyString());
            order.verify(settings).setString(1, "100");
            order.verify(settings).setString(2, "100");
            order.verify(settings).setQueryTimeout(1);
            order.verify(settings).execute();
            order.verify(recall).run();
            order.verify(settings).close();
            if (fails) {
                order.verify(connection).rollback();
                verify(connection, never()).commit();
            } else {
                order.verify(connection).commit();
                verify(connection, never()).rollback();
            }
            order.verify(connection).setAutoCommit(true);
            order.verify(connection).close();
        }
    }
}
