package com.zhishiku.kb.app.service.impl.health;

import com.zhishiku.kb.app.config.HealthStatementBudget;
import com.zhishiku.kb.app.document.applicability.ValidityProperties;
import com.zhishiku.kb.app.document.applicability.mapper.BusinessClockMapper;
import com.zhishiku.kb.app.domain.dto.health.HealthTotals;
import com.zhishiku.kb.app.domain.req.health.HealthQuery;
import com.zhishiku.kb.app.mapper.health.HealthMapper;
import com.zhishiku.kb.app.service.health.HealthFailure;
import com.zhishiku.kb.app.service.health.HealthReadService;
import com.zhishiku.kb.common.security.PermissionScope;
import org.apache.ibatis.executor.statement.StatementHandler;
import org.apache.ibatis.plugin.Invocation;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.SimpleTransactionStatus;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class HealthBudgetTest {
    @Test
    void statementPreparedAfterDeadlineIsClosedBeforeFailureEscapes() throws Throwable {
        var clock = new AtomicLong();
        var connection = mock(Connection.class);
        var settings = mock(PreparedStatement.class);
        var query = mock(PreparedStatement.class);
        var handler = mock(StatementHandler.class);
        when(connection.prepareStatement(any(String.class))).thenReturn(settings);
        when(handler.prepare(connection, 6)).thenAnswer(call -> {
            clock.set(6_000_000_000L);
            return query;
        });
        try (var budget = new HealthBudget(clock::get)) {
            var invocation = new Invocation(handler, StatementHandler.class.getMethod("prepare", Connection.class, Integer.class), new Object[]{connection, 6});
            assertThatThrownBy(() -> new HealthStatementBudget().intercept(invocation)).isInstanceOf(HealthFailure.class);
            verify(query).close();
        }
    }

    @Test
    void expiredBudgetAfterLastQueryNeverReturnsPartialSuccess() {
        var access = mock(HealthAccess.class);
        var mapper = mock(HealthMapper.class);
        var clock = mock(BusinessClockMapper.class);
        when(access.require(2)).thenReturn(PermissionScope.of(7, (short) 1, List.of(), Set.of("1:7")));
        when(clock.now()).thenReturn(OffsetDateTime.parse("2026-11-27T08:00:00Z"));
        when(mapper.totals(any())).thenReturn(new HealthTotals("0", "0", "0", "0", "0", "0", "0"));
        when(mapper.page(any())).thenReturn(List.of());
        try (var budgets = mockConstruction(HealthBudget.class, (budget, context) ->
                when(budget.remainingMillis()).thenReturn(1L).thenThrow(HealthFailure.unavailable()))) {
            var service = new HealthReadServiceImpl(access, mapper, clock, new ValidityProperties("Asia/Shanghai"));
            assertThatThrownBy(() -> service.read(2, new HealthQuery("ALL", 20, null))).isInstanceOf(HealthFailure.class);
            verify(mapper).page(any());
            verify(budgets.constructed().getFirst()).close();
        }
    }

    @Test
    void statementBudgetShrinksAndCallContextIsRemoved() throws Throwable {
        var clock = new AtomicLong();
        var connection = mock(Connection.class);
        var settings = mock(PreparedStatement.class);
        var query = mock(PreparedStatement.class);
        var handler = mock(StatementHandler.class);
        when(connection.prepareStatement(any(String.class))).thenReturn(settings);
        when(handler.prepare(connection, 6)).thenReturn(query);
        try (var budget = new HealthBudget(clock::get)) {
            clock.set(4_500_000_000L);
            var invocation = new Invocation(handler, StatementHandler.class.getMethod("prepare", Connection.class, Integer.class), new Object[]{connection, 6});
            assertThat(new HealthStatementBudget().intercept(invocation)).isSameAs(query);
            verify(settings).setString(1, "1500");
            verify(settings).setString(2, "1500");
            verify(settings).close();
            verify(query).setQueryTimeout(2);
            clock.set(6_000_000_000L);
            assertThatThrownBy(budget::remainingMillis).isInstanceOf(HealthFailure.class);
        }
        assertThat(HealthBudget.current()).isNull();
    }

    @Test
    void sharedQueriesUseReadOnlyRepeatableReadTransactionAndFailuresRollback() {
        var manager = mock(PlatformTransactionManager.class);
        var status = new SimpleTransactionStatus();
        when(manager.getTransaction(any())).thenAnswer(call -> {
            TransactionDefinition definition = call.getArgument(0);
            assertThat(definition.isReadOnly()).isTrue();
            assertThat(definition.getIsolationLevel()).isEqualTo(TransactionDefinition.ISOLATION_REPEATABLE_READ);
            assertThat(definition.getTimeout()).isEqualTo(6);
            return status;
        });
        var access = mock(HealthAccess.class);
        var mapper = mock(HealthMapper.class);
        var clock = mock(BusinessClockMapper.class);
        when(access.require(2)).thenReturn(PermissionScope.of(7, (short) 1, List.of(), Set.of("1:7")));
        when(clock.now()).thenReturn(OffsetDateTime.parse("2026-11-27T08:00:00Z"));
        when(mapper.totals(any())).thenReturn(new HealthTotals("0", "0", "0", "0", "0", "0", "0"));
        when(mapper.page(any())).thenReturn(List.of());
        var target = new HealthReadServiceImpl(access, mapper, clock, new ValidityProperties("Asia/Shanghai"));
        var factory = new ProxyFactory(target);
        factory.addAdvice(new TransactionInterceptor(manager, new AnnotationTransactionAttributeSource()));
        var service = (HealthReadService) factory.getProxy();
        assertThat(service.read(2, new HealthQuery("ALL", 20, null)).items()).isEmpty();
        verify(manager).commit(status);
        when(mapper.page(any())).thenThrow(new IllegalStateException("private"));
        assertThatThrownBy(() -> service.read(2, new HealthQuery("ALL", 20, null))).isInstanceOf(HealthFailure.class);
        verify(manager).rollback(status);
        assertThat(HealthBudget.current()).isNull();
    }
}
